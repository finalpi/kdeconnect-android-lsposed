/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.screenshot

import android.app.Activity
import android.os.Bundle
import org.kde.kdeconnect.KdeConnect

/** Non-exported local provisioning entry; ordinary apps cannot enable screenshot forwarding. */
class ScreenshotSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val on = intent.getBooleanExtra("enabled", false)
        if (!on || ScreenshotSettings.canReadImages(this)) {
            ScreenshotSettings.enable(this, on)
        }
        finish()
    }
}
