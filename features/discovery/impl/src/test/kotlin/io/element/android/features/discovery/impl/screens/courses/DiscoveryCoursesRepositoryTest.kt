/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.*
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryCoursesRepositoryTest {
    private val access = mockk<DefaultDiscoveryAccess>()
    private val transport = mockk<PinnedDiscoveryTransport>()
    private val session = DiscoverySession(DiscoverySecret("guest"), "exchange", 160000, 60000)
    private val repository = DiscoveryCoursesRepository(access, transport)
    init {
        coEvery { access.withSession<DiscoveryCourseCatalog>(any()) } coAnswers {
            firstArg<suspend (DiscoverySession) -> DiscoveryCourseCatalog>()(session)
        }
    }
    private fun course(id: Int) = buildJsonObject {
        put("course_id", id); put("title", "Course $id"); put("short_name", ""); put("cover_image_url", JsonNull)
    }
    private fun item(id: Int, courseId: Int = 1, publication: String = "published", enrollment: String = "open", status: String = "enrolling") = buildJsonObject {
        put("session_id", id); put("course_id", courseId); put("course", course(courseId)); put("title", "Session $id")
        put("mini_program_status", publication); put("enrollment_status", enrollment); put("session_status", status)
        listOf("city", "venue_short_name", "venue", "start_date", "end_date", "amap_location", "baidu_location", "address").forEach { put(it, JsonNull) }
        put("enrollment_quota", 20); put("fee", 12345)
        listOf("enable_accommodation", "enable_meal", "enable_pickup").forEach { put(it, false) }
    }
    private fun page(rows: List<JsonObject>, number: Int, total: Int = rows.size) = buildJsonObject {
        put("items", JsonArray(rows)); put("page", number); put("page_size", 20); put("total", total)
    }.toString().encodeToByteArray()

    @Test
    fun `all pages share one session and bytes are cleared before whole catalog is returned`() = runTest {
        val requests = mutableListOf<ByteArray>(); val responses = mutableListOf<ByteArray>()
        coEvery { transport.queryCourses(any(), any()) } answers {
            assertEquals(mapOf("Authorization" to "Bearer guest"), secondArg<Map<String,String>>())
            val raw = firstArg<ByteArray>();requests += raw
            val query = Json.parseToJsonElement(raw.decodeToString()).jsonObject
            val number = query.getValue("page").jsonPrimitive.int
            val rows = if (query.getValue("kind").jsonPrimitive.content == "courses") (1..21).map(::course) else (1..23).map { item(it) }
            page(rows.drop((number-1)*20).take(20), number, rows.size).also { responses += it }
        }
        val catalog = repository.load()
        assertEquals(21, catalog.courses.size); assertEquals(23, catalog.sessions.size)
        assertEquals(12345L, catalog.sessions.last().feeFen)
        assertEquals(4, requests.size)
        assertTrue((requests + responses).all { it.all { byte -> byte == 0.toByte() } })
        coVerify(exactly = 1) { access.withSession<DiscoveryCourseCatalog>(any()) }
    }

    @Test
    fun `source display priority is preserved including historical unlisted and cancelled open`() = runTest {
        val rows = listOf(item(1,publication="unlisted"), item(2,status="cancelled"), item(3,enrollment="full"),
            item(4,enrollment="closed"), item(5,status="finished"), item(6,enrollment="not_open",status="archived"),
            item(7,enrollment="not_open",status="preparing"))
        coEvery { transport.queryCourses(any(), any()) } answers {
            val query = Json.parseToJsonElement(firstArg<ByteArray>().decodeToString()).jsonObject
            page(if (query.getValue("kind").jsonPrimitive.content == "courses") listOf(course(1)) else rows,1)
        }
        val catalog = repository.load()
        assertEquals(listOf(CourseStatus.ENDED,CourseStatus.CANCELLED,CourseStatus.FULL,CourseStatus.CLOSED,
            CourseStatus.OPEN,CourseStatus.ENDED,CourseStatus.PENDING),catalog.sessions.map { it.status })
        assertEquals(5L,catalog.current?.id)
        assertEquals(7L,catalog.copy(sessions=catalog.sessions.filter { it.id!=5L }).current?.id)
    }

    @Test
    fun `incomplete duplicate changing or malformed pages never become a complete catalog`() = runTest {
        for (mode in listOf("duplicate", "changed_total", "missing", "boolean_page", "too_large", "wrong_parent", "string_fee")) {
            coEvery { transport.queryCourses(any(), any()) } answers {
                val q = Json.parseToJsonElement(firstArg<ByteArray>().decodeToString()).jsonObject
                val kind = q.getValue("kind").jsonPrimitive.content;val number=q.getValue("page").jsonPrimitive.int
                if (kind=="courses") page(listOf(course(1)),1) else when(mode) {
                    "wrong_parent" -> page(listOf(item(1,courseId=2)),1)
                    "string_fee" -> page(listOf(JsonObject(item(1)+("fee" to JsonPrimitive("12345")))),1)
                    "missing" -> page(emptyList(),1,1)
                    "boolean_page" -> """{"items":[],"page":true,"page_size":20,"total":0}""".encodeToByteArray()
                    "too_large" -> page(emptyList(),1,20001)
                    else -> if (number==1) page((1..20).map { item(it) },1,21)
                        else page(listOf(item(if(mode=="duplicate") 1 else 21)),2,if(mode=="changed_total") 22 else 21)
                }
            }
            assertTrue(mode,runCatching { repository.load() }.isFailure)
        }
    }

    @Test
    fun `catalog deadline reports retryable failure instead of endless loading cancellation`() = runTest {
        coEvery { transport.queryCourses(any(),any()) } coAnswers { delay(31_000); page(emptyList(),1) }
        val error = runCatching { repository.load() }.exceptionOrNull()
        assertTrue(error is DiscoveryFailure && error.status == 503)
    }
}
