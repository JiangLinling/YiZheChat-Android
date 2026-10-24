/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import io.element.android.features.discovery.impl.network.DiscoveryTarget
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryPersonReaderTest {
    @Test fun `child uses fixed protected query and clears both payload buffers`() = runTest {
        val access = mockk<DefaultDiscoveryAccess>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val session = DiscoverySession(DiscoverySecret("guest"), "login", 160000, 60000)
        coEvery { access.withSession<MemberPersonRecord>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> MemberPersonRecord>()(session) }
        var sent: ByteArray? = null
        val response = personWire(2, 3).toString().encodeToByteArray()
        coEvery { transport.post(DiscoveryTarget.GATEWAY, "/v1/discovery/business/member/query", any(), any()) } answers {
            sent = arg(2)
            assertEquals("Bearer guest", arg<Map<String, String>>(3)["Authorization"])
            assertEquals(buildJsonObject { put("kind", "child"); put("child_user_id", 2) }, Json.parseToJsonElement(sent!!.decodeToString()))
            response
        }
        assertEquals(2L, DiscoveryPersonReader(access, transport).child(2).target.userId)
        assertTrue(sent!!.all { it == 0.toByte() })
        assertTrue(response.all { it == 0.toByte() })
    }

    @Test fun `wrong child response fails closed and still clears response`() = runTest {
        val access = mockk<DefaultDiscoveryAccess>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val session = DiscoverySession(DiscoverySecret("guest"), "login", 160000, 60000)
        coEvery { access.withSession<MemberPersonRecord>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> MemberPersonRecord>()(session) }
        val response = personWire(9, 3).toString().encodeToByteArray()
        coEvery { transport.post(any(), any(), any(), any()) } returns response
        assertTrue(runCatching { DiscoveryPersonReader(access, transport).child(2) }.isFailure)
        assertTrue(response.all { it == 0.toByte() })
    }
}
