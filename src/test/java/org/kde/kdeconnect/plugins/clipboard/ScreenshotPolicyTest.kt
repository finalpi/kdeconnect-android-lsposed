/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard

import org.junit.Assert.*
import org.junit.Test
import org.kde.kdeconnect.plugins.clipboard.screenshot.ScreenshotPolicy

class ScreenshotPolicyTest {
    @Test fun onlyReadyScreenshotFoldersAreEligible() {
        assertTrue(ScreenshotPolicy.isReadyScreenshot("Pictures/Screenshots/", 512, false))
        assertTrue(ScreenshotPolicy.isReadyScreenshot("DCIM/截图/", 512, false))
        assertFalse(ScreenshotPolicy.isReadyScreenshot("DCIM/Camera/", 512, false))
        assertFalse(ScreenshotPolicy.isReadyScreenshot("Download/", 512, false))
        assertFalse(ScreenshotPolicy.isReadyScreenshot("Pictures/Screenshots/", 0, false))
        assertFalse(ScreenshotPolicy.isReadyScreenshot("Pictures/Screenshots/", 512, true))
    }
    @Test fun exportedFilenameContainsOnlyTheTransferMarkerAndOrderingFields() {
        assertEquals("KDEConnectScreenshot-1794268800000-123.png", ScreenshotPolicy.transferName(1794268800000, 123, "image/png"))
        assertTrue(ScreenshotPolicy.transferName(1794268800000, 124, "image/jpeg").endsWith(".jpg"))
    }
}
