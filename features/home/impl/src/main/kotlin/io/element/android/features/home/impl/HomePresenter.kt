/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.features.home.impl.roomlist.RoomListState
import io.element.android.features.home.impl.spaces.HomeSpacesState
import io.element.android.features.logout.api.direct.DirectLogoutState
import io.element.android.features.rageshake.api.RageshakeFeatureAvailability
import io.element.android.features.userprofile.api.FriendProfileRepository
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.collectSnackbarMessageAsState
import io.element.android.libraries.indicator.api.IndicatorService
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.sync.SyncService
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.DeletedIdentityProjection
import io.element.android.security.dataprotection.api.isDeletedMatrixUserId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.MessageDigest

@Inject
class HomePresenter(
    private val client: MatrixClient,
    private val syncService: SyncService,
    private val snackbarDispatcher: SnackbarDispatcher,
    private val indicatorService: IndicatorService,
    private val roomListPresenter: Presenter<RoomListState>,
    private val homeSpacesPresenter: Presenter<HomeSpacesState>,
    private val logoutPresenter: Presenter<DirectLogoutState>,
    private val rageshakeFeatureAvailability: RageshakeFeatureAvailability,
    private val sessionStore: SessionStore,
    private val friendProfileRepository: FriendProfileRepository,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val deletedIdentityProjection: DeletedIdentityProjection,
) : Presenter<HomeState> {
    private val currentUserWithNeighborsBuilder = CurrentUserWithNeighborsBuilder()

    @Composable
    override fun present(): HomeState {
        val coroutineState = rememberCoroutineScope()
        val matrixUser by client.userProfile.collectAsState()
        val currentUserAndNeighbors by remember {
            combine(
                client.userProfile,
                sessionStore.sessionsFlow(),
                currentUserWithNeighborsBuilder::build,
            )
        }.collectAsState(initial = persistentListOf(matrixUser))
        val isOnline by syncService.isOnline.collectAsState()
        val syncState by syncService.syncState.collectAsState()
        val canReportBug by remember { rageshakeFeatureAvailability.isAvailable() }.collectAsState(false)
        val roomListState = roomListPresenter.present()
        val homeSpacesState = homeSpacesPresenter.present()
        var currentHomeNavigationBarItemOrdinal by rememberSaveable { mutableIntStateOf(HomeNavigationBarItem.Chats.ordinal) }
        var pendingNetworkLostOperationId by rememberSaveable { mutableStateOf<String?>(null) }
        val currentHomeNavigationBarItem by remember {
            derivedStateOf {
                HomeNavigationBarItem.from(currentHomeNavigationBarItemOrdinal)
            }
        }
        LaunchedEffect(Unit) {
            // Force a refresh of the profile
            client.getUserProfile()
            friendProfileRepository.load()
        }
        LaunchedEffect(isOnline) {
            if (!isOnline) {
                val request = homeOperationRequest(FullAppLifecycleId.LC_18, "network_lost")
                pendingNetworkLostOperationId = request.operationId
                runCatching {
                    appOperationalStateCoordinator.perform(request) { Unit }
                }.onSuccess {
                    pendingNetworkLostOperationId = null
                }.onFailure {
                    Timber.w(it, "Unable to publish the network-loss lifecycle; retaining its operationId for reconnect.")
                }
                return@LaunchedEffect
            }

            pendingNetworkLostOperationId?.let { operationId ->
                runCatching {
                    appOperationalStateCoordinator.perform(
                        homeOperationRequest(FullAppLifecycleId.LC_18, "network_lost", operationId)
                    ) { Unit }
                }.onSuccess {
                    pendingNetworkLostOperationId = null
                }.onFailure {
                    Timber.w(it, "Unable to resume the network-loss lifecycle after reconnect.")
                }
            }

            runCatching {
                appOperationalStateCoordinator.perform(
                    homeOperationRequest(FullAppLifecycleId.LC_18, "network_available")
                ) { Unit }
            }.onFailure { Timber.w(it, "Unable to bind the network-available lifecycle") }

            // State refresh observes recovery after the Android network operation has its receipts.
            // A snapshot failure must not strand the already-authoritative connectivity transition.
            runCatching {
                appOperationalStateCoordinator.refresh(
                    client.sessionId.value.sha256Hex(),
                    client.deviceId.value.sha256Hex(),
                )
            }.onFailure { Timber.w(it, "Unable to refresh operational state after reconnect") }
        }
        val friendProfiles by friendProfileRepository.profiles.collectAsState()
        val deletedIdentityRevision by deletedIdentityProjection.revision.collectAsState()
        val isDeletedMatrixUserId = remember(deletedIdentityRevision) {
            { userId: String -> deletedIdentityProjection.isDeletedMatrixUserId(userId) }
        }
        // Avatar indicator
        val showAvatarIndicator by indicatorService.showRoomListTopBarIndicator()
        val directLogoutState = logoutPresenter.present()
        var isRefreshing by remember { mutableStateOf(false) }

        fun handleEvent(event: HomeEvent) {
            when (event) {
                is HomeEvent.SelectHomeNavigationBarItem -> {
                    currentHomeNavigationBarItemOrdinal = event.item.ordinal
                }
                is HomeEvent.SwitchToAccount -> coroutineState.launch {
                    appOperationalStateCoordinator.perform(
                        homeOperationRequest(FullAppLifecycleId.LC_14, "account_switch")
                    ) {
                        sessionStore.setLatestSession(event.sessionId.value)
                    }
                }
                HomeEvent.RetrySync -> {
                    if (!isRefreshing) {
                        coroutineState.launch {
                            isRefreshing = true
                            try {
                                appOperationalStateCoordinator.perform(
                                    homeOperationRequest(FullAppLifecycleId.LC_10, "matrix_sync")
                                ) {
                                    syncService.startSync()
                                    // Healthy Matrix sync is continuous; this is bounded pull-to-refresh feedback.
                                    delay(MIN_REFRESH_INDICATOR_DURATION_MS)
                                }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                // A manual retry is a recoverable user action. In particular, an offline
                                // lifecycle publication must not escape this UI coroutine and crash the app.
                                Timber.w(failure, "Unable to retry Matrix sync")
                            } finally {
                                isRefreshing = false
                            }
                        }
                    }
                }
            }
        }

        val snackbarMessage by snackbarDispatcher.collectSnackbarMessageAsState()
        return HomeState(
            currentUserAndNeighbors = currentUserAndNeighbors,
            showAvatarIndicator = showAvatarIndicator,
            hasNetworkConnection = isOnline,
            syncState = syncState,
            currentHomeNavigationBarItem = currentHomeNavigationBarItem,
            roomListState = roomListState,
            homeSpacesState = homeSpacesState,
            snackbarMessage = snackbarMessage,
            canReportBug = canReportBug,
            directLogoutState = directLogoutState,
            friendProfiles = friendProfiles,
            isRefreshing = isRefreshing,
            isDeletedMatrixUserId = isDeletedMatrixUserId,
            eventSink = ::handleEvent,
        )
    }

    suspend fun onHomeVisible() {
        runCatching {
            appOperationalStateCoordinator.perform(homeOperationRequest(FullAppLifecycleId.LC_10, "home_enter")) { Unit }
        }.onFailure { Timber.w(it, "Unable to bind the home entry lifecycle") }

        // The home projection is already complete at this point. A snapshot is observational and
        // must not prevent the two Android authority receipts from closing the LC-10 operation.
        runCatching {
            appOperationalStateCoordinator.refresh(
                client.sessionId.value.sha256Hex(),
                client.deviceId.value.sha256Hex(),
            )
        }.onFailure { Timber.w(it, "Unable to refresh operational state after entering home") }
    }

    private companion object {
        const val MIN_REFRESH_INDICATOR_DURATION_MS = 600L
    }

    private fun homeOperationRequest(
        lifecycleId: FullAppLifecycleId,
        trigger: String,
        operationId: String? = null,
    ) = if (operationId == null) {
        lifecycleOperationRequest(
            lifecycleId = lifecycleId,
            trigger = trigger,
            accountIdHash = client.sessionId.value.sha256Hex(),
            matrixDeviceIdHash = client.deviceId.value.sha256Hex(),
        )
    } else {
        lifecycleOperationRequest(
            lifecycleId = lifecycleId,
            trigger = trigger,
            operationId = operationId,
            accountIdHash = client.sessionId.value.sha256Hex(),
            matrixDeviceIdHash = client.deviceId.value.sha256Hex(),
        )
    }

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
