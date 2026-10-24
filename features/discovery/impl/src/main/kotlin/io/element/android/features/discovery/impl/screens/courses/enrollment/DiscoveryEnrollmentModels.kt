/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

internal enum class EnrollmentMode { SINGLE, PARENT_CHILD }
internal enum class EnrollmentRelation { SELF, CHILD }
internal enum class EnrollmentRoom { NONE, SINGLE, SHARED }
internal enum class EnrollmentTravel(val wireValue: String) { TRAIN("rail"), FLIGHT("flight"), OTHER("other") }
internal enum class EnrollmentMealKind(val wireValue: String) { BREAKFAST("breakfast"), LUNCH("lunch"), DINNER("dinner") }

internal data class EnrollmentPerson(
    val id: Long,
    val name: String,
    val relation: EnrollmentRelation,
    val description: String = "",
    val profileComplete: Boolean,
    val genderComplete: Boolean,
)

internal data class EnrollmentMeal(val id: String, val date: LocalDate, val title: String, val priceFen: Long, val kind: EnrollmentMealKind)

/** A validated, account-scoped projection. These models deliberately do not define a new HTTP contract. */
internal data class EnrollmentOptions(
    val courseId: Long,
    val sessionId: Long,
    val version: String,
    val title: String,
    val mode: EnrollmentMode,
    val people: List<EnrollmentPerson>,
    val serviceDates: List<LocalDate>,
    val rooms: Set<EnrollmentRoom>,
    val meals: List<EnrollmentMeal>,
    val pickupModes: Set<EnrollmentTravel>,
    val pickupDestination: String,
    val canEnroll: Boolean,
    val sessionStart: LocalDateTime? = null,
    val sessionEnd: LocalDateTime? = null,
    val accommodationDates: List<LocalDate> = serviceDates,
    val pickupFirst: LocalDate? = serviceDates.firstOrNull(),
    val pickupLast: LocalDate? = serviceDates.lastOrNull(),
    val eligibilityReason: String? = null,
) {
    init {
        require(courseId > 0 && sessionId > 0 && version.isNotBlank())
        require(people.isNotEmpty() && people.size <= 100 && people.all { it.id in 1..Int.MAX_VALUE.toLong() })
        require(people.map { it.id }.distinct().size == people.size)
        require(people.count { it.relation == EnrollmentRelation.SELF } == 1)
        require(serviceDates.size in 1..366 && serviceDates == serviceDates.distinct().sorted())
        require(accommodationDates.size <= 366 && accommodationDates == accommodationDates.distinct().sorted())
        require(EnrollmentRoom.NONE in rooms)
        require(meals.size <= 1100 && meals.map { it.id }.distinct().size == meals.size)
        require(meals.map { it.date to it.kind }.distinct().size == meals.size)
        require(meals.all { it.id.isNotBlank() && it.priceFen >= 0 && it.date in serviceDates })
        require(pickupModes.isEmpty() || (pickupDestination.isNotBlank() && pickupDestination.length <= 200))
    }

    val self: EnrollmentPerson get() = people.single { it.relation == EnrollmentRelation.SELF }

    fun dates(room: EnrollmentRoom): List<LocalDate> = if (room == EnrollmentRoom.NONE) serviceDates else accommodationDates

    fun startAt(date: LocalDate, room: EnrollmentRoom): LocalDateTime =
        if (room == EnrollmentRoom.NONE && sessionStart?.toLocalDate() == date) requireNotNull(sessionStart) else date.atStartOfDay()

    fun endAt(date: LocalDate, room: EnrollmentRoom): LocalDateTime =
        if (room == EnrollmentRoom.NONE && sessionEnd?.toLocalDate() == date) requireNotNull(sessionEnd) else date.atStartOfDay()

    fun initialDraft() = EnrollmentDraft(
        personIds = if (mode == EnrollmentMode.SINGLE) setOf(self.id) else emptySet(),
        start = serviceDates.first(), end = serviceDates.last(),
    )
}

/** Only memory-owned. Never put this personal data in Parcelable, rememberSaveable, logs or analytics. */
internal data class EnrollmentDraft(
    val personIds: Set<Long>,
    val guardian: Boolean = false,
    val start: LocalDate,
    val end: LocalDate,
    val room: EnrollmentRoom = EnrollmentRoom.NONE,
    val mealIds: Set<String> = emptySet(),
    val pickup: EnrollmentPickup = EnrollmentPickup(),
    val dietNote: String = "",
    val specialNote: String = "",
) {
    fun participants(options: EnrollmentOptions): List<EnrollmentPerson> = options.people.filter {
        it.id in personIds || (guardian && options.mode == EnrollmentMode.PARENT_CHILD && it.id == options.self.id)
    }
}

internal data class EnrollmentPickup(
    val enabled: Boolean = false,
    val mode: EnrollmentTravel = EnrollmentTravel.TRAIN,
    val date: String = "",
    val time: String = "",
    val origin: String = "",
    val transportNumber: String = "",
    val peopleCount: String = "1",
    val luggage: String = "",
    val needs: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    fun plannedAt(): LocalDateTime? = runCatching {
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && time.matches(Regex("\\d{2}:\\d{2}")))
        LocalDateTime.parse("${date}T${time}:00")
    }.getOrNull()

    fun validCoordinates(): Boolean = when {
        latitude == null && longitude == null -> true
        latitude == null || longitude == null -> false
        else -> latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
}

internal enum class EnrollmentIssue { CLOSED, PERSON, PROFILE, GENDER, DATES, ROOM, MEALS, PICKUP_TIME, PICKUP_ORIGIN, PICKUP_PEOPLE, NOTES }

internal fun EnrollmentDraft.issues(options: EnrollmentOptions): Set<EnrollmentIssue> = buildSet {
    if (!options.canEnroll) add(EnrollmentIssue.CLOSED)
    val selected = options.people.filter { it.id in personIds }
    if (personIds.size !in 1..10 || selected.size != personIds.size ||
        (options.mode == EnrollmentMode.SINGLE && (selected.size != 1 || guardian)) ||
        (options.mode == EnrollmentMode.PARENT_CHILD && selected.any { it.relation != EnrollmentRelation.CHILD })) add(EnrollmentIssue.PERSON)
    val participants = participants(options)
    if (participants.size !in 1..10) add(EnrollmentIssue.PERSON)
    if (participants.any { !it.profileComplete }) add(EnrollmentIssue.PROFILE)
    if (room == EnrollmentRoom.SHARED && participants.any { !it.genderComplete }) add(EnrollmentIssue.GENDER)
    val dates = options.dates(room)
    val from = options.startAt(start, room); val until = options.endAt(end, room)
    if (start !in dates || end !in dates || !until.isAfter(from) ||
        java.time.Duration.between(from, until) > java.time.Duration.ofDays(60) ||
        dates.count { it >= start && it <= end }.toLong() != ChronoUnit.DAYS.between(start, end) + 1) add(EnrollmentIssue.DATES)
    if (room !in options.rooms) add(EnrollmentIssue.ROOM)
    if (mealIds.size > 30 || mealIds.any { id -> options.meals.none { it.id == id && !it.date.isBefore(start) && !it.date.isAfter(end) } }) add(EnrollmentIssue.MEALS)
    if (pickup.enabled) {
        val planned = pickup.plannedAt()
        if (pickup.mode !in options.pickupModes || planned == null ||
            (options.pickupFirst != null && planned.toLocalDate().isBefore(options.pickupFirst)) ||
            (options.pickupLast != null && planned.toLocalDate().isAfter(options.pickupLast))) add(EnrollmentIssue.PICKUP_TIME)
        if (pickup.origin.isBlank() || pickup.origin.length > 200 || !pickup.validCoordinates()) add(EnrollmentIssue.PICKUP_ORIGIN)
        val count = pickup.peopleCount.toIntOrNull()
        if (count == null || count !in 1..21) add(EnrollmentIssue.PICKUP_PEOPLE)
    }
    if (dietNote.length > 1000 || specialNote.length > 1000 || pickup.needs.length > 500 ||
        pickup.luggage.length > 500 || pickup.transportNumber.length > 64) add(EnrollmentIssue.NOTES)
}

internal data class EnrollmentRequest(
    val courseId: Long,
    val sessionId: Long,
    val optionsVersion: String,
    val draft: EnrollmentDraft,
)

internal fun EnrollmentDraft.request(options: EnrollmentOptions): EnrollmentRequest {
    require(issues(options).isEmpty())
    return EnrollmentRequest(options.courseId, options.sessionId, options.version, copy(
        personIds = personIds.toSet(), mealIds = mealIds.toSet(),
        // Disabled pickup data must not leak into a request or change its digest.
        pickup = if (pickup.enabled) pickup.copy(origin = pickup.origin.trim(), transportNumber = pickup.transportNumber.trim()) else EnrollmentPickup(),
        dietNote = dietNote.trim(), specialNote = specialNote.trim(),
    ))
}

internal data class EnrollmentFeeLine(val title: String, val amountFen: Long)

internal data class EnrollmentQuote(
    val id: String,
    val request: EnrollmentRequest,
    val lines: List<EnrollmentFeeLine>,
    val totalFen: Long,
    val expiresAtMillis: Long,
    val selectionSha256: String? = null,
) {
    init {
        require(id.isNotBlank() && lines.isNotEmpty() && lines.size <= 30 && totalFen >= 0)
        require(lines.all { it.title.isNotBlank() && it.amountFen != Long.MIN_VALUE })
        require(lines.fold(0L) { sum, line -> Math.addExact(sum, line.amountFen) } == totalFen)
        require(selectionSha256 == null || selectionSha256.matches(Regex("[a-f0-9]{64}")))
    }
}

internal enum class EnrollmentReceiptStatus { PENDING_PAYMENT, PROCESSING, ENROLLED, WAITLISTED, CLOSED, REFUNDING, REFUNDED }
internal enum class EnrollmentPaymentChannel { NONE, READY, UNAVAILABLE }

/** Construct only from an authorised server query or terminal operation receipt, never an app payment callback. */
internal data class EnrollmentReceipt(
    val courseId: Long,
    val sessionId: Long,
    val enrollmentId: String,
    val orderId: String?,
    val title: String,
    val status: EnrollmentReceiptStatus,
    val amountFen: Long,
    val participantNames: List<String>,
    val paymentChannel: EnrollmentPaymentChannel = EnrollmentPaymentChannel.READY,
) {
    init {
        require(courseId > 0 && sessionId > 0 && enrollmentId.isNotBlank() && amountFen >= 0)
        require(orderId == null || orderId.isNotBlank())
        require(status != EnrollmentReceiptStatus.PENDING_PAYMENT || orderId != null)
    }
}

internal fun formatEnrollmentAmount(fen: Long): String {
    require(fen != Long.MIN_VALUE)
    val value = kotlin.math.abs(fen)
    return "${if (fen < 0) "−" else ""}¥${value / 100}.${(value % 100).toString().padStart(2, '0')}"
}
