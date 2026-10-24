/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import io.element.android.features.discovery.impl.session.DiscoverySecret
import io.element.android.features.discovery.impl.session.DiscoverySession
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryArticlesRepositoryTest {
    @Test
    fun `personal lists use only server resolved actor and validate page and relation flags`() = runTest {
        val access = mockk<DefaultDiscoveryAccess>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val session = DiscoverySession(DiscoverySecret("guest"), "exchange", 160000, 60000)
        coEvery { access.withSession<JsonObject>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> JsonObject>()(session) }
        val repository = DiscoveryArticlesRepository(access, transport)
        for (kind in DiscoveryPersonalKind.entries) {
            val valid = Json.parseToJsonElement(when (kind) {
                DiscoveryPersonalKind.REPLIES -> """{"comment_id":9,"article_id":5,"parent_id":8,"content":"Reply","parent_content":"Original","user_name":"Reader","article_title":"Test","status":"visible","like_count":0,"is_pinned":false,"is_author_reply":false}"""
                DiscoveryPersonalKind.FAVORITES -> """{"article_id":5,"title":"Test","user_state":{"favorited":true}}"""
                DiscoveryPersonalKind.HISTORY -> """{"article_id":5,"title":"Test","view_count":128}"""
                DiscoveryPersonalKind.FOLLOWS -> """{"author_id":7,"nickname":"Author","bio":"","article_count":1,"follower_count":2,"followed":true,"follow_enabled":true,"status":"active"}"""
            }).jsonObject
            var item = valid
            var responsePage = 2
            coEvery { transport.queryArticles(any(), any()) } answers {
                val query = Json.parseToJsonElement(firstArg<ByteArray>().decodeToString()).jsonObject
                assertEquals(setOf("kind", "page", "page_size"), query.keys)
                assertEquals(kind.wire, query.getValue("kind").jsonPrimitive.content)
                assertEquals(kind.pageSize, query.getValue("page_size").jsonPrimitive.int)
                assertEquals(2, query.getValue("page").jsonPrimitive.int)
                buildJsonObject {
                    put("page", responsePage); put("page_size", kind.pageSize); put("total", 21)
                    putJsonArray("items") { add(item) }
                }.toString().encodeToByteArray()
            }
            val result = repository.personal(kind, 2)
            assertEquals(1, result.items.size)
            if (kind == DiscoveryPersonalKind.HISTORY) assertEquals(128, (result.items.single() as DiscoveryPersonalItem.Article).value.views)
            item = when (kind) {
                DiscoveryPersonalKind.FAVORITES -> JsonObject(valid + ("user_state" to buildJsonObject { put("favorited", false) }))
                DiscoveryPersonalKind.FOLLOWS -> JsonObject(valid + ("followed" to JsonPrimitive(false)))
                DiscoveryPersonalKind.HISTORY -> JsonObject(valid + ("view_count" to JsonPrimitive(-1)))
                DiscoveryPersonalKind.REPLIES -> JsonObject(valid + ("parent_id" to JsonNull))
            }
            assertTrue(runCatching { repository.personal(kind, 2) }.isFailure)
            item = valid
            responsePage = 1
            assertTrue(runCatching { repository.personal(kind, 2) }.isFailure)
        }
    }

    @Test
    fun `collection metadata uses its exact selector and rejects wrong identity or unknown follow state`() = runTest {
        val access = mockk<DefaultDiscoveryAccess>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val session = DiscoverySession(DiscoverySecret("guest"), "exchange", 160000, 60000)
        coEvery { access.withSession<JsonObject>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> JsonObject>()(session) }
        val valid = Json.parseToJsonElement("""{"author_id":7,"nickname":"作者","bio":"共读","article_count":2,"follower_count":3,"followed":true,"follow_enabled":true,"status":"active"}""").jsonObject
        var metadata = valid
        coEvery { transport.queryArticles(any(), any()) } answers {
            val query = Json.parseToJsonElement(firstArg<ByteArray>().decodeToString()).jsonObject
            assertEquals(setOf("kind", "author_id"), query.keys)
            assertEquals(7, query.getValue("author_id").jsonPrimitive.int)
            buildJsonObject { put("author", metadata) }.toString().encodeToByteArray()
        }
        val repository = DiscoveryArticlesRepository(access, transport)
        val collection = ArticleCollection(ArticleCollectionKind.AUTHOR, 7)
        val result = repository.collection(collection)
        assertEquals("作者", result.title)
        assertTrue(result.followed && result.followEnabled)
        for (invalid in listOf(JsonObject(valid - "followed"), JsonObject(valid + ("followed" to JsonPrimitive("true"))),
            JsonObject(valid + ("author_id" to JsonPrimitive(8))), JsonObject(valid + ("status" to JsonPrimitive("inactive"))),
            JsonObject(valid + ("follower_count" to JsonPrimitive(-1))))) {
            metadata = invalid
            assertTrue(runCatching { repository.collection(collection) }.isFailure)
        }
    }

    @Test
    fun `tag filtered feed carries the same id on later pages and keeps navigation identities`() = runTest {
        val access = mockk<DefaultDiscoveryAccess>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val session = DiscoverySession(DiscoverySecret("guest"), "exchange", 160000, 60000)
        coEvery { access.withSession<JsonObject>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> JsonObject>()(session) }
        coEvery { transport.queryArticles(any(), any()) } answers {
            val query = Json.parseToJsonElement(firstArg<ByteArray>().decodeToString()).jsonObject
            assertEquals(7, query.getValue("tag_id").jsonPrimitive.int)
            assertEquals(2, query.getValue("page").jsonPrimitive.int)
            assertFalse(query.containsKey("author_id") || query.containsKey("user_id"))
            """{"page":2,"total":11,"items":[{"article_id":11,"author":{"author_id":5,"nickname":"作者"},"tags":[{"tag_id":7,"name":"经典"}]}]}""".encodeToByteArray()
        }
        val item = DiscoveryArticlesRepository(access, transport).feed("", ArticleSort.RECOMMEND, 2,
            ArticleCollection(ArticleCollectionKind.TAG, 7)).items.single()
        assertEquals(5L, item.authorId)
        assertEquals(listOf(ArticleTag(7, "经典")), item.tags)
    }

    @Test
    fun `interactive detail requires explicit actor flags and valid counts instead of assuming false`() = runTest {
        val access = mockk<DefaultDiscoveryAccess>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val session = DiscoverySession(DiscoverySecret("guest"), "exchange", 160000, 60000)
        coEvery { access.withSession<JsonObject>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> JsonObject>()(session) }
        val valid = Json.parseToJsonElement("""{"article_id":5,"title":"Test","like_count":4,"favorite_count":7,"comment_enabled":true,"user_state":{"liked":true,"favorited":false}}""").jsonObject
        var article = valid
        coEvery { transport.queryArticles(any(), any()) } answers {
            assertEquals(mapOf("Authorization" to "Bearer guest"), secondArg<Map<String, String>>())
            val query = Json.parseToJsonElement(firstArg<ByteArray>().decodeToString()).jsonObject
            assertEquals(setOf("kind", "article_id"), query.keys)
            buildJsonObject { put("article", article) }.toString().encodeToByteArray()
        }
        val repository = DiscoveryArticlesRepository(access, transport)
        val result = repository.detail(5)
        assertTrue(result.liked)
        assertFalse(result.favorited)
        assertEquals(4, result.likes)
        assertEquals(7, result.favorites)
        assertTrue(result.commentEnabled)
        val malformed = listOf(
            JsonObject(valid - "user_state"),
            JsonObject(valid + ("comment_enabled" to JsonPrimitive("true"))),
            JsonObject(valid + ("user_state" to buildJsonObject { put("liked", "true"); put("favorited", false) })),
            JsonObject(valid + ("like_count" to JsonPrimitive(-1))),
            JsonObject(valid + ("article_id" to JsonPrimitive(6))),
        )
        for (value in malformed) {
            article = value
            assertTrue(runCatching { repository.detail(5) }.isFailure)
        }
    }
    @Test
    fun `comments bind article and page and reject hidden malformed or duplicate rows`() = runTest {
        val access = mockk<DefaultDiscoveryAccess>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val session = DiscoverySession(DiscoverySecret("guest"), "exchange", 160000, 60000)
        coEvery { access.withSession<JsonObject>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> JsonObject>()(session) }
        val valid = Json.parseToJsonElement("""{"comment_id":9,"article_id":5,"parent_id":8,"content":"Reply","parent_content":"Original","parent_user_name":"Reader","user_name":"Author","create_at":"2026-10-24T09:30:00","status":"visible","like_count":2,"is_pinned":true,"is_author_reply":false}""").jsonObject
        var rows = listOf(valid)
        var responsePage = 3
        coEvery { transport.queryArticles(any(), any()) } answers {
            val query = Json.parseToJsonElement(firstArg<ByteArray>().decodeToString()).jsonObject
            assertEquals(setOf("kind", "article_id", "page", "page_size"), query.keys)
            assertEquals("comments", query.getValue("kind").jsonPrimitive.content)
            assertEquals(5, query.getValue("article_id").jsonPrimitive.int)
            assertEquals(3, query.getValue("page").jsonPrimitive.int)
            buildJsonObject { put("page", responsePage); put("page_size", 20); put("total", 41); put("items", JsonArray(rows)) }.toString().encodeToByteArray()
        }
        val repository = DiscoveryArticlesRepository(access, transport)
        val item = repository.comments(5, 3).items.single()
        assertEquals("Original", item.parentContent)
        assertEquals("2026-10-24 09:30", item.createdAt)
        assertTrue(item.pinned)
        for ((field, invalid) in listOf("article_id" to JsonPrimitive(6), "comment_id" to JsonPrimitive("9"),
            "parent_id" to JsonPrimitive(9), "like_count" to JsonPrimitive(-1), "is_pinned" to JsonPrimitive("true"), "status" to JsonPrimitive("hidden"))) {
            rows = listOf(JsonObject(valid + (field to invalid)))
            assertTrue(runCatching { repository.comments(5, 3) }.isFailure)
        }
        rows = listOf(valid, valid)
        assertTrue(runCatching { repository.comments(5, 3) }.isFailure)
        rows = listOf(valid); responsePage = 2
        assertTrue(runCatching { repository.comments(5, 3) }.isFailure)
    }

}
