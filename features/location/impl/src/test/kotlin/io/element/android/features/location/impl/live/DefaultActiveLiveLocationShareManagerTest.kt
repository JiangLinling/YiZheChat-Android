/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl.live

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.location.api.Location
import io.element.android.features.location.api.live.PausedLiveLocationShare
import io.element.android.features.location.impl.live.service.LiveLocationSharingCoordinator
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.location.BeaconInfoUpdate
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID_2
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import io.element.android.libraries.sessionstorage.test.observer.FakeSessionObserver
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.assert
import io.element.android.tests.testutils.lambda.lambdaRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultActiveLiveLocationShareManagerTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `starting the first share starts the coordinator service after the beacon echo and adds an active share`() = runTest {
        val startServiceRecorder = lambdaRecorder<Unit> { }
        val stopServiceRecorder = lambdaRecorder<Unit> { }
        val coordinator = createCoordinator(
            startService = startServiceRecorder,
            stopService = stopServiceRecorder
        )
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val room = FakeJoinedRoom(
            startLiveLocationShareResult = { Result.success(AN_EVENT_ID) },
            stopLiveLocationShareResult = { Result.success(Unit) },
        )
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply { givenGetRoomResult(A_ROOM_ID, room) },
            coordinator = coordinator,
            clock = FakeSystemClock(epochMillisResult = 123L),
        )
        advanceUntilIdle()

        val result = async { manager.startShare(A_ROOM_ID, 60.minutes) }
        beaconInfoUpdates.emit(BeaconInfoUpdate(roomId = A_ROOM_ID, beaconId = AN_EVENT_ID, isLive = true))

        assertThat(result.await().isSuccess).isTrue()
        assertThat(manager.sharingRoomIds.value).containsExactly(A_ROOM_ID)
        assert(startServiceRecorder).isCalledOnce()
        assert(stopServiceRecorder).isNeverCalled()
    }

    @Test
    fun `stopping the last share stops the coordinator service`() = runTest {
        val startServiceRecorder = lambdaRecorder<Unit> { }
        val stopServiceRecorder = lambdaRecorder<Unit> { }
        val coordinator = createCoordinator(
            startService = startServiceRecorder,
            stopService = stopServiceRecorder
        )
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val room = FakeJoinedRoom(
            startLiveLocationShareResult = { Result.success(AN_EVENT_ID) },
            stopLiveLocationShareResult = { Result.success(Unit) },
        )
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply { givenGetRoomResult(A_ROOM_ID, room) },
            coordinator = coordinator,
        )
        advanceUntilIdle()

        val startResult = async { manager.startShare(A_ROOM_ID, 15.minutes) }
        beaconInfoUpdates.emit(BeaconInfoUpdate(roomId = A_ROOM_ID, beaconId = AN_EVENT_ID, isLive = true))
        assertThat(startResult.await().isSuccess).isTrue()

        val result = manager.stopShare(A_ROOM_ID)

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.sharingRoomIds.value).isEmpty()
        assert(startServiceRecorder).isCalledOnce()
        assert(stopServiceRecorder).isCalledOnce()
    }

    @Test
    fun `two managers with the same room id keep isolated state per session`() = runTest {
        val coordinator = createCoordinator()
        val beaconInfoUpdatesOne = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val beaconInfoUpdatesTwo = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val managerOne = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdatesOne,
            ).apply {
                givenGetRoomResult(
                    A_ROOM_ID,
                    FakeJoinedRoom(
                        startLiveLocationShareResult = { Result.success(AN_EVENT_ID) },
                        stopLiveLocationShareResult = { Result.success(Unit) },
                    ),
                )
            },
            coordinator = coordinator,
        )
        val managerTwo = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID_2,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdatesTwo,
            ).apply {
                givenGetRoomResult(
                    A_ROOM_ID,
                    FakeJoinedRoom(
                        startLiveLocationShareResult = { Result.success(AN_EVENT_ID) },
                        stopLiveLocationShareResult = { Result.success(Unit) },
                    ),
                )
            },
            coordinator = coordinator,
        )
        advanceUntilIdle()

        val startResult = async { managerOne.startShare(A_ROOM_ID, 15.minutes) }
        beaconInfoUpdatesOne.emit(BeaconInfoUpdate(roomId = A_ROOM_ID, beaconId = AN_EVENT_ID, isLive = true))
        assertThat(startResult.await().isSuccess).isTrue()

        assertThat(managerOne.sharingRoomIds.value).containsExactly(A_ROOM_ID)
        assertThat(managerTwo.sharingRoomIds.value).isEmpty()
    }

    @Test
    fun `start share persists room expiry after beacon echo`() = runTest {
        val liveLocationStore = createInMemoryLiveLocationStore()
        val coordinator = createCoordinator()
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply {
                givenGetRoomResult(
                    A_ROOM_ID,
                    FakeJoinedRoom(
                        startLiveLocationShareResult = { Result.success(AN_EVENT_ID) },
                        stopLiveLocationShareResult = { Result.success(Unit) },
                    ),
                )
            },
            coordinator = coordinator,
            liveLocationStore = liveLocationStore,
            clock = FakeSystemClock(epochMillisResult = 123L),
        )
        advanceUntilIdle()

        val result = async { manager.startShare(A_ROOM_ID, 15.minutes) }
        beaconInfoUpdates.emit(BeaconInfoUpdate(roomId = A_ROOM_ID, beaconId = AN_EVENT_ID, isLive = true))

        assertThat(result.await().isSuccess).isTrue()
        assertThat(liveLocationStore.getLiveLocationExpiries()).containsKey(A_ROOM_ID)
    }

    @Test
    fun `location updates admit an independent lifecycle operation and retain the start identity`() = runTest {
        val operationContext = LifecycleOperationContext("live-location-operation", FullAppLifecycleId.LC_17)
        val operations = FakeAppOperationalStateCoordinator()
        var observedContext: LifecycleOperationContext? = null
        val liveLocationStore = createInMemoryLiveLocationStore()
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply {
                givenGetRoomResult(
                    A_ROOM_ID,
                    FakeJoinedRoom(
                        startLiveLocationShareResult = { Result.success(AN_EVENT_ID) },
                        stopLiveLocationShareResult = { Result.success(Unit) },
                        sendLiveLocationResult = {
                            observedContext = currentLifecycleOperationContext()
                            Result.success(Unit)
                        },
                    ),
                )
            },
            coordinator = createCoordinator(),
            liveLocationStore = liveLocationStore,
        )
        advanceUntilIdle()

        val startResult = async(operationContext) { manager.startShare(A_ROOM_ID, 15.minutes) }
        beaconInfoUpdates.emit(BeaconInfoUpdate(roomId = A_ROOM_ID, beaconId = AN_EVENT_ID, isLive = true))
        assertThat(startResult.await().isSuccess).isTrue()

        manager.onLocationUpdate(Location(lat = 1.0, lon = 2.0))

        assertThat(observedContext?.operationId).isNotEqualTo(operationContext.operationId)
        assertThat(observedContext?.operationId).isNotNull()
        assertThat(observedContext?.lifecycleId).isEqualTo(FullAppLifecycleId.LC_17)
        assertThat(liveLocationStore.getLifecycleOperationIds()[A_ROOM_ID]).isEqualTo(operationContext.operationId)
    }

    @Test
    fun `stop cancels an admitted location update before the room send executes`() = runTest {
        var sendCount = 0
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val room = FakeJoinedRoom(
            startLiveLocationShareResult = { Result.success(AN_EVENT_ID) },
            stopLiveLocationShareResult = { Result.success(Unit) },
            sendLiveLocationResult = {
                sendCount += 1
                Result.success(Unit)
            },
        )
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply { givenGetRoomResult(A_ROOM_ID, room) },
            liveLocationStore = createInMemoryLiveLocationStore(),
        )
        advanceUntilIdle()
        val start = async { manager.startShare(A_ROOM_ID, 15.minutes) }
        beaconInfoUpdates.emit(BeaconInfoUpdate(A_ROOM_ID, AN_EVENT_ID, isLive = true))
        assertThat(start.await().isSuccess).isTrue()

        val update = async { manager.onLocationUpdate(Location(lat = 1.0, lon = 2.0)) }
        runCurrent() // FakeJoinedRoom is now suspended in simulateLongTask.
        val stop = async { manager.stopShare(A_ROOM_ID) }
        runCurrent() // The synchronous stop fence cancels the admitted update.
        advanceUntilIdle()

        update.await()
        assertThat(stop.await().isSuccess).isTrue()
        assertThat(sendCount).isEqualTo(0)
        assertThat(manager.sharingRoomIds.value).isEmpty()
    }

    @Test
    fun `stop share removes persisted expiry`() = runTest {
        val liveLocationStore = createLiveLocationStore()
        val coordinator = createCoordinator()
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply {
                givenGetRoomResult(
                    A_ROOM_ID,
                    FakeJoinedRoom(
                        startLiveLocationShareResult = { Result.success(AN_EVENT_ID) },
                        stopLiveLocationShareResult = { Result.success(Unit) },
                    ),
                )
            },
            coordinator = coordinator,
            liveLocationStore = liveLocationStore,
        )
        advanceUntilIdle()

        val startResult = async { manager.startShare(A_ROOM_ID, 15.minutes) }
        beaconInfoUpdates.emit(BeaconInfoUpdate(roomId = A_ROOM_ID, beaconId = AN_EVENT_ID, isLive = true))
        assertThat(startResult.await().isSuccess).isTrue()

        manager.stopShare(A_ROOM_ID)

        assertThat(liveLocationStore.getLiveLocationExpiries()).doesNotContainKey(A_ROOM_ID)
    }

    @Test
    fun `failed remote stop halts local upload and retries persisted opaque target`() = runTest {
        var failNextStop = false
        var failedOnce = false
        val room = FakeJoinedRoom(
            startLiveLocationShareResult = { Result.success(AN_EVENT_ID) },
            stopLiveLocationShareResult = {
                if (failNextStop && !failedOnce) {
                    failedOnce = true
                    Result.failure(IllegalStateException("offline"))
                } else {
                    Result.success(Unit)
                }
            },
        )
        val liveLocationStore = createInMemoryLiveLocationStore()
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply { givenGetRoomResult(A_ROOM_ID, room) },
            coordinator = createCoordinator(),
            liveLocationStore = liveLocationStore,
        )
        advanceUntilIdle()
        val startResult = async { manager.startShare(A_ROOM_ID, 15.minutes) }
        beaconInfoUpdates.emit(BeaconInfoUpdate(roomId = A_ROOM_ID, beaconId = AN_EVENT_ID, isLive = true))
        assertThat(startResult.await().isSuccess).isTrue()

        failNextStop = true
        val stopResult = manager.stopShare(A_ROOM_ID, "yizhe-live-test-target")

        assertThat(stopResult.isFailure).isTrue()
        assertThat(manager.sharingRoomIds.value).isEmpty()
        assertThat(liveLocationStore.getPendingLiveLocationStops()).containsExactly(
            PendingLiveLocationStop(A_ROOM_ID, "yizhe-live-test-target")
        )

        // Move past (rather than exactly onto) the retry boundary so the
        // background-scope retry coroutine is dispatched by the test scheduler.
        advanceTimeBy(15_001L)
        runCurrent()
        assertThat(liveLocationStore.getPendingLiveLocationStops()).isEmpty()
    }

    @Test
    fun `setup retains unexpired stored share without registering coordinator`() = runTest {
        val startServiceRecorder = lambdaRecorder<Unit> { }
        val stopServiceRecorder = lambdaRecorder<Unit> { }
        val liveLocationStore = createLiveLocationStore().apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
        }
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
            ).apply {
                givenGetRoomResult(A_ROOM_ID, FakeJoinedRoom())
            },
            coordinator = createCoordinator(
                startService = startServiceRecorder,
                stopService = stopServiceRecorder,
            ),
            liveLocationStore = liveLocationStore,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
        )

        assertThat(manager.sharingRoomIds.value).isEmpty()
        assert(startServiceRecorder).isNeverCalled()
        assert(stopServiceRecorder).isNeverCalled()
        assertThat(manager.pausedShares.value[A_ROOM_ID]).isEqualTo(PausedLiveLocationShare.UNRECOVERABLE)
    }

    @Test
    fun `setup only makes stored share recoverable after matching current-session beacon`() = runTest {
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val store = createInMemoryLiveLocationStore().apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ),
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
        )

        assertThat(manager.pausedShares.value[A_ROOM_ID]).isEqualTo(PausedLiveLocationShare.CHECKING)
        beaconInfoUpdates.emit(BeaconInfoUpdate(A_ROOM_ID, AN_EVENT_ID, isLive = true))
        runCurrent()

        assertThat(manager.pausedShares.value[A_ROOM_ID]).isEqualTo(PausedLiveLocationShare.RECOVERABLE)
        assertThat(manager.sharingRoomIds.value).isEmpty()
    }

    @Test
    fun `resume reuses original identity and deadline without starting a new remote share`() = runTest {
        val startRemote = lambdaRecorder<Result<io.element.android.libraries.matrix.api.room.location.BeaconId>> {
            Result.failure(AssertionError("must not start a new share"))
        }
        val startService = lambdaRecorder<Unit> { }
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val store = createInMemoryLiveLocationStore().apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply {
                givenGetRoomResult(A_ROOM_ID, FakeJoinedRoom(startLiveLocationShareResult = { startRemote() }))
            },
            coordinator = createCoordinator(startService = startService),
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
        )
        beaconInfoUpdates.emit(BeaconInfoUpdate(A_ROOM_ID, AN_EVENT_ID, isLive = true))
        runCurrent()

        val result = manager.resumeShare(A_ROOM_ID)

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.sharingRoomIds.value).containsExactly(A_ROOM_ID)
        assertThat(manager.pausedShares.value).isEmpty()
        assertThat(store.getLiveLocationExpiries()[A_ROOM_ID]).isEqualTo(Instant.fromEpochMilliseconds(10_000L))
        assertThat(store.getLiveLocationBeaconIds()[A_ROOM_ID]).isEqualTo(AN_EVENT_ID.value)
        assert(startRemote).isNeverCalled()
        assert(startService).isCalledOnce()
    }

    @Test
    fun `timeout fence wins while resume is suspended reading persisted identity`() = runTest {
        var startServiceCount = 0
        val factory = GatedPreferenceDataStoreFactory()
        val store = createLiveLocationStore(preferenceDataStoreFactory = factory).apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val room = FakeJoinedRoom(stopLiveLocationShareResult = { Result.success(Unit) })
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply { givenGetRoomResult(A_ROOM_ID, room) },
            liveLocationStore = store,
            coordinator = createCoordinator(startService = { startServiceCount += 1 }),
            clock = FakeSystemClock(epochMillisResult = 1_000L),
        )
        beaconInfoUpdates.emit(BeaconInfoUpdate(A_ROOM_ID, AN_EVENT_ID, isLive = true))
        runCurrent()
        assertThat(manager.pausedShares.value[A_ROOM_ID]).isEqualTo(PausedLiveLocationShare.RECOVERABLE)

        factory.gateNextRead()
        val resume = async { manager.resumeShare(A_ROOM_ID) }
        factory.readStarted.await()
        advanceTimeBy(9_000L)
        runCurrent() // Timeout establishes its fence before its own persisted-state read suspends.
        factory.releaseRead.complete(Unit)
        advanceUntilIdle()

        assertThat(resume.await().isFailure).isTrue()
        assertThat(manager.sharingRoomIds.value).isEmpty()
        assertThat(startServiceCount).isEqualTo(0)
    }

    @Test
    fun `session clear fence wins while resume is suspended reading persisted identity`() = runTest {
        val factory = GatedPreferenceDataStoreFactory()
        val store = createLiveLocationStore(preferenceDataStoreFactory = factory).apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }
        val sessionObserver = FakeSessionObserver()
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        var startServiceCount = 0
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ),
            coordinator = createCoordinator(startService = { startServiceCount += 1 }),
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
            sessionObserver = sessionObserver,
        )
        beaconInfoUpdates.emit(BeaconInfoUpdate(A_ROOM_ID, AN_EVENT_ID, isLive = true))
        runCurrent()

        factory.gateNextRead()
        val resume = async { manager.resumeShare(A_ROOM_ID) }
        factory.readStarted.await()
        sessionObserver.onSessionDeleted(A_SESSION_ID.value)
        factory.releaseRead.complete(Unit)
        advanceUntilIdle()

        assertThat(resume.await().isFailure).isTrue()
        assertThat(manager.sharingRoomIds.value).isEmpty()
        assertThat(startServiceCount).isEqualTo(0)
    }

    @Test
    fun `newer start wins while the previous generation is suspended persisting identity`() = runTest {
        var startCount = 0
        var stopCount = 0
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val room = FakeJoinedRoom(
            startLiveLocationShareResult = {
                startCount += 1
                Result.success(AN_EVENT_ID)
            },
            stopLiveLocationShareResult = {
                stopCount += 1
                Result.success(Unit)
            },
        )
        val factory = GatedPreferenceDataStoreFactory()
        val store = createLiveLocationStore(preferenceDataStoreFactory = factory)
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply { givenGetRoomResult(A_ROOM_ID, room) },
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
        )

        factory.gateNextUpdate()
        val firstStart = async { manager.startShare(A_ROOM_ID, 15.minutes) }
        factory.updateStarted.await()
        val secondStart = async { manager.startShare(A_ROOM_ID, 15.minutes) }
        // Cross both simulated network suspensions before releasing the first writer.
        advanceTimeBy(2)
        runCurrent()

        factory.releaseUpdate.complete(Unit)
        runCurrent()
        beaconInfoUpdates.emit(BeaconInfoUpdate(A_ROOM_ID, AN_EVENT_ID, isLive = true))
        runCurrent()

        assertThat(firstStart.await().isFailure).isTrue()
        assertThat(secondStart.await().isSuccess).isTrue()
        assertThat(manager.sharingRoomIds.value).containsExactly(A_ROOM_ID)
        assertThat(store.getLiveLocationBeaconIds()[A_ROOM_ID]).isEqualTo(AN_EVENT_ID.value)
        assertThat(stopCount).isEqualTo(2)
    }

    @Test
    fun `pre-clear remote start cannot pass a post-clear generation`() = runTest {
        val firstRemoteStartEntered = CompletableDeferred<Unit>()
        val releaseFirstRemoteStart = CompletableDeferred<Unit>()
        var remoteStartCount = 0
        var stopCount = 0
        val baseRoom = FakeJoinedRoom(
            stopLiveLocationShareResult = {
                stopCount += 1
                Result.success(Unit)
            },
        )
        val room = object : JoinedRoom by baseRoom {
            override suspend fun startLiveLocationShare(durationMillis: Long): Result<EventId> {
                remoteStartCount += 1
                if (remoteStartCount == 1) {
                    firstRemoteStartEntered.complete(Unit)
                    releaseFirstRemoteStart.await()
                }
                return Result.success(AN_EVENT_ID)
            }
        }
        val sessionObserver = FakeSessionObserver()
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val store = createInMemoryLiveLocationStore()
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply { givenGetRoomResult(A_ROOM_ID, room) },
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
            sessionObserver = sessionObserver,
        )

        val oldStart = async { manager.startShare(A_ROOM_ID, 30.minutes) }
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()
        firstRemoteStartEntered.await()

        sessionObserver.onSessionDeleted(A_SESSION_ID.value)
        val newStart = async { manager.startShare(A_ROOM_ID, 15.minutes) }
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()
        beaconInfoUpdates.emit(BeaconInfoUpdate(A_ROOM_ID, AN_EVENT_ID, isLive = true))
        runCurrent()
        assertThat(newStart.await().isSuccess).isTrue()

        releaseFirstRemoteStart.complete(Unit)
        runCurrent()

        assertThat(oldStart.await().isFailure).isTrue()
        assertThat(manager.sharingRoomIds.value).containsExactly(A_ROOM_ID)
        assertThat(store.getLiveLocationExpiries()[A_ROOM_ID])
            .isEqualTo(Instant.fromEpochMilliseconds(901_000L))
        assertThat(stopCount).isEqualTo(2)
    }

    @Test
    fun `stop fence prevents suspended paused reconciliation from restoring the room`() = runTest {
        val factory = GatedPreferenceDataStoreFactory()
        val store = createLiveLocationStore(preferenceDataStoreFactory = factory).apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val room = FakeJoinedRoom(stopLiveLocationShareResult = { Result.success(Unit) })
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply { givenGetRoomResult(A_ROOM_ID, room) },
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
        )
        assertThat(manager.pausedShares.value[A_ROOM_ID]).isEqualTo(PausedLiveLocationShare.CHECKING)

        factory.gateNextRead()
        beaconInfoUpdates.emit(BeaconInfoUpdate(A_ROOM_ID, AN_EVENT_ID, isLive = true))
        factory.readStarted.await()
        val stop = async { manager.stopShare(A_ROOM_ID) }
        runCurrent()
        assertThat(stop.await().isSuccess).isTrue()

        factory.releaseRead.complete(Unit)
        runCurrent()

        assertThat(manager.pausedShares.value).isEmpty()
        assertThat(manager.sharingRoomIds.value).isEmpty()
    }

    @Test
    fun `stop fence prevents invalid suspended resume from restoring paused state`() = runTest {
        val factory = GatedPreferenceDataStoreFactory()
        val store = createLiveLocationStore(preferenceDataStoreFactory = factory).apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val room = FakeJoinedRoom(stopLiveLocationShareResult = { Result.success(Unit) })
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply { givenGetRoomResult(A_ROOM_ID, room) },
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
        )
        beaconInfoUpdates.emit(BeaconInfoUpdate(A_ROOM_ID, AN_EVENT_ID, isLive = true))
        runCurrent()
        assertThat(manager.pausedShares.value[A_ROOM_ID]).isEqualTo(PausedLiveLocationShare.RECOVERABLE)

        factory.gateReadAfter(completedReads = 1)
        val resume = async { manager.resumeShare(A_ROOM_ID) }
        factory.readStarted.await()
        beaconInfoUpdates.emit(BeaconInfoUpdate(A_ROOM_ID, AN_EVENT_ID, isLive = false))
        runCurrent()
        val stop = async { manager.stopShare(A_ROOM_ID) }
        runCurrent()
        assertThat(stop.await().isSuccess).isTrue()

        factory.releaseRead.complete(Unit)
        runCurrent()

        assertThat(resume.await().isFailure).isTrue()
        assertThat(manager.pausedShares.value).isEmpty()
        assertThat(manager.sharingRoomIds.value).isEmpty()
    }

    @Test
    fun `stop fence prevents suspended persisted recovery from restoring paused state or timeout`() = runTest {
        val factory = GatedPreferenceDataStoreFactory()
        val store = createLiveLocationStore(preferenceDataStoreFactory = factory).apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }
        var stopCount = 0
        val room = FakeJoinedRoom(
            stopLiveLocationShareResult = {
                stopCount += 1
                Result.success(Unit)
            },
        )
        val client = FakeMatrixClient(
            sessionId = A_SESSION_ID,
            sessionCoroutineScope = backgroundScope,
        ).apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = DefaultActiveLiveLocationShareManager(
            matrixClient = client,
            coordinator = createCoordinator(),
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
            sessionObserver = FakeSessionObserver(),
            operationalCoordinator = FakeAppOperationalStateCoordinator(),
        )

        factory.gateReadAfter(completedReads = 3)
        val setup = async { manager.setup() }
        factory.readStarted.await()
        val stop = async { manager.stopShare(A_ROOM_ID) }
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()
        assertThat(stop.await().isSuccess).isTrue()

        factory.releaseRead.complete(Unit)
        runCurrent()
        setup.await()

        assertThat(manager.pausedShares.value).isEmpty()
        assertThat(manager.sharingRoomIds.value).isEmpty()
        advanceTimeBy(9_001L)
        runCurrent()
        assertThat(stopCount).isEqualTo(1)
    }

    @Test
    fun `stale persisted recovery cannot replace a share explicitly restarted while the beacon read is suspended`() = runTest {
        val factory = GatedPreferenceDataStoreFactory()
        val oldExpiry = Instant.fromEpochMilliseconds(10_000L)
        val newBeaconId = EventId("\$yizhe-live-new")
        val store = createLiveLocationStore(preferenceDataStoreFactory = factory).apply {
            setLiveLocationExpiry(A_ROOM_ID, oldExpiry)
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }
        var stopCount = 0
        val room = FakeJoinedRoom(
            startLiveLocationShareResult = { Result.success(newBeaconId) },
            stopLiveLocationShareResult = {
                stopCount += 1
                Result.success(Unit)
            },
        )
        val client = FakeMatrixClient(
            sessionId = A_SESSION_ID,
            sessionCoroutineScope = backgroundScope,
        ).apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = DefaultActiveLiveLocationShareManager(
            matrixClient = client,
            coordinator = createCoordinator(),
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
            sessionObserver = FakeSessionObserver(),
            operationalCoordinator = FakeAppOperationalStateCoordinator(),
        )

        // Recovery has captured the old expiry and old beacon snapshot, but has not
        // published either projection when the explicit stop/new-start sequence wins.
        factory.gateReadAfter(completedReads = 3)
        val setup = async { manager.setup() }
        factory.readStarted.await()

        assertThat(manager.stopShare(A_ROOM_ID).isSuccess).isTrue()
        assertThat(manager.startShare(A_ROOM_ID, 15.minutes).isSuccess).isTrue()
        val stopCountAfterRestart = stopCount

        factory.releaseRead.complete(Unit)
        setup.await()
        runCurrent()

        assertThat(manager.sharingRoomIds.value).containsExactly(A_ROOM_ID)
        assertThat(manager.pausedShares.value).isEmpty()
        assertThat(store.getLiveLocationBeaconIds()[A_ROOM_ID]).isEqualTo(newBeaconId.value)
        assertThat(store.getLiveLocationExpiries()[A_ROOM_ID])
            .isEqualTo(Instant.fromEpochMilliseconds(901_000L))

        // The old 10-second recovery deadline must not retain a timeout capable of
        // stopping the newly started 15-minute share.
        advanceTimeBy(9_001L)
        runCurrent()
        assertThat(manager.sharingRoomIds.value).containsExactly(A_ROOM_ID)
        assertThat(store.getLiveLocationExpiries()[A_ROOM_ID])
            .isEqualTo(Instant.fromEpochMilliseconds(901_000L))
        assertThat(stopCount).isEqualTo(stopCountAfterRestart)
    }

    @Test
    fun `old timeout cannot stop a new generation while its remote start is suspended`() = runTest {
        val store = createInMemoryLiveLocationStore().apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }
        val remoteStartEntered = CompletableDeferred<Unit>()
        val releaseRemoteStart = CompletableDeferred<Unit>()
        val newBeaconId = EventId("\$yizhe-live-new")
        var stopCount = 0
        val baseRoom = FakeJoinedRoom(
            stopLiveLocationShareResult = {
                stopCount += 1
                Result.success(Unit)
            },
        )
        val room = object : JoinedRoom by baseRoom {
            override suspend fun startLiveLocationShare(durationMillis: Long): Result<EventId> {
                remoteStartEntered.complete(Unit)
                releaseRemoteStart.await()
                return Result.success(newBeaconId)
            }
        }
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
            ).apply { givenGetRoomResult(A_ROOM_ID, room) },
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
        )

        val newStart = async { manager.startShare(A_ROOM_ID, 15.minutes) }
        remoteStartEntered.await()

        // The new generation exists, but its remote start has not returned yet. Advancing
        // beyond the recovered share's old deadline must not let that timeout fence the room.
        advanceTimeBy(9_001L)
        runCurrent()
        assertThat(newStart.isCompleted).isFalse()
        assertThat(stopCount).isEqualTo(1)

        releaseRemoteStart.complete(Unit)
        assertThat(newStart.await().isSuccess).isTrue()

        assertThat(manager.sharingRoomIds.value).containsExactly(A_ROOM_ID)
        assertThat(manager.pausedShares.value).isEmpty()
        assertThat(store.getLiveLocationBeaconIds()[A_ROOM_ID]).isEqualTo(newBeaconId.value)
        assertThat(store.getLiveLocationExpiries()[A_ROOM_ID])
            .isEqualTo(Instant.fromEpochMilliseconds(901_000L))
        assertThat(stopCount).isEqualTo(1)
    }

    @Test
    fun `stale pending stop recovery cannot stop a share started while the pending stop read is suspended`() = runTest {
        val factory = GatedPreferenceDataStoreFactory()
        val newBeaconId = EventId("\$yizhe-live-new")
        val store = createLiveLocationStore(preferenceDataStoreFactory = factory).apply {
            setPendingLiveLocationStop(A_ROOM_ID, "old-protected-aggregate")
        }
        var stopCount = 0
        val room = FakeJoinedRoom(
            startLiveLocationShareResult = { Result.success(newBeaconId) },
            stopLiveLocationShareResult = {
                stopCount += 1
                Result.success(Unit)
            },
        )
        val client = FakeMatrixClient(
            sessionId = A_SESSION_ID,
            sessionCoroutineScope = backgroundScope,
        ).apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = DefaultActiveLiveLocationShareManager(
            matrixClient = client,
            coordinator = createCoordinator(),
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
            sessionObserver = FakeSessionObserver(),
            operationalCoordinator = FakeAppOperationalStateCoordinator(),
        )

        // The recovery read has snapshotted the old pending stop. The explicit start
        // resolves that stop and publishes a new generation before the snapshot returns.
        factory.gateReadAfter(completedReads = 1)
        val setup = async { manager.setup() }
        factory.readStarted.await()

        assertThat(manager.startShare(A_ROOM_ID, 15.minutes).isSuccess).isTrue()
        val stopCountAfterStart = stopCount

        factory.releaseRead.complete(Unit)
        setup.await()
        runCurrent()

        assertThat(manager.sharingRoomIds.value).containsExactly(A_ROOM_ID)
        assertThat(manager.pausedShares.value).isEmpty()
        assertThat(store.getPendingLiveLocationStops()).isEmpty()
        assertThat(store.getLiveLocationBeaconIds()[A_ROOM_ID]).isEqualTo(newBeaconId.value)
        assertThat(store.getLiveLocationExpiries()[A_ROOM_ID])
            .isEqualTo(Instant.fromEpochMilliseconds(901_000L))

        advanceTimeBy(15_001L)
        runCurrent()
        assertThat(manager.sharingRoomIds.value).containsExactly(A_ROOM_ID)
        assertThat(store.getLiveLocationExpiries()[A_ROOM_ID])
            .isEqualTo(Instant.fromEpochMilliseconds(901_000L))
        assertThat(stopCount).isEqualTo(stopCountAfterStart)
    }

    @Test
    fun `mismatched current-session beacon stays paused and cannot resume`() = runTest {
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val store = createInMemoryLiveLocationStore().apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ),
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
        )
        val otherBeacon = io.element.android.libraries.matrix.api.core.EventId("\$other")
        beaconInfoUpdates.emit(BeaconInfoUpdate(A_ROOM_ID, otherBeacon, isLive = true))
        runCurrent()

        assertThat(manager.pausedShares.value[A_ROOM_ID]).isEqualTo(PausedLiveLocationShare.UNRECOVERABLE)
        assertThat(manager.resumeShare(A_ROOM_ID).isFailure).isTrue()
        assertThat(manager.sharingRoomIds.value).isEmpty()
    }

    @Test
    fun `beacon identity store persists replaces and removes per room`() = runTest {
        val store = createInMemoryLiveLocationStore()

        store.setLiveLocationBeaconId(A_ROOM_ID, "first").getOrThrow()
        store.setLiveLocationBeaconId(A_ROOM_ID, "second").getOrThrow()
        assertThat(store.getLiveLocationBeaconIds()).containsExactly(A_ROOM_ID, "second")

        store.removeLiveLocationBeaconId(A_ROOM_ID).getOrThrow()
        assertThat(store.getLiveLocationBeaconIds()).isEmpty()
    }

    @Test
    fun `removing stored share clears expiry and identity together`() = runTest {
        val store = createInMemoryLiveLocationStore().apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }

        store.removeLiveLocationShare(A_ROOM_ID).getOrThrow()

        assertThat(store.getLiveLocationExpiries()).isEmpty()
        assertThat(store.getLiveLocationBeaconIds()).isEmpty()
    }

    @Test
    fun `setup remotely stops expired stored share and removes it from store`() = runTest {
        val stopLiveLocationShareResult = lambdaRecorder<Result<Unit>> { Result.success(Unit) }
        val liveLocationStore = createLiveLocationStore().apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(1_000L))
        }
        createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
            ).apply {
                givenGetRoomResult(
                    A_ROOM_ID,
                    FakeJoinedRoom(stopLiveLocationShareResult = stopLiveLocationShareResult),
                )
            },
            coordinator = createCoordinator(),
            liveLocationStore = liveLocationStore,
            clock = FakeSystemClock(epochMillisResult = 5_000L),
        )
        advanceUntilIdle()
        assert(stopLiveLocationShareResult).isCalledOnce()
        assertThat(liveLocationStore.getLiveLocationExpiries()).isEmpty()
    }

    @Test
    fun `stop share closes loaded room and removes persisted expiry when room is not tracked`() = runTest {
        val stopLiveLocationShareResult = lambdaRecorder<Result<Unit>> { Result.success(Unit) }
        val room = FakeJoinedRoom(stopLiveLocationShareResult = stopLiveLocationShareResult)
        val liveLocationStore = createInMemoryLiveLocationStore()
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
            ).apply {
                givenGetRoomResult(A_ROOM_ID, room)
            },
            coordinator = createCoordinator(),
            liveLocationStore = liveLocationStore,
        )
        liveLocationStore.setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))

        val result = manager.stopShare(A_ROOM_ID)

        assertThat(result.isSuccess).isTrue()
        assert(stopLiveLocationShareResult).isCalledOnce()
        assertThat(liveLocationStore.getLiveLocationExpiries()).doesNotContainKey(A_ROOM_ID)
        room.baseRoom.assertDestroyed()
    }

    @Test
    fun `share is automatically stopped when timeout elapses`() = runTest {
        val liveLocationStore = createInMemoryLiveLocationStore()
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val stopLiveLocationShareResult = lambdaRecorder<Result<Unit>> { Result.success(Unit) }
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply {
                givenGetRoomResult(
                    A_ROOM_ID,
                    FakeJoinedRoom(
                        startLiveLocationShareResult = { Result.success(AN_EVENT_ID) },
                        stopLiveLocationShareResult = stopLiveLocationShareResult
                    ),
                )
            },
            coordinator = createCoordinator(),
            liveLocationStore = liveLocationStore,
            clock = FakeSystemClock(epochMillisResult = 123L),
        )
        advanceUntilIdle()

        val startResult = async { manager.startShare(A_ROOM_ID, 1.minutes) }
        beaconInfoUpdates.emit(BeaconInfoUpdate(roomId = A_ROOM_ID, beaconId = AN_EVENT_ID, isLive = true))
        assertThat(startResult.await().isSuccess).isTrue()

        manager.sharingRoomIds.test {
            assertThat(awaitItem()).containsExactly(A_ROOM_ID)
            assertThat(awaitItem()).isEmpty()
            // Local delivery is stopped first for privacy; allow the remote
            // end request to finish after observing the local empty state.
            advanceTimeBy(2L)
            runCurrent()
            assertThat(liveLocationStore.getLiveLocationExpiries()).doesNotContainKey(A_ROOM_ID)
            assert(stopLiveLocationShareResult).isCalledExactly(2)
        }
    }

    @Test
    fun `restored share is automatically stopped when remaining timeout elapses`() = runTest {
        val liveLocationStore = createInMemoryLiveLocationStore().apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(6_000L))
        }
        val stopLiveLocationShareLambda = lambdaRecorder<Result<Unit>> { Result.success(Unit) }
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
            ).apply {
                givenGetRoomResult(
                    A_ROOM_ID,
                    FakeJoinedRoom(
                        stopLiveLocationShareResult = stopLiveLocationShareLambda
                    ),
                )
            },
            coordinator = createCoordinator(),
            liveLocationStore = liveLocationStore,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
        )

        // Recovery deliberately remains paused; the original deadline still closes it.
        assertThat(manager.sharingRoomIds.value).isEmpty()
        assertThat(manager.pausedShares.value[A_ROOM_ID]).isEqualTo(PausedLiveLocationShare.UNRECOVERABLE)
        advanceTimeBy(5_001L)
        runCurrent()
        assertThat(manager.pausedShares.value).isEmpty()
        assertThat(liveLocationStore.getLiveLocationExpiries()).doesNotContainKey(A_ROOM_ID)
        assert(stopLiveLocationShareLambda).isCalledOnce()
    }

    @Test
    fun `session deleted clears local state`() = runTest {
        val startServiceRecorder = lambdaRecorder<Unit> { }
        val stopServiceRecorder = lambdaRecorder<Unit> { }
        val liveLocationStore = createInMemoryLiveLocationStore()
        val sessionObserver = FakeSessionObserver()
        val beaconInfoUpdates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val manager = createManager(
            client = FakeMatrixClient(
                sessionId = A_SESSION_ID,
                sessionCoroutineScope = backgroundScope,
                ownBeaconInfoUpdates = beaconInfoUpdates,
            ).apply {
                givenGetRoomResult(
                    A_ROOM_ID,
                    FakeJoinedRoom(
                        startLiveLocationShareResult = { Result.success(AN_EVENT_ID) },
                        stopLiveLocationShareResult = { Result.success(Unit) },
                    ),
                )
            },
            coordinator = createCoordinator(
                startService = startServiceRecorder,
                stopService = stopServiceRecorder,
            ),
            liveLocationStore = liveLocationStore,
            sessionObserver = sessionObserver,
        )
        advanceUntilIdle()

        val firstStart = async { manager.startShare(A_ROOM_ID, 15.minutes) }
        beaconInfoUpdates.emit(BeaconInfoUpdate(roomId = A_ROOM_ID, beaconId = AN_EVENT_ID, isLive = true))
        assertThat(firstStart.await().isSuccess).isTrue()

        sessionObserver.onSessionDeleted(A_SESSION_ID.value)
        advanceUntilIdle()

        assertThat(manager.sharingRoomIds.value).isEmpty()
        assertThat(liveLocationStore.getLiveLocationExpiries()).doesNotContainKey(A_ROOM_ID)
        assert(startServiceRecorder).isCalledOnce()
        assert(stopServiceRecorder).isCalledOnce()

        val secondStart = async { manager.startShare(A_ROOM_ID, 15.minutes) }
        advanceUntilIdle()
        assertThat(secondStart.isCompleted).isFalse()

        beaconInfoUpdates.emit(BeaconInfoUpdate(roomId = A_ROOM_ID, beaconId = AN_EVENT_ID, isLive = true))
        assertThat(secondStart.await().isSuccess).isTrue()
    }

    @Test
    fun `protected updates and stop use distinct operations and stop awaits real runtime release`() = runTest {
        val operations = LiveOperationalProbe()
        val observed = mutableListOf<LifecycleOperationContext?>()
        val store = createInMemoryLiveLocationStore()
        val locationCoordinator = createCoordinator()
        val room = FakeJoinedRoom(
            startLiveLocationShareResult = { Result.success(EventId("\$yizhe-live-probe")) },
            sendLiveLocationResult = { observed += currentLifecycleOperationContext(); Result.success(Unit) },
            beforeStopLiveLocationShare = { observed += currentLifecycleOperationContext() },
            stopLiveLocationShareResult = { Result.success(Unit) },
        )
        val manager = createManager(client = FakeMatrixClient(sessionId = A_SESSION_ID, sessionCoroutineScope = backgroundScope)
            .apply { givenGetRoomResult(A_ROOM_ID, room) }, coordinator = locationCoordinator,
            liveLocationStore = store, operationalCoordinator = operations)
        val start = LifecycleOperationContext("original-live-start-operation", FullAppLifecycleId.LC_17)
        assertThat(withContext(start) { manager.startShare(A_ROOM_ID, 15.minutes) }.isSuccess).isTrue()
        val runtime = locationCoordinator.serviceCreated()
        manager.onLocationUpdate(Location(1.0, 2.0))
        manager.onLocationUpdate(Location(3.0, 4.0))
        val stop = async(start) { manager.stopShare(A_ROOM_ID) }
        advanceTimeBy(2)
        runCurrent()
        assertThat(stop.isCompleted).isFalse()
        assertThat(manager.sharingRoomIds.value).isEmpty()
        assertThat(operations.cleanup).isEmpty()
        locationCoordinator.serviceReleased(runtime)
        assertThat(stop.await().isSuccess).isTrue()
        val requests = operations.delegate.startedRequests
        assertThat(requests.map { it.trigger }).containsExactly("live_location_update", "live_location_update", "live_location_stop").inOrder()
        assertThat(requests.map { it.operationId }.distinct()).hasSize(3)
        assertThat(requests.map { it.operationId }).doesNotContain(start.operationId)
        assertThat(observed.map { it?.operationId }).containsExactlyElementsIn(requests.map { it.operationId }).inOrder()
        assertThat(operations.cleanup.single().operationId).isEqualTo(requests.last().operationId)
        assertThat(operations.seals).containsExactly(requests.last().operationId)
        assertThat(operations.delegate.clientReceipts.map { it.component }.distinct())
            .containsExactly(io.element.android.security.dataprotection.api.LifecycleComponent.ANDROID_SERVICE)
        assertThat(store.getPendingLiveLocationStops()).isEmpty()
    }

    @Test
    fun `failed protected stop retries the same durable operation without inheriting start`() = runTest {
        val operations = LiveOperationalProbe()
        var failStop = true
        val ids = mutableListOf<String?>()
        val store = createInMemoryLiveLocationStore()
        val locationCoordinator = createCoordinator()
        val room = FakeJoinedRoom(startLiveLocationShareResult = { Result.success(EventId("\$yizhe-live-retry")) },
            beforeStopLiveLocationShare = { ids += currentLifecycleOperationContext()?.operationId },
            stopLiveLocationShareResult = {
                if (failStop) Result.failure(IllegalStateException("offline")) else Result.success(Unit)
            })
        val manager = createManager(client = FakeMatrixClient(sessionId = A_SESSION_ID, sessionCoroutineScope = backgroundScope)
            .apply { givenGetRoomResult(A_ROOM_ID, room) }, coordinator = locationCoordinator,
            liveLocationStore = store, operationalCoordinator = operations)
        val start = LifecycleOperationContext("original-live-retry-start", FullAppLifecycleId.LC_17)
        assertThat(withContext(start) { manager.startShare(A_ROOM_ID, 15.minutes) }.isSuccess).isTrue()
        val runtime = locationCoordinator.serviceCreated()
        assertThat(manager.stopShare(A_ROOM_ID).isFailure).isTrue()
        val retained = store.getPendingLiveLocationStops().single()
        assertThat(retained.operationId).isNotEqualTo(start.operationId)
        assertThat(operations.cleanup).isEmpty()
        locationCoordinator.serviceReleased(runtime)
        failStop = false
        assertThat(manager.stopShare(A_ROOM_ID).isSuccess).isTrue()
        assertThat(ids).containsExactly(retained.operationId, retained.operationId).inOrder()
        assertThat(operations.delegate.startedRequests.map { it.operationId }.distinct()).containsExactly(retained.operationId)
    }

    @Test
    fun `seal failure retains accepted stop and retry never resends end or renews its operation`() = runTest {
        val operations = LiveOperationalProbe().apply { failSeal = true }
        val store = createInMemoryLiveLocationStore()
        val coordinator = createCoordinator()
        var ends = 0
        val room = FakeJoinedRoom(startLiveLocationShareResult = { Result.success(EventId("$" + "yizhe-live-seal-retry")) },
            beforeStopLiveLocationShare = { ends++ }, stopLiveLocationShareResult = { Result.success(Unit) })
        val manager = createManager(client = FakeMatrixClient(sessionId = A_SESSION_ID, sessionCoroutineScope = backgroundScope)
            .apply { givenGetRoomResult(A_ROOM_ID, room) }, coordinator = coordinator, liveLocationStore = store,
            operationalCoordinator = operations)
        withContext(LifecycleOperationContext("original-share-seal-retry", FullAppLifecycleId.LC_17)) {
            assertThat(manager.startShare(A_ROOM_ID, 15.minutes).isSuccess).isTrue()
        }
        val lease = coordinator.serviceCreated()
        val stop = async { manager.stopShare(A_ROOM_ID) }
        advanceTimeBy(2); runCurrent(); coordinator.serviceReleased(lease)
        assertThat(stop.await().isFailure).isTrue()
        val retained = store.getPendingLiveLocationStops().single()
        assertThat(retained.endAccepted).isTrue()
        assertThat(retained.runtimeReleasedAtEpochMillis).isNotNull()
        assertThat(retained.closureQueued).isFalse()
        assertThat(operations.delegate.clientReceipts.map { it.component })
            .containsExactly(io.element.android.security.dataprotection.api.LifecycleComponent.ANDROID_SERVICE)
        operations.failSeal = false
        assertThat(manager.stopShare(A_ROOM_ID).isSuccess).isTrue()
        assertThat(ends).isEqualTo(1)
        assertThat(operations.delegate.startedRequests.map { it.operationId }).containsExactly(retained.operationId)
        assertThat(operations.reads).containsExactly(retained.operationId)
        assertThat(operations.seals).containsExactly(retained.operationId, retained.operationId)
        assertThat(store.getPendingLiveLocationStops()).isEmpty()
    }

    private class LiveOperationalProbe(
        val delegate: FakeAppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
    ) : AppOperationalStateCoordinator by delegate {
        val cleanup = mutableListOf<io.element.android.security.dataprotection.api.LifecycleMediaRuntimeCleanup>()
        val seals = mutableListOf<String>()
        val reads = mutableListOf<String>()
        var failSeal = false
        private val acknowledged = mutableMapOf<String, io.element.android.security.dataprotection.api.LifecycleOperation>()
        override suspend fun recordClientReceipt(receipt: io.element.android.security.dataprotection.api.LifecycleClientReceipt) =
            delegate.recordClientReceipt(receipt).let { original ->
                original.copy(subjects = original.subjects.copy(installationId = "live-test-installation"),
                    steps = listOf(io.element.android.security.dataprotection.api.LifecycleStep(
                        stepId = "live-test-service-step", component = receipt.component,
                        status = io.element.android.security.dataprotection.api.LifecycleStepStatus.COMPLETE,
                        attemptCount = 1, receiptReference = receipt.receiptReference, updatedAtEpochMillis = receipt.occurredAtEpochMillis)))
                    .also { acknowledged[receipt.operationId] = it }
            }
        override suspend fun operation(operationId: String) = (acknowledged[operationId] ?: delegate.operation(operationId)).let {
            reads += operationId
            it.copy(subjects = it.subjects.copy(installationId = "live-test-installation"))
        }
        override suspend fun start(request: io.element.android.security.dataprotection.api.LifecycleOperationRequest) =
            delegate.start(request).let { it.copy(subjects = it.subjects.copy(installationId = "live-test-installation")) }
        override suspend fun reserveMediaResources(request: io.element.android.security.dataprotection.api.LifecycleMediaReservation) = Unit
        override suspend fun retainMediaRuntimeCleanup(fact: io.element.android.security.dataprotection.api.LifecycleMediaRuntimeCleanup) { cleanup += fact }
        override suspend fun sealMediaResources(operationId: String, outcome: io.element.android.security.dataprotection.api.LifecycleMediaOutcome) {
            check(outcome == io.element.android.security.dataprotection.api.LifecycleMediaOutcome.SUCCEEDED); seals += operationId
            if (failSeal) error("Original seal acknowledgement pending")
        }
    }

    private suspend fun createManager(
        client: FakeMatrixClient = FakeMatrixClient(sessionId = A_SESSION_ID),
        coordinator: LiveLocationSharingCoordinator = createCoordinator(),
        liveLocationStore: LiveLocationStore = createLiveLocationStore(),
        clock: SystemClock = FakeSystemClock(),
        sessionObserver: SessionObserver = FakeSessionObserver(),
        operationalCoordinator: AppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
    ): DefaultActiveLiveLocationShareManager {
        return DefaultActiveLiveLocationShareManager(
            matrixClient = client,
            coordinator = coordinator,
            liveLocationStore = liveLocationStore,
            clock = clock,
            sessionObserver = sessionObserver,
            operationalCoordinator = operationalCoordinator,
        ).apply {
            setup()
        }
    }

    @Test
    fun `paused projection matches original beacon and never starts capture`() = runTest {
        var starts = 0
        val store = createInMemoryLiveLocationStore().apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }
        val updates = MutableSharedFlow<BeaconInfoUpdate>(replay = 1)
        val manager = createManager(
            client = FakeMatrixClient(sessionId = A_SESSION_ID, sessionCoroutineScope = backgroundScope, ownBeaconInfoUpdates = updates),
            coordinator = createCoordinator(startService = { starts++ }),
            liveLocationStore = store,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
        )
        assertThat(manager.pausedShare(A_ROOM_ID, AN_EVENT_ID).first()).isEqualTo(PausedLiveLocationShare.CHECKING)
        assertThat(manager.pausedShare(A_ROOM_ID, EventId("\$different")).first()).isNull()
        updates.emit(BeaconInfoUpdate(A_ROOM_ID, AN_EVENT_ID, isLive = true))
        runCurrent()
        assertThat(manager.pausedShare(A_ROOM_ID, AN_EVENT_ID).first()).isEqualTo(PausedLiveLocationShare.RECOVERABLE)
        assertThat(store.getLiveLocationExpiries()[A_ROOM_ID]).isEqualTo(Instant.fromEpochMilliseconds(10_000L))
        assertThat(starts).isEqualTo(0)
        assertThat(manager.sharingRoomIds.value).isEmpty()
    }

    @Test
    fun `session clear fences a suspended paused projection read`() = runTest {
        val factory = GatedPreferenceDataStoreFactory()
        val store = createLiveLocationStore(preferenceDataStoreFactory = factory).apply {
            setLiveLocationExpiry(A_ROOM_ID, Instant.fromEpochMilliseconds(10_000L))
            setLiveLocationBeaconId(A_ROOM_ID, AN_EVENT_ID.value)
        }
        val observer = FakeSessionObserver()
        val manager = createManager(
            client = FakeMatrixClient(sessionId = A_SESSION_ID, sessionCoroutineScope = backgroundScope),
            liveLocationStore = store, sessionObserver = observer,
            clock = FakeSystemClock(epochMillisResult = 1_000L),
        )
        factory.gateNextRead()
        val projection = async { manager.pausedShare(A_ROOM_ID, AN_EVENT_ID).first() }
        factory.readStarted.await()
        observer.onSessionDeleted(A_SESSION_ID.value)
        factory.releaseRead.complete(Unit)
        runCurrent()
        assertThat(projection.await()).isNull()
        assertThat(manager.pausedShares.value).isEmpty()
    }

    private fun createCoordinator(
        startService: () -> Unit = {},
        stopService: () -> Unit = {},
        nowMillis: () -> Long = { 0L },
    ): LiveLocationSharingCoordinator {
        return LiveLocationSharingCoordinator(
            startService = startService,
            stopService = stopService,
            nowMillis = nowMillis,
        )
    }

    private fun createLiveLocationStore(
        sessionId: io.element.android.libraries.matrix.api.core.SessionId = A_SESSION_ID,
        preferenceDataStoreFactory: PreferenceDataStoreFactory = GatedPreferenceDataStoreFactory(),
    ): LiveLocationStore {
        return LiveLocationStore(
            preferenceDataStoreFactory = preferenceDataStoreFactory,
            sessionId = sessionId,
        )
    }

    private fun createInMemoryLiveLocationStore(
        sessionId: io.element.android.libraries.matrix.api.core.SessionId = A_SESSION_ID,
    ): LiveLocationStore {
        val preferenceDataStoreFactory = object : PreferenceDataStoreFactory {
            override fun create(name: String): DataStore<Preferences> {
                var preferences: Preferences = emptyPreferences()
                return object : DataStore<Preferences> {
                    override val data: Flow<Preferences>
                        get() = flowOf(preferences)

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                        preferences = transform(preferences)
                        return preferences
                    }
                }
            }
        }
        return createLiveLocationStore(
            sessionId = sessionId,
            preferenceDataStoreFactory = preferenceDataStoreFactory,
        )
    }

    private class GatedPreferenceDataStoreFactory : PreferenceDataStoreFactory {
        private var preferences: Preferences = emptyPreferences()
        private var gateEnabled = false
        private var readsBeforeGate = 0
        private var updateGateEnabled = false
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val updateStarted = CompletableDeferred<Unit>()
        val releaseUpdate = CompletableDeferred<Unit>()

        fun gateNextRead() {
            gateReadAfter(completedReads = 0)
        }

        fun gateReadAfter(completedReads: Int) {
            require(completedReads >= 0)
            readsBeforeGate = completedReads
            gateEnabled = true
        }

        fun gateNextUpdate() {
            updateGateEnabled = true
        }

        override fun create(name: String): DataStore<Preferences> = object : DataStore<Preferences> {
            override val data: Flow<Preferences>
                get() = flow {
                    val snapshot = preferences
                    if (gateEnabled && readsBeforeGate > 0) {
                        readsBeforeGate -= 1
                    } else if (gateEnabled) {
                        gateEnabled = false
                        readStarted.complete(Unit)
                        releaseRead.await()
                    }
                    emit(snapshot)
                }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (updateGateEnabled) {
                    updateGateEnabled = false
                    updateStarted.complete(Unit)
                    releaseUpdate.await()
                }
                preferences = transform(preferences)
                return preferences
            }
        }
    }
}
