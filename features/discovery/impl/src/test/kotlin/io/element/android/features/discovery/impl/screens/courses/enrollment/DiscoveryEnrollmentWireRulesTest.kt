/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryEnrollmentWireRulesTest {
    @Test fun `guardian counts toward the actual ten participant limit`() {
        val base = enrollmentOptions(EnrollmentMode.PARENT_CHILD)
        val children = (2L..11L).map { id -> EnrollmentPerson(id, "Child", EnrollmentRelation.CHILD, profileComplete = true, genderComplete = true) }
        val options = base.copy(people = listOf(base.self) + children)
        val ten = options.initialDraft().copy(personIds = children.map { it.id }.toSet())
        assertFalse(EnrollmentIssue.PERSON in ten.issues(options))
        assertTrue(EnrollmentIssue.PERSON in ten.copy(guardian = true).issues(options))
        assertFalse(EnrollmentIssue.PERSON in ten.copy(personIds = ten.personIds - 11L, guardian = true).issues(options))
    }

    @Test fun `sixty day boundary preserves fractional seconds in form and protected replay`() {
        val first = LocalDate.of(2026, 11, 1)
        val start = first.atTime(9, 0)
        val dates = (0L..60L).map(first::plusDays)
        val options = enrollmentOptions().copy(serviceDates = dates, meals = emptyList(), sessionStart = start, sessionEnd = start.plusDays(60))
        val draft = options.initialDraft()
        assertFalse(EnrollmentIssue.DATES in draft.issues(options))
        val selection = draft.request(options).selection(options)
        io.element.android.features.discovery.impl.actions.validateEnrollmentSelection(selection)
        val late = start.plusDays(60).plusNanos(1000)
        assertTrue(EnrollmentIssue.DATES in draft.issues(options.copy(sessionEnd = late)))
        assertThrows(IllegalArgumentException::class.java) {
            io.element.android.features.discovery.impl.actions.validateEnrollmentSelection(
                JsonObject(selection + ("service_end_date" to JsonPrimitive(late.toString()))))
        }
    }

    @Test fun `one day course preserves real session hours and does not invent another day`() {
        val day = LocalDate.of(2026, 11, 1)
        val options = enrollmentOptions().copy(serviceDates = listOf(day),
            sessionStart = LocalDateTime.parse("2026-11-01T09:00:00"), sessionEnd = LocalDateTime.parse("2026-11-01T17:00:00"))
        val draft = options.initialDraft()
        assertTrue(draft.issues(options).isEmpty())
        val selection = draft.request(options).selection(options)
        assertEquals(JsonPrimitive("2026-11-01T09:00:00"), selection["service_start_date"])
        assertEquals(JsonPrimitive("2026-11-01T17:00:00"), selection["service_end_date"])
        assertTrue(EnrollmentIssue.DATES in draft.issues(options.copy(sessionStart = null, sessionEnd = null)))
    }

    @Test fun `early accommodation and independently configured pickup retain exact choices`() {
        val options = enrollmentOptions().copy(accommodationDates = (0..4).map { LocalDate.of(2026, 10, 31).plusDays(it.toLong()) },
            pickupFirst = LocalDate.of(2026, 10, 30))
        val draft = options.initialDraft().copy(start = LocalDate.of(2026, 10, 31), room = EnrollmentRoom.SINGLE,
            pickup = EnrollmentPickup(enabled = true, date = "2026-10-30", time = "16:00", origin = "Station"))
        assertTrue(draft.issues(options).isEmpty())
        val selection = draft.request(options).selection(options)
        assertEquals(JsonPrimitive("2026-10-31"), selection.getValue("accommodation").jsonObject["check_in_date"])
        assertEquals(JsonPrimitive("2026-10-30T16:00:00"), selection.getValue("pickup_request").jsonObject["planned_at"])
        assertTrue(EnrollmentIssue.DATES in draft.copy(room = EnrollmentRoom.NONE).issues(options))
    }

    @Test fun `pending payment remains pending with unavailable channel even when amount is zero`() {
        val current = EnrollmentCurrentWire(1, 10, listOf(EnrollmentItemWire(2, 3, "pending_payment", 0, 4, "Student")),
            EnrollmentOrderWire(4, "pending", 0, null, "unavailable"), emptySet(), "Session", EnrollmentPaymentChannel.UNAVAILABLE)
        val receipt = current.receipt(2)
        assertEquals(EnrollmentReceiptStatus.PENDING_PAYMENT, receipt.status)
        assertEquals(EnrollmentPaymentChannel.UNAVAILABLE, receipt.paymentChannel)
        assertEquals("4", receipt.orderId)
        assertEquals(EnrollmentReceiptStatus.PROCESSING, current.copy(enrollments = current.enrollments.map { it.copy(status = "unknown") }).receipt(2).status)
    }

    @Test fun `free success requires persisted enrollment status and current group identity`() {
        val current = EnrollmentCurrentWire(1, 10, listOf(EnrollmentItemWire(2, 3, "enrolled", 0, null, "Student")),
            null, emptySet(), "Session", EnrollmentPaymentChannel.NONE)
        assertEquals(EnrollmentReceiptStatus.ENROLLED, current.receipt(2).status)
        assertThrows(IllegalArgumentException::class.java) { current.receipt(99) }
    }
}
