/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.lsposed

import android.content.ClipData
import org.junit.Assert.*
import org.junit.Test

class LsposedHookShapeTest {
    // Model the structural contract from AOSP 14/15/16 and a future added argument.
    private class Clipboard(@JvmField val userId: Int = 0, @JvmField val deviceId: Int = 0)
    private class Methods {
        fun setPrimaryClipInternalNoClassifyLocked(clipboard: Clipboard, clip: ClipData?, uid: Int, source: String?) { }
        fun setPrimaryClipInternalLocked(clipboard: Clipboard, clip: ClipData?, uid: Int, source: String?, attribution: String?) { }
        fun setPrimaryClipInternalLocked(clip: ClipData?, uid: Int, device: Int, source: String?) { }
    }

    @Test fun discoversStateCommitAndRejectsOuterWrapper() {
        val methods = Methods::class.java.declaredMethods
        val finalWrite = methods.single { it.name == "setPrimaryClipInternalNoClassifyLocked" }
        assertTrue(LsposedClipboardHook.isWriteMethod(finalWrite, finalWrite.name))
        val overloads = methods.filter { it.name == "setPrimaryClipInternalLocked" }
        assertEquals(1, overloads.count { LsposedClipboardHook.isWriteMethod(it, it.name) })
    }
}
