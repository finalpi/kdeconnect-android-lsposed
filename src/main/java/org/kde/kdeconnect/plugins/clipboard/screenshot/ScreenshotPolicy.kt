/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.screenshot

object ScreenshotPolicy {
    const val PREFIX = "KDEConnectScreenshot-"
    fun isReadyScreenshot(path: String?, size: Long, pending: Boolean): Boolean {
        if (pending || size <= 0 || path == null) return false
        val folders = path.replace('\\', '/').trim('/').split('/')
        return folders.any { it.equals("Screenshots", true) || it.equals("Screenshot", true) || it == "截图" || it == "截屏" }
    }
    fun transferName(time: Long, id: Long, mime: String?): String {
        val ext = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
        return "$PREFIX$time-$id.$ext"
    }
}
