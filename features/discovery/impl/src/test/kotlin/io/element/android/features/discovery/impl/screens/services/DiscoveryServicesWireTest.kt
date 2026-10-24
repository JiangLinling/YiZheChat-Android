/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services

import io.element.android.features.discovery.impl.screens.services.feedback.FeedbackTarget
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryServicesWireTest {
    private val target = FeedbackTarget(1, 2, 3)
    private fun teacher(role: String) = buildJsonObject {
        put("admin_id", 4); put("name", "Teacher"); put("role", role); put("phone", JsonNull); put("avatar_media_ref", JsonNull)
    }
    private fun teachers(vararg roles: String) = buildJsonObject {
        put("course_id", 1); put("session_id", 2); put("enrollment_id", 3); put("teachers", JsonArray(roles.map(::teacher)))
    }

    @Test fun `same teacher in two actual roles remains two entries without a fabricated telephone`() {
        val rows = parseServiceTeachers(teachers("lead", "assistant"), target)
        assertEquals(2, rows.size)
        assertEquals(rows[0].id, rows[1].id)
        assertNull(rows[0].phone)
        assertThrows(IllegalArgumentException::class.java) { parseServiceTeachers(teachers("lead", "lead"), target) }
    }

    @Test fun `teacher response for another selected enrolment is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { parseServiceTeachers(teachers("lead"), target.copy(enrollmentId = 8)) }
        val foreign = JsonObject(teachers("lead") + ("admin_note" to JsonPrimitive("private")))
        assertThrows(IllegalArgumentException::class.java) { parseServiceTeachers(foreign, target) }
    }

    @Test fun `service page accepts real empty names and rejects duplicates and mismatched page`() {
        val row = buildJsonObject {
            put("course_id", 1); put("session_id", 2); put("enrollment_id", 3); put("course_title", "Course"); put("session_title", "Session")
            put("person_name", ""); put("person_relation", "child"); put("status", "paid"); put("start_at_ms", JsonNull); put("end_at_ms", JsonNull)
        }
        fun page(items: List<JsonObject>) = buildJsonObject { put("page", 1); put("page_size", 20); put("total", items.size); put("items", JsonArray(items)) }
        assertEquals("", parseServiceEnrollments(page(listOf(row)), 1).rows.single().person)
        assertThrows(IllegalArgumentException::class.java) { parseServiceEnrollments(page(listOf(row, row)), 1) }
        assertThrows(IllegalArgumentException::class.java) { parseServiceEnrollments(page(listOf(row)), 2) }
    }
}
