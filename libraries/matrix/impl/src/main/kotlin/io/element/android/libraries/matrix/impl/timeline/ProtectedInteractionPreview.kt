/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.timeline.item.event.EventContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedOperationContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType

/** Called only after requireCurrentProjection has validated the decrypted interaction. */
internal fun protectedInteractionPreview(message: MessageContent, decrypted: EventContent): MessageContent = message.copy(
    body = "",
    type = TextMessageType("", formatted = null),
    protectedMessageStatus = null,
    isContentProtectionAuthorized = true,
    clipboardSource = null,
    protectedPreviewOperation = (decrypted as? ProtectedOperationContent)?.operation,
)
