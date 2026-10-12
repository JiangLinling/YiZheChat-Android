/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.emotions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmotionArrivalGateTest {
    @Test fun `catalog has 60 unique readable tokens in three balanced tiers`() {
        assertEquals(60, ChatEmotions.all.map { it.id }.toSet().size)
        assertEquals(60, ChatEmotions.all.map { it.token }.toSet().size)
        assertEquals(listOf(30, 18, 12), (0..2).map { tier -> ChatEmotions.all.count { it.tier == tier } })
        ChatEmotions.all.forEach { assertEquals(it, ChatEmotions.fromBody(it.token)) }
        assertEquals(ChatEmotions.byId("heart"), ChatEmotions.fromBody("❤"))
        listOf("hello 🎆", "🎆🎆", " 🎆", "[unknown]", "❤️\n").forEach { assertNull(ChatEmotions.fromBody(it)) }
    }
    @Test fun `entry history and backfill never autoplay`() {
        val gate = EmotionArrivalGate(1000)
        assertNull(gate.observe(listOf(EmotionArrival("history", 1000, true)), 1001, true))
        assertNull(gate.observe(listOf(EmotionArrival("backfill", 999, true)), 1002, true))
        assertNull(gate.observe(listOf(EmotionArrival("stale", 1001, true)), 20000, true))
    }
    @Test fun `first message in empty room works and stable local echo never replays`() {
        val gate = EmotionArrivalGate(1000)
        gate.observe(emptyList(), 1000, true)
        val rows = listOf(EmotionArrival("echo", 1002, true))
        assertEquals("echo", gate.observe(rows, 1003, true))
        assertNull(gate.observe(rows, 1004, true))
    }
    @Test fun `disabled background and undecrypted events are consumed without catchup`() {
        for (eligible in listOf(true, false)) {
            val gate = EmotionArrivalGate(1000)
            gate.observe(emptyList(), 1000, true)
            val row = EmotionArrival("event", 1001, eligible)
            assertNull(gate.observe(listOf(row), 1002, false))
            assertNull(gate.observe(listOf(row.copy(eligible = true)), 1003, true))
        }
    }
    @Test fun `a burst chooses only one effect and drops the rest`() {
        val gate = EmotionArrivalGate(1000)
        gate.observe(emptyList(), 1000, true)
        val rows = (1..20).map { EmotionArrival("$it", 1001, true) }
        assertEquals("1", gate.observe(rows, 1002, true))
        assertNull(gate.observe(rows, 1003, true))
    }
    @Test fun `resume rejects unseen messages received while suspended`() {
        val gate = EmotionArrivalGate(1000)
        gate.observe(emptyList(), 1000, true)
        gate.resume(5000)
        assertNull(gate.observe(listOf(EmotionArrival("background", 4999, true)), 5001, true))
        assertEquals("foreground", gate.observe(listOf(EmotionArrival("foreground", 5002, true)), 5003, true))
    }
    @Test fun `only a fresh live envelope may finish decrypting within the bounded window`() {
        val gate = EmotionArrivalGate(1000)
        gate.observe(emptyList(), 1000, true)
        val live = EmotionArrival("live", 1001, false, awaitingLiveDecryption = true)
        assertNull(gate.observe(listOf(live), 1002, true))
        assertEquals("live", gate.observe(listOf(live.copy(eligible = true, awaitingLiveDecryption = false)), 1100, true))
        assertNull(gate.observe(listOf(live.copy(eligible = true)), 1101, true))
    }
    @Test fun `expired decryption and historical envelopes do not replay`() {
        val gate = EmotionArrivalGate(1000)
        val history = EmotionArrival("history", 999, false, awaitingLiveDecryption = true)
        gate.observe(listOf(history), 1000, true)
        val live = EmotionArrival("live", 1001, false, awaitingLiveDecryption = true)
        gate.observe(listOf(history, live), 1002, true)
        assertNull(gate.observe(listOf(history.copy(eligible = true), live.copy(eligible = true)), 5000, true))
    }
}
