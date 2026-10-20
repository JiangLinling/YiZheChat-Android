/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.invite.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.invite.api.InviteNotification
import io.element.android.libraries.matrix.api.core.RoomId
import org.junit.Test

class InviteNotificationCodecTest {
    private val request = InviteNotification(RoomId("!request:example.test"), InviteNotification.Kind.FRIEND, "@peer:example.test", 100, sequence = 1)

    @Test fun `late direct classification corrects metadata without resetting declined history`() {
        val previous = request.copy(kind = InviteNotification.Kind.GROUP, senderId = null,
            outcome = InviteNotification.Outcome.DECLINED, handledAt = 200, seen = true)
        val actual = InviteNotificationCodec.merge(listOf(previous), listOf(request)).single()
        assertThat(actual).isEqualTo(previous.copy(kind = InviteNotification.Kind.FRIEND, senderId = request.senderId))
    }

    @Test fun `joining snapshot cannot downgrade known friend invitation to group`() {
        val transient = request.copy(kind = InviteNotification.Kind.GROUP)
        assertThat(InviteNotificationCodec.merge(listOf(request), listOf(transient)).single().kind)
            .isEqualTo(InviteNotification.Kind.FRIEND)
        assertThat(InviteNotificationCodec.refinedKind(InviteNotification.Kind.FRIEND, InviteNotification.Kind.GROUP))
            .isEqualTo(InviteNotification.Kind.FRIEND)
    }

    @Test fun `metadata survives round trip`() {
        val record = request.copy(outgoing = true, outcome = InviteNotification.Outcome.ACCEPTED, handledAt = 200, seen = true)
        assertThat(InviteNotificationCodec.decode(InviteNotificationCodec.encode(record))).isEqualTo(record)
        assertThat(InviteNotificationCodec.decode(InviteNotificationCodec.encode(request.copy(senderId = null)))).isEqualTo(request.copy(senderId = null))
    }
    @Test fun `malformed records do not break history`() {
        listOf("", "bad", "!r:x\tFRIEND\t@u:x\tno\tfalse\tPENDING\t0\tfalse",
            "!r:x\tUNKNOWN\t@u:x\t1\tfalse\tPENDING\t0\tfalse").forEach {
            assertThat(InviteNotificationCodec.decode(it)).isNull()
        }
    }
    @Test fun `refresh never resets read pending request`() {
        assertThat(InviteNotificationCodec.merge(listOf(request.copy(seen = true)), listOf(request.copy(timestamp = 150))))
            .containsExactly(request.copy(seen = true))
    }
    @Test fun `stale invite cannot resurrect completed action`() {
        val handled = request.copy(outcome = InviteNotification.Outcome.DECLINED, handledAt = 200, seen = true)
        assertThat(InviteNotificationCodec.merge(listOf(handled), listOf(request))).containsExactly(handled)
        assertThat(InviteNotificationCodec.merge(listOf(handled), listOf(request.copy(timestamp = 201))))
            .containsExactly(request.copy(timestamp = 201, sequence = 2))
    }
    @Test fun `history is bounded newest first and deduplicated`() {
        val records = (0..230).map { request.copy(roomId = RoomId("!r$it:example.test"), timestamp = it.toLong(), sequence = 0,
            outcome = InviteNotification.Outcome.ACCEPTED, seen = true) }
        val merged = InviteNotificationCodec.merge(records, records)
        assertThat(merged).hasSize(200)
        assertThat(merged.first().timestamp).isEqualTo(230)
        assertThat(merged.last().timestamp).isEqualTo(31)
    }
    @Test fun `local action after sync confirmation stays read`() {
        val remote = InviteNotificationCodec.confirmOutcome(request, InviteNotification.Outcome.ACCEPTED, 200, false)
        val local = InviteNotificationCodec.confirmOutcome(remote, InviteNotification.Outcome.ACCEPTED, 201, true)
        assertThat(local.seen).isTrue()
        assertThat(local.handledAt).isEqualTo(200)
    }
    @Test fun `late remote confirmation does not make local action unread`() {
        val local = InviteNotificationCodec.confirmOutcome(request, InviteNotification.Outcome.ACCEPTED, 200, true)
        assertThat(InviteNotificationCodec.confirmOutcome(local, InviteNotification.Outcome.ACCEPTED, 201, false)).isEqualTo(local)
    }
    @Test fun `confirmed local decline refines generic membership ended`() {
        val ended = InviteNotificationCodec.confirmOutcome(request, InviteNotification.Outcome.UNAVAILABLE, 200, false)
        assertThat(InviteNotificationCodec.confirmOutcome(ended, InviteNotification.Outcome.DECLINED, 201, true).outcome)
            .isEqualTo(InviteNotification.Outcome.DECLINED)
    }

}
