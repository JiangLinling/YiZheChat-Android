/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import kotlinx.serialization.json.*

@Inject
internal class DiscoveryCoursesRepository(private val access: DefaultDiscoveryAccess, private val transport: PinnedDiscoveryTransport) {
    // One session owns the entire load. Never publish the first page as the whole catalog.
    suspend fun load(): DiscoveryCourseCatalog = withTimeoutOrNull(30_000) {
        access.withSession { session ->
            suspend fun all(kind: String): List<JsonObject> {
                val rows = mutableListOf<JsonObject>()
                val ids = mutableSetOf<Long>()
                var total: Int? = null
                for (page in 1..1000) {
                    currentCoroutineContext().ensureActive()
                    val request = buildJsonObject { put("kind", kind); put("page", page); put("page_size", 20) }.toString().encodeToByteArray()
                    val value = try {
                        val bytes = transport.queryCourses(request, mapOf("Authorization" to "Bearer ${session.secret.value}"))
                        try { Json.parseToJsonElement(bytes.decodeToString()).jsonObject } finally { bytes.fill(0) }
                    } finally { request.fill(0) }
                    require(value.keys == setOf("items", "total", "page", "page_size"))
                    require(value.count("page") == page && value.count("page_size") == 20)
                    val count = value.count("total").also { require(it <= 20_000) }
                    require(total == null || total == count)
                    total = count
                    val items = value.getValue("items").jsonArray
                    require(items.size == minOf(20, (count - rows.size).coerceAtLeast(0)))
                    items.forEach {
                        val row = it.jsonObject
                        require(ids.add(row.id(if (kind == "courses") "course_id" else "session_id")))
                        rows += row
                    }
                    if (rows.size == count) return rows
                }
                error("Incomplete course catalog")
            }
            val courses = all("courses").map(::parseCourse)
            val indexed = courses.associateBy { it.id }
            val sessions = all("sessions").map { row ->
                val course = parseCourse(row.getValue("course").jsonObject)
                require(row.id("course_id") == course.id && indexed[course.id] == course)
                val publication = row.text("mini_program_status")
                val status = row.text("session_status")
                val enrollment = row.text("enrollment_status")
                require(publication in setOf("published", "unlisted"))
                require(status in setOf("archived", "cancelled", "finished", "ongoing", "enrolling", "preparing"))
                require(enrollment in setOf("not_open", "open", "full", "closed"))
                val displayed = when {
                    publication == "unlisted" -> CourseStatus.ENDED
                    status == "cancelled" -> CourseStatus.CANCELLED
                    enrollment == "full" -> CourseStatus.FULL
                    enrollment == "closed" -> CourseStatus.CLOSED
                    enrollment == "open" -> CourseStatus.OPEN
                    status in setOf("finished", "archived") -> CourseStatus.ENDED
                    else -> CourseStatus.PENDING
                }
                DiscoveryCourseSession(row.id("session_id"), course, row.text("title"), displayed,
                    row.text("city"), row.text("venue_short_name").ifBlank { row.text("venue") },
                    row.text("start_date"), row.text("end_date"), row.count("enrollment_quota"),
                    row.flag("enable_accommodation"), row.flag("enable_meal"), row.flag("enable_pickup"),
                    row.text("amap_location"), row.text("baidu_location"), row.text("address"), row.number("fee").also { require(it >= 0) },
                    enrollmentOpen = enrollment == "open")
            }
            DiscoveryCourseCatalog(courses, sessions)
        }
    } ?: throw DiscoveryFailure(503)

    private fun parseCourse(row: JsonObject) = DiscoveryCourse(row.id("course_id"), row.text("title"), row.text("short_name"))
    private fun JsonObject.text(key: String): String = getValue(key).let { value ->
        if (value == JsonNull) "" else value.jsonPrimitive.also { require(it.isString) }.content
    }
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.let { require(!it.isString); it.long }
    private fun JsonObject.id(key: String) = number(key).also { require(it in 1..Int.MAX_VALUE.toLong()) }
    private fun JsonObject.count(key: String) = number(key).also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.let { require(!it.isString); requireNotNull(it.booleanOrNull) }
}
