/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.lsposed

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener

/** Shared validation for the private Binder callback and system-only provider fallback. */
object LsposedClipboardDelivery {
    fun accept(context: Context, event: Bundle?): Boolean {
        val prefs = LsposedClipboardSettings.preferences(context)
        if (!LsposedClipboardSettings.isEnabled(context) || !LsposedClipboardContract.acceptsEvent(event,
                Process.myUid() / 100000, prefs.getLong("lsposed_enabled_at", Long.MAX_VALUE))) return false
        val copy = Bundle(event!!)
        prefs.edit().putLong("lsposed_events_accepted", prefs.getLong("lsposed_events_accepted", 0) + 1)
            .putLong("lsposed_hook_events", copy.getLong("captured_events", prefs.getLong("lsposed_hook_events", 0))).apply()
        Handler(Looper.getMainLooper()).post {
            if (LsposedClipboardSettings.isEnabled(context) && LsposedClipboardContract.acceptsEvent(copy,
                    Process.myUid() / 100000, prefs.getLong("lsposed_enabled_at", Long.MAX_VALUE))) {
                ClipboardListener.instance(context).onLsposedClipboardChanged(copy.getString("text"),
                    copy.getBoolean("sensitive"), copy.getLong("time"))
            }
        }
        return true
    }
}
