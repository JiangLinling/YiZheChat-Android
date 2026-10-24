/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryEnrollmentReaderTest {
    private fun current() = Json.parseToJsonElement("""{"course_id":1,"session_id":10,"course_title":"Course","session_title":"Session",
        "payment_channel_status":"READY","enrollments":[
        {"enrollment_id":2,"student_user_id":3,"student_name":"Person","status":"pending_payment","amount":0,"order_id":4}],
        "order":{"order_id":4,"status":"pending","amount":0,"expire_at":null,"pay_mode":"app"},"available_actions":[]}""").jsonObject

    @Test fun `current group keeps zero amount pending facts and validates requested identities`() {
        val parsed = parseCurrentEnrollment(current(), 1, 10, 2)
        assertEquals("pending_payment", parsed.enrollments.single().status)
        assertEquals("pending", parsed.order?.status)
        assertThrows(IllegalArgumentException::class.java) { parseCurrentEnrollment(current(), 1, 11, 2) }
        assertThrows(IllegalArgumentException::class.java) { parseCurrentEnrollment(current(), 1, 10, 99) }
    }

    @Test fun `current group rejects mock orders and mismatched enrollment order`() {
        val value = current()
        val order = value.getValue("order").jsonObject
        assertThrows(IllegalArgumentException::class.java) {
            parseCurrentEnrollment(JsonObject(value + ("order" to JsonObject(order + ("pay_mode" to JsonPrimitive("mock"))))), 1, 10, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCurrentEnrollment(JsonObject(value + ("order" to JsonObject(order + ("order_id" to JsonPrimitive(5))))), 1, 10, 2)
        }
    }
}
