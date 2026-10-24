/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import kotlinx.serialization.json.*

internal data class DiscoveryPromo(
    val courseId: Long,
    val sessionId: Long,
    val feeFen: Long,
    val quota: Int,
    val enrolled: Int,
    val status: CourseStatus,
    val allowWaitlist: Boolean,
    val data: JsonObject,
) {
    val remaining: Int? get() = quota.takeIf { it > 0 }?.let { (it - enrolled).coerceAtLeast(0) }
}

@Inject
internal class DiscoveryCourseDetailRepository(private val access: DefaultDiscoveryAccess, private val transport: PinnedDiscoveryTransport,
    private val courses: DiscoveryCoursesRepository) {
    suspend fun loadCourse(courseId: Long): DiscoveryPromo {
        require(courseId in 1..Int.MAX_VALUE.toLong())
        val catalog = courses.load()
        require(catalog.courses.any { it.id == courseId })
        val options = catalog.sessions.filter { it.course.id == courseId }
        val session = options.firstOrNull { it.enrollmentOpen } ?: options.firstOrNull()
            ?: throw IllegalStateException("No published course session")
        return load(courseId, session.id)
    }

    suspend fun load(courseId: Long, sessionId: Long): DiscoveryPromo {
        require(courseId in 1..Int.MAX_VALUE.toLong() && sessionId in 1..Int.MAX_VALUE.toLong())
        return access.withSession { session ->
            val request = buildJsonObject {
                put("kind", "promo")
                put("course_id", courseId)
                put("session_id", sessionId)
            }.toString().encodeToByteArray()
            val response = try {
                val bytes = transport.queryCourses(request, mapOf("Authorization" to "Bearer ${session.secret.value}"))
                try { Json.parseToJsonElement(bytes.decodeToString()).jsonObject } finally { bytes.fill(0) }
            } finally { request.fill(0) }
            require(response.keys == setOf("session", "data", "status", "is_published", "published_at"))
            require(response.string("status") == "published" && response.boolean("is_published"))
            val row = response.objectField("session")
            require(row.long("course_id") == courseId && row.long("session_id") == sessionId)
            require(row.objectField("course").long("course_id") == courseId)
            val fee = row.long("fee").also { require(it >= 0) }
            val quota = row.long("enrollment_quota").also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
            val enrolled = row.long("enrolled_count").also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
            val allowWaitlist = row.boolean("allow_waitlist")
            val publication = row.string("mini_program_status")
            val sessionStatus = row.string("session_status")
            val enrollment = row.string("enrollment_status")
            require(publication in setOf("published", "unlisted"))
            require(sessionStatus in setOf("archived", "cancelled", "finished", "ongoing", "enrolling", "preparing"))
            require(enrollment in setOf("not_open", "open", "full", "closed"))
            val status = when {
                publication == "unlisted" -> CourseStatus.ENDED
                sessionStatus == "cancelled" -> CourseStatus.CANCELLED
                enrollment == "full" -> CourseStatus.FULL
                enrollment == "closed" -> CourseStatus.CLOSED
                enrollment == "open" -> CourseStatus.OPEN
                sessionStatus in setOf("finished", "archived") -> CourseStatus.ENDED
                else -> CourseStatus.PENDING
            }
            val data = response.objectField("data")
            require(data.long("version") == 1L)
            require(data.keys == setOf("version", "hero", "value_proposition", "video", "resonance", "pain_points",
                "quiet_transition", "entry_steps", "experience", "daily_flow", "closing", "bottom_bar"))
            validatePromo(data)
            DiscoveryPromo(courseId, sessionId, fee, quota, enrolled, status, allowWaitlist, data)
        }
    }
}

private fun validatePromo(data: JsonObject) {
    data.objectField("hero").also { it.string("title"); it.string("subtitle"); it.string("summary"); it.string("scroll_hint"); it.string("background_image_url") }
    data.objectField("value_proposition").also { it.string("title"); it.string("body"); it.string("quote") }
    data.objectField("video").also { it.string("title"); it.string("description"); it.string("duration"); it.string("video_url"); it.string("cover_url") }
    data.objectField("resonance").also { it.string("title"); it.string("highlight"); it.strings("decor_words", 4); it.strings("paragraphs", 40) }
    data.objectField("pain_points").also { it.string("title"); it.strings("items", 40) }
    data.objectField("quiet_transition").also { it.string("image_url"); it.string("overlay_text") }
    data.objectField("entry_steps").objects("items", 20).forEach { it.string("seal"); it.string("title") }
    data.objectField("experience").also { it.string("image_url"); it.objects("items", 40).forEach { row -> row.string("icon"); row.string("title"); row.string("description") } }
    data.objectField("daily_flow").also { it.string("title"); it.objects("items", 40).forEach { row -> row.string("title"); row.string("description") } }
    data.objectField("closing").also { it.string("title"); it.string("short_sentence"); it.string("summary"); it.string("endorsement"); it.string("invitation") }
    // Published marketing labels are displayed only as prose. Price, status and quota are always derived from session facts.
    data.objectField("bottom_bar").also { it.string("button_text"); it.string("price_label"); it.string("status_label"); it.string("quota_label") }
}

internal fun JsonObject.objectField(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
internal fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.let { require(!it.isString); it.long }
internal fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.let { require(!it.isString); requireNotNull(it.booleanOrNull) }
internal fun JsonObject.strings(key: String, limit: Int): List<String> = getValue(key).jsonArray.also { require(it.size <= limit) }
    .map { it.jsonPrimitive.also { value -> require(value.isString) }.content }
internal fun JsonObject.objects(key: String, limit: Int): List<JsonObject> = getValue(key).jsonArray.also { require(it.size <= limit) }.map { it.jsonObject }
