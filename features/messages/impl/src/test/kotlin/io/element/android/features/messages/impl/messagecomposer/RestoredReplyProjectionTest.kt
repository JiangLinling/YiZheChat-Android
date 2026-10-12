/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.features.messages.impl.messagecomposer

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedOperationContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.aProfileDetails
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import org.junit.Test

class RestoredReplyProjectionTest {
    private val details = InReplyToDetails.Ready(
        AN_EVENT_ID, A_USER_ID, aProfileDetails(),
        ProtectedOperationContent(1, "original-operation", ProtectedOperationContent.Operation.HIDDEN, AN_EVENT_ID.value, null), null,
    )
    private val event = MessageComposerEvent.RestoreReplyProjection(details, "authorized current summary", true)

    @Test fun `disappearing authorized projection clears only the same captured summary`() {
        val restored = restoreReplyProjection(MessageComposerMode.Reply(details, false), event) as MessageComposerMode.Reply
        val capture = restored.replyToDetails as InReplyToDetails.Ready
        val invalidate = MessageComposerEvent.InvalidateReplyProjection(capture)
        val cleared = invalidateReplyProjection(restored, invalidate) as MessageComposerMode.Reply
        val clearedDetails = cleared.replyToDetails as InReplyToDetails.Ready
        assertThat(clearedDetails.textContent).isNull()
        assertThat(clearedDetails.isTextContentAuthorizedProjection).isFalse()
        assertThat(cleared.eventId).isEqualTo(AN_EVENT_ID)
        assertThat(invalidateReplyProjection(MessageComposerMode.Normal, invalidate)).isEqualTo(MessageComposerMode.Normal)
        val switched = restored.copy(replyToDetails = capture.copy(eventId = EventId("\$other-event")))
        assertThat(invalidateReplyProjection(switched, invalidate)).isSameInstanceAs(switched)
    }

    @Test fun `same authorized target may refresh its changed summary`() {
        val restored = restoreReplyProjection(MessageComposerMode.Reply(details, false), event) as MessageComposerMode.Reply
        val refreshed = restoreReplyProjection(restored, event.copy(expectedDetails = restored.replyToDetails as InReplyToDetails.Ready, textContent = "new authorized summary")) as MessageComposerMode.Reply
        assertThat((refreshed.replyToDetails as InReplyToDetails.Ready).textContent).isEqualTo("new authorized summary")
    }

    @Test fun `initial decrypting reply can use the current authorized projection`() {
        val pending = details.copy(eventContent = aMessageContent("").copy(protectedMessageStatus = ProtectedMessageStatus.DECRYPTING))
        val result = restoreReplyProjection(MessageComposerMode.Reply(pending, false), event.copy(expectedDetails = pending)) as MessageComposerMode.Reply
        assertThat((result.replyToDetails as InReplyToDetails.Ready).isTextContentAuthorizedProjection).isTrue()
    }

    @Test fun `terminal and unopened replies cannot inherit a projection`() {
        for (status in listOf(ProtectedMessageStatus.EXPIRED, ProtectedMessageStatus.REVOKED, ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED)) {
            val terminal = details.copy(eventContent = aMessageContent("").copy(protectedMessageStatus = status))
            val mode = MessageComposerMode.Reply(terminal, false)
            assertThat(restoreReplyProjection(mode, event.copy(expectedDetails = terminal))).isSameInstanceAs(mode)
        }
    }

    @Test fun `encrypted text carrier restores only its current authorized summary`() {
        val carrier = details.copy(eventContent = aMessageContent("yizhe-secure-v1:opaque"))
        val mode = MessageComposerMode.Reply(carrier, false)
        val result = restoreReplyProjection(mode, event.copy(expectedDetails = carrier)) as MessageComposerMode.Reply
        assertThat((result.replyToDetails as InReplyToDetails.Ready).textContent).isEqualTo(event.textContent)
        assertThat(restoreReplyProjection(mode, event.copy(expectedDetails = carrier, textContent = ""))).isSameInstanceAs(mode)
    }

    @Test fun `same protected reply can recover volatile authorized summary`() {
        val result = restoreReplyProjection(MessageComposerMode.Reply(details, false), event) as MessageComposerMode.Reply
        val ready = result.replyToDetails as InReplyToDetails.Ready
        assertThat(ready.textContent).isEqualTo("authorized current summary")
        assertThat(ready.isTextContentAuthorizedProjection).isTrue()
        assertThat(result.eventId).isEqualTo(AN_EVENT_ID)
        assertThat(result.hideImage).isTrue()
    }

    @Test fun `cancelled and switched replies reject captured old projection`() {
        assertThat(restoreReplyProjection(MessageComposerMode.Normal, event)).isEqualTo(MessageComposerMode.Normal)
        val switched = MessageComposerMode.Reply(details.copy(eventId = EventId("\$other-event")), false)
        assertThat(restoreReplyProjection(switched, event)).isSameInstanceAs(switched)
    }

    @Test fun `changed details cannot inherit a captured summary`() {
        val changed = MessageComposerMode.Reply(details.copy(textContent = "new state"), false)
        assertThat(restoreReplyProjection(changed, event)).isSameInstanceAs(changed)
    }

    @Test fun `ordinary content cannot be relabelled as a protected projection`() {
        val ordinary = details.copy(eventContent = aMessageContent("ordinary"))
        val mode = MessageComposerMode.Reply(ordinary, false)
        assertThat(restoreReplyProjection(mode, event.copy(expectedDetails = ordinary))).isSameInstanceAs(mode)
    }
}
