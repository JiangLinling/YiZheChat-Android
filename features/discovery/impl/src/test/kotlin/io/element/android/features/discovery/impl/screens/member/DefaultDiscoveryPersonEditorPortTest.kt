/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import io.element.android.features.discovery.impl.actions.DiscoveryBusinessActions
import io.element.android.features.discovery.impl.actions.DiscoveryPersonResultLocator
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DefaultDiscoveryPersonEditorPortTest {
    @Test fun `confirmed creation resumes without sending a new command and refresh binds guardian relation`() = runTest {
        val reader = mockk<DiscoveryPersonReader>()
        val actions = mockk<DiscoveryBusinessActions>()
        val target = PersonEditTarget(PersonEditKind.NEW_CHILD, null)
        val locator = DiscoveryPersonResultLocator("11111111-1111-4111-8111-111111111111", target, 2, 3)
        coEvery { actions.personResult() } returns locator
        coEvery { actions.pendingPerson() } returns null
        coEvery { reader.child(2) } returns parseMemberPerson(personWire(2, 3), true)
        val port = DefaultDiscoveryPersonEditorPort(reader, actions)
        assertEquals(PersonEditOutcome.SavedRefresh(locator), port.recover(target))
        assertEquals(2L, port.refresh(locator).person.id)
        coEvery { reader.child(2) } returns parseMemberPerson(personWire(2, 4), true)
        assertTrue(runCatching { port.refresh(locator) }.isFailure)
        coVerify(exactly = 0) { actions.execute<PersonEditOutcome>(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { actions.dismissPersonResult(any()) }
    }

    @Test fun `saved create cannot be recovered as a different edit target`() = runTest {
        val actions = mockk<DiscoveryBusinessActions>()
        val target = PersonEditTarget(PersonEditKind.NEW_CHILD, null)
        coEvery { actions.personResult() } returns DiscoveryPersonResultLocator("11111111-1111-4111-8111-111111111111", target, 2, 3)
        coEvery { actions.pendingPerson() } returns null
        val reader = mockk<DiscoveryPersonReader>()
        val port = DefaultDiscoveryPersonEditorPort(reader, actions)
        assertTrue(runCatching { port.recover(PersonEditTarget(PersonEditKind.CHILD, 2)) }.isFailure)
        coVerify(exactly = 0) { reader.child(any()) }
    }

    @Test fun `self edit checks current linked adult and sends no write for mismatched owner`() = runTest {
        val reader = mockk<DiscoveryPersonReader>()
        val actions = mockk<DiscoveryBusinessActions>()
        coEvery { reader.self() } returns MemberPersonRecord(PersonEditTarget(PersonEditKind.SELF, 9), null, MemberPersonDraft(), "", 0, emptyList())
        val port = DefaultDiscoveryPersonEditorPort(reader, actions)
        assertTrue(runCatching { port.save(PersonEditTarget(PersonEditKind.SELF, 1), MemberPersonDraft()) }.isFailure)
        coVerify(exactly = 0) { actions.execute<PersonEditOutcome>(any(), any(), any(), any(), any(), any(), any()) }
    }
}
