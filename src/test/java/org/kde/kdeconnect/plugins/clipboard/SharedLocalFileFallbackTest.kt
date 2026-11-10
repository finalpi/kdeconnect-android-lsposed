/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.spyk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kde.kdeconnect.helpers.SharedLocalFileFallback
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedLocalFileFallbackTest {
    @get:Rule val temp = TemporaryFolder()
    private fun uri(file: File, authority: String = "com.cxinventor.file.explorer.fileprovider") =
        Uri.Builder().scheme("content").authority(authority).path("/root/" + file.path.replace('\\', '/').trimStart('/')).build()

    @Test fun resolvesOnlyExistingCxFilesInsideSharedStorage() {
        val root = temp.newFolder("storage")
        val file = File(root, "Download/test.png").apply { parentFile!!.mkdirs(); writeText("probe") }
        assertEquals(file.canonicalFile, SharedLocalFileFallback.resolve(uri(file), root))
        assertNull(SharedLocalFileFallback.resolve(uri(file, "untrusted.provider"), root))
        assertNull(SharedLocalFileFallback.resolve(uri(temp.newFile("outside.txt")), root))
        assertNull(SharedLocalFileFallback.resolve(uri(File(root, "missing")), root))
        assertNull(SharedLocalFileFallback.resolve(uri(file).buildUpon().query("token=other").build(), root))
    }

    @Test fun neverFallsBackWithoutBothPermissions() {
        val context = spyk(ApplicationProvider.getApplicationContext<Application>())
        val root = Environment.getExternalStorageDirectory()
        val file = File(root, "Download/uri-probe.txt").apply { parentFile!!.mkdirs(); writeText("probe") }
        val source = uri(file)
        val error = FileNotFoundException("No content provider: $source")
        every { context.checkUriPermission(any(), any(), any(), Intent.FLAG_GRANT_READ_URI_PERMISSION) } returns PackageManager.PERMISSION_GRANTED
        mockkStatic(Environment::class)
        every { Environment.isExternalStorageManager() } returns false
        assertNull(SharedLocalFileFallback.forUnavailableProvider(context, source, error))
        every { Environment.isExternalStorageManager() } returns true
        every { context.checkUriPermission(any(), any(), any(), Intent.FLAG_GRANT_READ_URI_PERMISSION) } returns PackageManager.PERMISSION_DENIED
        assertNull(SharedLocalFileFallback.forUnavailableProvider(context, source, error))
        every { context.checkUriPermission(any(), any(), any(), Intent.FLAG_GRANT_READ_URI_PERMISSION) } returns PackageManager.PERMISSION_GRANTED
        assertEquals(file.canonicalFile, SharedLocalFileFallback.forUnavailableProvider(context, source, error))
        assertNull(SharedLocalFileFallback.forUnavailableProvider(context, source, FileNotFoundException("Permission denied")))
        unmockkStatic(Environment::class)
    }
}
