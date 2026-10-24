/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DiscoveryArticleCommentCommandTest {
    @Test
    fun `comment round trip has explicit null exact unicode digest and no content in diagnostic string`() {
        val command = DiscoveryArticleCommentCommand(UUID.randomUUID().toString(), 5, "文字😀", null)
        val value = Json.parseToJsonElement(command.bytes().decodeToString()).jsonObject
        assertEquals(JsonNull, value["reply_to_comment_id"])
        assertEquals(command, DiscoveryExplicitCommand.parse(value))
        assertFalse(command.toString().contains(command.content))
        assertFalse(command.receiptFields().containsKey("content"))
        assertEquals("ae478779c13a12c0804fe7ee827906e49655d4d4442194e4e3a2cea2612e15ed",
            command.receiptFields().getValue("content_sha256").jsonPrimitive.content)
        for (patch in listOf(mapOf("user_id" to JsonPrimitive(1)), mapOf("reply_to_comment_id" to JsonPrimitive(true)),
            mapOf("content" to JsonPrimitive(" ")), mapOf("article_id" to JsonPrimitive("5")))) {
            assertTrue(runCatching { DiscoveryExplicitCommand.parse(JsonObject(value + patch)) }.isFailure)
        }
    }

    @Test
    fun `input accepts unicode boundary without splitting surrogate pair and rejects malformed text`() {
        assertTrue(DiscoveryArticleCommentCommand.validInput("😀".repeat(140)))
        assertFalse(DiscoveryArticleCommentCommand.validInput("😀".repeat(141)))
        assertFalse(DiscoveryArticleCommentCommand.validInput("\uD800"))
        assertFalse(DiscoveryArticleCommentCommand.validInput("a\u0000"))
    }
}
