/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(com.google.accompanist.permissions.ExperimentalPermissionsApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.features.location.impl.common.permissions

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.common.truth.Truth.assertThat
import io.element.android.features.location.impl.common.actions.FakeLocationActions
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.tests.testutils.FakeLifecycleOwner
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.testWithLifecycleOwner
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class DefaultPermissionsPresenterTest {
    @get:Rule val warmUpRule = WarmUpRule()

    private class Provider : ComposableMultiplePermissionsStateProvider {
        var callback: (Map<String, Boolean>) -> Unit = {}
        var launches = 0
        var beforeLaunch: () -> Unit = {}
        private val state = mockk<MultiplePermissionsState> {
            every { allPermissionsGranted } returns false
            every { permissions } returns emptyList()
            every { shouldShowRationale } returns false
            every { launchMultiplePermissionRequest() } answers { beforeLaunch(); launches++ }
        }
        @Composable
        override fun provide(permissions: List<String>, onPermissionsResult: (Map<String, Boolean>) -> Unit): MultiplePermissionsState {
            callback = onPermissionsResult
            return state
        }
    }

    @Test
    fun `denied OS callback receipts the same operation without claiming a grant or repeating the request`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val provider = Provider()
        provider.beforeLaunch = { assertThat(coordinator.startedRequests).hasSize(1) }
        val presenter = DefaultPermissionsPresenter(listOf("fine", "coarse"), provider, FakeLocationActions(), coordinator, this)
        presenter.testWithLifecycleOwner {
            val state = awaitItem()
            assertThat(state.isAnyGranted).isFalse()
            state.eventSink(PermissionsEvents.RequestPermissions)
            state.eventSink(PermissionsEvents.RequestPermissions)
            advanceUntilIdle()
            assertThat(provider.launches).isEqualTo(1)
            val request = coordinator.startedRequests.single()
            assertThat(request.lifecycleId).isEqualTo(FullAppLifecycleId.LC_03)
            provider.callback(mapOf("fine" to false, "coarse" to false))
            advanceUntilIdle()
            assertThat(coordinator.clientReceipts).hasSize(2)
            assertThat(coordinator.clientReceipts.map { it.operationId }.toSet()).containsExactly(request.operationId)
            assertThat(state.isAnyGranted).isFalse()
            provider.callback(mapOf("fine" to false))
            advanceUntilIdle()
            assertThat(coordinator.clientReceipts).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `settings require an actual departure and return before the original operation is receipted`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val actions = FakeLocationActions()
        val owner = FakeLifecycleOwner(Lifecycle.State.RESUMED)
        val presenter = DefaultPermissionsPresenter(listOf("fine", "coarse"), Provider(), actions, coordinator, this)
        presenter.testWithLifecycleOwner(owner) {
            awaitItem().eventSink(PermissionsEvents.OpenAppSettings)
            advanceUntilIdle()
            assertThat(actions.openSettingsInvocationsCount).isEqualTo(1)
            assertThat(coordinator.clientReceipts).isEmpty()
            val request = coordinator.startedRequests.single()
            assertThat(request.trigger).isEqualTo("settings_return")
            owner.givenState(Lifecycle.State.STARTED)
            advanceUntilIdle()
            owner.givenState(Lifecycle.State.RESUMED)
            advanceUntilIdle()
            assertThat(coordinator.clientReceipts).hasSize(2)
            assertThat(coordinator.clientReceipts.map { it.operationId }.toSet()).containsExactly(request.operationId)
            owner.givenState(Lifecycle.State.STARTED)
            owner.givenState(Lifecycle.State.RESUMED)
            advanceUntilIdle()
            assertThat(coordinator.clientReceipts).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failed admission opens neither OS permission UI nor settings and issues no success receipt`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator(startFailure = { IllegalStateException("not admitted") })
        val provider = Provider()
        val actions = FakeLocationActions()
        val presenter = DefaultPermissionsPresenter(listOf("fine", "coarse"), provider, actions, coordinator, this)
        presenter.testWithLifecycleOwner {
            val state = awaitItem()
            state.eventSink(PermissionsEvents.RequestPermissions)
            advanceUntilIdle()
            assertThat(provider.launches).isEqualTo(0)
            state.eventSink(PermissionsEvents.OpenAppSettings)
            advanceUntilIdle()
            assertThat(actions.openSettingsInvocationsCount).isEqualTo(0)
            assertThat(coordinator.clientReceipts).isEmpty()
            assertThat(state.isAnyGranted).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
