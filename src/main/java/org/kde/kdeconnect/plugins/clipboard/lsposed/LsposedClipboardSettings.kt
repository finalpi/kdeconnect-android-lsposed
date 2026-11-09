/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.lsposed

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.getSystemService
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect_tp.R

object LsposedClipboardSettings {
    const val KEY_ENABLED = "lsposed_clipboard_enabled"
    const val KEY_KEEP_ALIVE = "lsposed_clipboard_keep_alive"
    const val KEY_STATUS = "lsposed_clipboard_status"

    fun preferences(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)
    fun isEnabled(context: Context) = Build.VERSION.SDK_INT >= 34 && Process.myUid() / 100000 == 0 &&
        preferences(context).getBoolean(KEY_ENABLED, false)

    fun refreshHook(context: Context) {
        // The receiver requires our signature permission from the sender. Do not also
        // require the platform-signed system receiver to hold our app's custom permission.
        context.sendBroadcast(Intent(LsposedClipboardContract.ACTION_REFRESH).setPackage("android"))
    }

    fun statusSummary(context: Context): String {
        val prefs = preferences(context)
        val age = SystemClock.elapsedRealtime() - prefs.getLong("lsposed_hook_seen", -60000)
        return if (age in 0..15000 && !prefs.getString("lsposed_hook_method", "").isNullOrEmpty())
            context.getString(R.string.lsposed_status_active)
        else context.getString(R.string.lsposed_status_waiting)
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
                summary = statusSummary(context)
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
