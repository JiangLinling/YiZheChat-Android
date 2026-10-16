/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalPermissionsApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.libraries.permissions.impl

import android.Manifest
import androidx.lifecycle.Lifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.permissions.api.PermissionsEvent
import io.element.android.libraries.permissions.api.PermissionsStore
import io.element.android.libraries.permissions.impl.action.FakePermissionActions
import io.element.android.libraries.permissions.impl.action.PermissionActions
import io.element.android.libraries.permissions.test.InMemoryPermissionsStore
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.tests.testutils.FakeLifecycleOwner
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.testWithLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private const val A_PERMISSION = "A_PERMISSION"

class DefaultPermissionsPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createPresenter()
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            assertThat(initialState.permission).isEqualTo(A_PERMISSION)
            assertThat(initialState.permissionGranted).isTrue()
            assertThat(initialState.shouldShowRationale).isFalse()
            assertThat(initialState.permissionAlreadyAsked).isFalse()
            assertThat(initialState.permissionAlreadyDenied).isFalse()
            assertThat(initialState.showDialog).isFalse()
        }
    }

    @Test
    fun `present - user closes dialog`() = runTest {
        val permissionsStore = InMemoryPermissionsStore(
            permissionDenied = true,
            permissionAsked = true
        )
        val permissionStateProvider = FakeComposablePermissionStateProvider(
            permissionState = aFakePermissionState(
                initialStatus = PermissionStatus.Denied(shouldShowRationale = false)
            ),
        )
        val presenter = createPresenter(
            permissionsStore = permissionsStore,
            permissionStateProvider = permissionStateProvider,
        )
        presenter.testWithLifecycleOwner {
            skipItems(1)
            val initialState = awaitItem()
            initialState.eventSink.invoke(PermissionsEvent.RequestPermissions)
            val withDialogState = awaitItem()
            assertThat(withDialogState.showDialog).isTrue()
            withDialogState.eventSink.invoke(PermissionsEvent.CloseDialog)
            assertThat(awaitItem().showDialog).isFalse()
        }
    }

    @Test
    fun `present - user open settings`() = runTest {
        val permissionsStore = InMemoryPermissionsStore(
            permissionDenied = true,
            permissionAsked = true
        )
        val permissionStateProvider = FakeComposablePermissionStateProvider(
            permissionState = aFakePermissionState(
                initialStatus = PermissionStatus.Denied(shouldShowRationale = false),
            ),
        )
        val openSettingsAction = lambdaRecorder<String, Unit> { }
        val permissionActions = FakePermissionActions(
            openSettingsAction = openSettingsAction,
        )
        val presenter = createPresenter(
            permissionsStore = permissionsStore,
            permissionStateProvider = permissionStateProvider,
            permissionActions = permissionActions,
        )
        presenter.testWithLifecycleOwner {
            skipItems(1)
            val initialState = awaitItem()
            initialState.eventSink.invoke(PermissionsEvent.RequestPermissions)
            val withDialogState = awaitItem()
            assertThat(withDialogState.showDialog).isTrue()
            openSettingsAction.assertions().isNeverCalled()
            withDialogState.eventSink.invoke(PermissionsEvent.OpenSystemSettingAndCloseDialog)
            assertThat(awaitItem().showDialog).isFalse()
            openSettingsAction.assertions().isCalledOnce().with(value(A_PERMISSION))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `camera first denial with rationale explains recovery`() = verifyDeniedCallback(Manifest.permission.CAMERA, rationale = true)

    @Test
    fun `camera silent system denial without rationale explains recovery`() = verifyDeniedCallback(Manifest.permission.CAMERA, rationale = false)

    @Test
    fun `microphone first denial with rationale explains recovery`() = verifyDeniedCallback(Manifest.permission.RECORD_AUDIO, rationale = true)

    @Test
    fun `microphone silent system denial without rationale explains recovery`() = verifyDeniedCallback(Manifest.permission.RECORD_AUDIO, rationale = false)

    private fun verifyDeniedCallback(permission: String, rationale: Boolean) = runTest {
        val osState = aFakePermissionState(permission, PermissionStatus.Denied(shouldShowRationale = false))
        val provider = FakeComposablePermissionStateProvider(osState)
        val presenter = createPresenter(permission = permission, permissionStateProvider = provider)
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            assertThat(initial.showDialog).isFalse()
            initial.eventSink(PermissionsEvent.RequestPermissions)
            // No history plus rationale=false is still a first request, never permanent denial.
            assertThat(osState.launchPermissionRequestCalled).isTrue()
            provider.userGiveAnswer(answer = false, firstTime = rationale)
            advanceUntilIdle()
            val denied = expectMostRecentItem()
            assertThat(denied.permissionGranted).isFalse()
            assertThat(denied.showDialog).isTrue()
            assertThat(denied.permissionAlreadyAsked).isTrue()
            assertThat(denied.permissionAlreadyDenied).isTrue()
            denied.eventSink(PermissionsEvent.CloseDialog)
            assertThat(awaitItem().showDialog).isFalse()
            denied.eventSink(PermissionsEvent.RequestPermissions)
            assertThat(awaitItem().showDialog).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `legacy asked history with false rationale shows recovery without another OS request`() = runTest {
        val osState = aFakePermissionState(initialStatus = PermissionStatus.Denied(shouldShowRationale = false))
        val presenter = createPresenter(
            permissionsStore = InMemoryPermissionsStore(permissionAsked = true),
            permissionStateProvider = FakeComposablePermissionStateProvider(osState),
        )
        presenter.testWithLifecycleOwner {
            awaitItem()
            advanceUntilIdle()
            expectMostRecentItem().eventSink(PermissionsEvent.RequestPermissions)
            assertThat(awaitItem().showDialog).isTrue()
            assertThat(osState.launchPermissionRequestCalled).isFalse()
        }
    }

    @Test
    fun `asked permission with rationale requests again and second silent denial remains explained`() = runTest {
        val osState = aFakePermissionState(initialStatus = PermissionStatus.Denied(shouldShowRationale = true))
        val provider = FakeComposablePermissionStateProvider(osState)
        val presenter = createPresenter(
            permissionsStore = InMemoryPermissionsStore(permissionAsked = true),
            permissionStateProvider = provider,
        )
        presenter.testWithLifecycleOwner {
            awaitItem()
            advanceUntilIdle()
            expectMostRecentItem().eventSink(PermissionsEvent.RequestPermissions)
            assertThat(osState.launchPermissionRequestCalled).isTrue()
            provider.userGiveAnswer(answer = false, firstTime = false)
            advanceUntilIdle()
            val denied = expectMostRecentItem()
            assertThat(denied.permissionGranted).isFalse()
            assertThat(denied.showDialog).isTrue()
            assertThat(denied.permissionAlreadyDenied).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `granted permission revoked in settings cannot silently deny after history reset`() = runTest {
        val store = InMemoryPermissionsStore(permissionAsked = true, permissionDenied = true)
        val osState = aFakePermissionState()
        val provider = FakeComposablePermissionStateProvider(osState)
        val presenter = createPresenter(permissionsStore = store, permissionStateProvider = provider)
        presenter.testWithLifecycleOwner {
            awaitItem()
            advanceUntilIdle()
            val granted = expectMostRecentItem()
            assertThat(granted.permissionGranted).isTrue()
            assertThat(granted.permissionAlreadyAsked).isFalse()
            assertThat(granted.permissionAlreadyDenied).isFalse()
            osState.givenPermissionStatus(hasPermission = false, shouldShowRationale = false)
            awaitItem().eventSink(PermissionsEvent.RequestPermissions)
            provider.userGiveAnswer(answer = false, firstTime = false)
            advanceUntilIdle()
            val denied = expectMostRecentItem()
            assertThat(denied.permissionGranted).isFalse()
            assertThat(denied.showDialog).isTrue()
            assertThat(denied.permissionAlreadyDenied).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `settings return still denied restores explanation and later grant clears it`() = runTest {
        val owner = FakeLifecycleOwner(Lifecycle.State.RESUMED)
        val osState = aFakePermissionState(initialStatus = PermissionStatus.Denied(shouldShowRationale = false))
        var settingsOpened = 0
        val presenter = createPresenter(
            permissionsStore = InMemoryPermissionsStore(permissionAsked = true, permissionDenied = true),
            permissionStateProvider = FakeComposablePermissionStateProvider(osState),
            permissionActions = FakePermissionActions(openSettingsAction = { settingsOpened++ }),
            appCoroutineScope = this,
        )
        presenter.testWithLifecycleOwner(owner) {
            awaitItem()
            advanceUntilIdle()
            expectMostRecentItem().eventSink(PermissionsEvent.RequestPermissions)
            awaitItem().eventSink(PermissionsEvent.OpenSystemSettingAndCloseDialog)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().showDialog).isFalse()
            owner.givenState(Lifecycle.State.STARTED)
            owner.givenState(Lifecycle.State.RESUMED)
            advanceUntilIdle()
            val denied = expectMostRecentItem()
            assertThat(denied.permissionGranted).isFalse()
            assertThat(denied.showDialog).isTrue()
            denied.eventSink(PermissionsEvent.OpenSystemSettingAndCloseDialog)
            advanceUntilIdle()
            owner.givenState(Lifecycle.State.STARTED)
            osState.givenPermissionStatus(hasPermission = true, shouldShowRationale = false)
            owner.givenState(Lifecycle.State.RESUMED)
            advanceUntilIdle()
            val restored = expectMostRecentItem()
            assertThat(restored.permissionGranted).isTrue()
            assertThat(restored.showDialog).isFalse()
            assertThat(settingsOpened).isEqualTo(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - user does not grant permission third time`() = runTest {
        val permissionsStore = InMemoryPermissionsStore(
            permissionDenied = true,
            permissionAsked = true,
        )
        val permissionState = aFakePermissionState(
            initialStatus = PermissionStatus.Denied(shouldShowRationale = false),
        )
        val permissionStateProvider = FakeComposablePermissionStateProvider(
            permissionState = permissionState,
        )
        val presenter = createPresenter(
            permissionsStore = permissionsStore,
            permissionStateProvider = permissionStateProvider,
        )
        presenter.testWithLifecycleOwner {
            skipItems(1)
            val initialState = awaitItem()
            initialState.eventSink.invoke(PermissionsEvent.RequestPermissions)
            val withDialogState = awaitItem()
            assertThat(withDialogState.showDialog).isTrue()
            assertThat(withDialogState.permissionGranted).isFalse()
            assertThat(withDialogState.permissionAlreadyDenied).isTrue()
            assertThat(withDialogState.permissionAlreadyAsked).isTrue()
        }
    }

    @Test
    fun `present - user grants permission`() = runTest {
        val permissionState = aFakePermissionState(
            initialStatus = PermissionStatus.Denied(shouldShowRationale = false)
        )
        val permissionStateProvider = FakeComposablePermissionStateProvider(
            permissionState = permissionState,
        )
        val presenter = createPresenter(
            permissionStateProvider = permissionStateProvider,
        )
        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            assertThat(initialState.showDialog).isFalse()
            initialState.eventSink.invoke(PermissionsEvent.RequestPermissions)
            assertThat(permissionState.launchPermissionRequestCalled).isTrue()
            // User grants permission
            permissionStateProvider.userGiveAnswer(answer = true, firstTime = true)
            skipItems(1)
            val state = awaitItem()
            assertThat(state.permissionGranted).isTrue()
            assertThat(state.showDialog).isFalse()
            assertThat(state.permissionAlreadyDenied).isFalse()
            assertThat(state.permissionAlreadyAsked).isTrue()
        }
    }

    @Test
    fun `settings receipt waits for actual background and return`() = verifySettingsReturn(backgroundBeforeObserver = false)

    @Test
    fun `settings cannot open before lifecycle identity is persisted and duplicate clicks are ignored`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        var opened = 0
        val presenter = createPresenter(
            permissionActions = FakePermissionActions(openSettingsAction = {
                assertThat(coordinator.startedRequests).hasSize(1)
                opened++
            }),
            appOperationalStateCoordinator = coordinator,
            appCoroutineScope = this,
        )
        presenter.testWithLifecycleOwner {
            val state = awaitItem()
            state.eventSink(PermissionsEvent.OpenSystemSettingAndCloseDialog)
            assertThat(opened).isEqualTo(0)
            state.eventSink(PermissionsEvent.OpenSystemSettingAndCloseDialog)
            advanceUntilIdle()
            assertThat(opened).isEqualTo(1)
            assertThat(coordinator.startedRequests).hasSize(1)
            assertThat(coordinator.clientReceipts).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failed lifecycle persistence does not open OS settings or fabricate return receipt`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator(startFailure = { IllegalStateException("intent persistence failed") })
        var opened = 0
        val presenter = createPresenter(
            permissionActions = FakePermissionActions(openSettingsAction = { opened++ }),
            appOperationalStateCoordinator = coordinator,
            appCoroutineScope = this,
        )
        presenter.testWithLifecycleOwner {
            awaitItem().eventSink(PermissionsEvent.OpenSystemSettingAndCloseDialog)
            advanceUntilIdle()
            assertThat(opened).isEqualTo(0)
            assertThat(coordinator.startedRequests).hasSize(1)
            assertThat(coordinator.clientReceipts).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `settings receipt handles departure before observer registration`() = verifySettingsReturn(backgroundBeforeObserver = true)

    @Test
    fun `activity settings launcher uses original lifecycle and waits for real return`() =
        verifySettingsReturn(backgroundBeforeObserver = true, usingActivityLauncher = true)

    private fun verifySettingsReturn(backgroundBeforeObserver: Boolean, usingActivityLauncher: Boolean = false) = runTest {
        val owner = FakeLifecycleOwner(Lifecycle.State.RESUMED)
        val coordinator = FakeAppOperationalStateCoordinator()
        var activityLaunches = 0
        val presenter = createPresenter(
            permissionStateProvider = FakeComposablePermissionStateProvider(
                permissionState = aFakePermissionState(initialStatus = PermissionStatus.Denied(shouldShowRationale = true)),
            ),
            permissionActions = FakePermissionActions(openSettingsAction = {
                if (backgroundBeforeObserver) owner.givenState(Lifecycle.State.STARTED)
            }),
            appOperationalStateCoordinator = coordinator,
            appCoroutineScope = this,
        )
        presenter.testWithLifecycleOwner(owner) {
            val event = if (usingActivityLauncher) {
                PermissionsEvent.OpenSystemSettingsUsingActivity {
                    assertThat(coordinator.startedRequests).hasSize(1)
                    activityLaunches++
                    owner.givenState(Lifecycle.State.STARTED)
                }
            } else {
                PermissionsEvent.OpenSystemSettingAndCloseDialog
            }
            awaitItem().eventSink(event)
            advanceUntilIdle()
            assertThat(activityLaunches).isEqualTo(if (usingActivityLauncher) 1 else 0)
            assertThat(coordinator.startedRequests).hasSize(1)
            val original = coordinator.startedRequests.single()
            assertThat(original.trigger).isEqualTo("settings_return")
            assertThat(coordinator.clientReceipts).isEmpty()

            if (!backgroundBeforeObserver) owner.givenState(Lifecycle.State.STARTED)
            advanceUntilIdle()
            assertThat(coordinator.clientReceipts).isEmpty()
            owner.givenState(Lifecycle.State.RESUMED)
            advanceUntilIdle()
            assertThat(coordinator.startedRequests).hasSize(2)
            assertThat(coordinator.startedRequests.map { it.operationId }.distinct()).containsExactly(original.operationId)
            assertThat(coordinator.clientReceipts).hasSize(2)

            owner.givenState(Lifecycle.State.STARTED)
            owner.givenState(Lifecycle.State.RESUMED)
            advanceUntilIdle()
            assertThat(coordinator.clientReceipts).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permission receipt survives presenter composition disposal`() = runTest {
        val permissionStateProvider = FakeComposablePermissionStateProvider(
            permissionState = aFakePermissionState(
                initialStatus = PermissionStatus.Denied(shouldShowRationale = false)
            ),
        )
        val coordinator = FakeAppOperationalStateCoordinator()
        val presenter = createPresenter(
            permissionStateProvider = permissionStateProvider,
            appOperationalStateCoordinator = coordinator,
            appCoroutineScope = this,
        )

        presenter.testWithLifecycleOwner {
            val initialState = awaitItem()
            initialState.eventSink.invoke(PermissionsEvent.RequestPermissions)
            permissionStateProvider.userGiveAnswer(answer = true, firstTime = true)
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()

        assertThat(coordinator.startedRequests.map { it.lifecycleId })
            .containsExactly(FullAppLifecycleId.LC_03, FullAppLifecycleId.LC_03)
        assertThat(coordinator.startedRequests.map { it.operationId }.distinct()).hasSize(1)
        assertThat(coordinator.clientReceipts.map { it.operationId }.distinct()).hasSize(1)
        assertThat(coordinator.clientReceipts).hasSize(2)
    }
}

private fun createPresenter(
    permission: String = A_PERMISSION,
    permissionsStore: PermissionsStore = InMemoryPermissionsStore(),
    permissionStateProvider: ComposablePermissionStateProvider = FakeComposablePermissionStateProvider(
        permissionState = aFakePermissionState(),
    ),
    permissionActions: PermissionActions = FakePermissionActions(),
    appOperationalStateCoordinator: FakeAppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
    appCoroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
) = DefaultPermissionsPresenter(
    permission = permission,
    permissionsStore = permissionsStore,
    composablePermissionStateProvider = permissionStateProvider,
    permissionActions = permissionActions,
    appOperationalStateCoordinator = appOperationalStateCoordinator,
    appCoroutineScope = appCoroutineScope,
)

private fun aFakePermissionState(
    permission: String = A_PERMISSION,
    initialStatus: PermissionStatus = PermissionStatus.Granted,
) = FakePermissionState(
    permission = permission,
    initialStatus = initialStatus,
)
