/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import io.element.android.features.discovery.impl.actions.DiscoveryCourseQuoteCommand
import io.element.android.features.discovery.impl.actions.DiscoveryExplicitCommand
import io.element.android.features.discovery.impl.actions.validateEnrollmentSelection
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryEnrollmentSelectionCodecTest {
    @Test fun `guardian dates meal identities and total travellers follow original business semantics`() {
        val options = enrollmentOptions(EnrollmentMode.PARENT_CHILD)
        val draft = options.initialDraft().copy(personIds = setOf(2), guardian = true, room = EnrollmentRoom.SHARED,
            mealIds = setOf("first-lunch"), pickup = EnrollmentPickup(enabled = true, date = "2026-11-01", time = "10:20",
                origin = "Station", peopleCount = "3", latitude = 0.0, longitude = 0.0))
        val selection = draft.request(options).selection(options)
        assertEquals(JsonPrimitive(1), selection.getValue("companions").jsonArray.single().jsonObject["user_id"])
        assertEquals(JsonPrimitive("2026-11-01T00:00:00"), selection["service_start_date"])
        assertEquals(JsonPrimitive("2026-11-04"), selection.getValue("accommodation").jsonObject["check_out_date"])
        assertEquals(setOf("service_date", "meal_type"), selection.getValue("meals").jsonArray.single().jsonObject.keys)
        val pickup = selection.getValue("pickup_request").jsonObject
        assertEquals(JsonPrimitive(2), pickup["companion_count"])
        assertEquals(JsonPrimitive("rail"), pickup["transport_type"])
        assertEquals(JsonPrimitive(0.0), pickup["origin_latitude"])
        assertEquals(JsonPrimitive(0.0), pickup["origin_longitude"])
    }

    @Test fun `selection never sends private disabled pickup fields or client prices`() {
        val options = enrollmentOptions()
        val request = options.initialDraft().copy(pickup = EnrollmentPickup(origin = "private stale location")).request(options)
        val value = request.selection(options)
        assertEquals(buildJsonObject { put("enabled", false) }, value["pickup_request"])
        assertFalse(value.toString().contains("private stale location"))
        assertFalse(value.toString().contains("price"))
        assertFalse(value.toString().contains("fee"))
    }

    @Test fun `protected journal rejects injected fee keys and duplicate meal selections`() {
        val options = enrollmentOptions().copy(version = "a".repeat(64))
        val request = options.initialDraft().copy(mealIds = setOf("first-lunch")).request(options)
        val selection = request.selection(options)
        val command = DiscoveryCourseQuoteCommand("11111111-1111-4111-8111-111111111111", 1, 10, options.version, selection)
        val bytes = command.bytes()
        try { assertEquals(command, DiscoveryExplicitCommand.parse(Json.parseToJsonElement(bytes.decodeToString()).jsonObject)) }
        finally { bytes.fill(0) }
        val room = selection.getValue("accommodation").jsonObject
        assertThrows(IllegalArgumentException::class.java) {
            validateEnrollmentSelection(JsonObject(selection + ("accommodation" to JsonObject(room + ("price" to JsonPrimitive(0))))))
        }
        val meal = selection.getValue("meals").jsonArray.single()
        assertThrows(IllegalArgumentException::class.java) {
            validateEnrollmentSelection(JsonObject(selection + ("meals" to JsonArray(listOf(meal, meal)))))
        }
    }

    @Test fun `quote serialization rejects changed version and removed guardian authorization`() {
        val options = enrollmentOptions(EnrollmentMode.PARENT_CHILD)
        val request = options.initialDraft().copy(personIds = setOf(2), guardian = true).request(options)
        assertThrows(IllegalArgumentException::class.java) { request.selection(options.copy(version = "changed")) }
        assertThrows(IllegalArgumentException::class.java) {
            request.selection(options.copy(people = options.people.filter { it.id != 2L }))
        }
    }
}
