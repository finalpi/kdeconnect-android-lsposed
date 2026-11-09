/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.lsposed;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import io.github.libxposed.api.XposedModule;
import org.kde.kdeconnect_tp.BuildConfig;

/**
 * Observes committed writes instead of relaxing global clipboard or keyguard checks.
 * No clipboard IPC, network IO or root command runs while ClipboardService holds its lock.
 */
public final class LsposedClipboardHook extends XposedModule {
    private final AtomicReference<Bundle> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile Handler worker;
    private volatile Context systemContext;
    private volatile boolean enabled;
    private String writeMethod = "";

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        if (Build.VERSION.SDK_INT < 34) return;
        try {
            Class<?> service = Class.forName("com.android.server.clipboard.ClipboardService", false, param.getClassLoader());
            // Android 14/15/16: hook the final state update, including profile propagation and clears.
            // Discover by shape so added attribution parameters do not require fixed signatures.
            String methodName = Arrays.stream(service.getDeclaredMethods())
                    .anyMatch(m -> isWriteMethod(m, "setPrimaryClipInternalNoClassifyLocked"))
                    ? "setPrimaryClipInternalNoClassifyLocked" : "setPrimaryClipInternalLocked";
            for (Method method : service.getDeclaredMethods()) {
                if (!isWriteMethod(method, methodName)) continue;
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    Object result = chain.proceed(); // Never report a failed/denied write.
                    try { capture(chain.getArgs().toArray()); } catch (Throwable ignored) { }
                    return result;
                });
                writeMethod = methodName;
            }
            if (writeMethod.isEmpty()) {
                log(Log.WARN, "KDEConnectClipboard", "No compatible clipboard commit method on SDK " + Build.VERSION.SDK_INT);
                return; // Unsupported ROM: leave its security policy intact.
            }
            Method onStart = service.getDeclaredMethod("onStart");
            hook(onStart).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Context context = (Context) service.getMethod("getContext").invoke(chain.getThisObject());
                    initialize(context);
                } catch (Throwable error) {
                    log(Log.WARN, "KDEConnectClipboard", "Cannot initialize clipboard bridge: " + error.getClass().getSimpleName());
                }
                return result;
            });
        } catch (Throwable error) {
            // A module failure must never prevent system_server from booting.
            log(Log.WARN, "KDEConnectClipboard", "Clipboard hook unavailable: " + error.getClass().getSimpleName());
        }
    }

    static boolean isWriteMethod(Method method, String name) {
        Class<?>[] types = method.getParameterTypes();
        return method.getName().equals(name) && types.length >= 3
                && types[1] == ClipData.class && hasIntField(types[0], "userId", "mUserId")
                && hasIntField(types[0], "deviceId", "mDeviceId");
    }

    private static boolean hasIntField(Class<?> type, String... names) {
        for (String name : names) {
            try { if (type.getDeclaredField(name).getType() == int.class) return true; }
            catch (ReflectiveOperationException ignored) { }
        }
        return false;
    }

    private synchronized void initialize(Context context) {
        if (worker != null) return;
        systemContext = context;
        HandlerThread thread = new HandlerThread("KDEConnectLsposedClipboard");
        thread.start();
        worker = new Handler(thread.getLooper());
        BroadcastReceiver refresh = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                refreshConfiguration();
            }
        };
        context.registerReceiver(refresh, new IntentFilter(LsposedClipboardContract.ACTION_REFRESH),
                LsposedClipboardContract.CONTROL_PERMISSION, worker, Context.RECEIVER_EXPORTED);
        // App sends a permission-protected refresh at startup / whenever settings change.
        worker.post(this::refreshConfiguration);
    }

    @SuppressWarnings("deprecation")
    private boolean targetMatchesModule() throws Exception {
        PackageManager pm = systemContext.getPackageManager();
        PackageInfo installed = pm.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_SIGNATURES);
        PackageInfo module = pm.getPackageArchiveInfo(getModuleApplicationInfo().sourceDir, PackageManager.GET_SIGNATURES);
        return module != null && installed.signatures != null && installed.signatures.length > 0
                && Arrays.equals(installed.signatures, module.signatures);
    }

    private void refreshConfiguration() {
        try {
            if (!targetMatchesModule()) { enabled = false; return; }
            Bundle info = new Bundle();
            info.putString("method", writeMethod);
            info.putInt("sdk", Build.VERSION.SDK_INT);
            Bundle reply = systemContext.getContentResolver().call(Uri.parse("content://" + LsposedClipboardContract.AUTHORITY),
                    "status", null, info);
            enabled = reply != null && reply.getBoolean("enabled");
        } catch (Throwable ignored) { enabled = false; }
    }

    private static int intField(Object object, String... names) throws ReflectiveOperationException {
        for (String name : names) {
            try {
                Field field = object.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.getInt(object);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException();
    }

    private void capture(Object[] args) throws ReflectiveOperationException {
        if (!enabled || worker == null) return;
        Object clipboard = args[0];
        int user = intField(clipboard, "userId", "mUserId");
        int device = intField(clipboard, "deviceId", "mDeviceId");
        // Explicitly exclude work profiles, other users and isolated virtual-device clipboards.
        if (user != 0 || device != 0) return;
        if (args.length > 3 && BuildConfig.APPLICATION_ID.equals(args[3])) return; // PC -> phone echo.
        ClipData clip = (ClipData) args[1];
        // Prefer the committed state in case another hook changed the write's arguments.
        for (String name : new String[] {"primaryClip", "mPrimaryClip"}) {
            try {
                Field field = clipboard.getClass().getDeclaredField(name);
                field.setAccessible(true);
                clip = (ClipData) field.get(clipboard);
                break;
            } catch (NoSuchFieldException ignored) { }
        }
        Bundle event = new Bundle();
        event.putInt("user", user);
        event.putInt("device", device);
        event.putLong("time", System.currentTimeMillis());
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).getText(); // Never dereference another app's URI.
            if (text != null && text.length() <= LsposedClipboardContract.MAX_TEXT_LENGTH) {
                event.putString("text", text.toString());
                event.putBoolean("sensitive", clip.getDescription().getExtras() != null
                        && clip.getDescription().getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE));
            }
            // A replacement with an image, URI or oversized value invalidates the cached text.
        }
        pending.set(event); // Keep only the newest value; do not accumulate OTPs in a queue.
        if (scheduled.compareAndSet(false, true)) worker.post(this::drain);
    }

    private void drain() {
        try {
            Bundle event;
            while ((event = pending.getAndSet(null)) != null) {
                try {
                    if (!targetMatchesModule()) { enabled = false; return; }
                    systemContext.getContentResolver().call(Uri.parse("content://" + LsposedClipboardContract.AUTHORITY),
                            "clipboard", null, event);
                } catch (Throwable ignored) { }
            }
        } finally {
            scheduled.set(false);
            if (pending.get() != null && scheduled.compareAndSet(false, true)) worker.post(this::drain);
        }
    }
}
