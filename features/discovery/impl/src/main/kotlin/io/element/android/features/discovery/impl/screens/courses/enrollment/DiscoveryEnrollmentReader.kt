/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.*

internal data class EnrollmentAccommodationWire(val enabled: Boolean, val first: LocalDate?, val last: LocalDate?,
    val singlePrice: Long, val sharedPrice: Long, val capacity: Int)
internal data class EnrollmentPickupWire(val enabled: Boolean, val first: LocalDate?, val last: LocalDate?, val price: Long, val configId: Long?,
    val modes: Set<EnrollmentTravel>)
internal data class EnrollmentRegistrationWire(
    val courseId: Long, val sessionId: Long, val title: String, val optionsRevision: String,
    val people: List<EnrollmentPerson>, val dates: List<LocalDate>, val accommodation: EnrollmentAccommodationWire,
    val meals: List<EnrollmentMeal>, val pickup: EnrollmentPickupWire, val destination: String,
    val canEnroll: Boolean, val canWaitlist: Boolean, val eligibilityReason: String,
    val mode: EnrollmentMode, val start: LocalDateTime?, val end: LocalDateTime?,
) {
    override fun toString() = "EnrollmentRegistrationWire(courseId=$courseId, sessionId=$sessionId, details=<redacted>)"
}
internal data class EnrollmentItemWire(val enrollmentId: Long, val personId: Long, val status: String, val amount: Long, val orderId: Long?, val name: String)
internal data class EnrollmentOrderWire(val orderId: Long, val status: String, val amount: Long, val expiresAt: String?, val payMode: String?)
internal data class EnrollmentCurrentWire(val courseId: Long, val sessionId: Long, val enrollments: List<EnrollmentItemWire>,
    val order: EnrollmentOrderWire?, val actions: Set<String>, val title: String, val paymentChannel: EnrollmentPaymentChannel)

/** Fixed, authorised course reads. Every form field is projected from the current server response. */
@Inject
internal class DiscoveryEnrollmentReader(private val access: DefaultDiscoveryAccess, private val transport: PinnedDiscoveryTransport) {
    suspend fun options(courseId: Long, sessionId: Long): EnrollmentRegistrationWire =
        read("registration_options", courseId, sessionId, null) { parseEnrollmentOptions(it, courseId, sessionId) }

    suspend fun current(courseId: Long, sessionId: Long, enrollmentId: Long): EnrollmentCurrentWire =
        read("enrollment", courseId, sessionId, enrollmentId) { parseCurrentEnrollment(it, courseId, sessionId, enrollmentId) }

    private suspend fun <T> read(kind: String, courseId: Long, sessionId: Long, enrollmentId: Long?, decode: (JsonObject) -> T): T {
        require(courseId in 1..Int.MAX_VALUE.toLong() && sessionId in 1..Int.MAX_VALUE.toLong())
        require(enrollmentId == null || enrollmentId in 1..Int.MAX_VALUE.toLong())
        return access.withSession { session ->
            val request = buildJsonObject {
                put("kind", kind); put("course_id", courseId); put("session_id", sessionId)
                enrollmentId?.let { put("enrollment_id", it) }
            }.toString().encodeToByteArray()
            try {
                val response = transport.queryCourses(request, mapOf("Authorization" to "Bearer ${session.secret.value}"))
                try { decode(Json.parseToJsonElement(response.decodeToString()).jsonObject) } finally { response.fill(0) }
            } finally { request.fill(0) }
        }
    }
}

internal fun parseEnrollmentOptions(value: JsonObject, courseId: Long, sessionId: Long): EnrollmentRegistrationWire {
    value.exact("session", "course", "identity", "participants", "eligibility", "options_revision", "service_dates", "accommodation",
        "student_accommodation", "meals", "transports", "pickup_assistance", "rooming_groups", "fee_rules", "enrollment_mode")
    val mode = when (value.text("enrollment_mode", 16)) {
        "single" -> EnrollmentMode.SINGLE
        "parent_child" -> EnrollmentMode.PARENT_CHILD
        else -> error("Invalid enrollment mode")
    }
    val session = value.obj("session")
    require(session.identifier("session_id") == sessionId && session.identifier("course_id") == courseId)
    require(session.obj("course").identifier("course_id") == courseId && value.obj("course").identifier("course_id") == courseId)
    require(session.text("mini_program_status", 32) == "published")
    val title = session.text("title", 255)
    val people = value.array("participants", 11).map { participant(it.jsonObject) }
    require(people.isNotEmpty() && people.map { it.id }.distinct().size == people.size && people.count { it.relation == EnrollmentRelation.SELF } == 1)
    require(participant(value.obj("identity")) == people.single { it.relation == EnrollmentRelation.SELF })
    val dates = value.array("service_dates", 61).map { LocalDate.parse(it.stringValue(10)) }
    require(dates == dates.distinct().sorted())
    fun room(row: JsonObject): EnrollmentAccommodationWire {
        row.exact("accommodation_enabled", "check_in_date", "check_out_date", "single_room_price", "shared_room_price", "room_capacity")
        val first = row.dateOrNull("check_in_date"); val last = row.dateOrNull("check_out_date")
        require((first == null) == (last == null) && (first == null || last!! >= first))
        return EnrollmentAccommodationWire(row.flag("accommodation_enabled"), first, last, row.money("single_room_price"),
            row.money("shared_room_price"), row.integer("room_capacity").also { require(it in 1..1000) }.toInt())
    }
    room(value.obj("accommodation"))
    val accommodation = room(value.obj("student_accommodation"))
    val meals = value.array("meals", 120).map { element ->
        val row = element.jsonObject
        row.exact("service_date", "meal_type", "meal_name", "price")
        val date = LocalDate.parse(row.text("service_date", 10)).also { require(it in dates) }
        val kind = EnrollmentMealKind.entries.single { it.wireValue == row.text("meal_type", 20) }
        EnrollmentMeal("$date:${kind.wireValue}", date, row.text("meal_name", 255), row.money("price"), kind)
    }
    require(meals.map { it.id }.distinct().size == meals.size)
    require(value.array("transports", 20).isEmpty()) // Current shared source exposes no general transport choices.
    value.array("rooming_groups", 20).forEach { element ->
        val group = element.jsonObject
        group.exact("group_id", "preference", "checkin_date", "checkout_date", "target_size")
        group.identifier("group_id"); group.text("preference", 32)
        group.dateOrNull("checkin_date"); group.dateOrNull("checkout_date")
        require(group.integer("target_size") in 1..1000)
    }
    val rawPickup = value.obj("pickup_assistance")
    rawPickup.exact("enabled", "price", "config_id", "service_start_date", "service_end_date", "transport_types")
    val modes = rawPickup.array("transport_types", 3).map { type -> EnrollmentTravel.entries.single { it.wireValue == type.stringValue(16) } }
    require(modes.distinct().size == modes.size)
    val pickup = EnrollmentPickupWire(rawPickup.flag("enabled"), rawPickup.dateOrNull("service_start_date"),
        rawPickup.dateOrNull("service_end_date"), rawPickup.money("price"), rawPickup.optionalId("config_id"), modes.toSet())
    val eligibility = value.obj("eligibility")
    eligibility.exact("can_enroll", "can_waitlist", "reason")
    val reason = eligibility.text("reason", 32).also { require(it in setOf("OPEN", "NOT_OPEN", "FULL", "CLOSED", "UNPUBLISHED", "PROFILE_INCOMPLETE")) }
    val canEnroll = eligibility.flag("can_enroll"); val waitlist = eligibility.flag("can_waitlist")
    require(!canEnroll || reason == "OPEN")
    require(!waitlist || (reason == "FULL" && session.flag("allow_waitlist")))
    val revision = value.text("options_revision", 64).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    val fees = value.obj("fee_rules")
    fees.exact("base_fee", "payment_mode")
    require(fees.money("base_fee") == session.money("fee"))
    require(fees.text("payment_mode", 10) in setOf("free", "paid"))
    return EnrollmentRegistrationWire(courseId, sessionId, title, revision, people, dates, accommodation, meals, pickup,
        session.optionalText("venue", 255).orEmpty().ifBlank { session.text("venue_short_name", 100) }, canEnroll, waitlist, reason, mode,
        session.optionalText("start_date", 32)?.let(LocalDateTime::parse), session.optionalText("end_date", 32)?.let(LocalDateTime::parse))
}

internal fun parseCurrentEnrollment(value: JsonObject, courseId: Long, sessionId: Long, enrollmentId: Long): EnrollmentCurrentWire {
    value.exact("course_id", "session_id", "enrollments", "order", "available_actions", "course_title", "session_title", "payment_channel_status")
    require(value.identifier("course_id") == courseId && value.identifier("session_id") == sessionId)
    val rows = value.array("enrollments", 10).map { element ->
        val row = element.jsonObject
        row.exact("enrollment_id", "student_user_id", "status", "amount", "order_id", "student_name")
        EnrollmentItemWire(row.identifier("enrollment_id"), row.identifier("student_user_id"), row.text("status", 32).also { require(it.isNotBlank()) },
            row.money("amount"), row.optionalId("order_id"), row.text("student_name", 120))
    }
    require(rows.isNotEmpty() && rows.map { it.enrollmentId }.distinct().size == rows.size && rows.any { it.enrollmentId == enrollmentId })
    require(rows.map { it.personId }.distinct().size == rows.size)
    val order = value.getValue("order").takeUnless { it == JsonNull }?.jsonObject?.let { row ->
        row.exact("order_id", "status", "amount", "expire_at", "pay_mode")
        EnrollmentOrderWire(row.identifier("order_id"), row.text("status", 32).also { require(it.isNotBlank()) }, row.money("amount"),
            row.optionalText("expire_at", 32), row.optionalText("pay_mode", 32)).also { require(it.payMode != "mock") }
    }
    require(rows.all { it.orderId == order?.orderId })
    val actions = value.array("available_actions", 10).map { it.stringValue(64).also { action -> require(action.isNotBlank()) } }
    require(actions.distinct().size == actions.size)
    // Status strings remain server facts until their allowed transitions are frozen; no amount-based success inference.
    val channel = EnrollmentPaymentChannel.valueOf(value.text("payment_channel_status", 16))
    require((order == null) == (channel == EnrollmentPaymentChannel.NONE))
    require(order?.payMode != "unavailable" || channel == EnrollmentPaymentChannel.UNAVAILABLE)
    val courseTitle = value.text("course_title", 255)
    return EnrollmentCurrentWire(courseId, sessionId, rows, order, actions.toSet(),
        value.text("session_title", 255).ifBlank { courseTitle }, channel)
}

internal fun EnrollmentRegistrationWire.formOptions(): EnrollmentOptions {
    val stayDates = if (accommodation.enabled && accommodation.first != null && accommodation.last != null) {
        val count = java.time.temporal.ChronoUnit.DAYS.between(accommodation.first, accommodation.last)
        require(count in 0..365)
        (0..count).map { accommodation.first.plusDays(it) }
    } else emptyList()
    return EnrollmentOptions(courseId, sessionId, optionsRevision, title, mode, people, dates,
        if (stayDates.size >= 2) EnrollmentRoom.entries.toSet() else setOf(EnrollmentRoom.NONE), meals,
        if (pickup.enabled) pickup.modes else emptySet(), destination, canEnroll,
        start, end, stayDates, pickup.first, pickup.last, eligibilityReason)
}

private fun participant(row: JsonObject): EnrollmentPerson {
    row.exact("user_id", "name", "relation", "gender_completed", "profile_completed", "missing_fields")
    val missing = row.array("missing_fields", 10).map { it.stringValue(64) }
    require(missing.distinct().size == missing.size)
    val relation = when (row.text("relation", 10)) { "self" -> EnrollmentRelation.SELF; "child" -> EnrollmentRelation.CHILD; else -> error("Invalid relation") }
    return EnrollmentPerson(row.identifier("user_id"), row.text("name", 120), relation,
        profileComplete = row.flag("profile_completed") && missing.none { it in setOf("full_name", "profile") },
        genderComplete = row.flag("gender_completed") && "gender" !in missing)
}

internal fun JsonObject.exact(vararg keys: String) { require(this.keys == keys.toSet()) }
internal fun JsonObject.obj(key: String) = getValue(key).jsonObject
internal fun JsonObject.array(key: String, max: Int) = getValue(key).jsonArray.also { require(it.size <= max) }
internal fun JsonElement.stringValue(max: Int) = jsonPrimitive.also { require(it.isString && it.content.length <= max) }.content
internal fun JsonObject.text(key: String, max: Int) = getValue(key).stringValue(max)
internal fun JsonObject.optionalText(key: String, max: Int) = getValue(key).takeUnless { it == JsonNull }?.stringValue(max)
internal fun JsonObject.integer(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.long
internal fun JsonObject.identifier(key: String) = integer(key).also { require(it in 1..Int.MAX_VALUE.toLong()) }
internal fun JsonObject.money(key: String) = integer(key).also { require(it in 0..Int.MAX_VALUE.toLong()) }
internal fun JsonObject.optionalId(key: String) = getValue(key).takeUnless { it == JsonNull }?.jsonPrimitive?.let {
    require(!it.isString); it.long.also { id -> require(id in 1..Int.MAX_VALUE.toLong()) }
}
internal fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean
internal fun JsonObject.dateOrNull(key: String) = optionalText(key, 10)?.let(LocalDate::parse)
