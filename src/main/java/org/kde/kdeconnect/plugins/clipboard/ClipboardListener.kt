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
            val content = item.coerceToText(context).toString()
            val contentType = detectContentType(clip)

            updateContent(content, contentType, System.currentTimeMillis())
        } catch (_: Exception) {
            //Probably clipboard was not text
        }
    }

    fun onLsposedClipboardChanged(content: String?, sensitive: Boolean, timestamp: Long) {
        if (timestamp < updateTimestamp) return
        if (content == null) {
            currentContent = null
            updateTimestamp = timestamp
            currentContentType = ClipboardContentType.Text
            return // Do not resend an auto-cleared OTP when a peer reconnects.
        }
        updateContent(content, if (sensitive) ClipboardContentType.Password else ClipboardContentType.Text, timestamp)
    }

    private fun updateContent(content: String, contentType: ClipboardContentType, timestamp: Long) {
        if (content == currentContent && contentType == currentContentType) return
        updateTimestamp = timestamp
        currentContent = content
        currentContentType = contentType
        observers.toList().forEach { it.clipboardChanged(content, contentType) }
    }

    fun setText(text: String?) {
        if (text == currentContent) return
        val previousContent = currentContent
        val previousType = currentContentType
        val previousTimestamp = updateTimestamp
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
