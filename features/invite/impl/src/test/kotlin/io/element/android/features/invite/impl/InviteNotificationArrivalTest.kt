/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.invite.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.invite.api.InviteNotification
import io.element.android.libraries.matrix.api.core.RoomId
import org.junit.Test

class InviteNotificationArrivalTest {
    private val request = InviteNotification(RoomId("!request:example.test"), InviteNotification.Kind.FRIEND, "@peer:example.test", 100, sequence = 1)
    @Test fun `confirmed acceptance can recover unavailable projection without rewriting refusal`() {
        val unavailable = request.copy(outcome = InviteNotification.Outcome.UNAVAILABLE, seen = true)
        val accepted = InviteNotificationCodec.confirmOutcome(unavailable, InviteNotification.Outcome.ACCEPTED, 200, false)
        assertThat(accepted.outcome).isEqualTo(InviteNotification.Outcome.ACCEPTED)
        assertThat(accepted.seen).isFalse()
        val declined = request.copy(outcome = InviteNotification.Outcome.DECLINED, seen = true)
        assertThat(InviteNotificationCodec.confirmOutcome(declined, InviteNotification.Outcome.ACCEPTED, 200, false)).isEqualTo(declined)
    }
    @Test fun `encrypted snapshots survive unicode separators and newlines`() {
        val record = request.copy(displayName = "讨论\t组\n😀", avatarUrl = "mxc://example.test/avatar", receivedAt = 180, active = false)
        assertThat(InviteNotificationCodec.decode(InviteNotificationCodec.encode(record))).isEqualTo(record)
        assertThat(InviteNotificationCodec.decode("!r:x\tFRIEND\t@u:x\t1\tfalse\tPENDING\t0\tfalse")?.sequence).isEqualTo(0L)
    }
    @Test fun `reinvite compares invitation clocks instead of local handled time`() {
        val handled = request.copy(outcome = InviteNotification.Outcome.DECLINED, handledAt = 9000, seen = true)
        val new = InviteNotificationCodec.merge(listOf(handled), listOf(request.copy(timestamp = 150)), receivedAt = 10).single()
        assertThat(new.outcome).isEqualTo(InviteNotification.Outcome.PENDING)
        assertThat(new.sequence).isEqualTo(2)
        assertThat(new.seen).isFalse()
    }
    @Test fun `complete removal then reappearance is fresh even with zero timestamp`() {
        val zero = request.copy(timestamp = 0, seen = true)
        val gone = InviteNotificationCodec.merge(listOf(zero), emptyList(), completeSnapshot = true)
        val new = InviteNotificationCodec.merge(gone, listOf(zero.copy(seen = false)), completeSnapshot = true).single()
        assertThat(new.sequence).isEqualTo(2)
        assertThat(new.active).isTrue()
        assertThat(new.seen).isFalse()
        assertThat(InviteNotificationCodec.merge(listOf(zero), emptyList()).single().active).isTrue()
    }
    @Test fun `all new types precede history independently of server and local clock`() {
        val old = request.copy(timestamp = 999999, handledAt = 9999999, outcome = InviteNotification.Outcome.ACCEPTED, sequence = 10)
        InviteNotification.Kind.entries.forEach { kind ->
            val new = request.copy(roomId = RoomId("!new:example.test"), kind = kind, timestamp = 1, sequence = 0)
            val merged = InviteNotificationCodec.merge(listOf(old), listOf(new), receivedAt = 0)
            assertThat(merged.first().roomId).isEqualTo(new.roomId)
            assertThat(InviteNotificationCodec.merge(merged, listOf(new))).isEqualTo(merged)
        }
    }
    @Test fun `history limit never drops pending requests or unread results`() {
        val records = (0..230).map { request.copy(roomId = RoomId("!r$it:example.test"), timestamp = it.toLong(), sequence = 0) }
        assertThat(InviteNotificationCodec.merge(records, emptyList())).hasSize(231)
        assertThat(InviteNotificationCodec.merge(records.map { it.copy(outcome = InviteNotification.Outcome.ACCEPTED) }, emptyList())).hasSize(231)
    }
}
