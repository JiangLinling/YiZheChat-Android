/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl.timeline.factories.event

import io.element.android.features.messages.impl.timeline.model.event.RtcNotificationState
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent

internal fun CallNotifyContent.toRtcNotificationState(sessionId: SessionId): RtcNotificationState = when (lifecycleState) {
    CallLifecycleState.CONNECTED -> RtcNotificationState.Connected
    CallLifecycleState.DECLINED -> RtcNotificationState.Declined(lifecycleActor == sessionId, listOfNotNull(lifecycleActor))
    CallLifecycleState.CANCELLED -> RtcNotificationState.Cancelled(lifecycleActor == sessionId, lifecycleActor)
    CallLifecycleState.MISSED -> RtcNotificationState.Missed(lifecycleActor == sessionId, lifecycleActor)
    CallLifecycleState.ENDED -> RtcNotificationState.Ended(durationMillis)
    null -> if (declinedBy.isEmpty()) {
        RtcNotificationState.Started
    } else {
        RtcNotificationState.Declined(sessionId in declinedBy, declinedBy.distinct())
    }
}
