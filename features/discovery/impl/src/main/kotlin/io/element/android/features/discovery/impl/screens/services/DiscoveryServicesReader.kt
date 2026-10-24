/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.network.DiscoveryTarget
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import io.element.android.features.discovery.impl.screens.services.feedback.FeedbackTarget
import io.element.android.features.discovery.impl.util.discoveryTextFits
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ServiceEnrollment(val target: FeedbackTarget, val course: String, val session: String, val person: String,
    val relation: String, val status: String, val startAt: Long?, val endAt: Long?) {
    override fun toString() = "ServiceEnrollment(<redacted>)"
}
internal data class ServiceEnrollments(val rows: List<ServiceEnrollment>, val total: Long, val page: Int)
internal data class ServiceTeacher(val id: Long, val name: String, val role: String, val phone: String?) {
    override fun toString() = "ServiceTeacher(<redacted>)"
}

@Inject
internal class DiscoveryServicesReader(private val access: DefaultDiscoveryAccess, private val transport: PinnedDiscoveryTransport) {
    suspend fun enrollments(page: Int): ServiceEnrollments {
        require(page in 1..100000)
        return query(buildJsonObject { put("kind", "enrollments"); put("page", page); put("page_size", 20) }) {
            parseServiceEnrollments(it, page)
        }
    }
    suspend fun teachers(target: FeedbackTarget): List<ServiceTeacher> = query(targetQuery("teachers", target)) {
        parseServiceTeachers(it, target)
    }

    internal suspend fun <T> query(request: JsonObject, parse: (JsonObject) -> T): T = access.withSession { session ->
        val body = request.toString().encodeToByteArray()
        try {
            val response = transport.post(DiscoveryTarget.GATEWAY, "/v1/discovery/business/services/query", body,
                mapOf("Authorization" to "Bearer ${session.secret.value}"))
            try { withContext(Dispatchers.Default) { parse(Json.parseToJsonElement(response.decodeToString(throwOnInvalidSequence = true)).jsonObject) } }
            finally { response.fill(0) }
        } finally { body.fill(0) }
    }
}

internal fun targetQuery(kind: String, target: FeedbackTarget) = buildJsonObject {
    put("kind", kind); put("course_id", target.courseId); put("session_id", target.sessionId); put("enrollment_id", target.enrollmentId)
}
internal fun JsonObject.serviceExact(vararg fields: String) = require(keys == fields.toSet())
internal fun JsonObject.serviceNumber(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.long
internal fun JsonObject.serviceId(key: String) = serviceNumber(key).also { require(it in 1..Int.MAX_VALUE.toLong()) }
internal fun JsonObject.serviceText(key: String, maximum: Int): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
    .also { require(it.discoveryTextFits(maximum)) }
internal fun JsonObject.serviceTarget() = FeedbackTarget(serviceId("course_id"), serviceId("session_id"), serviceId("enrollment_id"))

internal fun parseServiceEnrollments(value: JsonObject, page: Int): ServiceEnrollments {
    value.serviceExact("page", "page_size", "total", "items")
    require(value.serviceNumber("page") == page.toLong() && value.serviceNumber("page_size") == 20L)
    val total = value.serviceNumber("total").also { require(it >= 0) }
    val rows = value.getValue("items").jsonArray.also {
        require(it.size.toLong() == minOf(20L, (total - (page - 1L) * 20).coerceAtLeast(0)))
    }.map { element ->
        val row = element.jsonObject
        row.serviceExact("enrollment_id", "course_id", "session_id", "course_title", "session_title", "person_name", "person_relation", "status", "start_at_ms", "end_at_ms")
        val relation = row.serviceText("person_relation", 5).also { require(it in setOf("self", "child")) }
        val status = row.serviceText("status", 8).also { require(it in setOf("paid", "enrolled")) }
        fun time(key: String): Long? = if (row.getValue(key) == JsonNull) null else row.serviceNumber(key)
        ServiceEnrollment(row.serviceTarget(), row.serviceText("course_title", 255), row.serviceText("session_title", 255),
            row.serviceText("person_name", 120), relation, status, time("start_at_ms"), time("end_at_ms"))
    }
    require(rows.map { it.target.enrollmentId } == rows.map { it.target.enrollmentId }.distinct().sortedDescending())
    return ServiceEnrollments(rows, total, page)
}

internal fun parseServiceTeachers(value: JsonObject, target: FeedbackTarget): List<ServiceTeacher> {
    value.serviceExact("course_id", "session_id", "enrollment_id", "teachers")
    require(value.serviceTarget() == target)
    val rows = value.getValue("teachers").jsonArray.also { require(it.size <= 2) }.map { element ->
        val row = element.jsonObject
        row.serviceExact("admin_id", "name", "role", "phone", "avatar_media_ref")
        require(row.getValue("avatar_media_ref") == JsonNull)
        val role = row.serviceText("role", 9).also { require(it in setOf("lead", "assistant")) }
        ServiceTeacher(row.serviceId("admin_id"), row.serviceText("name", 120), role,
            if (row.getValue("phone") == JsonNull) null else row.serviceText("phone", 20))
    }
    require(rows.map { it.role }.distinct().size == rows.size)
    require(rows.map { if (it.role == "lead") 0 else 1 } == rows.map { if (it.role == "lead") 0 else 1 }.sorted())
    return rows
}
