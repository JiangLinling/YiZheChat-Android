/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.home.impl.roomlist.aRoomListState
import io.element.android.features.home.impl.spaces.HomeSpacesState
import io.element.android.features.home.impl.spaces.aHomeSpacesState
import io.element.android.features.logout.api.direct.aDirectLogoutState
import io.element.android.features.rageshake.api.RageshakeFeatureAvailability
import io.element.android.features.userprofile.api.FriendProfileRepository
import io.element.android.features.userprofile.test.FakeFriendProfileRepository
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.indicator.api.IndicatorService
import io.element.android.libraries.indicator.test.FakeIndicatorService
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.sync.SyncService
import io.element.android.libraries.matrix.api.sync.SyncState
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.test.AN_AVATAR_URL
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_NAME
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.sync.FakeSyncService
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.IOException
import org.junit.Rule
import org.junit.Test

class HomePresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - should start with no user and then load user with success`() = runTest {
        val matrixClient = FakeMatrixClient(
            userDisplayName = null,
            userAvatarUrl = null,
        )
        matrixClient.givenGetProfileResult(matrixClient.sessionId, Result.success(MatrixUser(matrixClient.sessionId, A_USER_NAME, AN_AVATAR_URL)))
        val presenter = createHomePresenter(
            client = matrixClient,
            rageshakeFeatureAvailability = { flowOf(false) },
            sessionStore = InMemorySessionStore(
                initialList = listOf(
                    aSessionData(
                        sessionId = matrixClient.sessionId.value,
                        userDisplayName = null,
                        userAvatarUrl = null,
                    )
                ),
            ),
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.currentUserAndNeighbors.first()).isEqualTo(
                MatrixUser(A_USER_ID, null, null)
            )
            assertThat(initialState.canReportBug).isFalse()
            skipItems(1)
            val withUserState = awaitItem()
            assertThat(withUserState.currentUserAndNeighbors.first()).isEqualTo(
                MatrixUser(A_USER_ID, A_USER_NAME, AN_AVATAR_URL)
            )
            assertThat(withUserState.showAvatarIndicator).isFalse()
        }
    }

    @Test
    fun `present - can report bug`() = runTest {
        val presenter = createHomePresenter(
            rageshakeFeatureAvailability = { flowOf(true) },
            sessionStore = InMemorySessionStore(
                updateUserProfileResult = { _, _, _ -> },
            ),
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.canReportBug).isFalse()
            val finalState = awaitItem()
            assertThat(finalState.canReportBug).isTrue()
        }
    }

    @Test
    fun `present - show avatar indicator`() = runTest {
        val indicatorService = FakeIndicatorService()
        val presenter = createHomePresenter(
            indicatorService = indicatorService,
            sessionStore = InMemorySessionStore(
                updateUserProfileResult = { _, _, _ -> },
            ),
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.showAvatarIndicator).isFalse()
            indicatorService.setShowRoomListTopBarIndicator(true)
            val finalState = awaitItem()
            assertThat(finalState.showAvatarIndicator).isTrue()
        }
    }

    @Test
    fun `present - should start with no user and then load user with error`() = runTest {
        val matrixClient = FakeMatrixClient(
            userDisplayName = null,
            userAvatarUrl = null,
        )
        matrixClient.givenGetProfileResult(matrixClient.sessionId, Result.failure(AN_EXCEPTION))
        val presenter = createHomePresenter(
            client = matrixClient,
            sessionStore = InMemorySessionStore(
                updateUserProfileResult = { _, _, _ -> },
            ),
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.currentUserAndNeighbors.first()).isEqualTo(MatrixUser(matrixClient.sessionId))
            // No new state is coming
        }
    }

    @Test
    fun `present - NavigationBar change`() = runTest {
        val presenter = createHomePresenter(
            sessionStore = InMemorySessionStore(
                updateUserProfileResult = { _, _, _ -> },
            ),
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.currentHomeNavigationBarItem).isEqualTo(HomeNavigationBarItem.Chats)
            initialState.eventSink(HomeEvent.SelectHomeNavigationBarItem(HomeNavigationBarItem.Contacts))
            val contactsState = awaitItem()
            assertThat(contactsState.currentHomeNavigationBarItem).isEqualTo(HomeNavigationBarItem.Contacts)
            contactsState.eventSink(HomeEvent.SelectHomeNavigationBarItem(HomeNavigationBarItem.Me))
            val settingsState = awaitItem()
            assertThat(settingsState.currentHomeNavigationBarItem).isEqualTo(HomeNavigationBarItem.Me)
            settingsState.eventSink(HomeEvent.SelectHomeNavigationBarItem(HomeNavigationBarItem.Chats))
            val chatsState = awaitItem()
            assertThat(chatsState.currentHomeNavigationBarItem).isEqualTo(HomeNavigationBarItem.Chats)
        }
    }

    @Test
    fun `present - retry sync exposes refreshing state and ignores duplicate requests`() = runTest {
        var startSyncCount = 0
        val syncService = FakeSyncService().apply {
            startSyncLambda = {
                startSyncCount += 1
                Result.success(Unit)
            }
        }
        val presenter = createHomePresenter(
            syncService = syncService,
            sessionStore = InMemorySessionStore(
                updateUserProfileResult = { _, _, _ -> },
            ),
        )

        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.isRefreshing).isFalse()

            initialState.eventSink(HomeEvent.RetrySync)
            val refreshingState = awaitItem()
            assertThat(refreshingState.isRefreshing).isTrue()

            refreshingState.eventSink(HomeEvent.RetrySync)
            val refreshedState = awaitItem()
            assertThat(refreshedState.isRefreshing).isFalse()
            assertThat(startSyncCount).isEqualTo(1)
        }
    }

    @Test
    fun `present - retry sync failure is recoverable and does not start Matrix sync`() = runTest {
        var startSyncCount = 0
        val syncService = FakeSyncService().apply {
            startSyncLambda = {
                startSyncCount += 1
                Result.success(Unit)
            }
        }
        val coordinator = FakeAppOperationalStateCoordinator(startFailure = { request ->
            if (request.trigger == "matrix_sync") IOException("control plane unavailable") else null
        })
        val presenter = createHomePresenter(
            syncService = syncService,
            appOperationalStateCoordinator = coordinator,
            sessionStore = InMemorySessionStore(
                updateUserProfileResult = { _, _, _ -> },
            ),
        )

        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.isRefreshing).isFalse()

            initialState.eventSink(HomeEvent.RetrySync)
            yield()
            assertThat(startSyncCount).isEqualTo(0)
            assertThat(coordinator.startedRequests.count { it.trigger == "matrix_sync" }).isEqualTo(1)

            // The failure must release the refresh guard so a later user retry is still accepted.
            initialState.eventSink(HomeEvent.RetrySync)
            yield()
            assertThat(coordinator.startedRequests.count { it.trigger == "matrix_sync" }).isEqualTo(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `home visibility binds the real home entry lifecycle`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val presenter = createHomePresenter(appOperationalStateCoordinator = coordinator)

        presenter.onHomeVisible()

        assertThat(coordinator.startedRequests).hasSize(1)
        assertThat(coordinator.startedRequests.single().lifecycleId).isEqualTo(FullAppLifecycleId.LC_10)
        assertThat(coordinator.startedRequests.single().trigger).isEqualTo("home_enter")
        assertThat(coordinator.clientReceipts.map { it.component.name })
            .containsExactly("ANDROID_SERVICE", "ANDROID_FRONTEND")
    }

    @Test
    fun `home snapshot failure cannot strand home entry receipts`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator(
            refreshFailure = { IOException("snapshot unavailable") },
        )
        val presenter = createHomePresenter(appOperationalStateCoordinator = coordinator)

        presenter.onHomeVisible()

        assertThat(coordinator.startedRequests.single().trigger).isEqualTo("home_enter")
        assertThat(coordinator.clientReceipts.map { it.component.name })
            .containsExactly("ANDROID_SERVICE", "ANDROID_FRONTEND")
        assertThat(coordinator.refreshRequests).hasSize(1)
    }

    @Test
    fun `network loss records client application without requiring an offline control refresh`() = runTest {
        val syncService = FakeSyncService(initialSyncState = SyncState.Offline)
        val coordinator = FakeAppOperationalStateCoordinator()
        val presenter = createHomePresenter(
            syncService = syncService,
            appOperationalStateCoordinator = coordinator,
        )

        presenter.test {
            awaitItem()
            assertThat(coordinator.startedRequests.last().lifecycleId).isEqualTo(FullAppLifecycleId.LC_18)
            assertThat(coordinator.startedRequests.last().trigger).isEqualTo("network_lost")
            assertThat(coordinator.refreshRequests).isEmpty()
            assertThat(coordinator.clientReceipts.map { it.component.name })
                .containsAtLeast("ANDROID_SERVICE", "ANDROID_FRONTEND")

            syncService.emitSyncState(SyncState.Running)
            awaitItem()
            assertThat(coordinator.startedRequests.last().trigger).isEqualTo("network_available")
            assertThat(coordinator.refreshRequests).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `network loss reuses its operation id after reconnect when offline publication failed`() = runTest {
        val syncService = FakeSyncService(initialSyncState = SyncState.Offline)
        var failFirstOfflineStart = true
        val coordinator = FakeAppOperationalStateCoordinator(startFailure = { request ->
            if (request.trigger == "network_lost" && failFirstOfflineStart) {
                failFirstOfflineStart = false
                IOException("offline")
            } else {
                null
            }
        })
        val presenter = createHomePresenter(
            syncService = syncService,
            appOperationalStateCoordinator = coordinator,
        )

        presenter.test {
            awaitItem()
            val failedOfflineRequest = coordinator.startedRequests.single()
            assertThat(failedOfflineRequest.trigger).isEqualTo("network_lost")
            assertThat(coordinator.clientReceipts).isEmpty()

            syncService.emitSyncState(SyncState.Running)
            awaitItem()

            val retriedOfflineRequest = coordinator.startedRequests
                .filter { it.trigger == "network_lost" }
                .last()
            assertThat(retriedOfflineRequest.operationId).isEqualTo(failedOfflineRequest.operationId)
            assertThat(coordinator.startedRequests.last().trigger).isEqualTo("network_available")
            assertThat(coordinator.refreshRequests).hasSize(1)
            assertThat(coordinator.clientReceipts.map { it.operationId })
                .contains(failedOfflineRequest.operationId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

internal fun createHomePresenter(
    client: MatrixClient = FakeMatrixClient(),
    syncService: SyncService = FakeSyncService(),
    snackbarDispatcher: SnackbarDispatcher = SnackbarDispatcher(),
    rageshakeFeatureAvailability: RageshakeFeatureAvailability = RageshakeFeatureAvailability { flowOf(false) },
    indicatorService: IndicatorService = FakeIndicatorService(),
    homeSpacesPresenter: Presenter<HomeSpacesState> = Presenter { aHomeSpacesState() },
    sessionStore: SessionStore = InMemorySessionStore(),
    friendProfileRepository: FriendProfileRepository = FakeFriendProfileRepository(),
    appOperationalStateCoordinator: AppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
    deletedIdentityProjection: io.element.android.security.dataprotection.api.DeletedIdentityProjection =
        io.element.android.security.dataprotection.test.FakeDeletedIdentityProjection(),
) = HomePresenter(
    client = client,
    syncService = syncService,
    snackbarDispatcher = snackbarDispatcher,
    indicatorService = indicatorService,
    roomListPresenter = { aRoomListState() },
    homeSpacesPresenter = homeSpacesPresenter,
    logoutPresenter = { aDirectLogoutState() },
    rageshakeFeatureAvailability = rageshakeFeatureAvailability,
    sessionStore = sessionStore,
    friendProfileRepository = friendProfileRepository,
    appOperationalStateCoordinator = appOperationalStateCoordinator,
    deletedIdentityProjection = deletedIdentityProjection,
)
