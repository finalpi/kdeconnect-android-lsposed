/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.screenshot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.clipboard.lsposed.LsposedClipboardSettings
import org.kde.kdeconnect_tp.R

object ScreenshotSettings {
    const val ENABLED = "screenshot_clipboard_enabled"
    fun prefs(context: Context) = LsposedClipboardSettings.preferences(context)
    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)
    fun canReadImages(context: Context) = Environment.isExternalStorageManager() ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    fun enable(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, value).putLong("screenshot_enabled_at", System.currentTimeMillis()).apply()
        if (value) BackgroundService.Start(context)
    }
    fun addPreferences(context: Context, screen: PreferenceScreen, requestPhotos: () -> Unit) {
        val category = PreferenceCategory(context).apply { setTitle(R.string.screenshot_clipboard_title) }
        screen.addPreference(category)
        category.addPreference(SwitchPreference(context).apply {
            key = ENABLED; isPersistent = false; isChecked = enabled(context)
            setTitle(R.string.screenshot_clipboard_enable)
            setSummary(R.string.screenshot_clipboard_summary)
            onPreferenceChangeListener = androidx.preference.Preference.OnPreferenceChangeListener { _, value ->
                val on = value as Boolean
                if (on && !canReadImages(context)) {
                    requestPhotos(); false
                } else { enable(context, on); true }
            }
        })
    }
}
