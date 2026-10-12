/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.factories.event

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Test

class TimelineClipboardSourceProjectionTest {
    @Test
    fun `missing classification fails closed for a normal message`() {
        assertThat(message().toTimelineItemClipboardSource(EVENT_ID)).isNull()
    }

    @Test
    fun `explicit unprotected classification is projected only for an unprotected message`() {
        val content = message(clipboardSource = MessageClipboardSource.Unprotected)

        assertThat(content.toTimelineItemClipboardSource(EVENT_ID))
            .isEqualTo(MessageClipboardSource.Unprotected)
        assertThat(content.copy(isContentProtectionAuthorized = true).toTimelineItemClipboardSource(EVENT_ID))
            .isNull()
    }

    @Test
    fun `protected classification requires authorization and the same event`() {
        val source = protectedSource()

        assertThat(
            message(
                isContentProtectionAuthorized = true,
                clipboardSource = source,
            ).toTimelineItemClipboardSource(EVENT_ID),
        ).isEqualTo(source)
        assertThat(message(clipboardSource = source).toTimelineItemClipboardSource(EVENT_ID)).isNull()
        assertThat(
            message(
                isContentProtectionAuthorized = true,
                clipboardSource = source,
            ).toTimelineItemClipboardSource(EventId("$" + OTHER_EVENT_ID)),
        ).isNull()
    }

    @Test
    fun `protected shell and burn after read never project clipboard authority`() {
        val source = protectedSource()
        val authorized = message(
            isContentProtectionAuthorized = true,
            clipboardSource = source,
        )

        assertThat(
            authorized.copy(protectedMessageStatus = ProtectedMessageStatus.DECRYPTING)
                .toTimelineItemClipboardSource(EVENT_ID)
        ).isNull()
        assertThat(authorized.copy(isBurnAfterRead = true).toTimelineItemClipboardSource(EVENT_ID)).isNull()
    }

    private fun message(
        isContentProtectionAuthorized: Boolean = false,
        clipboardSource: MessageClipboardSource? = null,
    ) = MessageContent(
        body = "body",
        inReplyTo = null,
        isEdited = false,
        threadInfo = null,
        type = TextMessageType("body", null),
        isContentProtectionAuthorized = isContentProtectionAuthorized,
        clipboardSource = clipboardSource,
    )

    private fun protectedSource() = MessageClipboardSource.Protected(
        sessionId = "session-a",
        roomId = "room-a",
        eventId = EVENT_ID.value,
        contentVersion = "version-a",
        contentIds = persistentSetOf("content-a"),
        authorizationRevision = 7L,
        expiresAtEpochMillis = 10_000L,
    )

    private companion object {
        const val OTHER_EVENT_ID = "other"
        val EVENT_ID = EventId("$" + "event-a")
    }
}
