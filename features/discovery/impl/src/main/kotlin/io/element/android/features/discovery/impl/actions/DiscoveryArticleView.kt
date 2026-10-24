/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import kotlinx.serialization.json.*
import java.util.UUID

internal enum class DiscoveryViewPhase(val wire: String) { START("start"), FINISH("finish") }
internal data class DiscoveryArticleView(
    override val operationId: String,
    val visitId: String,
    val articleId: Long,
    val phase: DiscoveryViewPhase,
    val staySeconds: Int,
) : DiscoveryContentCommand {
    init {
        require(UUID.fromString(operationId).toString() == operationId && UUID.fromString(visitId).toString() == visitId)
        require(articleId > 0 && staySeconds >= 0)
        require(if (phase == DiscoveryViewPhase.START) operationId == visitId && staySeconds == 0 else operationId != visitId)
    }
    override fun bytes(): ByteArray = buildJsonObject {
        put("operation_id", operationId); put("article_id", articleId); put("kind", "view")
        put("phase", phase.wire); put("visit_id", visitId); put("stay_seconds", staySeconds)
    }.toString().encodeToByteArray()

    companion object {
        val keys = setOf("operation_id", "article_id", "kind", "phase", "visit_id", "stay_seconds")
        fun parse(value: JsonObject): DiscoveryArticleView {
            require(value.keys == keys && value.getValue("kind") == JsonPrimitive("view"))
            for (key in listOf("operation_id", "visit_id", "phase")) require(value.getValue(key).jsonPrimitive.isString)
            for (key in listOf("article_id", "stay_seconds")) require(!value.getValue(key).jsonPrimitive.isString)
            return DiscoveryArticleView(value.getValue("operation_id").jsonPrimitive.content, value.getValue("visit_id").jsonPrimitive.content,
                value.getValue("article_id").jsonPrimitive.long,
                DiscoveryViewPhase.entries.single { it.wire == value.getValue("phase").jsonPrimitive.content },
                value.getValue("stay_seconds").jsonPrimitive.int)
        }
    }
}

/** A bounded reading fact; absolute timestamps and credentials never enter this journal. */
internal data class DiscoveryReadingVisit(
    val visitId: String,
    val finishId: String,
    val articleId: Long,
    val milliseconds: Long = 0,
    val startApplied: Boolean = false,
    val ended: Boolean = false,
) {
    init {
        require(UUID.fromString(visitId).toString() == visitId && UUID.fromString(finishId).toString() == finishId && visitId != finishId)
        require(articleId > 0 && milliseconds in 0..MAX_MILLIS)
    }
    fun start() = DiscoveryArticleView(visitId, visitId, articleId, DiscoveryViewPhase.START, 0)
    fun finish(): DiscoveryArticleView {
        check(ended && startApplied)
        return DiscoveryArticleView(finishId, visitId, articleId, DiscoveryViewPhase.FINISH, ((milliseconds + 500) / 1000).toInt())
    }
    fun json() = buildJsonObject {
        put("visit", visitId); put("finish", finishId); put("article", articleId)
        put("milliseconds", milliseconds); put("applied", startApplied); put("ended", ended)
    }
    companion object {
        const val MAX_MILLIS = Int.MAX_VALUE * 1000L
        fun parse(value: JsonObject): DiscoveryReadingVisit {
            require(value.keys == setOf("visit", "finish", "article", "milliseconds", "applied", "ended"))
            for (key in listOf("visit", "finish")) require(value.getValue(key).jsonPrimitive.isString)
            for (key in listOf("article", "milliseconds", "applied", "ended")) require(!value.getValue(key).jsonPrimitive.isString)
            return DiscoveryReadingVisit(value.getValue("visit").jsonPrimitive.content, value.getValue("finish").jsonPrimitive.content,
                value.getValue("article").jsonPrimitive.long, value.getValue("milliseconds").jsonPrimitive.long,
                value.getValue("applied").jsonPrimitive.boolean, value.getValue("ended").jsonPrimitive.boolean)
        }
    }
}
