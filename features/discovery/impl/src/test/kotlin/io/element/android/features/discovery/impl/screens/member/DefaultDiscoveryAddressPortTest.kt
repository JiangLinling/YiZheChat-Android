/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import io.element.android.features.discovery.impl.actions.DiscoveryAddressCommand
import io.element.android.features.discovery.impl.actions.DiscoveryExplicitCommand
import io.element.android.features.discovery.impl.network.DiscoveryTarget
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import io.element.android.features.discovery.impl.session.DiscoverySecret
import io.element.android.features.discovery.impl.session.DiscoverySession
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DefaultDiscoveryAddressPortTest {
    @Test fun `real reader loads all pages under one session and clears request response bytes`() = runTest {
        val access = mockk<DefaultDiscoveryAccess>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val session = DiscoverySession(DiscoverySecret("guest"), "login", 160000, 60000)
        val requests = mutableListOf<ByteArray>(); val responses = mutableListOf<ByteArray>()
        coEvery { access.withSession<MemberAddressBook>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> MemberAddressBook>()(session) }
        coEvery { transport.post(DiscoveryTarget.GATEWAY, "/v1/discovery/business/member/query", any(), any()) } answers {
            val request = arg<ByteArray>(2)
            requests += request
            val query = Json.parseToJsonElement(request.decodeToString()).jsonObject
            assertEquals(setOf("kind", "page", "page_size"), query.keys)
            assertEquals(JsonPrimitive("addresses"), query["kind"])
            assertEquals("Bearer guest", arg<Map<String, String>>(3)["Authorization"])
            val page = query.getValue("page").jsonPrimitive.int
            addressPage(page, 21, if (page == 1) (1..20).toList() else listOf(21)).toString().encodeToByteArray().also { responses += it }
        }
        val book = DefaultDiscoveryAddressPort(access, transport, mockk()).load()
        assertEquals(21, book.rows.size)
        assertEquals(1L, book.ownerId)
        assertEquals(listOf(1L), book.rows.filter { it.draft.isDefault }.map { it.id })
        coVerify(exactly = 1) { access.withSession<MemberAddressBook>(any()) }
        assertTrue(requests.all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue(responses.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test fun `address journal preserves canonical detail and rejects actor or unicode phone injection`() {
        val payload = address(1).let { JsonObject(it - "address_id") }
        val command = DiscoveryAddressCommand("11111111-1111-4111-8111-111111111111", null, payload)
        val bytes = command.bytes()
        try { assertEquals(command, DiscoveryExplicitCommand.parse(Json.parseToJsonElement(bytes.decodeToString()).jsonObject)) }
        finally { bytes.fill(0) }
        assertFalse(command.toString().contains("Recipient"))
        assertThrows(IllegalArgumentException::class.java) {
            command.copy(address = JsonObject(payload + ("user_id" to JsonPrimitive(2))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            command.copy(address = JsonObject(payload + ("receiver_phone" to JsonPrimitive("1١١١١١١١١١١"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            command.copy(address = JsonObject(payload + ("is_default" to JsonPrimitive("true"))))
        }
    }

    @Test fun `list parser rejects wrong paging and incompatible default facts`() {
        val page = addressPage(1, 1, listOf(1))
        assertThrows(IllegalArgumentException::class.java) { parseAddressPage(page, 2, 20) }
        assertThrows(IllegalArgumentException::class.java) {
            parseAddressPage(JsonObject(page + ("default_address_id" to JsonPrimitive(9))), 1, 20)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseAddressPage(JsonObject(page + ("has_more" to JsonPrimitive(true))), 1, 20)
        }
    }

    @Test fun `page overlap cannot silently hide an address while claiming the complete book`() = runTest {
        val failure = runCatching {
            readCompleteAddressBook { page ->
                parseAddressPage(addressPage(page, 21, if (page == 1) (1..20).toList() else listOf(20)), page, 20)
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun `concurrent total change requires a fresh address read`() = runTest {
        val failure = runCatching {
            readCompleteAddressBook { page ->
                parseAddressPage(addressPage(page, if (page == 1) 21 else 22, if (page == 1) (1..20).toList() else listOf(21, 22)), page, 20)
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun addressPage(page: Int, total: Int, ids: List<Int>) = buildJsonObject {
        put("owner_id", 1); put("page", page); put("page_size", 20); put("total", total); put("has_more", page * 20 < total)
        put("default_address_id", 1); put("items", JsonArray(ids.map(::address)))
    }
    private fun address(id: Int) = buildJsonObject {
        put("address_id", id); put("receiver_name", "Recipient"); put("receiver_phone", "13800000000")
        put("province", "上海市"); put("city", "上海市"); put("district", "浦东新区"); put("detail", "Street Room 302")
        put("is_default", id == 1)
    }
}
