/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.helpers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/** Narrow compatibility for CX's local root-path URI; never substitutes for a denied URI grant. */
object SharedLocalFileFallback {
    fun resolve(uri: Uri, storageRoot: File): File? {
        if (uri.scheme != "content" || uri.authority != "com.cxinventor.file.explorer.fileprovider" ||
            uri.query != null || uri.fragment != null) return null
        val path = uri.path ?: return null
        if (!path.startsWith("/root/")) return null
        val root = storageRoot.canonicalFile
        val file = File(path.removePrefix("/root")).canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile && it.canRead() }
    }

    fun forUnavailableProvider(context: Context, uri: Uri, error: FileNotFoundException): File? {
        if (!error.message.orEmpty().startsWith("No content provider:")) return null
        if (!Environment.isExternalStorageManager()) {
            Log.w("ShareUriCompat", "Local recovery unavailable: all-files access is missing")
            return null
        }
        if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("ShareUriCompat", "Local recovery unavailable: source did not grant URI read access")
            return null
        }
        val file = resolve(uri, Environment.getExternalStorageDirectory())
        if (file == null) Log.w("ShareUriCompat", "Local recovery unavailable: local file is outside allowed storage or unreadable")
        return file
    }
}
