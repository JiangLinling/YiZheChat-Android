/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.push.api.notifications

import android.app.Notification
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Activity-owned, in-memory presentation of notifications while the unlocked app is visible. */
interface ForegroundNotificationHost {
    fun setResumed(resumed: Boolean)
    /** Returns true when the visible unlocked app owns the incoming-call presentation. */
    fun showIncomingCall(id: Int, notification: Notification): Boolean
    fun clearIncomingCall(id: Int)

    @Composable
    fun Render(modifier: Modifier = Modifier)
}
