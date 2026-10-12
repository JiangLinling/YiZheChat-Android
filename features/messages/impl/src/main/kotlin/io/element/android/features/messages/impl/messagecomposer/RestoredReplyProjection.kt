/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.features.messages.impl.messagecomposer

import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedOperationContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.isAuthorizedForUserDisplay
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import io.element.android.libraries.matrix.ui.messages.reply.supportsAuthorizedTextProjection
import io.element.android.libraries.textcomposer.model.MessageComposerMode

internal fun requiresAuthorizedReplyProjection(details: InReplyToDetails.Ready): Boolean =
    !details.isTextContentAuthorizedProjection && details.supportsAuthorizedTextProjection() && when (val content = details.eventContent) {
        is ProtectedOperationContent -> true
        is MessageContent -> content.protectedMessageStatus != null || !content.isAuthorizedForUserDisplay()
        else -> false
    }

internal fun restoreReplyProjection(
    currentMode: MessageComposerMode,
    event: MessageComposerEvent.RestoreReplyProjection,
): MessageComposerMode {
    val reply = currentMode as? MessageComposerMode.Reply ?: return currentMode
    if (reply.replyToDetails != event.expectedDetails || !requiresAuthorizedReplyProjection(event.expectedDetails.copy(isTextContentAuthorizedProjection = false)) ||
        event.textContent.isBlank()) return currentMode
    return reply.copy(
        replyToDetails = event.expectedDetails.copy(textContent = event.textContent, isTextContentAuthorizedProjection = true),
        hideImage = event.hideImage,
    )
}

internal fun invalidateReplyProjection(
    currentMode: MessageComposerMode,
    event: MessageComposerEvent.InvalidateReplyProjection,
): MessageComposerMode {
    val reply = currentMode as? MessageComposerMode.Reply ?: return currentMode
    if (reply.replyToDetails != event.expectedDetails || !event.expectedDetails.isTextContentAuthorizedProjection ||
        !requiresAuthorizedReplyProjection(event.expectedDetails.copy(isTextContentAuthorizedProjection = false))) return currentMode
    return reply.copy(
        replyToDetails = event.expectedDetails.copy(textContent = null, isTextContentAuthorizedProjection = false),
        hideImage = true,
    )
}
