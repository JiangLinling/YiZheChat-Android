/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedOperationContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import org.junit.Test

class ProtectedInteractionPreviewTest {
    @Test
    fun `preview retains authenticated operation without carrier or entry payload`() {
        val carrier = aMessageContent(body = "yizhe-secure-v1:encrypted-envelope").copy(clipboardSource = MessageClipboardSource.Unprotected)
        val close = ProtectedOperationContent(
            protocolVersion = 1, operationId = "qa-close", operation = ProtectedOperationContent.Operation.RELAY_CLOSE,
            targetEventId = "qa-root", aggregateId = null, relayEntry = "must not appear",
        )
        val preview = protectedInteractionPreview(carrier, close)
        assertThat(preview.protectedPreviewOperation).isEqualTo(ProtectedOperationContent.Operation.RELAY_CLOSE)
        assertThat(preview.body).isEmpty()
        assertThat((preview.type as TextMessageType).body).isEmpty()
        assertThat(preview.clipboardSource).isNull()
        assertThat(preview.isContentProtectionAuthorized).isTrue()
        assertThat(preview.protectedMessageStatus).isNull()
    }

    @Test
    fun `ordinary decrypted content cannot invent a relay end marker`() {
        val preview = protectedInteractionPreview(aMessageContent(), aMessageContent(body = "RELAY_CLOSE"))
        assertThat(preview.protectedPreviewOperation).isNull()
        assertThat(preview.body).isEmpty()
    }
}
