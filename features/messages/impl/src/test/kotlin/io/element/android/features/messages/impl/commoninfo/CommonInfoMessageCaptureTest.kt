/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.commoninfo

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.fixtures.aMessageEvent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextContent
import org.junit.Test
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource
import kotlinx.collections.immutable.persistentSetOf

class CommonInfoMessageCaptureTest {
    private fun copyEligibleEvent(content: TimelineItemEventContent) = aMessageEvent(content = content).copy(clipboardSource = MessageClipboardSource.Unprotected)

    private fun text(body: String) = TimelineItemTextContent(body, null, body, false)

    @Test fun `capture keeps complete literal text and requires an explicit title`() {
        val event = copyEligibleEvent(content = text("/address\n收件人 张三\n示例路 66 号"))
        val capture = checkNotNull(captureCommonInfoMessage(event))
        assertThat(capture.draft.content).isEqualTo("/address\n收件人 张三\n示例路 66 号")
        assertThat(capture.draft.title).isEmpty()
        assertThat(capture.draft.bodyRevision).isEqualTo(0)
        assertThat(capture.isValid(listOf(event))).isTrue()
    }

    @Test fun `burn shells redactions optimistic echoes and oversized text never export`() {
        val ordinary = copyEligibleEvent(content = text("private text"))
        val blocked = listOf(
            ordinary.copy(content = text("private text").copy(isBurnAfterRead = true)),
            ordinary.copy(content = text("status").copy(isProtectedContentShell = true)),
            ordinary.copy(content = TimelineItemRedactedContent),
            ordinary.copy(eventId = null),
            ordinary.copy(content = text(" ")),
            ordinary.copy(content = text("x".repeat(20_001))),
        )
        blocked.forEach { assertThat(captureCommonInfoMessage(it)).isNull() }
    }

    @Test fun `source edits removal and authorization loss invalidate the volatile capture`() {
        val event = copyEligibleEvent(content = text("old address"))
        val capture = checkNotNull(captureCommonInfoMessage(event))
        assertThat(capture.isValid(emptyList())).isFalse()
        assertThat(capture.isValid(listOf(event.copy(content = text("new address"))))).isFalse()
        assertThat(capture.isValid(listOf(event.copy(content = text("locked").copy(isProtectedContentShell = true))))).isFalse()
    }

    @Test fun `standalone identity and phone values use masked summary kinds`() {
        val identity = checkNotNull(captureCommonInfoMessage(copyEligibleEvent(content = text("12345678901234567X"))))
        val phone = checkNotNull(captureCommonInfoMessage(copyEligibleEvent(content = text("13800000000"))))
        assertThat(identity.draft.kind).isEqualTo(CommonInfoKind.IDENTITY)
        assertThat(phone.draft.kind).isEqualTo(CommonInfoKind.PHONE)
        assertThat(identity.draft.maskedSummary()).doesNotContain("123456")
        assertThat(phone.draft.maskedSummary()).isEqualTo("138 **** 0000")
    }
    @Test fun `unknown source and expired or mismatched protected source fail closed`() {
        val event = copyEligibleEvent(content = text("address"))
        assertThat(captureCommonInfoMessage(event.copy(clipboardSource = null))).isNull()
        val source = MessageClipboardSource.Protected("@test:yizhe.test", "!test:yizhe.test", checkNotNull(event.eventId).value,
            "version-1", persistentSetOf("content-1"), 1L, 1L)
        assertThat(captureCommonInfoMessage(event.copy(clipboardSource = source))).isNull()
        assertThat(captureCommonInfoMessage(event.copy(clipboardSource = source.copy(eventId = "other-event", expiresAtEpochMillis = Long.MAX_VALUE)))).isNull()
    }

    @Test fun `same text with changed source authorization does not keep a capture valid`() {
        val event = copyEligibleEvent(content = text("address"))
        val source = MessageClipboardSource.Protected("@test:yizhe.test", "!test:yizhe.test", checkNotNull(event.eventId).value,
            "version-1", persistentSetOf("content-1"), 1L, Long.MAX_VALUE)
        val projected = event.copy(clipboardSource = source)
        val capture = checkNotNull(captureCommonInfoMessage(projected))
        assertThat(capture.isValid(listOf(projected.copy(clipboardSource = source.copy(authorizationRevision = 2L))))).isFalse()
        assertThat(capture.isValid(listOf(projected.copy(clipboardSource = null)))).isFalse()
    }

}
