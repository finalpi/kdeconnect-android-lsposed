/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect_tp.BuildConfig
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.concurrent.Semaphore

/** Receives images into app-private storage; the clipboard grants readers URI access. */
object ClipboardImageReceiver {
    internal const val MAX_BYTES = 50L * 1024 * 1024
    private val slots = Semaphore(2)
    private val main = Handler(Looper.getMainLooper())

    fun receive(context: Context, packet: NetworkPacket, stillPaired: () -> Boolean): Boolean {
        val payload = packet.payload
        if (!packet.getBoolean("isImage") || payload?.inputStream == null || payload.payloadSize !in 1..MAX_BYTES || !slots.tryAcquire()) {
            payload?.close()
            return false
        }
        val app = context.applicationContext
        main.post {
            val listener = ClipboardListener.instance(app)
            val transfer = listener.beginImageTransfer()
            ThreadHelper.runOnNewThread {
                try {
                    val file = writePayload(app, payload)
                    main.post {
                        var applied = false
                        try {
                            val uri = FileProvider.getUriForFile(app, BuildConfig.APPLICATION_ID + ".fileprovider", file)
                            applied = stillPaired() && listener.setImage(uri, transfer)
                            if (applied) {
                                Log.i("ClipboardImage", "Received image clipboard (${file.length()} bytes)")
                                prune(app, file)
                            }
                        } catch (error: Exception) {
                            Log.w("ClipboardImage", "Image clipboard write failed: ${error.javaClass.simpleName}")
                        } finally {
                            if (!applied) file.delete()
                        }
                    }
                } catch (error: Exception) {
                    Log.w("ClipboardImage", "Image transfer rejected: ${error.javaClass.simpleName}")
                } finally {
                    slots.release()
                }
            }
        }
        return true
    }

    internal fun writePayload(context: Context, payload: NetworkPacket.Payload): File = payload.use {
        val directory = File(context.cacheDir, "clipboard-images").apply { mkdirs() }
        val partial = File.createTempFile("incoming-", ".part", directory)
        try {
            require(payload.payloadSize in 1..MAX_BYTES) { "Invalid image payload size" }
            payload.setReadTimeout(10_000)
            val input = payload.inputStream ?: throw IOException("Missing payload stream")
            partial.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = payload.payloadSize
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw EOFException("Incomplete image payload")
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            partial.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outMimeType == "image/png" && bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 80_000_000) { "Invalid PNG dimensions" }
            // Validate decoding without allocating an 80-megapixel bitmap in the background.
            var sample = 1
            while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > 16_000_000) sample *= 2
            val bitmap = BitmapFactory.decodeFile(partial.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IOException("Invalid PNG payload")
            bitmap.recycle()
            val file = File(directory, partial.nameWithoutExtension + ".png")
            check(partial.renameTo(file)) { "Could not publish clipboard image" }
            file
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private fun prune(context: Context, current: File) {
        // Retain the current image for paste and briefly retain older ones for IME previews.
        val files = File(context.cacheDir, "clipboard-images").listFiles()?.filter { it.extension == "png" } ?: return
        var bytes = files.sumOf { it.length() }
        files.sortedBy { it.lastModified() }.forEach {
            if (it != current && (bytes > 2 * MAX_BYTES || System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L)) {
                val size = it.length()
                if (it.delete()) bytes -= size
            }
        }
    }
}
