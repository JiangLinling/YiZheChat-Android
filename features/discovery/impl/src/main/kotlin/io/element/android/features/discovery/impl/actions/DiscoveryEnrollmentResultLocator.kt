/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import java.util.UUID
import kotlinx.serialization.json.*

/** Encrypted journal locator only. Every display requires a fresh authorised server query. */
internal data class DiscoveryEnrollmentResultLocator(
    val operationId: String,
    val courseId: Long,
    val sessionId: Long,
    val enrollmentIds: List<Long>,
    val orderId: Long?,
) {
    init {
        require(UUID.fromString(operationId).toString() == operationId)
        require(listOfNotNull(courseId, sessionId, orderId).all { it in 1..Int.MAX_VALUE.toLong() })
        require(enrollmentIds.size in 1..10 && enrollmentIds.distinct().size == enrollmentIds.size)
        require(enrollmentIds.all { it in 1..Int.MAX_VALUE.toLong() })
    }
    override fun toString() = "DiscoveryEnrollmentResultLocator(<redacted>)"
    fun json() = buildJsonObject {
        put("operation_id", operationId); put("course_id", courseId); put("session_id", sessionId)
        put("enrollment_ids", buildJsonArray { enrollmentIds.forEach { add(it) } })
        put("order_id", orderId?.let(::JsonPrimitive) ?: JsonNull)
    }
    companion object {
        fun parse(value: JsonObject): DiscoveryEnrollmentResultLocator {
            require(value.keys == setOf("operation_id", "course_id", "session_id", "enrollment_ids", "order_id"))
            fun number(element: JsonElement) = element.jsonPrimitive.also { require(!it.isString) }.long
            val operation = value.getValue("operation_id").jsonPrimitive.also { require(it.isString) }.content
            return DiscoveryEnrollmentResultLocator(operation, number(value.getValue("course_id")), number(value.getValue("session_id")),
                value.getValue("enrollment_ids").jsonArray.map(::number), value.getValue("order_id").takeUnless { it == JsonNull }?.let(::number))
        }
    }
}

/** Backward compatible extension of the same Vault journal, not another result cache. */
internal data class DiscoveryInteractionRecord(
    val command: DiscoveryExplicitCommand?,
    val enrollments: List<DiscoveryEnrollmentResultLocator> = emptyList(),
    val person: DiscoveryPersonResultLocator? = null,
    val mall: DiscoveryMallResultLocator? = null,
    val results: List<DiscoveryConfirmedResult> = emptyList(),
) {
    init {
        require(enrollments.size <= 32)
        require(enrollments.map { it.courseId to it.sessionId }.distinct().size == enrollments.size)
        require(enrollments.map { it.operationId }.distinct().size == enrollments.size)
        require(results.size <= 8 && results.map { it.kind }.distinct().size == results.size)
        require(results.map { it.operationId }.distinct().size == results.size)
    }
    fun bytes(): ByteArray {
        if (enrollments.isEmpty() && person == null && mall == null && results.isEmpty() && command != null) return command.journalBytes()
        val pendingBytes = command?.journalBytes()
        try {
            return buildJsonObject {
                put("schema_version", if (results.isNotEmpty()) 5 else if (mall != null) 4 else if (person == null) 2 else 3)
                put("command", pendingBytes?.let { Json.parseToJsonElement(it.decodeToString()) } ?: JsonNull)
                put("enrollments", JsonArray(enrollments.map { it.json() }))
                if (results.isNotEmpty()) {
                    put("person", person?.json() ?: JsonNull); put("mall", mall?.json() ?: JsonNull)
                    put("results", JsonArray(results.map { buildJsonObject { put("kind", it.kind); put("body", it.json()) } }))
                } else if (mall != null) { put("person", person?.json() ?: JsonNull); put("mall", mall.json()) }
                else person?.let { put("person", it.json()) }
            }.toString().encodeToByteArray()
        } finally { pendingBytes?.fill(0) }
    }
    companion object {
        fun parse(value: JsonObject): DiscoveryInteractionRecord {
            if ("schema_version" !in value) return DiscoveryInteractionRecord(DiscoveryExplicitCommand.parse(value))
            val hasResults = value["schema_version"] == JsonPrimitive(5)
            val hasMall = hasResults || value["schema_version"] == JsonPrimitive(4)
            val hasPerson = hasMall || value["schema_version"] == JsonPrimitive(3)
            require(hasPerson || value["schema_version"] == JsonPrimitive(2))
            require(!hasPerson || hasMall || value.getValue("person") != JsonNull)
            require(!hasMall || hasResults || value.getValue("mall") != JsonNull)
            require(value.keys == setOf("schema_version", "command", "enrollments") + (if (hasPerson) setOf("person") else emptySet()) +
                (if (hasMall) setOf("mall") else emptySet()) + (if (hasResults) setOf("results") else emptySet()))
            val rows = value.getValue("enrollments").jsonArray.also { require(it.size <= 32) }
            return DiscoveryInteractionRecord(value.getValue("command").takeUnless { it == JsonNull }?.let { DiscoveryExplicitCommand.parse(it.jsonObject) },
                rows.map { DiscoveryEnrollmentResultLocator.parse(it.jsonObject) },
                if (hasPerson && value.getValue("person") != JsonNull) DiscoveryPersonResultLocator.parse(value.getValue("person").jsonObject) else null,
                if (hasMall && value.getValue("mall") != JsonNull) DiscoveryMallResultLocator.parse(value.getValue("mall").jsonObject) else null,
                if (hasResults) value.getValue("results").jsonArray.also { require(it.size in 1..8) }.map { DiscoveryConfirmedResult.parse(it.jsonObject) } else emptyList())
        }
    }
}
