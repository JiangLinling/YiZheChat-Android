/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import io.element.android.features.discovery.impl.session.DiscoverySecret
import io.element.android.features.discovery.impl.session.DiscoverySession
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryCourseDetailRepositoryTest {
    private val access = mockk<DefaultDiscoveryAccess>()
    private val transport = mockk<PinnedDiscoveryTransport>()
    private val courses = mockk<DiscoveryCoursesRepository>()
    private val session = DiscoverySession(DiscoverySecret("guest"), "exchange", 160000, 60000)
    private val repository = DiscoveryCourseDetailRepository(access, transport, courses)

    init {
        coEvery { access.withSession<DiscoveryPromo>(any()) } coAnswers {
            firstArg<suspend (DiscoverySession) -> DiscoveryPromo>()(session)
        }
    }

    private fun content() = buildJsonObject {
        put("version", 1)
        put("hero", buildJsonObject { listOf("title", "subtitle", "summary", "scroll_hint", "background_image_url").forEach { put(it, "真实宣传") } })
        put("value_proposition", buildJsonObject { listOf("title", "body", "quote").forEach { put(it, "内容") } })
        put("video", buildJsonObject { listOf("title", "description", "duration", "video_url", "cover_url").forEach { put(it, "") } })
        put("resonance", buildJsonObject { put("title", "共鸣"); put("highlight", ""); put("decor_words", JsonArray(emptyList())); put("paragraphs", JsonArray(emptyList())) })
        put("pain_points", buildJsonObject { put("title", "痛点"); put("items", JsonArray(emptyList())) })
        put("quiet_transition", buildJsonObject { put("image_url", ""); put("overlay_text", "") })
        put("entry_steps", buildJsonObject { put("items", JsonArray(emptyList())) })
        put("experience", buildJsonObject { put("image_url", ""); put("items", JsonArray(emptyList())) })
        put("daily_flow", buildJsonObject { put("title", "流程"); put("items", JsonArray(emptyList())) })
        put("closing", buildJsonObject { listOf("title", "short_sentence", "summary", "endorsement", "invitation").forEach { put(it, "") } })
        put("bottom_bar", buildJsonObject { put("price_label", "¥999999"); put("status_label", "热卖"); put("quota_label", "仅剩1席"); put("button_text", "立即报名") })
    }

    private fun response(published: Boolean = true, courseId: Int = 1, fee: Int = 12345, enrolled: Int = 19) = buildJsonObject {
        put("status", if (published) "published" else "draft")
        put("is_published", published)
        put("published_at", JsonNull)
        put("data", content())
        put("session", buildJsonObject {
            put("session_id", 10); put("course_id", courseId); put("fee", fee); put("enrollment_quota", 20); put("enrolled_count", enrolled)
            put("allow_waitlist", false)
            put("mini_program_status", "published"); put("session_status", "enrolling"); put("enrollment_status", "open")
            put("course", buildJsonObject { put("course_id", courseId) })
        })
    }.toString().encodeToByteArray()

    @Test
    fun `published detail preserves IDs and calculates real price and quota instead of marketing labels`() = runTest {
        val requests = mutableListOf<ByteArray>()
        val responses = mutableListOf<ByteArray>()
        var requestedCourse: Long? = null
        coEvery { transport.queryCourses(any(), any()) } answers {
            assertEquals(mapOf("Authorization" to "Bearer guest"), secondArg<Map<String, String>>())
            firstArg<ByteArray>().also {
                requests += it
                requestedCourse = Json.parseToJsonElement(it.decodeToString()).jsonObject.getValue("course_id").jsonPrimitive.long
            }
            response().also { responses += it }
        }
        val promo = repository.load(1, 10)
        assertEquals(12345L, promo.feeFen)
        assertEquals(1, promo.remaining)
        assertEquals(CourseStatus.OPEN, promo.status)
        assertEquals(1L, requestedCourse)
        assertTrue((requests + responses).all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun `unpublished and wrong parent detail fail closed`() = runTest {
        for (payload in listOf(response(published = false), response(courseId = 2))) {
            coEvery { transport.queryCourses(any(), any()) } returns payload.copyOf()
            assertTrue(runCatching { repository.load(1, 10) }.isFailure)
        }
    }

    @Test
    fun `course only banner resolves its real open session before requesting promo`() = runTest {
        coEvery { courses.load() } returns sampleCourseCatalog().copy(sessions = sampleCourseCatalog().sessions.mapIndexed { index, row ->
            row.copy(enrollmentOpen = index == 1)
        })
        var requestedSession: Long? = null
        coEvery { transport.queryCourses(any(), any()) } answers {
            requestedSession = Json.parseToJsonElement(firstArg<ByteArray>().decodeToString()).jsonObject.getValue("session_id").jsonPrimitive.long
            val original = Json.parseToJsonElement(response(courseId = 2).decodeToString()).jsonObject
            val row = JsonObject(original.getValue("session").jsonObject + ("session_id" to JsonPrimitive(20)))
            JsonObject(original + ("session" to row)).toString().encodeToByteArray()
        }
        assertEquals(20L, repository.loadCourse(2).sessionId)
        assertEquals(20L, requestedSession)
    }
}
