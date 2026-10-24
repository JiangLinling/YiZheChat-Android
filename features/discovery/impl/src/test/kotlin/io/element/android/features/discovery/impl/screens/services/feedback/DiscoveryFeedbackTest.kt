/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services.feedback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryFeedbackTest {
    private val target = FeedbackTarget(1, 2, 3)
    private val complete = FeedbackDraft(1, 2, 3, 4, 0, " Learning gains ")
    private fun port() = FeedbackFake(FeedbackPage(target, "Course", "Person", FeedbackAvailability.OPEN, true, null, null, FeedbackDraft()))

    @Test fun `zero recommendation is valid and missing ratings or oversized text are not`() {
        assertTrue(complete.issues().isEmpty())
        assertEquals("Learning gains", complete.normalized().gain)
        assertTrue(FeedbackIssue.RECOMMENDATION in complete.copy(recommendScore = -1).issues())
        assertTrue(FeedbackIssue.SCORES in complete.copy(teachingScore = 0).issues())
        assertTrue(FeedbackIssue.GAIN in complete.copy(gain = " ").issues())
        assertTrue(FeedbackIssue.IMPROVEMENT in complete.copy(improvement = "x".repeat(301)).issues())
    }

    @Test fun `unknown submission cannot be unlocked by a missing operation`() = runTest {
        val port = port()
        val controller = DiscoveryFeedbackController(target, port)
        controller.load(); controller.edit { complete }; controller.submit()
        controller.recover(); controller.edit { it.copy(gain = "changed") }; controller.submit()
        assertEquals(FeedbackPhase.UNCERTAIN, controller.state.value.phase)
        assertEquals(1, port.submits)
        assertEquals("Learning gains", controller.state.value.draft?.gain)
    }

    @Test fun `saved feedback survives read failure and cannot be submitted a second time`() = runTest {
        val port = port().apply { outcome = FeedbackOutcome.Saved(FeedbackSaved(target, 5, "a".repeat(64))) }
        val controller = DiscoveryFeedbackController(target, port)
        controller.load(); controller.edit { complete }; controller.submit()
        port.failRead = true
        controller.load(); controller.submit()
        assertEquals(FeedbackPhase.FAILED, controller.state.value.phase)
        assertEquals(5L, controller.state.value.receipt?.feedbackId)
        assertEquals(1, port.submits)
        port.failRead = false
        port.page = port.page.copy(availability = FeedbackAvailability.SUBMITTED, feedbackId = 5, revision = "a".repeat(64), draft = complete)
        controller.load()
        assertEquals(FeedbackPhase.READ_ONLY, controller.state.value.phase)
        controller.continueEditing()
        assertEquals(FeedbackPhase.EDITING, controller.state.value.phase)
        assertEquals(1, port.submits)
    }

    @Test fun `closed page cannot submit and wrong enrollment reads fail closed`() = runTest {
        val port = port().apply { page = page.copy(availability = FeedbackAvailability.CLOSED, canSubmit = false) }
        val controller = DiscoveryFeedbackController(target, port)
        controller.load(); controller.edit { complete }; controller.submit()
        assertEquals(FeedbackPhase.READ_ONLY, controller.state.value.phase)
        assertEquals(0, port.submits)
        val wrong = port().apply { page = page.copy(target = target.copy(enrollmentId = 99)) }
        val another = DiscoveryFeedbackController(target, wrong)
        another.load()
        assertEquals(FeedbackPhase.FAILED, another.state.value.phase)
        assertNull(another.state.value.page)
    }

    @Test fun `closing during save discards late receipt and keeps duplicate clicks out`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = port().apply { submitGate = gate; outcome = FeedbackOutcome.Saved(FeedbackSaved(target, 5, "a".repeat(64))) }
        val controller = DiscoveryFeedbackController(target, port)
        controller.load(); controller.edit { complete }
        val job = launch { controller.submit() }
        testScheduler.runCurrent()
        controller.submit(); controller.close(); gate.complete(Unit); job.join()
        assertEquals(1, port.submits)
        assertNull(controller.state.value.receipt)
        assertNull(controller.state.value.draft)
    }
    @Test fun `conflict preserves draft until user selects current version explicitly`() = runTest {
        val port = port()
        val controller = DiscoveryFeedbackController(target, port)
        controller.load(); controller.edit { complete.normalized() }
        port.page = port.page.copy(availability = FeedbackAvailability.SUBMITTED, feedbackId = 5, revision = "b".repeat(64),
            draft = complete.normalized().copy(gain = "Remote content"))
        port.outcome = FeedbackOutcome.Rejected("FEEDBACK_CHANGED")
        controller.submit(); controller.submit()
        assertEquals(1, port.submits)
        assertEquals(FeedbackPhase.CONFLICT, controller.state.value.phase)
        assertEquals("Learning gains", controller.state.value.draft?.gain)
        assertEquals("Remote content", controller.state.value.conflict?.draft?.gain)
        controller.resolveConflict(true)
        assertEquals(FeedbackPhase.EDITING, controller.state.value.phase)
        assertEquals("Learning gains", controller.state.value.draft?.gain)
        assertEquals("b".repeat(64), controller.state.value.page?.revision)
        assertEquals(1, port.submits)
    }

    @Test fun `revoked enrolment clears private form and cannot submit again`() = runTest {
        val port = port().apply { outcome = FeedbackOutcome.Rejected("ENROLLMENT_UNAVAILABLE") }
        val controller = DiscoveryFeedbackController(target, port)
        controller.load(); controller.edit { complete }; controller.submit(); controller.submit()
        assertEquals(FeedbackPhase.UNAVAILABLE, controller.state.value.phase)
        assertNull(controller.state.value.page)
        assertNull(controller.state.value.draft)
        assertEquals(1, port.submits)
    }
}

private class FeedbackFake(var page: FeedbackPage) : DiscoveryFeedbackPort {
    override suspend fun acknowledge(receipt: FeedbackSaved) = Unit
    var outcome: FeedbackOutcome = FeedbackOutcome.Pending
    var failRead = false
    var submits = 0
    var submitGate: CompletableDeferred<Unit>? = null
    override suspend fun load(target: FeedbackTarget): FeedbackPage { if (failRead) error("read failed"); return page }
    override suspend fun submit(target: FeedbackTarget, feedbackId: Long?, revision: String?, draft: FeedbackDraft): FeedbackOutcome {
        submits++; submitGate?.await(); return outcome
    }
    override suspend fun recover(target: FeedbackTarget) = FeedbackOutcome.Clear
}
