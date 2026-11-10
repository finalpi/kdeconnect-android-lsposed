/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.screenshot

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.FilesHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.share.SharePlugin
import org.kde.kdeconnect.plugins.clipboard.ClipboardPlugin

/** Watches newly published screenshots only; never captures the display or scans image contents. */
class ScreenshotSync(private val context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val thread = HandlerThread("KDEScreenshotSync").apply { start() }
    private val worker = Handler(thread.looper)
    private val seen = linkedSetOf<Long>()
    private var watching = false
    private var startedAt = System.currentTimeMillis()
    private var activationBaselineId = 0L
    @Volatile private var closed = false
    private val scan = Runnable { scanNewImages() }
    private val observer = object : ContentObserver(worker) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            worker.removeCallbacks(scan); worker.postDelayed(scan, 400)
        }
    }
    init {
        ScreenshotSettings.prefs(context).registerOnSharedPreferenceChangeListener(this)
        worker.post { updateWatching() }
    }
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == ScreenshotSettings.ENABLED)
            worker.post { updateWatching() }
    }
    private fun status(value: String) {
        ScreenshotSettings.prefs(context).edit().putString("screenshot_last_status", value).apply()
    }
    private fun updateWatching() {
        if (closed) return
        val enabled = ScreenshotSettings.enabled(context)
        if (watching && !enabled) {
            context.contentResolver.unregisterContentObserver(observer); watching = false
            worker.removeCallbacks(scan); seen.clear(); status("disabled")
        } else if (enabled && !watching) {
            if (!ScreenshotSettings.canReadImages(context)) { status("photos_permission_missing"); return }
            startedAt = System.currentTimeMillis()
            try {
                val baselineArgs = Bundle().apply {
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media._ID))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                }
                context.contentResolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    arrayOf("_id"), baselineArgs, null)?.use { if (it.moveToFirst()) activationBaselineId = it.getLong(0) }
                context.contentResolver.registerContentObserver(Uri.parse("content://media"), true, observer)
                watching = true; status("watching"); worker.post(scan)
            } catch (error: Exception) { status("observer:${error.javaClass.simpleName}") }
        }
    }
    private fun scanNewImages() {
        if (closed || !watching || !ScreenshotSettings.enabled(context)) return
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val minDate = maxOf(startedAt, ScreenshotSettings.prefs(context).getLong("screenshot_enabled_at", 0)) / 1000
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.DATE_ADDED} >= ? AND _id > ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(minDate.toString(), activationBaselineId.toString()))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media._ID))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 100)
        }
        try {
            val ready = mutableListOf<Pair<Uri, String>>()
            context.contentResolver.query(collection, arrayOf("_id", "relative_path", "_size", "is_pending", "date_added", "mime_type"), args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    if (id in seen) continue
                    val pending = cursor.getInt(3) != 0
                    if (pending || cursor.getLong(2) == 0L) continue
                    seen.add(id)
                    if (seen.size > 512) seen.remove(seen.first())
                    if (!ScreenshotPolicy.isReadyScreenshot(cursor.getString(1), cursor.getLong(2), pending)) continue
                    ready.add(ContentUris.withAppendedId(collection, id) to
                        ScreenshotPolicy.transferName(cursor.getLong(4) * 1000, id, cursor.getString(5)))
                }
            }
            // Deliver oldest to newest and close the database cursor before network I/O.
            ready.asReversed().forEach { (uri, filename) -> send(uri, filename) }
        } catch (error: Exception) {
            status("query:${error.javaClass.simpleName}")
            Log.w("ScreenshotSync", "Screenshot query failed: ${error.javaClass.simpleName}")
        }
    }
    private fun send(uri: Uri, filename: String) {
        val devices = KdeConnect.getInstance().devices.values.filter {
            it.isPaired && it.isReachable && it.getPlugin<ClipboardPlugin>() != null &&
                it.deviceInfo.incomingCapabilities?.contains(ClipboardPlugin.PACKET_TYPE_CLIPBOARD_FILE) == true
        }
        if (devices.isEmpty()) { status("clipboard_peers_offline"); return }
        for (device in devices) sendTo(device, uri, filename)
    }
    private fun sendTo(device: Device, uri: Uri, filename: String) {
        val packet = FilesHelper.uriToNetworkPacket(context, uri, ClipboardPlugin.PACKET_TYPE_CLIPBOARD_FILE)
        if (packet == null) { status("image_unreadable"); return }
        if (packet.payloadSize > 50L * 1024 * 1024) { packet.payload?.close(); status("image_too_large"); return }
        packet["filename"] = filename
        packet["isImage"] = true
        try {
            device.sendPacketBlocking(packet, object : Device.SendPacketStatusCallback() {
                override fun onSuccess() {
                    status("sent")
                    ScreenshotSettings.prefs(context).edit().putLong("screenshot_last_sent", System.currentTimeMillis()).apply()
                }
                override fun onFailure(e: Throwable) { status("send:${e.javaClass.simpleName}") }
            }, true)
        } finally { packet.payload?.close() }
    }
    fun close() {
        closed = true
        ScreenshotSettings.prefs(context).unregisterOnSharedPreferenceChangeListener(this)
        if (watching) context.contentResolver.unregisterContentObserver(observer)
        worker.removeCallbacksAndMessages(null); thread.quitSafely()
    }
}
