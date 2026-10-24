/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import io.element.android.features.discovery.impl.session.sha256
import kotlinx.serialization.json.*
import java.util.UUID

internal data class DiscoveryArticleCommentCommand(
    override val operationId: String,
    val articleId: Long,
    val content: String,
    val replyToCommentId: Long?,
) : DiscoveryExplicitCommand {
    init {
        require(UUID.fromString(operationId).toString() == operationId && articleId > 0)
        require(replyToCommentId == null || replyToCommentId > 0)
        require(validInput(content) && content.isNotBlank() && content == content.trim())
    }

    override fun toString() = "DiscoveryArticleCommentCommand(operationId=$operationId, articleId=$articleId, content=<redacted>)"

    override fun bytes(): ByteArray = buildJsonObject {
        put("operation_id", operationId); put("article_id", articleId); put("kind", "comment")
        put("content", content); put("reply_to_comment_id", replyToCommentId?.let(::JsonPrimitive) ?: JsonNull)
    }.toString().encodeToByteArray()

    fun receiptFields(): JsonObject = buildJsonObject {
        put("operation_id", operationId); put("article_id", articleId); put("kind", "comment")
        put("reply_to_comment_id", replyToCommentId?.let(::JsonPrimitive) ?: JsonNull)
        put("content_sha256", sha256(content))
    }

    companion object {
        const val MAX_LENGTH = 140
        fun validInput(value: String): Boolean = value.codePointCount(0, value.length) <= MAX_LENGTH &&
            value.codePoints().allMatch { it !in 0xD800..0xDFFF && (it >= 32 || it == 10 || it == 9) }

        fun parse(value: JsonObject): DiscoveryArticleCommentCommand {
            require(value.keys == setOf("operation_id", "article_id", "kind", "content", "reply_to_comment_id"))
            require(value["kind"] == JsonPrimitive("comment"))
            require(value.getValue("operation_id").jsonPrimitive.isString && value.getValue("content").jsonPrimitive.isString)
            val article = value.getValue("article_id").jsonPrimitive
            val reply = value.getValue("reply_to_comment_id").jsonPrimitive
            require(!article.isString && !reply.isString)
            return DiscoveryArticleCommentCommand(value.getValue("operation_id").jsonPrimitive.content,
                article.long, value.getValue("content").jsonPrimitive.content, if (reply == JsonNull) null else reply.long)
        }
    }
}
