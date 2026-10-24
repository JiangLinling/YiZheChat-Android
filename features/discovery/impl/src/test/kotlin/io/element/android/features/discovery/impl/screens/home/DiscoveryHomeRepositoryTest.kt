/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.home

import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryHomeRepositoryTest {
    @Test
    fun `open session card keeps exact course and session destination`() = runTest {
        val access = mockk<DefaultDiscoveryAccess>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val session = DiscoverySession(DiscoverySecret("guest"), "exchange", 160000, 60000)
        coEvery { access.withSession<DiscoveryHomeContent>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> DiscoveryHomeContent>()(session) }
        coEvery { transport.getHome(any()) } returns """{"banners":[],"news":[],"realm_articles":[],"features":[],"open_sessions":[{"course_id":8,"session_id":19,"title":"公开课程","venue":"书院"}]}""".encodeToByteArray()
        val card = DiscoveryHomeRepository(access, transport).load().sessions.single()
        assertEquals(8L, card.targetCourseId)
        assertEquals(19L, card.targetSessionId)
    }

    @Test
    fun `academy targets require positive article ids and legacy content ids cannot impersonate them`() = runTest {
        val access = mockk<DefaultDiscoveryAccess>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val session = DiscoverySession(DiscoverySecret("guest"), "exchange", 160000, 60000)
        coEvery { access.withSession<DiscoveryHomeContent>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> DiscoveryHomeContent>()(session) }
        val realm = Json.parseToJsonElement("""{"article_id":9,"title":"Realm","description":"Summary","type_id":"realm"}""")
        var rows = listOf(realm)
        coEvery { transport.getHome(any()) } answers {
            assertEquals(mapOf("Authorization" to "Bearer guest"), firstArg<Map<String,String>>())
            buildJsonObject {
                put("banners", JsonArray(emptyList())); put("news", JsonArray(emptyList())); put("open_sessions", JsonArray(emptyList()))
                put("realm_articles", JsonArray(rows))
                put("features", Json.parseToJsonElement("""[{"content_id":999,"title":"Legacy","description":"Description"}]"""))
            }.toString().encodeToByteArray()
        }
        val repository = DiscoveryHomeRepository(access, transport)
        assertEquals(9L, repository.load().academy.single().targetArticleId)
        rows = emptyList()
        assertEquals("999", repository.load().academy.single().id)
        assertNull(repository.load().academy.single().targetArticleId)
        for (invalid in listOf(
            JsonObject(realm.jsonObject + ("article_id" to JsonPrimitive(true))),
            JsonObject(realm.jsonObject + ("article_id" to JsonPrimitive("9"))),
            JsonObject(realm.jsonObject + ("type_id" to JsonPrimitive("news"))),
        )) {
            rows = listOf(invalid)
            assertTrue(runCatching { repository.load() }.isFailure)
        }
        rows = listOf(realm, realm)
        assertTrue(runCatching { repository.load() }.isFailure)
    }
}
