/*
 * Copyright (c) 2026 YiZheChat.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugVisibleRoomContextTest {
    private val sessionId = "@next01:matrix-next.yizhesy.cn"
    private val roomId = "!PXnfZNCcdMNHgesHdn:matrix-next.yizhesy.cn"
    private val deviceId = "QA_DEVICE_1"

    @Test
    fun `release callers expose no diagnostic tag`() {
        assertNull(tag(debug = false))
    }

    @Test
    fun `unknown identity or process exposes no diagnostic tag`() {
        assertNull(tag(session = ""))
        assertNull(tag(room = " "))
        assertNull(tag(device = ""))
        assertNull(tag(pid = 0))
        assertNull(tag(pid = -1))
    }

    @Test
    fun `a room and a different selected client cannot create a coherent tag`() {
        assertNull(tag(clientSession = "@next02:matrix-next.yizhesy.cn"))
    }

    @Test
    fun `known coherent context uses stable hashes without raw identifiers`() {
        val value = requireNotNull(tag())
        assertEquals(
            "yizhe_debug_room_v2_1234_" +
                "7d41da7691f29d5a18011e8c7d85b010df06118f22b69bd21533302b50fa8e07_" +
                "a511830c1c029e8f9d93fbc0b59dc8bdb85d977e0c2c9c64ff23143bf8fc07aa_" +
                "963b6ba7066e7f8d72f2b833cc2c67949729a7314bc65591e4ce57bff4587242",
            value,
        )
        assertFalse(value.contains(sessionId))
        assertFalse(value.contains(roomId))
        assertFalse(value.contains(deviceId))
    }

    @Test
    fun `different account room device and process do not alias the original context`() {
        val value = tag()
        val other = "@next02:matrix-next.yizhesy.cn"
        assertNotEquals(value, tag(session = other, clientSession = other))
        assertNotEquals(value, tag(room = "!other:matrix-next.yizhesy.cn"))
        assertNotEquals(value, tag(device = "QA_DEVICE_2"))
        assertNotEquals(value, tag(pid = 5678))
    }

    private fun tag(
        debug: Boolean = true,
        session: String = sessionId,
        clientSession: String = sessionId,
        device: String = deviceId,
        room: String = roomId,
        pid: Int = 1234,
    ): String? = debugVisibleRoomContextTag(debug, session, clientSession, device, room, pid)
}
