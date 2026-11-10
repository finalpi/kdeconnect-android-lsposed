/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.getSystemService
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.plugins.clipboard.lsposed.LsposedClipboardSettings
import org.kde.kdeconnect_tp.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

class ClipboardListener {
    enum class ClipboardContentType {
        Text,
        Password,
    }

    interface ClipboardObserver {
        fun clipboardChanged(content: String, contentType: ClipboardContentType)
    }

    private val observers = CopyOnWriteArraySet<ClipboardObserver>()

    private val context: Context
    var currentContent: String? = null
        private set
    var currentContentType: ClipboardContentType = ClipboardContentType.Text
        private set
    var updateTimestamp: Long = 0
        private set

    private val cm: ClipboardManager
    var currentImageUri: Uri? = null
        private set
    private var revision = 0L
    private var imageRequest = 0L
    class ImageTransfer internal constructor(val request: Long, val revision: Long)

    private constructor(ctx: Context) {
        context = ctx.applicationContext
        cm = context.getSystemService<ClipboardManager>()!!
        cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !LsposedClipboardSettings.isEnabled(context) && ClipboardPlugin.canSyncAutomatically(context)) {
            ThreadHelper.runOnNewThread {
                try {
                    val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                    // Listen only ClipboardService errors after now
                    val logcatFilter = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) { "E ClipboardService" } else { "ClipboardService:E" }
                    val process = Runtime.getRuntime().exec(arrayOf<String>("logcat", "-T", timeStamp, logcatFilter, "*:S"))
                    val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                    bufferedReader.forEachLine { line ->
                        if (line.contains(BuildConfig.APPLICATION_ID) && !LsposedClipboardSettings.isEnabled(context)) {
                            context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun registerObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }

    fun onClipboardChanged() {
        // Keep Android's ordinary listener as a fallback when a read is permitted.
        // A configured but unavailable system hook must not disable foreground sync.
        try {
            val clip = cm.primaryClip ?: return // A denied locked-screen read is not a clipboard clear.
            if (clip.itemCount == 0) return
            val item = clip.getItemAt(0)
            if (clip.description.hasMimeType("image/*")) {
                onImageChanged(item.uri)
                return // Never coerce a private image URI into a text clipboard packet.
            }
            val content = item.coerceToText(context).toString()
            val contentType = detectContentType(clip)

            updateContent(content, contentType, System.currentTimeMillis())
        } catch (_: Exception) {
            //Probably clipboard was not text
        }
    }

    @Synchronized
    fun onLsposedClipboardChanged(content: String?, sensitive: Boolean, timestamp: Long) {
        if (timestamp < updateTimestamp) return
        if (content == null) {
            revision++
            currentImageUri = null
            currentContent = null
            updateTimestamp = timestamp
            currentContentType = ClipboardContentType.Text
            return // Do not resend an auto-cleared OTP when a peer reconnects.
        }
        updateContent(content, if (sensitive) ClipboardContentType.Password else ClipboardContentType.Text, timestamp)
    }

    @Synchronized
    private fun updateContent(content: String, contentType: ClipboardContentType, timestamp: Long) {
        if (content == currentContent && contentType == currentContentType) return
        revision++
        currentImageUri = null
        updateTimestamp = timestamp
        currentContent = content
        currentContentType = contentType
        observers.toList().forEach { it.clipboardChanged(content, contentType) }
    }

    @Synchronized
    fun setText(text: String?) {
        if (text == currentContent) return
        val previousContent = currentContent
        val previousType = currentContentType
        val previousTimestamp = updateTimestamp
        val previousImage = currentImageUri
        revision++
        currentImageUri = null
        updateTimestamp = System.currentTimeMillis()
        currentContent = text
        currentContentType = ClipboardContentType.Text
        val writeTimestamp = updateTimestamp
        try {
            // Publish the remote value before Android can invoke a native callback.
            cm.setPrimaryClip(ClipData.newPlainText(null, text))
        } catch (error: Exception) {
            if (currentContent == text && updateTimestamp == writeTimestamp) {
                currentContent = previousContent
                currentContentType = previousType
                updateTimestamp = previousTimestamp
                currentImageUri = previousImage
            }
            throw error
        }
    }

    @Synchronized
    private fun onImageChanged(uri: Uri?) {
        if (uri == currentImageUri) return
        revision++
        currentImageUri = uri
        currentContent = null
        currentContentType = ClipboardContentType.Text
        updateTimestamp = System.currentTimeMillis()
    }

    @Synchronized
    fun beginImageTransfer(): ImageTransfer = ImageTransfer(++imageRequest, revision)

    @Synchronized
    fun setImage(uri: Uri, transfer: ImageTransfer): Boolean {
        if (transfer.request != imageRequest || transfer.revision != revision) return false
        val previousText = currentContent
        val previousType = currentContentType
        val previousImage = currentImageUri
        val previousTimestamp = updateTimestamp
        currentContent = null
        currentImageUri = uri
        currentContentType = ClipboardContentType.Text
        updateTimestamp = System.currentTimeMillis()
        val writeRevision = ++revision
        try {
            cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "KDE Connect image", uri))
            return true
        } catch (error: Exception) {
            if (revision == writeRevision) {
                currentContent = previousText
                currentContentType = previousType
                currentImageUri = previousImage
                updateTimestamp = previousTimestamp
            }
            throw error
        }
    }

    companion object {
        private var _instance: ClipboardListener? = null

        @Synchronized
        fun instance(context: Context): ClipboardListener {
            return _instance ?: ClipboardListener(context).also { _instance = it }
        }

        fun detectContentType(clip: ClipData?): ClipboardContentType {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return ClipboardContentType.Text
            }
            if (clip?.description?.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) {
                return ClipboardContentType.Password
            }
            return ClipboardContentType.Text
        }
    }
}
