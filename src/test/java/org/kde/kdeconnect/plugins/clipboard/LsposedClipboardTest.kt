/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard

import android.content.pm.ProviderInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import android.app.KeyguardManager
import androidx.core.content.getSystemService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.plugins.clipboard.lsposed.LsposedClipboardContract
import org.kde.kdeconnect.plugins.clipboard.lsposed.LsposedClipboardProvider
import org.kde.kdeconnect.plugins.clipboard.lsposed.LsposedClipboardSettings
import org.robolectric.shadows.ShadowBinder
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class LsposedClipboardTest {
    private lateinit var listener: ClipboardListener
    private val received = mutableListOf<Pair<String, ClipboardListener.ClipboardContentType>>()

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        // Start with a fresh singleton for each test, including timestamp state.
        val field = ClipboardListener::class.java.getDeclaredField("_instance")
        field.isAccessible = true
        field.set(null, null)
        listener = ClipboardListener.instance(ctx)
        shadowOf(Looper.getMainLooper()).idle()
        listener.registerObserver(object : ClipboardListener.ClipboardObserver {
            override fun clipboardChanged(content: String, contentType: ClipboardListener.ClipboardContentType) {
                received += content to contentType
            }
        })
    }

    @Test fun lockedWritePushDoesNotReadClipboard() {
        // The provider supplies committed text directly even when primaryClip is unavailable.
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(ctx.getSystemService<KeyguardManager>()!!).setIsDeviceLocked(true)
        listener.onLsposedClipboardChanged("验证码 012345\n第二行 🔐", false, 100)
        assertEquals("验证码 012345\n第二行 🔐", received.single().first)
        assertEquals(100L, listener.updateTimestamp)
    }

    @Test fun duplicateAndRemoteEchoAreSuppressed() {
        listener.onLsposedClipboardChanged("012345", false, 100)
        listener.onLsposedClipboardChanged("012345", false, 101)
        assertEquals(1, received.size)
        listener.setText("from desktop")
        shadowOf(Looper.getMainLooper()).idle()
        received.clear()
        listener.onLsposedClipboardChanged("from desktop", false, System.currentTimeMillis() + 1)
        assertTrue(received.isEmpty())
    }

    @Test fun sensitiveFlagAndClearArePreserved() {
        listener.onLsposedClipboardChanged("123456", true, 100)
        assertEquals(ClipboardListener.ClipboardContentType.Password, received.single().second)
        listener.onLsposedClipboardChanged(null, false, 101)
        assertNull(listener.currentContent)
        assertEquals(1, received.size)
        listener.onLsposedClipboardChanged("stale code", false, 99)
        assertNull(listener.currentContent)
    }

    @Test fun rejectsOtherUsersDevicesAndStaleEvents() {
        val event = Bundle().apply { putInt("user", 0); putInt("device", 0); putLong("time", 100); putString("text", "123456") }
        assertTrue(LsposedClipboardContract.acceptsEvent(event, 0, 100))
        assertFalse(LsposedClipboardContract.acceptsEvent(event, 10, 100))
        assertFalse(LsposedClipboardContract.acceptsEvent(event, 0, 101))
        event.putInt("device", 1)
        assertFalse(LsposedClipboardContract.acceptsEvent(event, 0, 100))
        event.putInt("device", 0)
        event.putString("text", "x".repeat(LsposedClipboardContract.MAX_TEXT_LENGTH + 1))
        assertFalse(LsposedClipboardContract.acceptsEvent(event, 0, 100))
    }

    @Test fun onlySystemUidCanCallBridge() {
        assertTrue(LsposedClipboardContract.isTrustedCaller(1000))
        listOf(0, 2000, 10001, 101000).forEach { assertFalse(LsposedClipboardContract.isTrustedCaller(it)) }
    }

    @Test fun providerDeliversOnlyEnabledAuthenticatedEvents() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val provider = LsposedClipboardProvider()
        provider.attachInfo(ctx, ProviderInfo().apply { authority = LsposedClipboardContract.AUTHORITY })
        val event = Bundle().apply { putInt("user", 0); putInt("device", 0); putLong("time", 100); putString("text", "654321") }
        ShadowBinder.setCallingUid(10001)
        try {
            provider.call("clipboard", null, event)
            fail("Untrusted caller accepted")
        } catch (_: SecurityException) { }
        ShadowBinder.setCallingUid(1000)
        try {
            LsposedClipboardSettings.preferences(ctx).edit().putBoolean(LsposedClipboardSettings.KEY_ENABLED, false).apply()
            provider.call("clipboard", null, event)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(received.isEmpty())
            LsposedClipboardSettings.preferences(ctx).edit().putBoolean(LsposedClipboardSettings.KEY_ENABLED, true)
                .putLong("lsposed_enabled_at", 100).apply()
            provider.call("clipboard", null, event)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("654321", received.single().first)
        } finally {
            ShadowBinder.reset()
            LsposedClipboardSettings.preferences(ctx).edit().clear().apply()
        }
    }
}
