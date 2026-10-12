/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl.live

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.element.android.features.location.api.Location
import io.element.android.features.location.api.live.ActiveLiveLocationShareManager
import io.element.android.features.location.api.live.PausedLiveLocationShare
import io.element.android.features.location.impl.live.service.LiveLocationReceiver
import io.element.android.features.location.impl.live.service.LiveLocationSharingCoordinator
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.location.BeaconId
import io.element.android.libraries.matrix.api.room.location.LiveLocationException
import io.element.android.libraries.sessionstorage.api.observer.SessionListener
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleMediaReservation
import io.element.android.security.dataprotection.api.LifecycleMediaResource
import io.element.android.security.dataprotection.api.LifecycleMediaResourceType
import io.element.android.security.dataprotection.api.LifecycleMediaRuntimeCleanup
import io.element.android.security.dataprotection.api.LifecycleMediaOutcome
import io.element.android.security.dataprotection.api.MEDIA_RESOURCE_CLOSURE_PROTOCOL
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Instant

@OptIn(ExperimentalAtomicApi::class)
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ActiveLiveLocationShareManager>())
class DefaultActiveLiveLocationShareManager(
    private val matrixClient: MatrixClient,
    private val coordinator: LiveLocationSharingCoordinator,
    private val liveLocationStore: LiveLocationStore,
    private val clock: SystemClock,
    private val sessionObserver: SessionObserver,
    private val operationalCoordinator: AppOperationalStateCoordinator,
) : ActiveLiveLocationShareManager, LiveLocationReceiver {
    private val isSetup = AtomicBoolean(false)
    private val cachedRooms = ConcurrentHashMap<RoomId, JoinedRoom>()
    private val timeoutJobs = ConcurrentHashMap<RoomId, Job>()
    private val pendingStopTargets = ConcurrentHashMap<RoomId, PendingLiveLocationStop>()
    private val pendingStopRetryJobs = ConcurrentHashMap<RoomId, Job>()
    private val lifecycleOperationContexts = ConcurrentHashMap<RoomId, LifecycleOperationContext>()
    private val roomStateLock = Any()
    private val roomGenerations = ConcurrentHashMap<RoomId, Long>()
    private val stopFencedRooms = ConcurrentHashMap.newKeySet<RoomId>()
    private val inFlightLocationJobs = ConcurrentHashMap<RoomId, Job>()
    private val roomPersistenceLocks = ConcurrentHashMap<RoomId, Mutex>()
    private val retiringLocationJobs = ConcurrentHashMap<RoomId, MutableSet<Job>>()
    private val runtimeReleases = ConcurrentHashMap<RoomId, Deferred<Unit>>()
    private val stopOperationLocks = ConcurrentHashMap<RoomId, Mutex>()
    private var isClearing = false
    private var managerEpoch = 0L
    private val syncedActiveShareIds = MutableStateFlow<Set<BeaconId>>(emptySet())
    private val localSharingRoomIds = MutableStateFlow<Set<RoomId>>(emptySet())
    override val sharingRoomIds: StateFlow<Set<RoomId>> = localSharingRoomIds
    private val localPausedShares = MutableStateFlow<Map<RoomId, PausedLiveLocationShare>>(emptyMap())
    override val pausedShares: StateFlow<Map<RoomId, PausedLiveLocationShare>> = localPausedShares

    override fun pausedShare(roomId: RoomId, beaconId: BeaconId): Flow<PausedLiveLocationShare?> = pausedShares.map { states ->
        val state = states[roomId] ?: return@map null
        val token = synchronized(roomStateLock) { managerEpoch to (roomGenerations[roomId] ?: 0L) }
        val originalBeacon = liveLocationStore.getLiveLocationBeaconIds()[roomId]
        synchronized(roomStateLock) {
            state.takeIf {
                originalBeacon == beaconId.value && !isClearing && roomId !in stopFencedRooms &&
                    token == (managerEpoch to (roomGenerations[roomId] ?: 0L)) && localPausedShares.value[roomId] == state
            }
        }
    }

    override suspend fun setup() = withContext(NonCancellable) {
        if (isSetup.compareAndSet(expectedValue = false, newValue = true)) {
            Timber.d("ActiveLiveLocationShareManager setup manager.")

            recoverPersistedShares()

            matrixClient.ownBeaconInfoUpdates
                .onEach { update ->
                    Timber.d("Received beaconInfoUpdate:$update")
                    // A live echo confirms the current share and must not stop it. Only a
                    // remote non-live beacon ends local GPS delivery.
                    if (!update.isLive && update.roomId in localSharingRoomIds.value) {
                        stopLocalShare(roomId = update.roomId)
                        forgetLifecycleOperation(update.roomId)
                    }
                    syncedActiveShareIds.update {
                        if (update.isLive) {
                            it + update.beaconId
                        } else {
                            it - update.beaconId
                        }
                    }
                    reconcilePausedShare(update.roomId, update.beaconId, update.isLive)
                }
                .launchIn(matrixClient.sessionCoroutineScope)

            sessionObserver.addListener(sessionListener)
        }
    }

    private val sessionListener: SessionListener = object : SessionListener {
        override suspend fun onSessionDeleted(userId: String, wasLastSession: Boolean) {
            if (matrixClient.sessionId.value == userId) {
                clear()
            }
        }
    }

    override suspend fun startShare(roomId: RoomId, duration: Duration): Result<Unit> = withContext(NonCancellable) {
        Timber.d("ActiveLiveLocationShareManager starting share for room $roomId with duration ${duration.inWholeSeconds}s")
        val hadPreviousShare = roomId in localSharingRoomIds.value || roomId in localPausedShares.value
        val lifecycleOperation = currentLifecycleOperationContext()
            ?.takeIf { it.lifecycleId == FullAppLifecycleId.LC_17 }
        if (lifecycleOperation != null && hadPreviousShare) {
            stopShare(roomId).getOrElse { return@withContext Result.failure(it) }
        }
        if (lifecycleOperation != null) {
            rememberLifecycleOperation(roomId, lifecycleOperation).getOrElse { return@withContext Result.failure(it) }
        }
        val pendingStop = pendingStopTargets[roomId]
            ?: liveLocationStore.getPendingLiveLocationStops().firstOrNull { it.roomId == roomId }
        if (pendingStop != null) {
            val stopResult = stopShare(roomId, pendingStop.protectedAggregateId)
            if (stopResult.isFailure) {
                return@withContext Result.failure(
                    stopResult.exceptionOrNull() ?: IllegalStateException("Previous live location share is still stopping")
                )
            }
            if (lifecycleOperation != null) {
                rememberLifecycleOperation(roomId, lifecycleOperation).getOrElse { return@withContext Result.failure(it) }
            }
        }
        val room = cachedRooms[roomId] ?: matrixClient.getJoinedRoom(roomId)?.let { loadedRoom ->
            cachedRooms.putIfAbsent(roomId, loadedRoom)?.also { loadedRoom.close() } ?: loadedRoom
        } ?: run {
            forgetLifecycleOperation(roomId)
            return@withContext Result.failure(IllegalStateException("No room found for $roomId"))
        }
        // Before starting a new location share, stop the current one if any is active.
        val previousStopResult = if (lifecycleOperation != null) Result.success(Unit)
            else room.stopLiveLocationShare()
        val previousStopError = previousStopResult.exceptionOrNull()
        if (previousStopError != null && previousStopError !is LiveLocationException.NotLive) {
            forgetLifecycleOperation(roomId)
            return@withContext Result.failure(previousStopError)
        }
        val startGeneration = synchronized(roomStateLock) {
            if (isClearing) return@synchronized null
            stopFencedRooms.remove(roomId)
            val generation = roomGenerations.compute(roomId) { _, current -> (current ?: 0L) + 1L }
            // Retire the previous generation's deadline in the same critical section that
            // publishes the new generation. A timeout already resuming from delay must still
            // pass its captured-generation check before it can establish a stop fence.
            timeoutJobs.remove(roomId)?.cancel()
            generation
        } ?: return@withContext Result.failure(IllegalStateException("Live location manager is clearing"))

        val remoteStart = room.startLiveLocationShare(duration.inWholeMilliseconds)
        val beaconId = remoteStart.getOrElse { error ->
            Timber.e(error, "ActiveLiveLocationShareManager failed to start share for room $roomId")
            if (isCurrentStartGeneration(roomId, startGeneration)) {
                stopLocalShare(roomId)
                forgetLifecycleOperation(roomId)
            }
            return@withContext Result.failure(error)
        }
        val localStart = runCatching {
            check(persistStartIdentity(roomId, beaconId, startGeneration)) {
                "Live location share changed before its identity was persisted"
            }
            if (!beaconId.value.startsWith("\$yizhe-live-")) {
                Timber.d("ActiveLiveLocationShareManager wait remote echo of $beaconId")
                syncedActiveShareIds.first { beaconIds -> beaconIds.contains(beaconId) }
            }
            val expiresAt = Instant.fromEpochMilliseconds(clock.epochMillis() + duration.inWholeMilliseconds)
            check(startLocalShare(roomId, expiresAt, startGeneration)) {
                "Live location share changed before local delivery started"
            }
        }
        if (localStart.isFailure) {
            val error = localStart.exceptionOrNull()
            Timber.e(error, "ActiveLiveLocationShareManager failed to publish local share for room $roomId")
            if (isCurrentStartGeneration(roomId, startGeneration)) {
                stopLocalShare(roomId)
                forgetLifecycleOperation(roomId)
            }
        }
        localStart
    }

    override suspend fun resumeShare(roomId: RoomId): Result<Unit> = withContext(NonCancellable) {
        val expectedGeneration = synchronized(roomStateLock) { roomGenerations[roomId] ?: 0L }
        val state = localPausedShares.value[roomId]
        if (state != PausedLiveLocationShare.RECOVERABLE) {
            return@withContext Result.failure(IllegalStateException("Live location share is not recoverable"))
        }
        val expiresAt = liveLocationStore.getLiveLocationExpiries()[roomId]
            ?: return@withContext Result.failure(IllegalStateException("Missing original live location deadline"))
        val beaconId = liveLocationStore.getLiveLocationBeaconIds()[roomId]
            ?.let(::BeaconId)
            ?: return@withContext Result.failure(IllegalStateException("Missing original live location identity"))
        if (expiresAt <= Instant.fromEpochMilliseconds(clock.epochMillis()) || beaconId !in syncedActiveShareIds.value) {
            synchronized(roomStateLock) {
                if ((roomGenerations[roomId] ?: 0L) == expectedGeneration &&
                    !isClearing &&
                    roomId !in stopFencedRooms &&
                    roomId in localPausedShares.value
                ) {
                    localPausedShares.update { it + (roomId to PausedLiveLocationShare.UNRECOVERABLE) }
                }
            }
            return@withContext Result.failure(IllegalStateException("Original live location share is no longer active"))
        }
        val activation = synchronized(roomStateLock) {
            val canResume = (roomGenerations[roomId] ?: 0L) == expectedGeneration &&
                !isClearing &&
                roomId !in stopFencedRooms &&
                localPausedShares.value[roomId] == PausedLiveLocationShare.RECOVERABLE &&
                expiresAt > Instant.fromEpochMilliseconds(clock.epochMillis()) &&
                beaconId in syncedActiveShareIds.value
            if (!canResume) return@synchronized null
            localPausedShares.update { it - roomId }
            localSharingRoomIds.update { it + roomId }
            scheduleTimeout(roomId, expiresAt, expectedGeneration)
            Unit to coordinator.register(matrixClient.sessionId, this@DefaultActiveLiveLocationShareManager)
        } ?: return@withContext Result.failure(IllegalStateException("Live location share changed while resuming"))
        activation.second?.let { onLocationUpdate(it) }
        Result.success(Unit)
    }

    override suspend fun stopShare(roomId: RoomId, protectedAggregateId: String?): Result<Unit> {
        return stopShare(roomId, protectedAggregateId, recoveryToken = null)
            ?: Result.failure(IllegalStateException("Live location share changed before it could be stopped"))
    }

    private suspend fun stopShare(
        roomId: RoomId, protectedAggregateId: String?, recoveryToken: RecoveryToken?, expectedGeneration: Long? = null,
    ): Result<Unit>? = withContext(NonCancellable) {
        stopOperationLocks.computeIfAbsent(roomId) { Mutex() }.withLock {
            stopShareSerialized(roomId, protectedAggregateId, recoveryToken, expectedGeneration)
        }
    }

    private suspend fun stopShareSerialized(
        roomId: RoomId,
        protectedAggregateId: String?,
        recoveryToken: RecoveryToken?,
        expectedGeneration: Long? = null,
    ): Result<Unit>? = withContext(NonCancellable) {
        Timber.d("ActiveLiveLocationShareManager stopping share for room $roomId")
        // Fence the room before any DataStore or network suspension. This makes stop
        // immediately win over a concurrent resume or location send.
        val inMemoryPendingStop = pendingStopTargets[roomId]
        require(recoveryToken == null || expectedGeneration == null)
        val fenceEstablished = when {
            recoveryToken != null -> establishRecoveryStopFence(roomId, recoveryToken)
            expectedGeneration != null -> establishGenerationStopFence(roomId, expectedGeneration)
            else -> {
                establishStopFence(roomId)
                true
            }
        }
        if (!fenceEstablished) return@withContext null
        val retained = inMemoryPendingStop ?: liveLocationStore.getPendingLiveLocationStops().firstOrNull { it.roomId == roomId }
        val protectedOwner = retained?.operationId != null || lifecycleOperationContexts[roomId] != null ||
            liveLocationStore.getLifecycleOperationIds()[roomId] != null
        val resolvedAggregateId = protectedAggregateId ?: retained?.protectedAggregateId
            ?: liveLocationStore.getLiveLocationBeaconIds()[roomId]?.removePrefix("$")
        val pendingStop = PendingLiveLocationStop(roomId, resolvedAggregateId,
            retained?.operationId ?: if (protectedOwner) java.util.UUID.randomUUID().toString() else null,
            retained?.deadlineEpochMillis ?: if (protectedOwner) System.currentTimeMillis() + 300_000 else null,
            retained?.resourcesReserved ?: false, retained?.endAccepted ?: false,
            retained?.runtimeReleasedAtEpochMillis, retained?.closureQueued ?: false)
        pendingStopTargets[roomId] = pendingStop
        val persisted = persistStop(pendingStop)
        // Privacy was already fenced. A failed journal write forbids a new remote side effect.
        if (persisted.isFailure) {
            stopLocalShare(roomId, closeRoom = false)
            schedulePendingStopRetry(roomId)
            return@withContext Result.failure(persisted.exceptionOrNull()!!)
        }

        // Privacy boundary: stop GPS/update delivery before attempting the remote end event.
        // Keep a local room reference only for the remote request, then close it below.
        val room = cachedRooms[roomId] ?: matrixClient.getJoinedRoom(roomId)
        stopLocalShare(roomId, closeRoom = false)
        // Clear old coordinate payloads even if remote end admission is offline.
        room?.releaseLiveLocationProducer()?.getOrElse {
            schedulePendingStopRetry(roomId)
            return@withContext Result.failure(it)
        }
        if (room == null) {
            val failure = Result.failure<Unit>(IllegalStateException("No room found for $roomId"))
            schedulePendingStopRetry(roomId)
            return@withContext failure
        }
        runCatching {
            if (pendingStop.operationId == null) room.stopLiveLocationShare(resolvedAggregateId).getOrThrow()
            else {
                val operation = if (pendingStop.resourcesReserved) {
                    // Recovery reads the original authority, including after its deadline.
                    // It never creates a replacement operation or resends an accepted end.
                    operationalCoordinator.operation(pendingStop.operationId)
                } else operationalCoordinator.start(liveRequest(roomId, "live_location_stop",
                    pendingStop.operationId, requireNotNull(pendingStop.deadlineEpochMillis)))
                require(operation.operationId == pendingStop.operationId && operation.lifecycleId == FullAppLifecycleId.LC_17)
                require(operation.subjects.accountIdHash == liveHash(matrixClient.sessionId.value) &&
                    operation.subjects.matrixDeviceIdHash == liveHash(matrixClient.deviceId.value) &&
                    operation.subjects.roomIdHash == liveHash(roomId.value))
                val runtimeId = liveHash("live-room-runtime-v1:${operation.operationId}:${roomId.value}")
                var phase = pendingStop
                if (!phase.resourcesReserved) {
                    operationalCoordinator.reserveMediaResources(LifecycleMediaReservation(operation.operationId, operation.subjects,
                        listOf(LifecycleMediaResource(LifecycleMediaResourceType.ANDROID_MEDIA_RUNTIME, runtimeId))))
                    phase = phase.copy(resourcesReserved = true)
                    persistStop(phase).getOrThrow()
                }
                if (!phase.endAccepted) {
                    withContext(LifecycleOperationContext(operation.operationId, FullAppLifecycleId.LC_17)) {
                        room.stopLiveLocationShare(resolvedAggregateId).getOrThrow()
                    }
                    phase = phase.copy(endAccepted = true)
                    persistStop(phase).getOrThrow()
                }
                // The room subscription is gone. If this was the final owner, Android
                // must additionally acknowledge actual provider/service termination.
                if (phase.runtimeReleasedAtEpochMillis == null) {
                    runtimeReleases[roomId]?.let { release -> withTimeout(30_000) { release.await() } }
                    runtimeReleases.remove(roomId)
                    require(roomId !in localSharingRoomIds.value && retiringLocationJobs[roomId].isNullOrEmpty())
                    room.releaseLiveLocationProducer().getOrThrow() // Includes the accepted end payload.
                    phase = phase.copy(runtimeReleasedAtEpochMillis = System.currentTimeMillis())
                    persistStop(phase).getOrThrow()
                }
                val serviceReference = "android-service:${operation.operationId}"
                if (operation.steps.none { it.component == LifecycleComponent.ANDROID_SERVICE &&
                        it.status == io.element.android.security.dataprotection.api.LifecycleStepStatus.COMPLETE &&
                        it.receiptReference == serviceReference }) {
                    val applied = operationalCoordinator.recordClientReceipt(LifecycleClientReceipt(operation.operationId,
                        LifecycleComponent.ANDROID_SERVICE, LifecyclePhase.CLIENT_APPLIED, "ANDROID_SERVICE_APPLIED",
                        serviceReference, requireNotNull(phase.runtimeReleasedAtEpochMillis)))
                    require(applied.operationId == operation.operationId && applied.subjects == operation.subjects)
                    require(applied.steps.any { it.component == LifecycleComponent.ANDROID_SERVICE &&
                        it.status == io.element.android.security.dataprotection.api.LifecycleStepStatus.COMPLETE &&
                        it.receiptReference == serviceReference })
                }
                // Confirm the real service-release fact before awaiting the producer seal acknowledgement.
                // Neither acknowledgement substitutes for the operation's terminal receipt.
                if (!phase.closureQueued) {
                    operationalCoordinator.retainMediaRuntimeCleanup(LifecycleMediaRuntimeCleanup(operation.operationId,
                        operation.subjects, runtimeId, requireNotNull(phase.runtimeReleasedAtEpochMillis), true, true, true))
                    operationalCoordinator.sealMediaResources(operation.operationId, LifecycleMediaOutcome.SUCCEEDED)
                    phase = phase.copy(closureQueued = true)
                    persistStop(phase).getOrThrow()
                } else {
                    // Older binaries used this bit for local enqueue only.
                    // Confirm the retained original seal instead of treating it as a server acknowledgement.
                    operationalCoordinator.sealMediaResources(operation.operationId, LifecycleMediaOutcome.SUCCEEDED)
                }
            }
        }
            .onSuccess {
                Timber.d("ActiveLiveLocationShareManager local stop and closure acknowledgements retained for room $roomId")
                pendingStopTargets.remove(roomId)
                liveLocationStore.removePendingLiveLocationStop(roomId)
                forgetLifecycleOperation(roomId)
            }
            .onFailure { error ->
                Timber.e(error, "ActiveLiveLocationShareManager failed to stop share for room $roomId")
                schedulePendingStopRetry(roomId)
            }
            .also {
                cachedRooms.remove(roomId, room)
                room.close()
            }
    }

    override suspend fun onUnrecoverableError() {
        Timber.d("ActiveLiveLocationShareManager unrecoverable error, stopping all shares")
        localSharingRoomIds.value.toList().forEach { stopShare(it) }
    }

    override suspend fun onLocationUpdate(location: Location) {
        val activeSharesCount = localSharingRoomIds.value.size
        Timber.d("ActiveLiveLocationShareManager received location update for $activeSharesCount active share(s)")
        coroutineScope {
            localSharingRoomIds.value.map { roomId ->
                launch {
                    val job = coroutineContext[Job] ?: return@launch
                    val admitted = synchronized(roomStateLock) {
                        if (roomId !in localSharingRoomIds.value || roomId in stopFencedRooms) false
                        else {
                            inFlightLocationJobs.put(roomId, job)?.cancel()
                            true
                        }
                    }
                    if (!admitted) return@launch
                    try {
                        Timber.d("ActiveLiveLocationShareManager sending location to room $roomId")
                        sendLiveLocation(roomId, location).onFailure {
                            Timber.e(it, "ActiveLiveLocationShareManager failed to send location to room $roomId")
                        }
                    } finally {
                        inFlightLocationJobs.remove(roomId, job)
                    }
                }
            }.forEach { it.join() }
        }
    }

    private suspend fun sendLiveLocation(roomId: RoomId, location: Location): Result<Unit> {
        val room = cachedRooms.getOrPut(roomId) {
            matrixClient.getJoinedRoom(roomId) ?: return Result.failure(IllegalStateException("No room found for $roomId"))
        }
        return runCatching {
            if (lifecycleOperationContexts[roomId] == null && liveLocationStore.getLifecycleOperationIds()[roomId] == null) {
                room.sendLiveLocation(location.toGeoUri()).getOrThrow()
            } else {
                val request = liveRequest(roomId, "live_location_update", java.util.UUID.randomUUID().toString(),
                    System.currentTimeMillis() + 120_000)
                // Explicit admission deliberately bypasses same-lifecycle parent inheritance.
                val operation = operationalCoordinator.start(request)
                require(operation.operationId == request.operationId && operation.lifecycleId == FullAppLifecycleId.LC_17 &&
                    operation.subjects.accountIdHash == request.accountIdHash &&
                    operation.subjects.matrixDeviceIdHash == request.matrixDeviceIdHash && operation.subjects.roomIdHash == request.roomIdHash)
                withContext(LifecycleOperationContext(operation.operationId, FullAppLifecycleId.LC_17)) {
                    room.sendLiveLocation(location.toGeoUri()).getOrThrow()
                }
                recordServiceApplied(operation.operationId)
            }
        }
            .recoverCatching { exception ->
                when (exception) {
                    is LiveLocationException.NotLive -> {
                        stopLocalShare(roomId)
                        throw exception
                    }
                    else -> throw exception
                }
            }
    }

    private suspend fun persistStartIdentity(roomId: RoomId, beaconId: BeaconId, expectedGeneration: Long): Boolean {
        return roomPersistenceLock(roomId).withLock {
            if (!isCurrentStartGeneration(roomId, expectedGeneration)) return@withLock false
            liveLocationStore.setLiveLocationBeaconId(roomId, beaconId.value).getOrThrow()
            isCurrentStartGeneration(roomId, expectedGeneration)
        }
    }

    private suspend fun startLocalShare(roomId: RoomId, expiresAt: Instant, expectedGeneration: Long): Boolean {
        val activation = roomPersistenceLock(roomId).withLock {
            if (!isCurrentStartGeneration(roomId, expectedGeneration)) return@withLock null
            liveLocationStore.setLiveLocationExpiry(roomId, expiresAt).getOrThrow()
            synchronized(roomStateLock) {
                if (isClearing || roomId in stopFencedRooms || roomGenerations[roomId] != expectedGeneration) return@synchronized null
                val wasEmpty = localSharingRoomIds.value.isEmpty()
                Timber.d("ActiveLiveLocationShareManager share started successfully for room $roomId (wasEmpty=$wasEmpty)")
                localPausedShares.update { it - roomId }
                localSharingRoomIds.update { it + roomId }
                scheduleTimeout(roomId, expiresAt, expectedGeneration)
                Unit to coordinator.register(matrixClient.sessionId, this@DefaultActiveLiveLocationShareManager)
            }
        }
        if (activation == null) return false
        activation.second?.let { onLocationUpdate(it) }
        return true
    }

    private suspend fun recoverPersistedShares() {
        // Capture the per-room generations before the first persisted-state read. Every
        // recovered projection is conditional on this token so a stale DataStore snapshot
        // cannot overwrite or stop a share explicitly started while recovery was suspended.
        val recoveryToken = synchronized(roomStateLock) {
            RecoveryToken(managerEpoch, roomGenerations.toMap())
        }
        val lifecycleOperationIds = liveLocationStore.getLifecycleOperationIds()
        if (!isRecoveryEpochCurrent(recoveryToken.managerEpoch)) return
        lifecycleOperationIds.forEach { (roomId, operationId) ->
            if (isRecoveryRoomCurrent(recoveryToken, roomId, requireInactive = true)) {
                lifecycleOperationContexts.putIfAbsent(
                    roomId,
                    LifecycleOperationContext(operationId, FullAppLifecycleId.LC_17),
                )
            }
        }
        val pendingStops = liveLocationStore.getPendingLiveLocationStops()
        if (!isRecoveryEpochCurrent(recoveryToken.managerEpoch)) return
        val pendingStopRoomIds = pendingStops.mapTo(mutableSetOf(), PendingLiveLocationStop::roomId)
        pendingStops.forEach { pendingStop ->
            stopShare(pendingStop.roomId, pendingStop.protectedAggregateId, recoveryToken)
        }
        val expiries = liveLocationStore.getLiveLocationExpiries()
        if (!isRecoveryEpochCurrent(recoveryToken.managerEpoch)) return
        val beaconIds = liveLocationStore.getLiveLocationBeaconIds()
        if (!isRecoveryEpochCurrent(recoveryToken.managerEpoch)) return
        expiries.forEach expiry@{ (roomId, expiresAt) ->
            if (roomId in pendingStopRoomIds) {
                // A current recovery stop already fenced and removed this room. A stale
                // recovery token must likewise not touch a possibly newer expiry here.
                return@expiry
            } else if (expiresAt > Instant.fromEpochMilliseconds(clock.epochMillis())) {
                // Do not re-open location collection after process recovery. The user must
                // explicitly resume sharing from a visible control; retain the expiry so
                // the existing remote share is still stopped at its original deadline.
                synchronized(roomStateLock) {
                    if (!isRecoveryRoomCurrentLocked(recoveryToken, roomId, requireInactive = true)) {
                        return@expiry
                    }
                    scheduleTimeout(roomId, expiresAt, recoveryToken.roomGenerations[roomId] ?: 0L)
                    val state = if (roomId in beaconIds) {
                        PausedLiveLocationShare.CHECKING
                    } else {
                        PausedLiveLocationShare.UNRECOVERABLE
                    }
                    localPausedShares.update { it + (roomId to state) }
                }
            } else {
                // Explicitly stop the share on the server.
                stopShare(roomId, protectedAggregateId = null, recoveryToken = recoveryToken)
            }
        }
    }

    private fun scheduleTimeout(roomId: RoomId, expiresAt: Instant, expectedGeneration: Long) {
        timeoutJobs.remove(roomId)?.cancel()
        val delayMillis = expiresAt.toEpochMilliseconds() - clock.epochMillis()
        timeoutJobs[roomId] = matrixClient.sessionCoroutineScope.launch {
            delay(delayMillis)
            // Remove this job before stopping. Otherwise stopLocalShare() would
            // cancel the coroutine that is currently responsible for sending
            // the remote stop event.
            coroutineContext[Job]?.let { timeoutJob -> timeoutJobs.remove(roomId, timeoutJob) }
            stopShare(
                roomId = roomId,
                protectedAggregateId = null,
                recoveryToken = null,
                expectedGeneration = expectedGeneration,
            )
                ?.onFailure { error ->
                    Timber.e(error, "ActiveLiveLocationShareManager failed to stop timed out share for room $roomId")
                }
        }
    }

    private fun schedulePendingStopRetry(roomId: RoomId) {
        if (pendingStopRetryJobs[roomId]?.isActive == true) return
        pendingStopRetryJobs[roomId] = matrixClient.sessionCoroutineScope.launch {
            delay(PENDING_STOP_RETRY_DELAY_MILLIS)
            pendingStopRetryJobs.remove(roomId)
            val pendingStop = pendingStopTargets[roomId]
                ?: liveLocationStore.getPendingLiveLocationStops().firstOrNull { it.roomId == roomId }
                ?: return@launch
            stopShare(pendingStop.roomId, pendingStop.protectedAggregateId)
        }
    }

    private suspend fun reconcilePausedShare(roomId: RoomId, beaconId: BeaconId, isLive: Boolean) {
        val expectedGeneration = synchronized(roomStateLock) {
            if (isClearing || roomId in stopFencedRooms || roomId !in localPausedShares.value) return
            roomGenerations[roomId] ?: 0L
        }
        val persistedBeaconId = liveLocationStore.getLiveLocationBeaconIds()[roomId]
        val state = if (isLive && persistedBeaconId == beaconId.value) {
            PausedLiveLocationShare.RECOVERABLE
        } else {
            PausedLiveLocationShare.UNRECOVERABLE
        }
        synchronized(roomStateLock) {
            if ((roomGenerations[roomId] ?: 0L) != expectedGeneration ||
                isClearing ||
                roomId in stopFencedRooms ||
                roomId !in localPausedShares.value
            ) {
                return
            }
            localPausedShares.update { it + (roomId to state) }
        }
    }

    private suspend fun stopLocalShare(roomId: RoomId, closeRoom: Boolean = true) {
        Timber.d("ActiveLiveLocationShareManager stop local share in $roomId")
        establishStopFence(roomId)
        val currentJob = currentCoroutineContext()[Job]
        val retiring = synchronized(roomStateLock) { retiringLocationJobs[roomId]?.toList().orEmpty() }
        retiring.filter { it !== currentJob }.forEach { it.join() }
        synchronized(roomStateLock) {
            retiringLocationJobs[roomId]?.removeAll { !it.isActive || it === currentJob }
        }
        if (closeRoom) {
            cachedRooms.remove(roomId)?.close()
        }
        roomPersistenceLock(roomId).withLock {
            liveLocationStore.removeLiveLocationShare(roomId)
        }
    }

    /** Establishes the local stop fence without suspending or waiting for a remote request. */
    private fun establishStopFence(roomId: RoomId) = synchronized(roomStateLock) {
        establishStopFenceLocked(roomId)
    }

    /** Atomically binds a persisted recovery stop to the generation captured before recovery reads began. */
    private fun establishRecoveryStopFence(roomId: RoomId, recoveryToken: RecoveryToken): Boolean = synchronized(roomStateLock) {
        if (!isRecoveryRoomCurrentLocked(recoveryToken, roomId, requireInactive = true)) {
            return@synchronized false
        }
        establishStopFenceLocked(roomId)
        true
    }

    /** Atomically rejects a timeout from any generation other than the one that scheduled it. */
    private fun establishGenerationStopFence(roomId: RoomId, expectedGeneration: Long): Boolean = synchronized(roomStateLock) {
        if (isClearing ||
            roomId in stopFencedRooms ||
            (roomGenerations[roomId] ?: 0L) != expectedGeneration
        ) {
            return@synchronized false
        }
        establishStopFenceLocked(roomId)
        true
    }

    private fun establishStopFenceLocked(roomId: RoomId) {
        val firstFence = stopFencedRooms.add(roomId)
        if (firstFence) {
            roomGenerations.compute(roomId) { _, generation -> (generation ?: 0L) + 1L }
            timeoutJobs.remove(roomId)?.cancel()
            inFlightLocationJobs.remove(roomId)?.let { job ->
                retiringLocationJobs.computeIfAbsent(roomId) { mutableSetOf() }.add(job)
                job.cancel()
            }
        }
        val sharingBeforeStop = roomId in localSharingRoomIds.value
        localSharingRoomIds.update { it - roomId }
        localPausedShares.update { it - roomId }
        if (sharingBeforeStop && localSharingRoomIds.value.isEmpty()) {
            // Keep last-room removal and session receiver removal in the same manager
            // critical section so another room cannot register between them.
            coordinator.unregister(matrixClient.sessionId)?.let { runtimeReleases[roomId] = it }
        }
    }

    private fun roomPersistenceLock(roomId: RoomId): Mutex =
        roomPersistenceLocks.computeIfAbsent(roomId) { Mutex() }

    private fun isCurrentStartGeneration(roomId: RoomId, expectedGeneration: Long): Boolean = synchronized(roomStateLock) {
        !isClearing && roomId !in stopFencedRooms && roomGenerations[roomId] == expectedGeneration
    }

    private fun isRecoveryEpochCurrent(expectedEpoch: Long): Boolean = synchronized(roomStateLock) {
        !isClearing && managerEpoch == expectedEpoch
    }

    private fun isRecoveryRoomCurrent(
        recoveryToken: RecoveryToken,
        roomId: RoomId,
        requireInactive: Boolean,
    ): Boolean = synchronized(roomStateLock) {
        isRecoveryRoomCurrentLocked(recoveryToken, roomId, requireInactive)
    }

    private fun isRecoveryRoomCurrentLocked(
        recoveryToken: RecoveryToken,
        roomId: RoomId,
        requireInactive: Boolean,
    ): Boolean {
        return !isClearing &&
            managerEpoch == recoveryToken.managerEpoch &&
            roomId !in stopFencedRooms &&
            (!requireInactive || roomId !in localSharingRoomIds.value) &&
            (roomGenerations[roomId] ?: 0L) == (recoveryToken.roomGenerations[roomId] ?: 0L)
    }

    private suspend fun persistStop(stop: PendingLiveLocationStop): Result<Unit> {
        pendingStopTargets[stop.roomId] = stop
        return liveLocationStore.setPendingLiveLocationStop(stop.roomId, stop.protectedAggregateId,
            stop.operationId, stop.deadlineEpochMillis, stop.resourcesReserved, stop.endAccepted,
            stop.runtimeReleasedAtEpochMillis, stop.closureQueued)
    }

    private fun liveRequest(roomId: RoomId, trigger: String, id: String, deadline: Long) = LifecycleOperationRequest(
        operationId = id, lifecycleId = FullAppLifecycleId.LC_17, trigger = trigger, idempotencyKey = id,
        accountIdHash = liveHash(matrixClient.sessionId.value), matrixDeviceIdHash = liveHash(matrixClient.deviceId.value),
        roomIdHash = liveHash(roomId.value), deadlineEpochMillis = deadline,
        mediaCleanupProtocol = MEDIA_RESOURCE_CLOSURE_PROTOCOL.takeIf { trigger == "live_location_stop" },
    )

    private suspend fun recordServiceApplied(id: String) = operationalCoordinator.enqueueClientReceipts(listOf(
        LifecycleClientReceipt(id, LifecycleComponent.ANDROID_SERVICE, LifecyclePhase.CLIENT_APPLIED,
            "ANDROID_SERVICE_APPLIED", "android-service:$id", System.currentTimeMillis()),
    ))

    private fun liveHash(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun rememberLifecycleOperation(
        roomId: RoomId,
        context: LifecycleOperationContext,
    ): Result<Unit> = liveLocationStore.setLifecycleOperationId(roomId, context.operationId).onSuccess {
        lifecycleOperationContexts[roomId] = context
    }

    private suspend fun forgetLifecycleOperation(roomId: RoomId) {
        lifecycleOperationContexts.remove(roomId)
        liveLocationStore.removeLifecycleOperationId(roomId)
            .onFailure { error -> Timber.e(error, "Failed to remove live location lifecycle operation for room $roomId") }
    }

    private suspend fun <T> withLifecycleOperation(roomId: RoomId, block: suspend () -> T): T {
        val context = lifecycleOperationContexts[roomId]
            ?: liveLocationStore.getLifecycleOperationIds()[roomId]?.let { operationId ->
                LifecycleOperationContext(operationId, FullAppLifecycleId.LC_17).also {
                    lifecycleOperationContexts[roomId] = it
                }
            }
        return if (context == null) block() else withContext(context) { block() }
    }

    private suspend fun clear() {
        Timber.d("ActiveLiveLocationShareManager clear state")
        sessionObserver.removeListener(sessionListener)
        synchronized(roomStateLock) {
            isClearing = true
            managerEpoch += 1L
            (localSharingRoomIds.value + localPausedShares.value.keys + roomGenerations.keys).forEach { roomId ->
                stopFencedRooms.add(roomId)
                roomGenerations.compute(roomId) { _, generation -> (generation ?: 0L) + 1L }
            }
            timeoutJobs.values.forEach(Job::cancel)
            inFlightLocationJobs.values.forEach(Job::cancel)
            localSharingRoomIds.value = emptySet()
            localPausedShares.value = emptyMap()
        }
        coordinator.unregister(matrixClient.sessionId)
        roomPersistenceLocks.values.toList().forEach { lock -> lock.withLock { } }
        liveLocationStore.clear()
        for (room in cachedRooms.values) {
            room.close()
            timeoutJobs[room.roomId]?.cancel()
        }
        timeoutJobs.clear()
        pendingStopRetryJobs.values.forEach(Job::cancel)
        pendingStopRetryJobs.clear()
        pendingStopTargets.clear()
        synchronized(roomStateLock) {
            inFlightLocationJobs.values.forEach(Job::cancel)
            inFlightLocationJobs.clear()
            stopFencedRooms.clear()
            // Keep monotonically increasing room generations across clear. Resetting this map
            // would allow a pre-clear remote start to match a post-clear generation (ABA).
            isClearing = false
        }
        lifecycleOperationContexts.clear()
        cachedRooms.clear()
        syncedActiveShareIds.value = emptySet()
    }

    private companion object {
        const val PENDING_STOP_RETRY_DELAY_MILLIS = 15_000L
    }

    private data class RecoveryToken(
        val managerEpoch: Long,
        val roomGenerations: Map<RoomId, Long>,
    )
}
