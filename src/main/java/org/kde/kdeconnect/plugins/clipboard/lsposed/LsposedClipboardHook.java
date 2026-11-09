/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.lsposed;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ResultReceiver;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.security.MessageDigest;
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
    private volatile String stage = "module_loaded";
    private volatile String failure = "";
    private boolean receiverRegistered;
    private int initializationAttempts;
    private long events;
    private SharedPreferences remotePreferences;
    private ResultReceiver client;
    private Boolean appEnabledControl;
    private volatile String lastFailure = "";
    private final SharedPreferences.OnSharedPreferenceChangeListener remoteListener = (prefs, key) -> {
        if (worker != null) worker.post(() -> refreshConfiguration(null));
    };

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, "KDEConnectClipboard", "module=" + BuildConfig.VERSION_CODE + " process=" + param.getProcessName());
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        if (Build.VERSION.SDK_INT < 34) return;
        try {
            log(Log.INFO, "KDEConnectClipboard", "system_start sdk=" + Build.VERSION.SDK_INT);
            HandlerThread thread = new HandlerThread("KDEConnectLsposedClipboard");
            thread.start();
            worker = new Handler(thread.getLooper());
            try {
                remotePreferences = getRemotePreferences(LsposedClipboardContract.REMOTE_GROUP);
                remotePreferences.registerOnSharedPreferenceChangeListener(remoteListener);
                enabled = remotePreferences.getBoolean("lsposed_clipboard_enabled", false);
            } catch (Throwable error) { report("remote_preferences", error); }
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
                    try { capture(chain.getArgs().toArray()); } catch (Throwable error) { report("capture", error); }
                    return result;
                });
                writeMethod = methodName;
            }
            if (writeMethod.isEmpty()) {
                log(Log.WARN, "KDEConnectClipboard", "No compatible clipboard commit method on SDK " + Build.VERSION.SDK_INT);
                return; // Unsupported ROM: leave its security policy intact.
            }
            // Constructors are also intercepted: onStart alone may be optimized/inlined by a ROM.
            for (Constructor<?> constructor : service.getDeclaredConstructors()) {
                if (constructor.getParameterCount() == 0 || !Context.class.isAssignableFrom(constructor.getParameterTypes()[0])) continue;
                hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    systemContext = (Context) chain.getArg(0);
                    worker.post(this::initialize);
                    return result;
                });
            }
            Method onStart = service.getDeclaredMethod("onStart");
            hook(onStart).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Context context = (Context) service.getMethod("getContext").invoke(chain.getThisObject());
                    systemContext = context;
                    worker.post(this::initialize);
                } catch (Throwable error) {
                    report("context_lookup", error);
                }
                return result;
            });
            stage = "hooks_installed";
            log(Log.INFO, "KDEConnectClipboard", "hooks_installed method=" + writeMethod);
        } catch (Throwable error) {
            // A module failure must never prevent system_server from booting.
            report("hook_install", error);
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

    private void report(String location, Throwable error) {
        stage = location;
        failure = error.getClass().getSimpleName();
        lastFailure = location + ":" + failure;
        log(Log.WARN, "KDEConnectClipboard", location + ":" + failure);
    }

    private void initialize() {
        if (receiverRegistered || systemContext == null) return;
        BroadcastReceiver refresh = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                ResultReceiver reply = intent.getParcelableExtra("reply", ResultReceiver.class);
                refreshConfiguration(reply, intent.hasExtra("enabled") ? intent.getBooleanExtra("enabled", false) : null);
            }
        };
        try {
            systemContext.registerReceiver(refresh, new IntentFilter(LsposedClipboardContract.ACTION_REFRESH),
                    LsposedClipboardContract.CONTROL_PERMISSION, worker, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
            stage = "receiver_ready";
            log(Log.INFO, "KDEConnectClipboard", stage);
            refreshConfiguration(null);
        } catch (Throwable error) {
            report("receiver_registration", error);
            if (++initializationAttempts < 20) worker.postDelayed(this::initialize, 3000);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean targetMatchesModule() throws Exception {
        PackageManager pm = systemContext.getPackageManager();
        PackageInfo installed = pm.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_SIGNATURES);
        return matchesSignature(installed.signatures, BuildConfig.LSPOSED_SIGNER_SHA256);
    }

    static boolean matchesSignature(Signature[] signatures, String expected) throws Exception {
        if (expected == null || expected.isEmpty() || signatures == null) return false;
        for (Signature signature : signatures) {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
            StringBuilder digest = new StringBuilder();
            for (byte value : hash) digest.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            if (expected.equals(digest.toString())) return true;
        }
        return false;
    }

    private Bundle diagnostics() {
        Bundle result = new Bundle();
        result.putString("stage", stage);
        result.putString("failure", failure);
        result.putString("last_failure", lastFailure);
        result.putString("method", writeMethod);
        result.putInt("sdk", Build.VERSION.SDK_INT);
        result.putLong("module_version", BuildConfig.VERSION_CODE);
        result.putLong("events", events);
        result.putString("transport", client != null ? "binder_callback" : "provider");
        return result;
    }

    private void refreshConfiguration(ResultReceiver replyReceiver) {
        refreshConfiguration(replyReceiver, null);
    }

    private void refreshConfiguration(ResultReceiver replyReceiver, Boolean requestedEnabled) {
        try {
            if (requestedEnabled != null) appEnabledControl = requestedEnabled;
            if (appEnabledControl != null) enabled = appEnabledControl;
            else if (remotePreferences != null) enabled = remotePreferences.getBoolean("lsposed_clipboard_enabled", false);
            if (!targetMatchesModule()) {
                enabled = false;
                stage = "signature_mismatch";
                failure = "SignerMismatch";
                log(Log.WARN, "KDEConnectClipboard", stage);
                return;
            }
            if (replyReceiver != null) client = replyReceiver;
            if (client != null) {
                stage = "connected";
                failure = "";
                return; // The authenticated Binder capability avoids system-server provider access.
            }
            stage = "provider_status";
            Bundle reply = systemContext.getContentResolver().call(Uri.parse("content://" + LsposedClipboardContract.AUTHORITY),
                    "status", null, diagnostics());
            if (reply != null) {
                enabled = reply.getBoolean("enabled");
                stage = "connected";
                failure = "";
            } else { stage = "provider_no_reply"; failure = "EmptyReply"; }
        } catch (Throwable error) { report("provider_status", error); }
        finally {
            // Independent of the provider: return initialization/signature/IPC errors to the app.
            if (replyReceiver != null) replyReceiver.send(0, diagnostics());
        }
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
        events++;
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
        event.putLong("captured_events", events);
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
                    if (client != null) {
                        event.putString("kind", "clipboard");
                        client.send(1, event);
                    } else {
                        systemContext.getContentResolver().call(Uri.parse("content://" + LsposedClipboardContract.AUTHORITY),
                                "clipboard", null, event);
                    }
                } catch (Throwable error) { report("clipboard_delivery", error); }
            }
        } finally {
            scheduled.set(false);
            if (pending.get() != null && scheduled.compareAndSet(false, true)) worker.post(this::drain);
        }
    }
}
