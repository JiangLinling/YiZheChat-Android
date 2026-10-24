/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.network

import io.element.android.features.discovery.impl.actions.DiscoveryContentCommand
import io.element.android.features.discovery.impl.actions.DiscoveryArticleCommentCommand
import io.element.android.features.discovery.impl.actions.DiscoveryArticleView
import io.element.android.features.discovery.impl.actions.DiscoveryArticleInteraction
import io.element.android.features.discovery.impl.actions.DiscoveryInteractionKind
import io.element.android.features.discovery.impl.actions.DiscoveryInteractionOutcome
import io.element.android.features.discovery.impl.session.*
import kotlinx.serialization.json.*

internal class HttpDiscoveryInteractionClient(private val transport: DiscoveryTransport, private val clock: DiscoveryClock) {
    suspend fun recovered(session: DiscoverySession, command: DiscoveryContentCommand): DiscoveryInteractionOutcome? {
        val bytes = try {
            transport.operation(command.operationId, headers(session))
        } catch (failure: DiscoveryFailure) {
            // An intent may have been persisted before admission. A fresh proof still has to pass every server gate.
            if (failure.status == 409) return null
            throw failure
        }
        return try {
            val result = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            when (result["status"]?.jsonPrimitive?.content) {
                "PENDING" -> { require(result.keys == setOf("status")); null }
                "COMPLETED" -> {
                    require(result.keys == setOf("status", "result"))
                    validateReceipt(result.getValue("result").jsonObject, command)
                }
                else -> throw DiscoveryFailure(503)
            }
        } finally { bytes.fill(0) }
    }

    suspend fun send(session: DiscoverySession, code: DiscoveryCode, command: DiscoveryContentCommand, body: ByteArray): DiscoveryInteractionOutcome {
        val expected = command.bytes()
        try { require(expected.contentEquals(body)) } finally { expected.fill(0) }
        fun fresh() {
            if (clock.wallMillis() >= minOf(code.authorizationExpiresAtMillis, code.codeExpiresAtMillis, session.expiresAtMillis) ||
                clock.elapsedMillis() - code.startedAtElapsedMillis !in 0 until 5_000 ||
                clock.elapsedMillis() >= session.expiresAtElapsedMillis) throw DiscoveryFailure(401)
        }
        fresh()
        val bytes = transport.post(DiscoveryTarget.GATEWAY, DiscoveryActionTarget(command.operationId, sha256(body)).path, body,
            headers(session) + mapOf("X-Discovery-Code" to code.secret.value, "X-Discovery-Verifier" to code.verifier.value,
                "X-Discovery-Exchange" to code.exchangeId))
        try {
            val outcome = validateReceipt(Json.parseToJsonElement(bytes.decodeToString()).jsonObject, command)
            fresh()
            return outcome
        } finally { bytes.fill(0) }
    }

    private fun headers(session: DiscoverySession) = mapOf("Authorization" to "Bearer ${session.secret.value}")

    private fun validateReceipt(result: JsonObject, command: DiscoveryContentCommand): DiscoveryInteractionOutcome {
        if (command is DiscoveryArticleCommentCommand) {
            val expected = command.receiptFields()
            require(expected.all { (key, value) -> result[key] == value })
            val rejected = result["outcome"] == JsonPrimitive("REJECTED")
            if (rejected) {
                require(result.keys == expected.keys + setOf("outcome", "reason"))
                require(result["reason"] in setOf(JsonPrimitive("ARTICLE_UNAVAILABLE"), JsonPrimitive("COMMENTS_DISABLED"), JsonPrimitive("COMMENT_UNAVAILABLE")))
            } else {
                require(result["outcome"] == JsonPrimitive("APPLIED"))
                require(result.keys == expected.keys + setOf("outcome", "count", "comment_id"))
                require(!result.getValue("count").jsonPrimitive.isString && result.getValue("count").jsonPrimitive.long >= 0)
                require(!result.getValue("comment_id").jsonPrimitive.isString && result.getValue("comment_id").jsonPrimitive.long > 0)
            }
            return if (rejected) DiscoveryInteractionOutcome.REJECTED else DiscoveryInteractionOutcome.APPLIED
        }
        if (command is DiscoveryArticleView) {
            val rejected = result["outcome"] == JsonPrimitive("REJECTED")
            if (rejected) {
                require(result.keys == DiscoveryArticleView.keys + setOf("outcome", "reason"))
                require(result["reason"] in setOf(JsonPrimitive("ARTICLE_UNAVAILABLE"), JsonPrimitive("VIEW_UNAVAILABLE")))
            } else {
                require(result["outcome"] == JsonPrimitive("APPLIED"))
                require(result.keys == DiscoveryArticleView.keys + setOf("outcome", "count", "interaction_id"))
                require(!result.getValue("count").jsonPrimitive.isString && result.getValue("count").jsonPrimitive.long >= 0)
                require(!result.getValue("interaction_id").jsonPrimitive.isString && result.getValue("interaction_id").jsonPrimitive.long > 0)
            }
            require(DiscoveryArticleView.parse(JsonObject(result.filterKeys { it in DiscoveryArticleView.keys })) == command)
            return if (rejected) DiscoveryInteractionOutcome.REJECTED else DiscoveryInteractionOutcome.APPLIED
        }
        require(command is DiscoveryArticleInteraction)
        val commandKeys = setOf("operation_id", command.kind.targetField, "kind", "enabled")
        if (result["outcome"]?.jsonPrimitive?.content == "REJECTED") {
            require(result.keys == commandKeys + setOf("outcome", "reason"))
            val reasons = if (command.kind == DiscoveryInteractionKind.FOLLOW) setOf("AUTHOR_UNAVAILABLE", "AUTHOR_FOLLOW_DISABLED") else setOf("ARTICLE_UNAVAILABLE")
            require(result["reason"]?.jsonPrimitive?.content in reasons)
        } else if (command.kind == DiscoveryInteractionKind.FOLLOW) {
            when (result["outcome"]?.jsonPrimitive?.content) {
                "APPLIED" -> require(result.keys == commandKeys + setOf("outcome", "count"))
                else -> throw DiscoveryFailure(503)
            }
        } else require(result.keys == commandKeys + "count")
        require(DiscoveryArticleInteraction.parse(JsonObject(result.filterKeys { it in commandKeys })) == command)
        if ("count" in result) require(!result.getValue("count").jsonPrimitive.isString && result.getValue("count").jsonPrimitive.long >= 0)
        // This is a historical receipt only. Never replace the current article state with it.
        return if (result["outcome"]?.jsonPrimitive?.content == "REJECTED") DiscoveryInteractionOutcome.REJECTED else DiscoveryInteractionOutcome.APPLIED
    }
}
