/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.lsposed

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.getSystemService

/** Optional CPU/Wi-Fi locks; the display stays off. No su or device-wide Doze changes. */
class ClipboardKeepAlive(private val context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val main = Handler(Looper.getMainLooper())
    private val networks = mutableSetOf<Network>()
    private var stopped = false
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    init {
        LsposedClipboardSettings.preferences(context).registerOnSharedPreferenceChangeListener(this)
        LsposedClipboardSettings.refreshHook(context)
    }

    fun onNetworkChanged(network: Network, available: Boolean) {
        main.post {
            if (!stopped) {
                if (available) networks.add(network) else networks.remove(network)
                updateLocks()
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == LsposedClipboardSettings.KEY_ENABLED || key == LsposedClipboardSettings.KEY_KEEP_ALIVE)
            main.post { if (!stopped) updateLocks() }
    }

    @Suppress("DEPRECATION")
    private fun updateLocks() {
        val enabled = networks.isNotEmpty() && LsposedClipboardSettings.isEnabled(context) &&
            LsposedClipboardSettings.preferences(context).getBoolean(LsposedClipboardSettings.KEY_KEEP_ALIVE, true)
        if (!enabled) { releaseLocks(); return }
        if (cpuLock == null) {
            cpuLock = context.getSystemService<PowerManager>()?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "KDEConnect:LsposedClipboard")?.apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            wifiLock = context.applicationContext.getSystemService<WifiManager>()
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KDEConnect:LsposedClipboard")
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        cpuLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        cpuLock = null
        wifiLock = null
    }

    fun close() {
        stopped = true
        LsposedClipboardSettings.preferences(context).unregisterOnSharedPreferenceChangeListener(this)
        main.removeCallbacksAndMessages(null)
        releaseLocks()
    }
}
