/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryPersonEditorTest {
    private val target = PersonEditTarget(PersonEditKind.NEW_CHILD, null)
    private val today = LocalDate.of(2026, 10, 24)

    @Test fun `leaving displayed result retains durable recovery until explicit acknowledgement`() = runTest {
        val port = FakePersonEditorPort(target).apply { savedRefresh = true }
        val first = DiscoveryPersonEditorController(target, port) { today }
        first.load(); first.edit { it.copy(fullName = "Child") }; first.save(true)
        first.close()
        assertEquals(0, port.acknowledgements)
        port.recovery = PersonEditOutcome.SavedRefresh(port.locator())
        val restored = DiscoveryPersonEditorController(target, port) { today }
        restored.load()
        assertEquals(PersonEditPhase.SAVED, restored.state.value.phase)
        assertFalse(restored.state.value.useAfterSave)
        assertEquals(1, port.saves)
        restored.finish(true) { result, use -> assertEquals(2L, result.person.id); assertTrue(use) }
        assertEquals(1, port.acknowledgements)
    }

    @Test fun `confirmed save with failed read retries reading without another create`() = runTest {
        val port = FakePersonEditorPort(target).apply { savedRefresh = true; failRefresh = true }
        val controller = DiscoveryPersonEditorController(target, port) { today }
        controller.load(); controller.edit { it.copy(fullName = "Child") }; controller.save(true)
        assertEquals(PersonEditPhase.SAVED_REFRESH, controller.state.value.phase)
        assertEquals(PersonEditNotice.SAVED_REFRESH_FAILED, controller.state.value.notice)
        assertNull(controller.state.value.draft)
        controller.save(true)
        port.failRefresh = false
        controller.recover()
        assertEquals(1, port.saves)
        assertEquals(2, port.refreshes)
        assertEquals(PersonEditPhase.SAVED, controller.state.value.phase)
        assertTrue(controller.state.value.useAfterSave)
    }

    @Test fun `recreated editor rereads confirmed child without guessing previous use intent`() = runTest {
        val port = FakePersonEditorPort(target)
        port.recovery = PersonEditOutcome.SavedRefresh(port.locator())
        val controller = DiscoveryPersonEditorController(target, port) { today }
        controller.load()
        assertEquals(PersonEditPhase.SAVED, controller.state.value.phase)
        assertEquals(0, port.saves)
        assertEquals(1, port.refreshes)
        assertFalse(controller.state.value.useAfterSave)
    }

    @Test fun `acknowledgement failure keeps saved result and cannot navigate`() = runTest {
        val port = FakePersonEditorPort(target).apply { failAcknowledge = true }
        val controller = DiscoveryPersonEditorController(target, port) { today }
        controller.load(); controller.edit { it.copy(fullName = "Child") }; controller.save(false)
        var delivered = false
        controller.finish { _, _ -> delivered = true }
        assertFalse(delivered)
        assertNotNull(controller.state.value.result)
        port.failAcknowledge = false
        controller.finish { _, _ -> delivered = true }
        assertTrue(delivered)
        assertEquals(1, port.saves)
    }

    @Test fun `child fields validate name calendar date and actual database lengths`() {
        assertTrue(PersonEditIssue.NAME in MemberPersonDraft().issues(target, today))
        val draft = MemberPersonDraft(fullName = "Child", birthdate = "2024-02-29")
        assertTrue(draft.issues(target, today).isEmpty())
        assertTrue(PersonEditIssue.BIRTHDATE in draft.copy(birthdate = "2025-02-29").issues(target, today))
        assertTrue(PersonEditIssue.BIRTHDATE in draft.copy(birthdate = "2027-01-01").issues(target, today))
        assertTrue(PersonEditIssue.LENGTH in draft.copy(grade = "x".repeat(81)).issues(target, today))
        assertFalse(draft.toString().contains("Child"))
    }

    @Test fun `two save buttons share one operation and cannot edit while saving`() = runTest {
        val port = FakePersonEditorPort(target)
        val wait = CompletableDeferred<Unit>()
        port.waitForSave = wait
        val controller = DiscoveryPersonEditorController(target, port) { today }
        controller.load(); controller.edit { it.copy(fullName = "Child") }
        val job = launch { controller.save(true) }
        testScheduler.runCurrent()
        controller.save(false); controller.edit { it.copy(fullName = "Other") }
        assertEquals(1, port.saves)
        assertEquals("Child", controller.state.value.draft?.fullName)
        wait.complete(Unit); job.join()
        assertEquals(PersonEditPhase.SAVED, controller.state.value.phase)
        assertTrue(controller.state.value.useAfterSave)
        assertNull(controller.state.value.draft)
    }

    @Test fun `unknown child creation keeps original intent and clear cannot permit another child`() = runTest {
        val port = FakePersonEditorPort(target).apply { failSave = true }
        val controller = DiscoveryPersonEditorController(target, port) { today }
        controller.load(); controller.edit { it.copy(fullName = "Child") }; controller.save(false)
        controller.load(); controller.save(false); controller.recover()
        assertEquals(1, port.saves)
        assertEquals(PersonEditPhase.UNCERTAIN, controller.state.value.phase)
        port.recovery = PersonEditOutcome.Saved(port.result())
        controller.recover()
        assertEquals(PersonEditPhase.SAVED, controller.state.value.phase)
        assertFalse(controller.state.value.useAfterSave)
    }

    @Test fun `late save after leaving does not restore details or switch identity`() = runTest {
        val port = FakePersonEditorPort(target)
        val wait = CompletableDeferred<Unit>()
        port.waitForSave = wait
        val controller = DiscoveryPersonEditorController(target, port) { today }
        controller.load(); controller.edit { it.copy(fullName = "Child") }
        val job = launch { controller.save(true) }
        testScheduler.runCurrent(); controller.close(); wait.complete(Unit); job.join()
        assertNull(controller.state.value.draft)
        assertNull(controller.state.value.result)
        assertFalse(controller.state.value.useAfterSave)
    }

    @Test fun `editing another child cannot accept wrong user result`() {
        val child = PersonEditTarget(PersonEditKind.CHILD, 2)
        assertThrows(IllegalArgumentException::class.java) {
            PersonEditResult(child, MemberIdentity(3, "Other", true, "", null))
        }
    }
}

private class FakePersonEditorPort(private val target: PersonEditTarget) : DiscoveryPersonEditorPort {
    var acknowledgements = 0
    var savedRefresh = false
    var failRefresh = false
    var refreshes = 0
    var failAcknowledge = false
    fun locator() = io.element.android.features.discovery.impl.actions.DiscoveryPersonResultLocator(
        "11111111-1111-4111-8111-111111111111", target, target.userId ?: 2, if (target.kind == PersonEditKind.SELF) null else 3)
    override suspend fun refresh(locator: io.element.android.features.discovery.impl.actions.DiscoveryPersonResultLocator): PersonEditResult {
        refreshes++
        check(!failRefresh)
        return result()
    }
    override suspend fun acknowledge(result: PersonEditResult) { check(!failAcknowledge); acknowledgements++ }
    var saves = 0
    var failSave = false
    var waitForSave: CompletableDeferred<Unit>? = null
    var recovery: PersonEditOutcome = PersonEditOutcome.Clear
    fun result() = PersonEditResult(target, MemberIdentity(target.userId ?: 2, "Child", target.kind != PersonEditKind.SELF, "", null))
    override suspend fun read(target: PersonEditTarget) = PersonEditorSnapshot(target, MemberPersonDraft(), "")
    override suspend fun save(target: PersonEditTarget, draft: MemberPersonDraft): PersonEditOutcome {
        saves++; waitForSave?.await()
        if (failSave) error("response lost")
        if (savedRefresh) return PersonEditOutcome.SavedRefresh(locator())
        return PersonEditOutcome.Saved(result())
    }
    override suspend fun recover(target: PersonEditTarget) = recovery
}
