/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import io.element.android.features.discovery.impl.screens.services.feedback.*
import io.element.android.features.discovery.impl.screens.services.*
import java.util.UUID
import kotlinx.serialization.json.*

internal data class DiscoveryFeedbackCommand(override val operationId: String, val target: FeedbackTarget,
    val feedbackId: Long?, val revision: String?, val draft: FeedbackDraft) : DiscoveryResultCommand {
    override val resultKind = "feedback"
    override val path = "/v1/discovery/business/course-feedback"
    init {
        require(UUID.fromString(operationId).toString() == operationId)
        require(feedbackId == null || feedbackId in 1..Int.MAX_VALUE.toLong())
        require((feedbackId == null) == (revision == null) && (revision == null || revision.matches(Regex("[a-f0-9]{64}"))))
        require(draft == draft.normalized() && draft.issues().isEmpty())
    }
    override fun bytes() = buildJsonObject {
        put("operation_id", operationId); put("course_id", target.courseId); put("session_id", target.sessionId); put("enrollment_id", target.enrollmentId)
        put("feedback_id", feedbackId?.let(::JsonPrimitive) ?: JsonNull); put("revision", revision?.let(::JsonPrimitive) ?: JsonNull)
        put("feedback", feedbackForm(draft))
    }.toString().encodeToByteArray()
    override fun toString() = "DiscoveryFeedbackCommand(<redacted>)"
    companion object {
        fun parse(value: JsonObject): DiscoveryFeedbackCommand {
            value.serviceExact("operation_id", "course_id", "session_id", "enrollment_id", "feedback_id", "revision", "feedback")
            return DiscoveryFeedbackCommand(value.serviceText("operation_id", 36), value.serviceTarget(),
                if (value.getValue("feedback_id") == JsonNull) null else value.serviceId("feedback_id"),
                if (value.getValue("revision") == JsonNull) null else value.serviceText("revision", 64), parseFeedbackForm(value.getValue("feedback").jsonObject))
        }
    }
}

internal data class DiscoveryFeedbackResult(override val operationId: String, val target: FeedbackTarget,
    val feedbackId: Long, val revision: String) : DiscoveryConfirmedResult {
    override val kind = "feedback"
    init {
        require(UUID.fromString(operationId).toString() == operationId)
        require(feedbackId in 1..Int.MAX_VALUE.toLong() && revision.matches(Regex("[a-f0-9]{64}")))
    }
    override fun matches(command: DiscoveryResultCommand) = command is DiscoveryFeedbackCommand && operationId == command.operationId &&
        target == command.target && (command.feedbackId == null || feedbackId == command.feedbackId)
    fun receipt() = FeedbackSaved(target, feedbackId, revision, operationId)
    override fun json() = buildJsonObject {
        put("operation_id", operationId); put("course_id", target.courseId); put("session_id", target.sessionId); put("enrollment_id", target.enrollmentId)
        put("feedback_id", feedbackId); put("revision", revision)
    }
    override fun toString() = "DiscoveryFeedbackResult(<redacted>)"
    companion object {
        fun parse(value: JsonObject): DiscoveryFeedbackResult {
            value.serviceExact("operation_id", "course_id", "session_id", "enrollment_id", "feedback_id", "revision")
            return DiscoveryFeedbackResult(value.serviceText("operation_id", 36), value.serviceTarget(), value.serviceId("feedback_id"), value.serviceText("revision", 64))
        }
    }
}
