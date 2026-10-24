/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import kotlinx.serialization.json.*
import java.util.UUID

internal sealed interface DiscoveryBusinessCommand : DiscoveryExplicitCommand {
    val path: String
}
internal sealed interface DiscoveryCourseCommand : DiscoveryBusinessCommand {
    val courseId: Long
    val sessionId: Long
}

/** Selection is produced by the accepted strict nested serializer; no host identity or client price is sent. */
internal data class DiscoveryCourseQuoteCommand(
    override val operationId: String,
    override val courseId: Long,
    override val sessionId: Long,
    val optionsRevision: String,
    val selection: JsonObject,
) : DiscoveryCourseCommand {
    override val path get() = "/v1/discovery/business/course-quotes"
    init {
        validateCourseOperation(operationId, courseId, sessionId)
        require(optionsRevision.matches(Regex("[a-f0-9]{64}")))
        validateEnrollmentSelection(selection)
        val bytes = selection.toString().encodeToByteArray()
        try { require(bytes.size <= 15000) } finally { bytes.fill(0) }
    }
    override fun toString() = "DiscoveryCourseQuoteCommand(operationId=$operationId, selection=<redacted>)"
    override fun bytes(): ByteArray = buildJsonObject {
        put("operation_id", operationId); put("course_id", courseId); put("session_id", sessionId)
        put("options_revision", optionsRevision); put("selection", selection)
    }.toString().encodeToByteArray()

    companion object {
        val selectionKeys = setOf("student_user_ids", "companion_mode", "companions", "service_start_date", "service_end_date",
            "accommodation", "meals", "transport", "pickup_request", "diet_note", "special_note")
        fun parse(value: JsonObject): DiscoveryCourseQuoteCommand {
            require(value.keys == setOf("operation_id", "course_id", "session_id", "options_revision", "selection"))
            return DiscoveryCourseQuoteCommand(value.text("operation_id"), value.id("course_id"), value.id("session_id"),
                value.text("options_revision"), value.getValue("selection").jsonObject)
        }
    }
}

internal data class DiscoveryCourseEnrollmentCommand(
    override val operationId: String,
    override val courseId: Long,
    override val sessionId: Long,
    val quoteId: String,
    val selectionSha256: String,
) : DiscoveryCourseCommand {
    override val path get() = "/v1/discovery/business/course-enrollments"
    init {
        validateCourseOperation(operationId, courseId, sessionId)
        require(UUID.fromString(quoteId).toString() == quoteId)
        require(selectionSha256.matches(Regex("[a-f0-9]{64}")))
    }
    override fun bytes(): ByteArray = buildJsonObject {
        put("operation_id", operationId); put("course_id", courseId); put("session_id", sessionId)
        put("quote_id", quoteId); put("selection_sha256", selectionSha256)
    }.toString().encodeToByteArray()
    companion object {
        fun parse(value: JsonObject): DiscoveryCourseEnrollmentCommand {
            require(value.keys == setOf("operation_id", "course_id", "session_id", "quote_id", "selection_sha256"))
            return DiscoveryCourseEnrollmentCommand(value.text("operation_id"), value.id("course_id"), value.id("session_id"),
                value.text("quote_id"), value.text("selection_sha256"))
        }
    }
}

private fun validateCourseOperation(operationId: String, courseId: Long, sessionId: Long) {
    require(UUID.fromString(operationId).toString() == operationId)
    require(courseId in 1..Int.MAX_VALUE.toLong() && sessionId in 1..Int.MAX_VALUE.toLong())
}
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.id(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.long
