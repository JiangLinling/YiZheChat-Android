/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.model.event

import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.timeline.item.event.EventType

// Actor identities belong to the terminal action, not the original invitation sender.
sealed interface RtcNotificationState {
    /** Retain all legacy decline actors, or the protected lifecycle action actor. */
    data class Declined(val byMe: Boolean, val actors: List<UserId> = emptyList()) : RtcNotificationState

    data class Cancelled(val byMe: Boolean, val actor: UserId? = null) : RtcNotificationState

    data class Missed(val byMe: Boolean, val actor: UserId? = null) : RtcNotificationState

    data class Ended(val durationMillis: Long?) : RtcNotificationState

    data object Connected : RtcNotificationState

    data object Started : RtcNotificationState
}

class TimelineItemRtcNotificationContent(
    val callIntent: CallIntent,
    val state: RtcNotificationState,
) : TimelineItemEventContent {
    override val type: String = EventType.RTC_NOTIFICATION
}
