/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.util

import io.element.android.libraries.matrix.api.room.IntentionalMention
import io.element.android.libraries.matrix.api.timeline.MsgType
import io.element.android.libraries.matrix.impl.room.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.matrix.rustcomponents.sdk.MessageContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.contentWithoutRelationFromMessage
import org.matrix.rustcomponents.sdk.messageEventContentFromHtml
import org.matrix.rustcomponents.sdk.messageEventContentFromHtmlAsEmote
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdownAsEmote
import org.matrix.rustcomponents.sdk.MessageContent as RustMessageContent
import org.matrix.rustcomponents.sdk.MessageType as RustMessageType

/**
 * Creates a [RoomMessageEventContentWithoutRelation] from a body, an html body and a list of mentions.
 */
object MessageEventContent {
    fun protected(
        encodedEnvelope: String,
        intentionalMentions: List<IntentionalMention> = emptyList(),
    ): RoomMessageEventContentWithoutRelation {
        val transportBody = PROTECTED_BODY_PREFIX + encodedEnvelope
        return contentWithoutRelationFromMessage(
            RustMessageContent(
                msgType = RustMessageType.Text(
                    TextMessageContent(
                        body = transportBody,
                        formatted = null,
                    )
                ),
                body = transportBody,
                isEdited = false,
                mentions = null,
            )
        ).withMentions(intentionalMentions.map())
    }

    /** Ordinary protected text with an explicit durable send transaction. */
    fun protectedJson(
        encodedEnvelope: String,
        intentionalMentions: List<IntentionalMention>,
    ): String = buildJsonObject {
        put("msgtype", "m.text")
        put("body", PROTECTED_BODY_PREFIX + encodedEnvelope)
        put("m.mentions", buildJsonObject {
            put("user_ids", buildJsonArray {
                intentionalMentions.filterIsInstance<IntentionalMention.User>().forEach {
                    add(JsonPrimitive(it.userId.value))
                }
            })
            if (intentionalMentions.any { it is IntentionalMention.Room }) put("room", true)
        })
    }.toString()

    /**
     * Builds a standards-compatible Matrix reply around an opaque YiZhe envelope.
     * Deliberately omits the legacy plain-text/HTML reply fallback so protected
     * target content is never copied outside the encrypted YiZhe payload.
     */
    fun protectedReplyJson(
        encodedEnvelope: String,
        intentionalMentions: List<IntentionalMention>,
        repliedToEventId: String,
    ): String {
        val mentionedUsers = intentionalMentions.filterIsInstance<IntentionalMention.User>()
        return buildJsonObject {
            put("msgtype", "m.text")
            put("body", PROTECTED_BODY_PREFIX + encodedEnvelope)
            put("m.relates_to", buildJsonObject {
                put("m.in_reply_to", buildJsonObject {
                    put("event_id", repliedToEventId)
                })
            })
            put("m.mentions", buildJsonObject {
                put("user_ids", buildJsonArray {
                    mentionedUsers.forEach { add(JsonPrimitive(it.userId.value)) }
                })
                if (intentionalMentions.any { it is IntentionalMention.Room }) {
                    put("room", true)
                }
            })
        }.toString()
    }

    fun from(
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        msgType: MsgType = MsgType.MSG_TYPE_TEXT,
        asPlainText: Boolean = false,
    ): RoomMessageEventContentWithoutRelation {
        return when {
            asPlainText -> contentWithoutRelationFromMessage(
                MessageContent(
                    msgType = MessageType.Text(
                        TextMessageContent(
                            body = body,
                            formatted = null,
                        )
                    ),
                    body = body,
                    isEdited = false,
                    mentions = null,
                )
            )
            htmlBody != null -> if (msgType == MsgType.MSG_TYPE_EMOTE) {
                messageEventContentFromHtmlAsEmote(body, htmlBody)
            } else {
                messageEventContentFromHtml(body, htmlBody)
            }
            else -> if (msgType == MsgType.MSG_TYPE_EMOTE) {
                messageEventContentFromMarkdownAsEmote(body)
            } else {
                messageEventContentFromMarkdown(body)
            }
        }
            .withMentions(intentionalMentions.map())
    }

    const val PROTECTED_MESSAGE_TYPE = "io.yizhe.secure.v1"
    const val PROTECTED_BODY_PREFIX = "yizhe-secure-v1:"
}
