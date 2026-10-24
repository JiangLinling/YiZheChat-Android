/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import kotlinx.serialization.json.*
import java.util.UUID

internal enum class DiscoveryInteractionKind(val wire: String, val targetField: String = "article_id") {
    LIKE("like"), FAVORITE("favorite"), FOLLOW("follow", "author_id")
}
internal enum class DiscoveryInteractionOutcome { APPLIED, REJECTED, NOTHING_PENDING }

internal sealed interface DiscoveryContentCommand {
    val operationId: String
    fun bytes(): ByteArray
}

internal sealed interface DiscoveryExplicitCommand : DiscoveryContentCommand {
    fun journalBytes(): ByteArray = bytes()
    companion object {
        fun parse(value: JsonObject): DiscoveryExplicitCommand = when {
            "mall_action" in value -> DiscoveryMallCommand.parse(value)
            "feedback" in value -> DiscoveryFeedbackCommand.parse(value)
            "profile" in value -> DiscoveryPersonCommand.parse(value)
            "address" in value -> DiscoveryAddressCommand.parse(value)
            "selection" in value -> DiscoveryCourseQuoteCommand.parse(value)
            "quote_id" in value -> DiscoveryCourseEnrollmentCommand.parse(value)
            value["kind"] == JsonPrimitive("comment") -> DiscoveryArticleCommentCommand.parse(value)
            else -> DiscoveryArticleInteraction.parse(value)
        }
    }
}

internal data class DiscoveryArticleInteraction(
    override val operationId: String,
    val targetId: Long,
    val kind: DiscoveryInteractionKind,
    val enabled: Boolean,
) : DiscoveryExplicitCommand {
    init {
        require(UUID.fromString(operationId).toString() == operationId && targetId > 0)
    }

    override fun bytes(): ByteArray = buildJsonObject {
        put("operation_id", operationId); put(kind.targetField, targetId); put("kind", kind.wire); put("enabled", enabled)
    }.toString().encodeToByteArray()

    companion object {
        fun parse(value: JsonObject): DiscoveryArticleInteraction {
            val kind = DiscoveryInteractionKind.entries.single { it.wire == value.getValue("kind").jsonPrimitive.content }
            require(value.keys == setOf("operation_id", kind.targetField, "kind", "enabled"))
            require(!value.getValue("enabled").jsonPrimitive.isString && !value.getValue(kind.targetField).jsonPrimitive.isString)
            return DiscoveryArticleInteraction(value.getValue("operation_id").jsonPrimitive.content,
                value.getValue(kind.targetField).jsonPrimitive.long, kind,
                value.getValue("enabled").jsonPrimitive.boolean)
        }
    }
}
