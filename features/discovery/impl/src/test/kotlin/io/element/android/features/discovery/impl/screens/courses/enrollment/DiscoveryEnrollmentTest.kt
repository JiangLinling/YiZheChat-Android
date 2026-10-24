/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal fun enrollmentOptions(mode: EnrollmentMode = EnrollmentMode.SINGLE) = EnrollmentOptions(
    courseId = 1, sessionId = 10, version = "fixture-v1", title = "Course", mode = mode,
    people = listOf(
        EnrollmentPerson(1, "Parent", EnrollmentRelation.SELF, profileComplete = true, genderComplete = true),
        EnrollmentPerson(2, "Child A", EnrollmentRelation.CHILD, profileComplete = true, genderComplete = true),
        EnrollmentPerson(3, "Child B", EnrollmentRelation.CHILD, profileComplete = true, genderComplete = true),
    ),
    serviceDates = (1..4).map { LocalDate.of(2026, 11, it) }, rooms = EnrollmentRoom.entries.toSet(),
    meals = listOf(EnrollmentMeal("first-lunch", LocalDate.of(2026, 11, 1), "Lunch", 1234, EnrollmentMealKind.LUNCH)),
    pickupModes = setOf(EnrollmentTravel.TRAIN), pickupDestination = "Venue", canEnroll = true,
)

internal class DiscoveryEnrollmentTest {
    @Test fun `request keeps actual selected dates instead of configured interval`() {
        val options = enrollmentOptions()
        val draft = options.initialDraft().copy(start = options.serviceDates[1], end = options.serviceDates[2], room = EnrollmentRoom.SINGLE)
        val request = draft.request(options)
        assertEquals(LocalDate.of(2026, 11, 2), request.draft.start)
        assertEquals(LocalDate.of(2026, 11, 3), request.draft.end)
    }

    @Test fun `single and family selection cannot forge an authorised identity`() {
        val single = enrollmentOptions()
        assertTrue(EnrollmentIssue.PERSON in single.initialDraft().copy(personIds = setOf(99)).issues(single))
        assertTrue(EnrollmentIssue.PERSON in single.initialDraft().copy(personIds = setOf(1, 2)).issues(single))
        val family = enrollmentOptions(EnrollmentMode.PARENT_CHILD)
        assertTrue(EnrollmentIssue.PERSON in family.initialDraft().copy(personIds = setOf(1)).issues(family))
        val valid = family.initialDraft().copy(personIds = setOf(2, 3), guardian = true)
        assertTrue(valid.issues(family).isEmpty())
        assertEquals(setOf(1L, 2L, 3L), valid.participants(family).map { it.id }.toSet())
    }

    @Test fun `shared room checks guardian details as well as children`() {
        val options = enrollmentOptions(EnrollmentMode.PARENT_CHILD).let {
            it.copy(people = it.people.map { person -> if (person.id == 1L) person.copy(genderComplete = false) else person })
        }
        val draft = options.initialDraft().copy(personIds = setOf(2), guardian = true, room = EnrollmentRoom.SHARED)
        assertTrue(EnrollmentIssue.GENDER in draft.issues(options))
    }

    @Test fun `meal selection is invalidated by date changes and unknown meal ids`() {
        val options = enrollmentOptions()
        assertTrue(EnrollmentIssue.MEALS in options.initialDraft().copy(mealIds = setOf("other")).issues(options))
        assertTrue(EnrollmentIssue.MEALS in options.initialDraft().copy(start = options.serviceDates[1], mealIds = setOf("first-lunch")).issues(options))
    }

    @Test fun `empty reversed or discontinuous service range cannot be quoted`() {
        val options = enrollmentOptions()
        assertTrue(EnrollmentIssue.DATES in options.initialDraft().copy(end = options.serviceDates.first()).issues(options))
        val gap = options.copy(serviceDates = listOf(options.serviceDates.first(), options.serviceDates.last()))
        assertTrue(EnrollmentIssue.DATES in gap.initialDraft().issues(gap))
    }

    @Test fun `pickup accepts zero coordinates rejects incomplete and infinite coordinates`() {
        val valid = EnrollmentPickup(latitude = 0.0, longitude = 0.0)
        assertTrue(valid.validCoordinates())
        assertFalse(valid.copy(longitude = null).validCoordinates())
        assertFalse(valid.copy(latitude = Double.NaN).validCoordinates())
        assertFalse(valid.copy(longitude = Double.POSITIVE_INFINITY).validCoordinates())
        assertFalse(valid.copy(latitude = 91.0).validCoordinates())
    }

    @Test fun `pickup validates dates times people and strips disabled fields`() {
        val options = enrollmentOptions()
        val draft = options.initialDraft().copy(pickup = EnrollmentPickup(enabled = true, date = "2026-11-01", time = "10:20", origin = "Station"))
        assertTrue(draft.issues(options).isEmpty())
        assertTrue(EnrollmentIssue.PICKUP_TIME in draft.copy(pickup = draft.pickup.copy(time = "25:00")).issues(options))
        assertTrue(EnrollmentIssue.PICKUP_PEOPLE in draft.copy(pickup = draft.pickup.copy(peopleCount = "0")).issues(options))
        assertEquals(EnrollmentPickup(), draft.copy(pickup = draft.pickup.copy(enabled = false)).request(options).draft.pickup)
    }

    @Test fun `server quote must reconcile integer fees and detect overflow`() {
        val request = enrollmentOptions().let { it.initialDraft().request(it) }
        assertThrows(IllegalArgumentException::class.java) { EnrollmentQuote("q", request, listOf(EnrollmentFeeLine("fee", 125)), 124, 100) }
        assertThrows(ArithmeticException::class.java) {
            EnrollmentQuote("q", request, listOf(EnrollmentFeeLine("fee", Long.MAX_VALUE), EnrollmentFeeLine("extra", 1)), 0, 100)
        }
        assertEquals("¥1.05", formatEnrollmentAmount(105))
        assertEquals("−¥0.05", formatEnrollmentAmount(-5))
    }

    @Test fun `changing any selection clears the quote and cannot submit previous amount`() = runTest {
        val port = FakeEnrollmentPort()
        val controller = controller(port)
        controller.load(); controller.requestQuote()
        assertEquals(EnrollmentPhase.QUOTED, controller.state.value.phase)
        controller.edit { it.copy(specialNote = "New request") }
        controller.submit()
        assertEquals(0, port.submissions)
        assertNull(controller.state.value.quote)
    }

    @Test fun `expired quote never reaches submission`() = runTest {
        val port = FakeEnrollmentPort()
        var now = 0L
        val controller = DiscoveryEnrollmentController(1, 10, port) { now }
        controller.load(); controller.requestQuote(); now = 100
        controller.submit()
        assertEquals(0, port.submissions)
        assertEquals(EnrollmentNotice.QUOTE_EXPIRED, controller.state.value.notice)
    }

    @Test fun `return from profile edit reloads identities and drops revoked child and quote`() = runTest {
        val port = FakeEnrollmentPort()
        val controller = controller(port)
        controller.load(); controller.edit { it.copy(personIds = setOf(2)) }; controller.requestQuote()
        port.available = port.available.copy(people = port.available.people.filter { it.id != 2L })
        controller.refreshOptions()
        assertTrue(controller.state.value.draft!!.personIds.isEmpty())
        assertNull(controller.state.value.quote)
        assertTrue(EnrollmentIssue.PERSON in controller.state.value.issues)
    }

    @Test fun `uncertain submit is not repeated even after empty recovery or reload`() = runTest {
        val port = FakeEnrollmentPort().apply { failSubmit = true }
        val controller = controller(port)
        controller.load(); controller.requestQuote(); controller.submit()
        controller.load(); controller.requestQuote(); controller.submit(); controller.checkResult()
        assertEquals(1, port.submissions)
        assertEquals(EnrollmentPhase.UNCERTAIN, controller.state.value.phase)
    }

    @Test fun `double taps and edits during submission cannot change exact request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakeEnrollmentPort().apply { submitGate = gate }
        val controller = controller(port)
        controller.load(); controller.requestQuote()
        val job = launch { controller.submit() }
        testScheduler.runCurrent()
        controller.edit { it.copy(personIds = setOf(2)) }; controller.submit()
        assertEquals(setOf(1L), controller.state.value.draft?.personIds)
        assertEquals(1, port.submissions)
        gate.complete(Unit); job.join()
    }

    @Test fun `only explicit durable rejection unlocks an uncertain form`() = runTest {
        val port = FakeEnrollmentPort().apply { failSubmit = true }
        val controller = controller(port)
        controller.load(); controller.requestQuote(); controller.submit()
        port.recovery = EnrollmentRecovery.Rejected()
        controller.checkResult()
        assertEquals(EnrollmentPhase.EDITING, controller.state.value.phase)
        assertEquals(EnrollmentNotice.REJECTED, controller.state.value.notice)
        assertNull(controller.state.value.quote)
    }

    @Test fun `late quote after leaving cannot restore private form data`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakeEnrollmentPort().apply { quoteGate = gate }
        val controller = controller(port)
        controller.load()
        val job = launch { controller.requestQuote() }
        testScheduler.runCurrent(); controller.close(); gate.complete(Unit); job.join()
        assertNull(controller.state.value.options)
        assertNull(controller.state.value.quote)
        assertNull(controller.state.value.draft)
    }

    @Test fun `wrong session quote or result cannot be accepted`() = runTest {
        val port = FakeEnrollmentPort().apply { wrongQuote = true }
        val controller = controller(port)
        controller.load(); controller.requestQuote()
        assertEquals(EnrollmentPhase.UNCERTAIN, controller.state.value.phase)
        assertTrue(controller.state.value.pendingQuote)
        port.wrongQuote = false; controller.checkResult(); controller.requestQuote()
        port.outcome = EnrollmentOutcome.Confirmed(receipt().copy(sessionId = 11))
        controller.submit()
        assertEquals(EnrollmentPhase.UNCERTAIN, controller.state.value.phase)
        assertNull(controller.state.value.receipt)
    }

    @Test fun `free or pending payment is never inferred as confirmed enrollment`() = runTest {
        val port = FakeEnrollmentPort().apply { outcome = EnrollmentOutcome.Confirmed(receipt()) }
        val controller = controller(port)
        controller.load(); controller.requestQuote(); controller.submit()
        assertEquals(EnrollmentReceiptStatus.PENDING_PAYMENT, controller.state.value.receipt?.status)
        assertNull(controller.state.value.draft)
        port.latest = receipt().copy(status = EnrollmentReceiptStatus.ENROLLED)
        controller.checkResult()
        assertEquals(EnrollmentReceiptStatus.ENROLLED, controller.state.value.receipt?.status)
    }

    @Test fun `uncertain quote cannot create another operation until its journal is resolved`() = runTest {
        val port = FakeEnrollmentPort().apply { failQuote = true }
        val controller = controller(port)
        controller.load(); controller.requestQuote()
        port.recovery = EnrollmentRecovery.Pending
        controller.requestQuote(); controller.edit { it.copy(specialNote = "changed") }; controller.checkResult()
        assertEquals(1, port.quotes)
        assertEquals("", controller.state.value.draft?.specialNote)
        assertEquals(EnrollmentPhase.UNCERTAIN, controller.state.value.phase)
        port.recovery = EnrollmentRecovery.Clear
        controller.checkResult()
        assertEquals(EnrollmentPhase.EDITING, controller.state.value.phase)
        port.failQuote = false; controller.requestQuote()
        assertEquals(2, port.quotes)
        assertEquals(EnrollmentPhase.QUOTED, controller.state.value.phase)
    }

    @Test fun `result refresh uses owned enrollment query after operation journal is clear`() = runTest {
        val port = FakeEnrollmentPort().apply { outcome = EnrollmentOutcome.Confirmed(receipt()); latest = receipt().copy(status = EnrollmentReceiptStatus.ENROLLED) }
        val controller = controller(port)
        controller.load(); controller.requestQuote(); controller.submit(); controller.checkResult()
        assertEquals(1, port.currentReads)
        assertEquals(EnrollmentPhase.RECEIPT, controller.state.value.phase)
        assertEquals(EnrollmentReceiptStatus.ENROLLED, controller.state.value.receipt?.status)
        port.latest = receipt().copy(enrollmentId = "someone-else")
        controller.checkResult()
        assertEquals("enrollment", controller.state.value.receipt?.enrollmentId)
        assertEquals(EnrollmentNotice.CHECK_RESULT, controller.state.value.notice)
    }

    @Test fun `terminal quote reason is shown without discarding choices and clears after editing`() = runTest {
        val port = FakeEnrollmentPort().apply { resolvedQuoteFailure = EnrollmentQuoteResolvedFailure(reason = "MULTIPERSON_SERVICE_UNPRICED") }
        val controller = controller(port)
        controller.load()
        controller.edit { it.copy(specialNote = "Keep this choice") }
        controller.requestQuote()
        assertEquals(EnrollmentPhase.EDITING, controller.state.value.phase)
        assertEquals("MULTIPERSON_SERVICE_UNPRICED", controller.state.value.businessReason)
        assertEquals("Keep this choice", controller.state.value.draft?.specialNote)
        controller.edit { it.copy(specialNote = "Updated choice") }
        assertNull(controller.state.value.businessReason)
    }

    @Test fun `recovered rejection keeps the definitive server reason`() = runTest {
        val port = FakeEnrollmentPort().apply { recovery = EnrollmentRecovery.Rejected("ENROLLMENT_FULL") }
        val controller = controller(port)
        controller.load()
        assertEquals(EnrollmentPhase.EDITING, controller.state.value.phase)
        assertEquals("ENROLLMENT_FULL", controller.state.value.businessReason)
        assertEquals(0, port.submissions)
    }

    @Test fun `background close does not acknowledge result and recreated controller resumes it`() = runTest {
        val port = FakeEnrollmentPort().apply { recovery = EnrollmentRecovery.Confirmed(receipt()) }
        val first = controller(port)
        first.load()
        first.close()
        assertEquals(0, port.dismissals)
        val restored = controller(port)
        restored.load()
        assertEquals(receipt(), restored.state.value.receipt)
        assertEquals(EnrollmentPhase.RECEIPT, restored.state.value.phase)
        assertEquals(0, port.quotes)
        assertEquals(0, port.submissions)
    }

    @Test fun `result navigation waits for durable acknowledgement and failure keeps retryable receipt`() = runTest {
        val port = FakeEnrollmentPort().apply { recovery = EnrollmentRecovery.Confirmed(receipt()); failDismiss = true }
        val controller = controller(port)
        controller.load()
        var navigated = false
        controller.dismissResult { navigated = true }
        assertFalse(navigated)
        assertEquals(receipt(), controller.state.value.receipt)
        assertEquals(EnrollmentNotice.CHECK_RESULT, controller.state.value.notice)
        port.failDismiss = false
        controller.dismissResult { navigated = true }
        assertTrue(navigated)
        assertEquals(2, port.dismissals)
    }

    @Test fun `return from editor selects only a currently authorized participant and invalidates quote`() = runTest {
        val port = FakeEnrollmentPort()
        val controller = controller(port)
        controller.load(); controller.requestQuote()
        assertEquals(EnrollmentPhase.QUOTED, controller.state.value.phase)
        val person = port.available.people.first { it.relation == EnrollmentRelation.CHILD }
        controller.refreshOptions()
        controller.useReturnedPerson(person.id)
        assertEquals(setOf(person.id), controller.state.value.draft?.personIds)
        assertNull(controller.state.value.quote)
        port.available = port.available.copy(people = listOf(port.available.self))
        controller.refreshOptions()
        controller.useReturnedPerson(person.id)
        assertFalse(person.id in controller.state.value.draft!!.personIds)
    }

    private fun controller(port: FakeEnrollmentPort) = DiscoveryEnrollmentController(1, 10, port) { 0L }
    private fun receipt() = EnrollmentReceipt(1, 10, "enrollment", "order", "Course", EnrollmentReceiptStatus.PENDING_PAYMENT, 0, listOf("Parent"))
}

private class FakeEnrollmentPort : DiscoveryEnrollmentPort {
    var dismissals = 0
    var failDismiss = false
    override suspend fun dismiss(receipt: EnrollmentReceipt) {
        dismissals++
        check(!failDismiss) { "Acknowledgement failed" }
    }
    var available = enrollmentOptions()
    var submissions = 0
    var failSubmit = false
    var wrongQuote = false
    var failQuote = false
    var resolvedQuoteFailure: EnrollmentQuoteResolvedFailure? = null
    var quotes = 0
    var currentReads = 0
    var latest: EnrollmentReceipt? = null
    var submitGate: CompletableDeferred<Unit>? = null
    var quoteGate: CompletableDeferred<Unit>? = null
    var recovery: EnrollmentRecovery = EnrollmentRecovery.Clear
    var outcome: EnrollmentOutcome = EnrollmentOutcome.Pending
    override suspend fun options(courseId: Long, sessionId: Long) = available
    override suspend fun quote(request: EnrollmentRequest): EnrollmentQuote {
        quotes++
        quoteGate?.await()
        resolvedQuoteFailure?.let { throw it }
        if (failQuote) error("response lost")
        return EnrollmentQuote("quote", if (wrongQuote) request.copy(sessionId = 11) else request, listOf(EnrollmentFeeLine("Fee", 100)), 100, 100)
    }
    override suspend fun submit(quote: EnrollmentQuote): EnrollmentOutcome {
        submissions++
        submitGate?.await()
        if (failSubmit) error("connection interrupted")
        return outcome
    }
    override suspend fun recover(courseId: Long, sessionId: Long) = recovery
    override suspend fun current(receipt: EnrollmentReceipt): EnrollmentReceipt {
        currentReads++
        return latest ?: receipt
    }
}
