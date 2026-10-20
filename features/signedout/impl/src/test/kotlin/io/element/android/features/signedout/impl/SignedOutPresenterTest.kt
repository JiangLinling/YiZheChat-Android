/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.signedout.impl

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.test.AN_APPLICATION_NAME
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.tests.testutils.WarmUpRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import io.mockk.mockk
import io.mockk.coEvery
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DeletionReport
import io.element.android.security.dataprotection.test.FakeDataDeletionCoordinator
import io.element.android.security.securestorage.api.VaultKeyService

class SignedOutPresenterTest {
    @Test
    fun `sign in again durably preserves exact old cleanup before removing row`() = runTest {
        val old = aSessionData()
        val store = InMemorySessionStore(initialList = listOf(old))
        val deletion = FakeDataDeletionCoordinator()
        val vault = mockk<VaultKeyService>()
        coEvery { vault.destroy(any(), any()) } coAnswers {
            assertThat(deletion.preparedRequests).hasSize(1)
            assertThat(store.getSession(old.userId)).isEqualTo(old)
            true
        }
        createSignedOutPresenter(sessionStore = store, deletion = deletion, vault = vault).prepareSignInAgain(old)
        assertThat(store.getAllSessions()).isEmpty()
        assertThat(deletion.preparedRequests.single().confirmedPreconditions).isEmpty()
        assertThat(deletion.requests).isEmpty() // No fabricated SDK-closed or file-deleted receipt.
    }

    @Test
    fun `failed cleanup preparation retains old row for retry`() = runTest {
        val old = aSessionData()
        val store = InMemorySessionStore(initialList = listOf(old))
        val deletion = mockk<DataDeletionCoordinator>()
        coEvery { deletion.prepare(any()) } answers {
            DeletionReport(firstArg<io.element.android.security.dataprotection.api.DeletionRequest>().operationId,
                emptySet(), true, "DELETION_JOURNAL_WRITE_FAILED")
        }
        val result = runCatching { createSignedOutPresenter(sessionStore = store, deletion = deletion).prepareSignInAgain(old) }
        assertThat(result.isFailure).isTrue()
        assertThat(store.getSession(old.userId)).isEqualTo(old)
    }

    @Test
    fun `late sign in action cannot remove replacement session`() = runTest {
        val old = aSessionData()
        val replacement = old.copy(deviceId = "NEW", sessionPath = "new-files", cachePath = "new-cache", isTokenValid = true)
        val store = InMemorySessionStore(initialList = listOf(replacement))
        createSignedOutPresenter(sessionStore = store).prepareSignInAgain(old)
        assertThat(store.getSession(old.userId)).isEqualTo(replacement)
    }
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        val aSessionData = aSessionData()
        val sessionStore = InMemorySessionStore(
            initialList = listOf(aSessionData)
        )
        val presenter = createSignedOutPresenter(sessionStore = sessionStore)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val initialState = awaitItem()
            assertThat(initialState.appName).isEqualTo(AN_APPLICATION_NAME)
            assertThat(initialState.signedOutSession).isEqualTo(aSessionData)
        }
    }

    @Test
    fun `present - sign in again`() = runTest {
        val aSessionData = aSessionData()
        val sessionStore = InMemorySessionStore(
            initialList = listOf(aSessionData)
        )
        val presenter = createSignedOutPresenter(sessionStore = sessionStore)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val initialState = awaitItem()
            assertThat(initialState.signedOutSession).isEqualTo(aSessionData)
            assertThat(sessionStore.getAllSessions()).isNotEmpty()
            assertThat(sessionStore.numberOfSessions()).isEqualTo(1)
            initialState.eventSink(SignedOutEvents.SignInAgain)
            var updated = awaitItem()
            while (updated.signedOutSession != null) updated = awaitItem()
            assertThat(sessionStore.getAllSessions()).isEmpty()
            assertThat(sessionStore.numberOfSessions()).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

internal fun createSignedOutPresenter(
    sessionId: SessionId = A_SESSION_ID,
    sessionStore: SessionStore = InMemorySessionStore(),
    deletion: DataDeletionCoordinator = FakeDataDeletionCoordinator(),
    vault: VaultKeyService = mockk { coEvery { destroy(any(), any()) } returns true },
): SignedOutPresenter {
    return SignedOutPresenter(
        sessionId = sessionId,
        sessionStore = sessionStore,
        buildMeta = aBuildMeta(applicationName = AN_APPLICATION_NAME),
        managementControlService = mockk(relaxed = true),
        dataDeletionCoordinator = deletion,
        vaultKeyService = vault,
    )
}
