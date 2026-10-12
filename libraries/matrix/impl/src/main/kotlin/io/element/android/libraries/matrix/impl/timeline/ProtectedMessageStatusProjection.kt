/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.EventThreadInfo
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedOperationContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import kotlinx.collections.immutable.persistentListOf

/** A kind selects a fail-closed shell; it never authorizes payload or aggregate state. */
internal fun projectProtectedMessageStatus(
    eventItem: MatrixTimelineItem.Event,
    threadInfo: EventThreadInfo?,
    state: ProtectedMessageDisplayState,
    kind: ContentKind?,
    confirmedContentId: String? = null,
): MatrixTimelineItem {
    // Pending, rejected and cached failures remain internal operations. Only a later
    // authorized decode of the original raw event may change the target call card.
    if (kind in OPERATION_CONTENT_KINDS) return hiddenProtectedOperationItem(eventItem, state)
    return eventItem.copy(event = eventItem.event.copy(content = MessageContent(
        body = "",
        inReplyTo = null,
        isEdited = false,
        threadInfo = threadInfo,
        type = TextMessageType("", formatted = null),
        confirmedProtectedContentId = confirmedContentId,
    ).withProtectedDisplayState(state)))
}

internal fun hiddenProtectedOperationItem(
    eventItem: MatrixTimelineItem.Event,
    state: ProtectedMessageDisplayState? = null,
): MatrixTimelineItem = eventItem.copy(
    event = eventItem.event.copy(
        content = ProtectedOperationContent(
            protocolVersion = 1,
            // Display sentinel only. The raw SDK event/transaction and encrypted producer journal
            // retain the original operation; this sentinel creates no lifecycle or receipt.
            operationId = "hidden:${eventItem.eventId?.value ?: eventItem.transactionId?.value ?: eventItem.uniqueId.value}",
            operation = ProtectedOperationContent.Operation.HIDDEN,
            targetEventId = "",
            aggregateId = null,
            protectedMessageStatus = state?.status,
        ),
        reactions = persistentListOf(),
    )
)

internal fun isInteractionProjectionOwner(lifecycleId: FullAppLifecycleId, kind: ContentKind): Boolean =
    lifecycleId == FullAppLifecycleId.LC_15 || lifecycleId == FullAppLifecycleId.LC_20 && kind == ContentKind.CALL_OPERATION
