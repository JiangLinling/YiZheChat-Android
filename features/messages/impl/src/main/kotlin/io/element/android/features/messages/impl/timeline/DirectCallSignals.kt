/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.features.messages.impl.timeline

import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent

/** The widget's join announcement is not a second direct call. Keep the underlying events and
 * every ring, terminal/declined record, unknown legacy record, and all group call announcements.
 */
internal fun List<MatrixTimelineItem>.withoutDirectCallSignals(isDm: Boolean): List<MatrixTimelineItem> {
    if (!isDm) return this
    return filterNot { item ->
        val call = (item as? MatrixTimelineItem.Event)?.event?.content as? CallNotifyContent
        call?.notificationType == RtcNotificationType.NOTIFY && call.lifecycleState == null && call.declinedBy.isEmpty()
    }
}
