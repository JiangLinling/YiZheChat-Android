/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl.timeline.components

import androidx.annotation.StringRes
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.model.event.RtcNotificationState

// Do not infer a terminal actor's name from the invitation profile. Neutral wording also
// protects absent/redacted actor profiles and avoids exposing Matrix IDs as name fallbacks.
@StringRes
internal fun RtcNotificationState.terminalDetailResource(isDm: Boolean): Int? {
    if (!isDm) return null
    return when (this) {
        is RtcNotificationState.Declined -> if (byMe) R.string.screen_room_call_detail_declined_by_you
            else R.string.screen_room_call_detail_declined
        is RtcNotificationState.Cancelled -> if (byMe) R.string.screen_room_call_detail_cancelled_by_you
            else R.string.screen_room_call_detail_cancelled
        is RtcNotificationState.Missed -> if (byMe) R.string.screen_room_call_detail_missed_by_you
            else R.string.screen_room_call_detail_missed
        else -> null
    }
}
