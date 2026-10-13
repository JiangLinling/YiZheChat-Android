/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.roomlist

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import org.junit.Test

class RoomPreviewIdentityRetryGateTest {
    private val room = RoomId("!room:next")

    @Test
    fun `unrelated updates coalesce running and failed identity lookups`() {
        var time = 0L
        val gate = RoomPreviewIdentityRetryGate<String>(now = { time })
        val first = checkNotNull(gate.begin(room, "exact encrypted summary"))
        repeat(100) { assertThat(gate.begin(room, first.signature)).isNull() }
        gate.complete(first, success = false)
        repeat(100) { assertThat(gate.begin(room, first.signature)).isNull() }
        time = 1_000
        assertThat(gate.begin(room, first.signature)).isNotNull()
    }

    @Test
    fun `repeated failures back off but remain retryable`() {
        var time = 0L
        val gate = RoomPreviewIdentityRetryGate<String>(now = { time })
        for (delay in listOf(1_000L, 5_000L, 30_000L, 30_000L)) {
            gate.complete(checkNotNull(gate.begin(room, "same")), success = false)
            time += delay - 1
            assertThat(gate.begin(room, "same")).isNull()
            time++
        }
        assertThat(gate.begin(room, "same")).isNotNull()
    }

    @Test
    fun `new message bypasses old backoff and obsolete completion`() {
        val gate = RoomPreviewIdentityRetryGate<String>(now = { 0L })
        val old = checkNotNull(gate.begin(room, "old"))
        gate.complete(old, success = false)
        val current = checkNotNull(gate.begin(room, "new"))
        gate.complete(old, success = true)
        assertThat(gate.begin(room, "new")).isNull()
        gate.complete(current, success = true)
        assertThat(gate.begin(room, "new")).isNotNull()
    }

    @Test
    fun `successful identity is not a cached read permission`() {
        val gate = RoomPreviewIdentityRetryGate<String>(now = { 0L })
        gate.complete(checkNotNull(gate.begin(room, "same")), success = true)
        assertThat(gate.begin(room, "same")).isNotNull()
    }

    @Test
    fun `capacity evicts idle metadata but never duplicates active work`() {
        val gate = RoomPreviewIdentityRetryGate<String>(now = { 0L }, capacity = 1)
        val active = checkNotNull(gate.begin(room, "one"))
        val other = RoomId("!other:next")
        assertThat(gate.begin(other, "two")).isNull()
        gate.complete(active, success = false)
        assertThat(gate.begin(other, "two")).isNotNull()
        gate.complete(active, success = true)
        assertThat(gate.begin(other, "two")).isNull()
    }

    @Test
    fun `room identity separates equal summaries`() {
        val gate = RoomPreviewIdentityRetryGate<String>(now = { 0L })
        gate.complete(checkNotNull(gate.begin(room, "same")), success = false)
        assertThat(gate.begin(RoomId("!other:next"), "same")).isNotNull()
    }
}
