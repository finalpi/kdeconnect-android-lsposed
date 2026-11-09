/* SPDX-License-Identifier: GPL-3.0-only */
package org.kde.kdeconnect.plugins.clipboard.lsposed;

import android.os.Bundle;
import org.kde.kdeconnect_tp.BuildConfig;

/** Only framework primitives cross this IPC boundary. Never put clipboard text in logs. */
public final class LsposedClipboardContract {
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".lsposedclipboard";
    public static final String ACTION_REFRESH = BuildConfig.APPLICATION_ID + ".LSPOSED_REFRESH";
    public static final String CONTROL_PERMISSION = BuildConfig.APPLICATION_ID + ".LSPOSED_CONTROL";
    public static final int MAX_TEXT_LENGTH = 131072;

    private LsposedClipboardContract() { }

    public static boolean isTrustedCaller(int uid) {
        return uid == 1000; // system_server only; ordinary apps, shell and root are rejected.
    }

    public static boolean acceptsEvent(Bundle event, int appUser, long enabledAt) {
        return event != null && appUser == 0 && event.getInt("user", -1) == appUser
                && event.getInt("device", -1) == 0
                && event.getLong("time", 0) >= enabledAt
                && (!event.containsKey("text") || event.getString("text", "").length() <= MAX_TEXT_LENGTH);
    }
}
