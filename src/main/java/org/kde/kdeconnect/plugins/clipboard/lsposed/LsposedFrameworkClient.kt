/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.lsposed

import android.content.Context
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Framework-reported injection state, independent of our clipboard IPC. No clipboard values. */
object LsposedFrameworkClient {
    private val registered = AtomicBoolean()
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var service: XposedService? = null

    fun refresh(context: Context) {
        val app = context.applicationContext
        if (registered.compareAndSet(false, true)) {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(bound: XposedService) {
                    service = bound
                    LsposedClipboardSettings.preferences(app).edit().putString("lsp_framework", "bound").apply()
                    refresh(app)
                }
                override fun onServiceDied(dead: XposedService) {
                    if (service === dead) service = null
                    LsposedClipboardSettings.preferences(app).edit().putString("lsp_framework", "unbound").apply()
                }
            })
        }
        io.execute {
            val prefs = LsposedClipboardSettings.preferences(app)
            val bound = service
            if (bound == null) {
                prefs.edit().putString("lsp_framework", "unbound").apply()
                return@execute
            }
            try {
                val framework = "${bound.frameworkName} ${bound.frameworkVersion} API ${bound.apiVersion}"
                prefs.edit().putString("lsp_framework", framework).apply()
                val scopes = bound.scope
                prefs.edit().putString("lsp_scopes", scopes.joinToString(",")).apply()
                if (bound.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L) {
                    bound.getRemotePreferences(LsposedClipboardContract.REMOTE_GROUP).edit()
                        .putBoolean(LsposedClipboardSettings.KEY_ENABLED, LsposedClipboardSettings.isEnabled(app))
                        .putLong("enabled_at", prefs.getLong("lsposed_enabled_at", Long.MAX_VALUE)).commit()
                }
                val targets = bound.runningTargets.filter { it.uid == 1000 || it.processName == "system_server" }
                prefs.edit().putString("lsp_framework", framework)
                    .putString("lsp_scopes", scopes.joinToString(","))
                    .putString("lsp_targets", targets.joinToString("; ") { "${it.processName}:${it.loadedVersionCode}:${it.state}" })
                    .putString("lsp_framework_error", "").apply()
            } catch (error: Exception) {
                prefs.edit().putString("lsp_framework_error", error.javaClass.simpleName).apply()
            }
        }
    }
}
