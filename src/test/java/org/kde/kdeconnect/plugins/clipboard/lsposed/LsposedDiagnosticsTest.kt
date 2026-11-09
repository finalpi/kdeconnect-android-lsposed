/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.lsposed

import android.app.Application
import android.content.pm.Signature
import android.os.Bundle
import android.os.Looper
import android.os.ResultReceiver
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect_tp.BuildConfig
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LsposedDiagnosticsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun reset() {
        LsposedClipboardSettings.preferences(context).edit().clear().commit()
        ClipboardListener::class.java.getDeclaredField("_instance").apply { isAccessible = true }.set(null, null)
    }

    @Test fun ipcFailureMustNotBeReportedAsConnected() {
        LsposedClipboardSettings.recordDiagnostics(context, Bundle().apply {
            putString("stage", "provider_status")
            putString("failure", "SecurityException")
            putString("method", "setPrimaryClipInternalNoClassifyLocked")
            putLong("module_version", BuildConfig.VERSION_CODE.toLong())
        })
        assertEquals(LsposedClipboardSettings.Status.STAGE, LsposedClipboardSettings.status(context))
        val detail = LsposedClipboardSettings.diagnosticText(context)
        assertTrue(detail.contains("provider_status"))
        assertTrue(detail.contains("SecurityException"))
    }

    @Test fun aReplyFromOldCodeIsIdentifiedAsOldCode() {
        LsposedClipboardSettings.recordDiagnostics(context, Bundle().apply {
            putString("stage", "connected")
            putLong("module_version", 1370202)
        })
        assertEquals(LsposedClipboardSettings.Status.OLD_MODULE, LsposedClipboardSettings.status(context))
        val detail = LsposedClipboardSettings.diagnosticText(context)
        assertTrue(detail.contains("1370202"))
        assertTrue(detail.contains(BuildConfig.VERSION_CODE.toString()))
    }

    @Test fun diagnosticExportContainsNoClipboardValue() {
        LsposedClipboardSettings.preferences(context).edit()
            .putString("clipboard_text", "secret-OTP-999999")
            .putString("lsp_framework", "Framework API 102")
            .putString("lsp_scopes", "system").commit()
        val text = LsposedClipboardSettings.diagnosticText(context)
        assertTrue(text.contains("Framework API 102"))
        assertFalse(text.contains("secret-OTP-999999"))
        assertFalse(text.contains("clipboard_text"))
    }

    @Test fun signerIsCheckedWithoutOpeningAnArchivePath() {
        val signature = Signature(byteArrayOf(1, 2, 3, 4))
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertTrue(LsposedClipboardHook.matchesSignature(arrayOf(signature), digest))
        assertFalse(LsposedClipboardHook.matchesSignature(arrayOf(signature), "0".repeat(64)))
        assertFalse(LsposedClipboardHook.matchesSignature(null, digest))
        assertFalse(LsposedClipboardHook.matchesSignature(arrayOf(signature), ""))
        assertEquals(64, BuildConfig.LSPOSED_SIGNER_SHA256.length)
    }

    private fun handshake(): ResultReceiver {
        LsposedClipboardSettings.preferences(context).edit()
            .putBoolean(LsposedClipboardSettings.KEY_ENABLED, true).putLong("lsposed_enabled_at", 100).commit()
        LsposedClipboardSettings.refreshHook(context)
        val request = shadowOf(context).broadcastIntents.last { it.action == LsposedClipboardContract.ACTION_REFRESH }
        assertEquals("android", request.`package`)
        return requireNotNull(request.getParcelableExtra("reply", ResultReceiver::class.java))
    }

    private fun clipboardEvent() = Bundle().apply {
        putString("kind", "clipboard"); putString("text", "binder-test-012345")
        putInt("user", 0); putInt("device", 0); putLong("time", 100)
    }

    @Test fun binderCapabilityDeliversClipboardWithoutProvider() {
        handshake().send(1, clipboardEvent())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("binder-test-012345", ClipboardListener.instance(context).currentContent)
        assertEquals(1L, LsposedClipboardSettings.preferences(context).getLong("lsposed_events_accepted", 0))
    }

    @Test fun disablingBeforeQueuedBinderDeliveryDiscardsText() {
        val reply = handshake()
        reply.send(1, clipboardEvent())
        LsposedClipboardSettings.preferences(context).edit().putBoolean(LsposedClipboardSettings.KEY_ENABLED, false).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ClipboardListener.instance(context).currentContent)
    }

    @Test fun frameworkQueryFailureIsNotMisreportedAsMissingScope() {
        LsposedClipboardSettings.preferences(context).edit().putString("lsp_framework", "bound")
            .putString("lsp_framework_error", "ServiceException").commit()
        assertEquals(LsposedClipboardSettings.Status.FRAMEWORK_ERROR, LsposedClipboardSettings.status(context))
    }
}
