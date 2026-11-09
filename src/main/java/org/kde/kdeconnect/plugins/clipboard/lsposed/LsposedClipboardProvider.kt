/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.lsposed

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener

/** The system hook pushes values here, so locked-device reads are never needed. */
class LsposedClipboardProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!LsposedClipboardContract.isTrustedCaller(Binder.getCallingUid())) throw SecurityException("System UID required")
        val ctx = requireNotNull(context)
        val prefs = LsposedClipboardSettings.preferences(ctx)
        val enabled = LsposedClipboardSettings.isEnabled(ctx)
        when (method) {
            "status" -> {
                prefs.edit().putLong("lsposed_hook_seen", android.os.SystemClock.elapsedRealtime())
                    .putString("lsposed_hook_method", extras?.getString("method", "")).apply()
            }
            "clipboard" -> if (enabled && LsposedClipboardContract.acceptsEvent(extras, Process.myUid() / 100000,
                    prefs.getLong("lsposed_enabled_at", Long.MAX_VALUE))) {
                val event = Bundle(extras!!)
                Handler(Looper.getMainLooper()).post {
                    if (LsposedClipboardSettings.isEnabled(ctx)) {
                        ClipboardListener.instance(ctx).onLsposedClipboardChanged(event.getString("text"),
                            event.getBoolean("sensitive"), event.getLong("time"))
                    }
                }
            }
            else -> throw IllegalArgumentException("Unknown bridge operation")
        }
        return Bundle().apply { putBoolean("enabled", enabled) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
