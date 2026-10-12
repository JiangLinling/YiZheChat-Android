/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent

internal data class CommonInfoSendConfirmation(val contentId: String, val eventId: String, val timestamp: Long)

/** Pending transaction matching happens in the repository; this function never grants permission to read a body. */
internal fun commonInfoSendConfirmation(item: MatrixTimelineItem.Event): CommonInfoSendConfirmation? {
    if (!item.event.isOwn) return null
    val content = item.event.content as? MessageContent ?: return null
    val eventId = (item.event.localSendState as? LocalEventSendState.Sent)?.eventId ?: item.event.eventId ?: return null
    val contentId = content.authenticatedContentId?.takeIf { content.isContentProtectionAuthorized }
        ?: content.confirmedProtectedContentId ?: return null
    return CommonInfoSendConfirmation(contentId, eventId.value, item.event.timestamp)
}
