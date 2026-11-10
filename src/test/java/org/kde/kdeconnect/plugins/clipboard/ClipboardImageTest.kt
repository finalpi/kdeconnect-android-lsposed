/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard

import android.app.Application
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.NetworkPacket
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.EOFException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class ClipboardImageTest {
    // Unit tests do not load the merged Android manifest. The installed FileProvider
    // and URI grants are verified separately on the device.
    class ImageProvider : ContentProvider() {
        lateinit var file: java.io.File
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
    private lateinit var context: Application
    private lateinit var listener: ClipboardListener
    private val echoes = mutableListOf<String>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ClipboardListener::class.java.getDeclaredField("_instance").apply { isAccessible = true }.set(null, null)
        listener = ClipboardListener.instance(context)
        listener.setText("baseline")
        shadowOf(Looper.getMainLooper()).idle()
        listener.registerObserver(object : ClipboardListener.ClipboardObserver {
            override fun clipboardChanged(content: String, contentType: ClipboardListener.ClipboardContentType) { echoes += content }
        })
    }

    private fun png(): ByteArray {
        val bitmap = java.awt.image.BufferedImage(32, 18, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = bitmap.createGraphics()
        graphics.color = java.awt.Color(32, 96, 192)
        graphics.fillRect(0, 0, 32, 18)
        graphics.dispose()
        val output = ByteArrayOutputStream()
        check(javax.imageio.ImageIO.write(bitmap, "png", output))
        return output.toByteArray()
    }

    @Test fun receivesActualImageUriAndDoesNotEchoTextOrUri() {
        val file = ClipboardImageReceiver.writePayload(context, NetworkPacket.Payload(png()))
        try {
            val authority = "org.kde.clipboard.test"
            Robolectric.buildContentProvider(ImageProvider::class.java).create(authority).get().file = file
            val uri = Uri.parse("content://$authority/image.png")
            assertEquals("image/png", context.contentResolver.getType(uri))
            assertTrue(listener.setImage(uri, listener.beginImageTransfer()))
            listener.onClipboardChanged()
            shadowOf(Looper.getMainLooper()).idle()
            val clip = context.getSystemService<ClipboardManager>()!!.primaryClip!!
            assertTrue(clip.description.hasMimeType("image/png"))
            assertEquals(uri, clip.getItemAt(0).uri)
            assertNull(clip.getItemAt(0).text)
            assertNull(listener.currentContent)
            assertTrue(echoes.isEmpty())
            context.contentResolver.openInputStream(uri)!!.use { assertTrue(it.readBytes().contentEquals(png())) }
        } finally { file.delete() }
    }

    @Test fun newLocalCopyCancelsPendingRemoteImage() {
        val transfer = listener.beginImageTransfer()
        listener.onLsposedClipboardChanged("new user text", false, System.currentTimeMillis() + 1)
        assertFalse(listener.setImage(android.net.Uri.parse("content://unused/image.png"), transfer))
        assertEquals("new user text", listener.currentContent)
    }

    @Test fun latestImageRequestWinsAcrossDevices() {
        val old = listener.beginImageTransfer()
        listener.beginImageTransfer()
        assertFalse(listener.setImage(android.net.Uri.parse("content://unused/image.png"), old))
    }

    @Test fun missingImageFlagIsRejectedAndPayloadClosed() {
        var closed = false
        val stream = object : java.io.ByteArrayInputStream(png()) {
            override fun close() { closed = true; super.close() }
        }
        val packet = NetworkPacket(ClipboardPlugin.PACKET_TYPE_CLIPBOARD_FILE).apply { payload = NetworkPacket.Payload(stream, stream.available().toLong()) }
        assertFalse(ClipboardImageReceiver.receive(context, packet) { true })
        assertTrue(closed)
    }

    @Test(expected = EOFException::class) fun truncatedPayloadIsRejected() {
        val data = png()
        ClipboardImageReceiver.writePayload(context, NetworkPacket.Payload(data.inputStream(), data.size.toLong() + 10))
    }

    @Test(expected = IllegalArgumentException::class) fun nonImagePayloadIsRejected() {
        ClipboardImageReceiver.writePayload(context, NetworkPacket.Payload("not a png".toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedPayloadIsRejectedBeforeReading() {
        ClipboardImageReceiver.writePayload(context, NetworkPacket.Payload(byteArrayOf(1).inputStream(), ClipboardImageReceiver.MAX_BYTES + 1))
    }
}
