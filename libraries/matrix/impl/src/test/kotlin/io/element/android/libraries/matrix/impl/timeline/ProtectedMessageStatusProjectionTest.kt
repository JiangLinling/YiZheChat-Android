/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedOperationContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.test.A_TRANSACTION_ID
import io.element.android.libraries.matrix.test.A_UNIQUE_ID
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import org.junit.Test

class ProtectedMessageStatusProjectionTest {
    private val event = MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem(
        isOwn = true, transactionId = A_TRANSACTION_ID, content = aMessageContent(body = "encrypted carrier"),
    ))

    @Test
    fun `pending call binding is hidden without changing original Matrix identity`() {
        val projected = projectProtectedMessageStatus(event, null,
            ContentProtectionException.SendBindingPending().protectedMessageDisplayState(), ContentKind.CALL_OPERATION) as MatrixTimelineItem.Event
        val hidden = projected.event.content as ProtectedOperationContent
        assertThat(hidden.operation).isEqualTo(ProtectedOperationContent.Operation.HIDDEN)
        assertThat(hidden.targetEventId).isEmpty()
        assertThat(hidden.callLifecycleState).isNull()
        assertThat(hidden.protectedMessageStatus).isEqualTo(ProtectedMessageDisplayState.SEND_BINDING_PENDING.status)
        assertThat(projected.uniqueId).isEqualTo(event.uniqueId)
        assertThat(projected.event.copy(content = event.event.content, reactions = event.event.reactions)).isEqualTo(event.event)
        assertThat(event.event.content).isInstanceOf(MessageContent::class.java)
    }

    @Test
    fun `all internal operation failure and cached status projections remain hidden`() {
        OPERATION_CONTENT_KINDS.forEach { kind ->
            ProtectedMessageDisplayState.entries.forEach { state ->
                val projected = projectProtectedMessageStatus(event, null, state, kind) as MatrixTimelineItem.Event
                val hidden = projected.event.content as ProtectedOperationContent
                assertThat(hidden.operation).isEqualTo(ProtectedOperationContent.Operation.HIDDEN)
                assertThat(hidden.protectedMessageStatus).isEqualTo(state.status)
            }
        }
    }

    @Test
    fun `ordinary text pending and denied states keep visible fail closed status`() {
        listOf(ContentKind.TEXT, null).forEach { kind ->
            ProtectedMessageDisplayState.entries.forEach { state ->
                val projected = projectProtectedMessageStatus(event, null, state, kind, "original-content") as MatrixTimelineItem.Event
                val message = projected.event.content as MessageContent
                assertThat(message.type).isInstanceOf(TextMessageType::class.java)
                assertThat(message.protectedMessageStatus).isEqualTo(state.status)
                assertThat(message.body).isEmpty()
                assertThat(message.isContentProtectionAuthorized).isFalse()
                assertThat(message.clipboardSource).isNull()
                assertThat(message.confirmedProtectedContentId).isEqualTo("original-content")
            }
        }
    }

    @Test
    fun `LC20 producer memory accepts only original call operation without granting receive`() {
        ContentKind.entries.forEach { kind ->
            val owner = LifecycleOperationContext("original-lc20-call-operation", FullAppLifecycleId.LC_20)
            assertThat(PendingProtectedPayloadCache.acceptsOwner(owner, kind)).isEqualTo(kind == ContentKind.CALL_OPERATION)
            assertThat(isInteractionProjectionOwner(owner.lifecycleId, kind)).isEqualTo(kind == ContentKind.CALL_OPERATION)
        }
        assertThat(PendingProtectedPayloadCache.acceptsOwner(
            LifecycleOperationContext("original-lc15-call-operation", FullAppLifecycleId.LC_15), ContentKind.CALL_OPERATION)).isTrue()
    }

    @Test
    fun `LC20 call echo rejects stale authorization and expires or destroys only original content`() {
        var now = 1_000L
        val cache = PendingProtectedPayloadCache({ now })
        val key = PendingProtectedPayloadCache.Key(ContentId("call-content"), "account", "room", "ciphertext")
        val identity = PendingProtectedPayloadCache.Identity("device", "installation", "full-envelope", 7L, 9L)
        val owner = PendingProtectedPayloadCache.Owner(
            LifecycleOperationContext("original-lc20-call-operation", FullAppLifecycleId.LC_20), identity, 1_050L, ContentKind.CALL_OPERATION,
        )
        cache.put(key, "local-state".encodeToByteArray(), owner)
        val ticket = checkNotNull(cache.ownership(key, identity))
        assertThat(ticket.owner.operation).isSameInstanceAs(owner.operation)
        listOf(identity.copy(authorizationRevision = 8L), identity.copy(previewRevision = 10L),
            identity.copy(installationId = "other"), identity.copy(envelopeHash = "altered")
        ).forEach { assertThat(cache.ownership(key, it)).isNull() }
        val other = key.copy(contentId = ContentId("other-content"))
        cache.put(other, "other".encodeToByteArray(), owner)
        cache.remove(key.contentId)
        assertThat(cache.get(ticket)).isNull()
        assertThat(cache.ownership(other, identity)).isNotNull()
        now = 1_050L
        assertThat(cache.ownership(other, identity)).isNull()
    }
}
