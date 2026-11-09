/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.lsposed

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.provider.Settings
import androidx.core.content.getSystemService
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.BuildConfig

object LsposedClipboardSettings {
    const val KEY_ENABLED = "lsposed_clipboard_enabled"
    const val KEY_KEEP_ALIVE = "lsposed_clipboard_keep_alive"
    const val KEY_STATUS = "lsposed_clipboard_status"

    fun preferences(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)
    fun isEnabled(context: Context) = Build.VERSION.SDK_INT >= 34 && Process.myUid() / 100000 == 0 &&
        preferences(context).getBoolean(KEY_ENABLED, false)

    fun refreshHook(context: Context) {
        val app = context.applicationContext
        LsposedFrameworkClient.refresh(app)
        // The receiver requires our signature permission from the sender. Do not also
        // require the platform-signed system receiver to hold our app's custom permission.
        val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                resultData?.let {
                    if (resultCode == 1 && it.getString("kind") == "clipboard") LsposedClipboardDelivery.accept(app, it)
                    else recordDiagnostics(app, it)
                }
            }
        }
        context.sendBroadcast(Intent(LsposedClipboardContract.ACTION_REFRESH).setPackage("android")
            .putExtra("reply", reply).putExtra("enabled", isEnabled(context)))
    }

    fun recordDiagnostics(context: Context, data: Bundle) {
        preferences(context).edit().putLong("lsposed_hook_seen", SystemClock.elapsedRealtime())
            .putString("lsposed_hook_method", data.getString("method", ""))
            .putString("lsposed_hook_stage", data.getString("stage", ""))
            .putString("lsposed_hook_error", data.getString("failure", ""))
            .putString("lsposed_hook_last_failure", data.getString("last_failure", ""))
            .putLong("lsposed_hook_version", data.getLong("module_version"))
            .putString("lsposed_hook_transport", data.getString("transport", ""))
            .putLong("lsposed_hook_events", data.getLong("events")).apply()
    }

    enum class Status { ACTIVE, OLD_MODULE, STAGE, NO_FRAMEWORK, FRAMEWORK_ERROR, MISSING_SCOPE, INJECTION }

    fun status(context: Context): Status {
        val prefs = preferences(context)
        val age = SystemClock.elapsedRealtime() - prefs.getLong("lsposed_hook_seen", -60000)
        if (age in 0..15000) {
            val version = prefs.getLong("lsposed_hook_version", 0)
            if (version > 0 && version != BuildConfig.VERSION_CODE.toLong())
                return Status.OLD_MODULE
            val stage = prefs.getString("lsposed_hook_stage", "")
            if (stage == "connected") return Status.ACTIVE
            if (!stage.isNullOrEmpty()) return Status.STAGE
        }
        val framework = prefs.getString("lsp_framework", "unbound")
        if (framework == "unbound") return Status.NO_FRAMEWORK
        if (!prefs.getString("lsp_framework_error", "").isNullOrEmpty()) return Status.FRAMEWORK_ERROR
        if ("system" !in prefs.getString("lsp_scopes", "")!!.split(','))
            return Status.MISSING_SCOPE
        return Status.INJECTION
    }

    fun statusSummary(context: Context): String {
        val prefs = preferences(context)
        return when (status(context)) {
            Status.ACTIVE -> context.getString(R.string.lsposed_status_active)
            Status.OLD_MODULE -> context.getString(R.string.lsposed_status_old_module,
                prefs.getLong("lsposed_hook_version", 0), BuildConfig.VERSION_CODE)
            Status.STAGE -> context.getString(R.string.lsposed_status_stage,
                prefs.getString("lsposed_hook_stage", ""), prefs.getString("lsposed_hook_error", ""))
            Status.NO_FRAMEWORK -> context.getString(R.string.lsposed_status_no_framework)
            Status.FRAMEWORK_ERROR -> context.getString(R.string.lsposed_status_framework_error,
                prefs.getString("lsp_framework_error", ""))
            Status.MISSING_SCOPE -> context.getString(R.string.lsposed_status_missing_scope)
            Status.INJECTION -> context.getString(R.string.lsposed_status_injection,
                prefs.getString("lsp_framework", ""), prefs.getString("lsp_targets", "")?.ifBlank { "none" })
        }
    }

    fun diagnosticText(context: Context): String {
        val prefs = preferences(context)
        return listOf(
            "app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) sdk=${Build.VERSION.SDK_INT}",
            "framework=${prefs.getString("lsp_framework", "unbound")}",
            "scope=${prefs.getString("lsp_scopes", "")}",
            "targets=${prefs.getString("lsp_targets", "")}",
            "framework_error=${prefs.getString("lsp_framework_error", "")}",
            "stage=${prefs.getString("lsposed_hook_stage", "no_reply")}",
            "error=${prefs.getString("lsposed_hook_error", "")}",
            "last_failure=${prefs.getString("lsposed_hook_last_failure", "")}",
            "module=${prefs.getLong("lsposed_hook_version", 0)} method=${prefs.getString("lsposed_hook_method", "")}",
            "transport=${prefs.getString("lsposed_hook_transport", "")}",
            "enabled=${isEnabled(context)} captured=${prefs.getLong("lsposed_hook_events", 0)} accepted=${prefs.getLong("lsposed_events_accepted", 0)}",
        ).joinToString("\n")
    }

    fun addPreferences(context: Context, screen: PreferenceScreen) {
        val category = PreferenceCategory(context).apply { setTitle(R.string.lsposed_settings_title) }
        screen.addPreference(category)
        category.addPreference(SwitchPreference(context).apply {
            key = KEY_ENABLED
            isPersistent = false
            isChecked = isEnabled(context)
            isEnabled = Build.VERSION.SDK_INT >= 34 && Process.myUid() / 100000 == 0
            setTitle(R.string.lsposed_clipboard_title)
            setSummary(R.string.lsposed_clipboard_summary)
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                preferences(context).edit().putBoolean(KEY_ENABLED, value as Boolean)
                    .putLong("lsposed_enabled_at", System.currentTimeMillis()).apply()
                refreshHook(context)
                if (value) BackgroundService.Start(context)
                true
            }
        })
        category.addPreference(SwitchPreference(context).apply {
            key = KEY_KEEP_ALIVE
            setDefaultValue(true)
            setTitle(R.string.lsposed_keep_alive_title)
            setSummary(R.string.lsposed_keep_alive_summary)
        })
        category.addPreference(Preference(context).apply {
            key = KEY_STATUS
            setTitle(R.string.lsposed_status_title)
            summary = statusSummary(context)
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                refreshHook(context)
                Handler(Looper.getMainLooper()).postDelayed({ summary = statusSummary(context) }, 1000)
                true
            }
        })
        category.addPreference(Preference(context).apply {
            setTitle(R.string.lsposed_copy_diagnostics)
            setSummary(R.string.lsposed_copy_diagnostics_summary)
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                context.getSystemService<ClipboardManager>()?.setPrimaryClip(
                    ClipData.newPlainText("LSPosed diagnostics", diagnosticText(context)))
                true
            }
        })
        category.addPreference(Preference(context).apply {
            setTitle(R.string.lsposed_battery_title)
            setSummary(R.string.lsposed_battery_summary)
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val power = context.getSystemService<PowerManager>()
                val intent = if (power?.isIgnoringBatteryOptimizations(context.packageName) == true)
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                true
            }
        })
    }
}
