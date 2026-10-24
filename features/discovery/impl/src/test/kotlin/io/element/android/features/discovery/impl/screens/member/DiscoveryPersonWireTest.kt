/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import io.element.android.features.discovery.impl.actions.DiscoveryExplicitCommand
import io.element.android.features.discovery.impl.actions.DiscoveryPersonCommand
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryPersonWireTest {
    @Test fun `complete forms distinguish child from self and retain explicit clearing`() {
        val draft = MemberPersonDraft(fullName = " Child ", nickname = "Private nickname", school = "School", primaryGuardian = false)
        val child = personForm(draft, child = true)
        assertEquals(childPersonFields, child.keys)
        assertEquals(JsonPrimitive("Child"), child["full_name"])
        assertEquals(JsonNull, child["birthdate"])
        assertEquals(JsonPrimitive(false), child["is_primary"])
        assertFalse(child.containsKey("nickname"))
        val adult = personForm(MemberPersonDraft(), child = false)
        assertEquals(adultPersonFields, adult.keys)
        assertTrue(adult.values.all { it == JsonNull })
        assertThrows(IllegalArgumentException::class.java) { parsePersonForm(JsonObject(adult - "email"), false) }
        assertThrows(IllegalArgumentException::class.java) { parsePersonForm(JsonObject(child + ("guardian_user_id" to JsonPrimitive(1))), true) }
    }

    @Test fun `protected command preserves create or edit and cannot accept client owner`() {
        val create = DiscoveryPersonCommand("11111111-1111-4111-8111-111111111111", true, null,
            personForm(MemberPersonDraft(fullName = "Private child"), true))
        val bytes = create.bytes()
        try {
            val value = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            assertEquals(create, DiscoveryExplicitCommand.parse(value))
            assertEquals(JsonNull, value["child_user_id"])
            assertTrue(create.matches(PersonEditTarget(PersonEditKind.NEW_CHILD, null)))
            assertFalse(create.matches(PersonEditTarget(PersonEditKind.CHILD, 2)))
            assertThrows(IllegalArgumentException::class.java) { DiscoveryPersonCommand.parse(JsonObject(value + ("user_id" to JsonPrimitive(1)))) }
        } finally { bytes.fill(0) }
        assertFalse(create.toString().contains("Private child"))
    }

    @Test fun `read projection preserves guardian relation and rejects leaked phone or internal fields`() {
        val valid = personWire(2, 3)
        val person = parseMemberPerson(valid, true)
        assertEquals(3L, person.relationId)
        assertEquals(75, person.completion)
        assertEquals("138****0000", person.maskedPhone)
        assertThrows(IllegalArgumentException::class.java) { parseMemberPerson(JsonObject(valid + ("phone" to JsonPrimitive("13800000000"))), true) }
        assertThrows(IllegalArgumentException::class.java) { parseMemberPerson(JsonObject(valid + ("masked_phone" to JsonPrimitive("13800000000"))), true) }
    }

    @Test fun `complete children list rejects overlaps and changing totals`() = runTest {
        val a = parseMemberPerson(personWire(2, 3), true)
        val b = parseMemberPerson(personWire(4, 5), true)
        assertEquals(listOf(a, b), readAllMemberChildren { page -> MemberChildrenPage(listOf(if (page == 1) a else b), 2) })
        assertTrue(runCatching { readAllMemberChildren { MemberChildrenPage(listOf(a), 2) } }.isFailure)
        assertTrue(runCatching { readAllMemberChildren { page -> MemberChildrenPage(listOf(if (page == 1) a else b), if (page == 1) 2 else 3) } }.isFailure)
    }
}

internal fun personWire(id: Long, relation: Long): JsonObject = JsonObject(personForm(MemberPersonDraft(fullName = "Child"), true) + buildJsonObject {
    put("child_user_id", id); put("relation_id", relation); put("user_type", "child"); put("avatar_media_ref", JsonNull)
    put("masked_phone", "138****0000")
    put("completion", buildJsonObject { put("percent", 75); put("completed", 3); put("total", 4); put("missing_fields", buildJsonArray { add("gender") }) })
})
