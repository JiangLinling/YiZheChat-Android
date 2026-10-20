/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.appconfig.ElementCallConfig
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.features.call.api.CallData
import io.element.android.features.call.api.CurrentCall
import io.element.android.features.call.impl.notifications.CallNotificationData
import io.element.android.features.call.impl.notifications.RingingCallNotificationCreator
import io.element.android.features.call.impl.services.IncomingCallForegroundServiceController
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.ui.media.ImageLoaderHolder
import io.element.android.libraries.push.api.notifications.ForegroundServiceType
import io.element.android.libraries.push.api.notifications.ForegroundNotificationHost
import io.element.android.libraries.push.api.notifications.NotificationIdProvider
import io.element.android.libraries.push.api.notifications.OnMissedCallNotificationHandler
import io.element.android.services.appnavstate.api.AppForegroundStateService
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import io.element.android.security.dataprotection.api.perform
import timber.log.Timber
import kotlin.math.min

/**
 * Manages the active call state.
 */
interface ActiveCallManager {
    /** Resolve the original invitation before the room's Join action creates a local attempt. */
    suspend fun resolveRoomEntry(callData: CallData): CallData = IntentProvider.forRoomEntry(callData, activeCall.value?.callData)
    suspend fun checkpointCall(callData: CallData, operationId: String) = Unit
    suspend fun finalizeReleasedCall(callData: CallData, cleanupSucceeded: Boolean, noInvitationConfirmed: Boolean = false): Boolean = false
    /**
     * The active call state flow, which will be updated when the active call changes.
     */
    val activeCall: StateFlow<ActiveCall?>

    /**
     * Registers an incoming call if there isn't an existing active call and posts a [CallState.Ringing] notification.
     * @param notificationData The data for the incoming call notification.
     */
    suspend fun registerIncomingCall(notificationData: CallNotificationData)

    /**
     * Called to hang up the active call. It will hang up the call and remove any existing UI and the active call.
     * @param callData The data about the call.
     * @param notificationData The data for the incoming call notification.
     */
    suspend fun hangUpCall(
        callData: CallData,
        notificationData: CallNotificationData? = null,
    )

    /**
     * Releases local call resources immediately and returns the authority-scoped completion.
     * The returned action must run inside the existing LC-20 operation. If authority is
     * unavailable, local media/UI still stop while no lifecycle receipt is fabricated.
     */
    suspend fun prepareHangUpCall(
        callData: CallData,
        notificationData: CallNotificationData? = null,
    ): (suspend () -> Unit)?

    /**
     * Called after the user joined a call. It will remove any existing UI and set the call state as [CallState.InCall].
     *
     * @param callData The data about the call.
     */
    suspend fun joinedCall(callData: CallData)

    /** Called only after the embedded call reports connected, protected, decoded media. */
    suspend fun validateIncomingCall(callData: CallData, invitationRoom: io.element.android.libraries.matrix.api.room.JoinedRoom? = null)
    suspend fun bindOutgoingInvitation(callData: CallData, eventId: EventId)

    suspend fun mediaConnected(callData: CallData)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = dev.zacsweers.metro.binding<ActiveCallManager>())
@ContributesBinding(AppScope::class, binding = dev.zacsweers.metro.binding<io.element.android.features.call.api.CallRecoveryService>())
class DefaultActiveCallManager(
    @ApplicationContext private val context: Context,
    @AppCoroutineScope
    private val coroutineScope: CoroutineScope,
    private val onMissedCallNotificationHandler: OnMissedCallNotificationHandler,
    private val ringingCallNotificationCreator: RingingCallNotificationCreator,
    private val notificationManagerCompat: NotificationManagerCompat,
    private val matrixClientProvider: MatrixClientProvider,
    private val defaultCurrentCallService: DefaultCurrentCallService,
    private val appForegroundStateService: AppForegroundStateService,
    private val imageLoaderHolder: ImageLoaderHolder,
    private val systemClock: SystemClock,
    private val incomingCallRingtonePlayer: IncomingCallRingtonePlayer,
    private val incomingCallForegroundServiceController: IncomingCallForegroundServiceController,
    private val foregroundNotificationHost: ForegroundNotificationHost,
    private val dataProtectionPolicyProvider: io.element.android.security.dataprotection.api.DataProtectionPolicyProvider,
    private val appOperationalStateCoordinator: io.element.android.security.dataprotection.api.AppOperationalStateCoordinator,
    private val finalizationRecovery: io.element.android.features.call.impl.security.CallFinalizationRecovery,
    private val activeRoomsHolder: io.element.android.services.appnavstate.api.ActiveRoomsHolder,
    private val nextCalls: Boolean = NextDeploymentConfig.isNextDeployment,
) : ActiveCallManager, io.element.android.features.call.api.CallRecoveryService {
    private val tag = "ActiveCallManager"
    private var timedOutCallJob: Job? = null
    private var foregroundInvitation: Triple<SessionId, io.element.android.libraries.matrix.api.core.RoomId, EventId>? = null

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal val activeWakeLock: PowerManager.WakeLock? = context.getSystemService<PowerManager>()
        ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PARTIAL_WAKE_LOCK) }
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:IncomingCallWakeLock")

    override val activeCall = MutableStateFlow<ActiveCall?>(null)

    private val mutex = Mutex()
    private var callTracking: CallTracking? = null
    private val outgoingInvitation = MutableStateFlow<Pair<CallData, EventId>?>(null)
    private val retiredInvitations = mutableSetOf<Triple<SessionId, io.element.android.libraries.matrix.api.core.RoomId, EventId>>()

    override fun start() {
        if (!nextCalls) return
        finalizationRecovery.start {
            mutex.withLock {
                if (activeCall.value != null) false else {
                    cancelIncomingCallNotification()
                    incomingCallRingtonePlayer.stop()
                    if (activeWakeLock?.isHeld == true) activeWakeLock.release()
                    defaultCurrentCallService.onCallEnded()
                    true
                }
            }
        }
    }

    override suspend fun checkpointCall(callData: CallData, operationId: String) {
        if (nextCalls) { start(); finalizationRecovery.checkpoint(callData, operationId) }
    }

    override suspend fun finalizeReleasedCall(callData: CallData, cleanupSucceeded: Boolean, noInvitationConfirmed: Boolean): Boolean =
        nextCalls && finalizationRecovery.released(callData, cleanupSucceeded, noInvitationConfirmed)

    init {
        if (nextCalls) {
            observeInvitationLifecycle()
            observeInvitationDeparture()
            observeOutgoingDecline()
        } else observeRingingCall()
        // Matrix membership is signalling only. Next uses the authenticated widget media report.
        if (!nextCalls) observeConnectedCall()
        observeCurrentCall()
        observeBackgroundedRingingCall()
    }

    /** If the app leaves the foreground while our in-app banner is ringing, hand ownership
     * back to the normal Android notification service immediately. */
    private fun observeBackgroundedRingingCall() {
        appForegroundStateService.isInForeground
            .drop(1)
            .filter { !it }
            .onEach {
                val data = (activeCall.value?.callState as? CallState.Ringing)?.takeUnless { it.isAnswering }?.notificationData ?: return@onEach
                showIncomingCallNotification(data)
            }
            .launchIn(coroutineScope)
    }

    override suspend fun registerIncomingCall(notificationData: CallNotificationData) {
        val info = if (nextCalls) matrixClientProvider.getOrNull(notificationData.sessionId)
            ?.getJoinedRoom(notificationData.roomId)?.roomInfoFlow?.value else null
        val resolved = if (info?.isDm == true && !info.name.isNullOrBlank() &&
            (notificationData.senderName.isNullOrBlank() || notificationData.senderName == notificationData.senderId.value)) {
            notificationData.copy(senderName = info.name, avatarUrl = notificationData.avatarUrl ?: info.avatarUrl)
        } else notificationData
        registerResolvedIncomingCall(resolved.copy(isGroupCall = info?.isDm == false))
    }

    override suspend fun resolveRoomEntry(callData: CallData): CallData {
        if (!nextCalls || callData.notificationEventId != null || callData.attemptId != null) return callData
        activeCall.value?.let { current ->
            val target = IntentProvider.forRoomEntry(callData, current.callData)
            if (target == callData || current.remoteEnded) throw IncomingCallUnavailableException(true)
            return target
        }
        val visibleRoom = activeRoomsHolder.getActiveRoomMatching(callData.sessionId, callData.roomId)
        // This is only a Join hint. The exact decrypted invitation below owns admission.
        if (visibleRoom?.info()?.hasRoomCall != true) return callData
        if (!visibleRoom.info().isDm) return callData
        val client = matrixClientProvider.getOrRestore(callData.sessionId).getOrThrow()
        val room = client.getJoinedRoom(callData.roomId) ?: throw IncomingCallUnavailableException(false)
        try {
            val info = room.info()
            if (!info.isDm || info.joinedMembersCount != 2L ||
                info.currentUserMembership != io.element.android.libraries.matrix.api.room.CurrentUserMembership.JOINED
            ) throw IncomingCallUnavailableException(false)
            val notification = withTimeoutOrNull(5_000) {
                room.liveTimeline.timelineItems.map { items ->
                    // Select the latest invitation before checking validity: a finished newer
                    // call must never cause an older ringing generation to be revived.
                    val event = items.filterIsInstance<MatrixTimelineItem.Event>().map { it.event }
                        .filter { !it.isOwn && it.isRemote && it.sender != callData.sessionId && it.content is CallNotifyContent }
                        .maxByOrNull { it.timestamp } ?: return@map null
                    val id = event.eventId ?: return@map null
                    val content = event.content as CallNotifyContent
                    val now = systemClock.epochMillis()
                    if (content.isFinishedInvitation(callData.sessionId) || event.timestamp > now || now - event.timestamp >= 90_000) {
                        throw IncomingCallUnavailableException(true)
                    }
                    val authority = room.loadRtcNotification(id).getOrNull() ?: throw IncomingCallUnavailableException(false)
                    val protocol = authority.content
                    if (authority.eventId != id || authority.timestamp != event.timestamp || protocol.senderId != event.sender ||
                        protocol.type != io.element.android.libraries.matrix.api.notification.RtcNotificationType.RING ||
                        protocol.callIntent != content.callIntent || protocol.expirationTimestampMillis <= now ||
                        protocol.expirationTimestampMillis - event.timestamp !in 1..90_000
                    ) throw IncomingCallUnavailableException(true)
                    val member = room.getUpdatedMember(event.sender).getOrNull() ?: throw IncomingCallUnavailableException(false)
                    if (member.membership != io.element.android.libraries.matrix.api.room.RoomMembershipState.JOIN || member.isIgnored) {
                        throw IncomingCallUnavailableException(true)
                    }
                    CallNotificationData(
                        sessionId = callData.sessionId, roomId = callData.roomId, eventId = id, senderId = event.sender,
                        roomName = info.name, senderName = member.displayName, avatarUrl = member.avatarUrl,
                        notificationChannelId = "", timestamp = event.timestamp, textContent = null,
                        expirationTimestamp = protocol.expirationTimestampMillis, audioOnly = protocol.callIntent == CallIntent.AUDIO,
                    )
                }.filterNotNull().first()
            } ?: throw IncomingCallUnavailableException(false)
            // Explicit Join does not ring the user's own phone. It still uses the normal
            // invitation validation, expiry, generation, cancellation and cleanup machinery.
            registerResolvedIncomingCall(notification, userInitiated = true)
            val current = activeCall.value ?: throw IncomingCallUnavailableException(true)
            if (current.remoteEnded || current.callData.sessionId != callData.sessionId || current.callData.roomId != callData.roomId ||
                current.callData.notificationEventId != notification.eventId.value
            ) throw IncomingCallUnavailableException(true)
            return current.callData
        } finally {
            room.close()
        }
    }

    private suspend fun registerResolvedIncomingCall(notificationData: CallNotificationData, userInitiated: Boolean = false) {
        if (nextCalls) {
            // Push is a wakeup, not permission to resurrect a finished invitation.
            val content = invitationContent(notificationData.sessionId, notificationData.roomId, notificationData.eventId)
            if (content == null || content.isFinishedInvitation(notificationData.sessionId)) return
            val old = (activeCall.value?.callState as? CallState.Ringing)?.notificationData
            if (old != null && old.eventId != notificationData.eventId) {
                val oldContent = invitationContent(old.sessionId, old.roomId, old.eventId)
                if (oldContent?.isFinishedInvitation(old.sessionId) == true || old.expirationTimestamp in 1..systemClock.epochMillis() ||
                    (old.sessionId == notificationData.sessionId && old.roomId == notificationData.roomId &&
                        old.senderId == notificationData.senderId && notificationData.timestamp > old.timestamp)) {
                    clearRingingInvitation(old)
                }
            }
        }
        mutex.withLock {
            if (nextCalls && Triple(notificationData.sessionId, notificationData.roomId, notificationData.eventId) in retiredInvitations) return
            val defaultRingDuration = ElementCallConfig.RINGING_CALL_DURATION_SECONDS * 1000L
            val ringDuration =
                if (notificationData.expirationTimestamp <= 0L) {
                    defaultRingDuration
                } else {
                    min(
                        notificationData.expirationTimestamp - systemClock.epochMillis(),
                        defaultRingDuration
                    )
                }

            if (ringDuration <= 0) {
                // Should already have stopped ringing, ignore.
                Timber.tag(tag).d("Received timed-out incoming ringing call for room id: ${notificationData.roomId}, cancel ringing")
                return
            }

            Timber.tag(tag).d("Received incoming call for room id: ${notificationData.roomId}, ringDuration(ms): $ringDuration")
            if (activeCall.value != null) {
                val currentData = activeCall.value?.callData
                if (nextCalls && currentData?.notificationEventId == notificationData.eventId.value &&
                    currentData.sessionId == notificationData.sessionId && currentData.roomId == notificationData.roomId) return
                val existing = (activeCall.value?.callState as? CallState.Ringing)?.notificationData
                if (nextCalls && existing?.eventId == notificationData.eventId && existing.sessionId == notificationData.sessionId) return
                // Busy is signalled for this invitation; never discard a new invite silently.
                if (nextCalls) coroutineScope.launch {
                    matrixClientProvider.getOrRestore(notificationData.sessionId).getOrNull()
                        ?.getRoom(notificationData.roomId)?.declineCall(notificationData.eventId)
                }
                displayMissedCallNotification(notificationData)
                Timber.tag(tag).w("Already have an active call; ignoring another incoming call.")
                return
            }
            appForegroundStateService.updateHasRingingCall(true)
            activeCall.value = ActiveCall(
                callData = CallData(
                    sessionId = notificationData.sessionId,
                    roomId = notificationData.roomId,
                    isAudioCall = notificationData.audioOnly,
                    notificationEventId = notificationData.eventId.value.takeIf { nextCalls },
                    attemptId = notificationData.eventId.value.takeIf { nextCalls },
                ),
                callState = CallState.Ringing(notificationData, isAnswering = userInitiated),
            )
            callTracking = CallTracking(
                callData = activeCall.value!!.callData,
                notificationEventId = notificationData.eventId,
                isIncoming = true,
                joinedAtMillis = null,
                connectedAtMillis = null,
            )
            if (!userInitiated) incomingCallRingtonePlayer.start(notificationData.notificationChannelId)

            timedOutCallJob = coroutineScope.launch {
                // Avatar initialization must not delay native answer controls.
                if (!userInitiated) {
                    showForegroundIncomingCall(notificationData)
                    setUpCoil(notificationData.sessionId)
                }
                if ((activeCall.value?.callState as? CallState.Ringing)?.notificationData?.eventId != notificationData.eventId) return@launch
                if (!userInitiated) showIncomingCallNotification(notificationData)

                // Wait for the ringing call to time out
                delay(timeMillis = ringDuration)
                if (nextCalls) expireInvitation(notificationData)
                else incomingCallTimedOut(displayMissedCallNotification = true)
            }

            // Acquire a wake lock to keep the device awake during the incoming call, so we can process the room info data
            if (activeWakeLock?.isHeld == false) {
                Timber.tag(tag).d("Acquiring partial wakelock")
                activeWakeLock.acquire(ringDuration)
            }
        }
    }

    @OptIn(DelicateCoilApi::class)
    private suspend fun setUpCoil(sessionId: SessionId) {
        val matrixClient = matrixClientProvider.getOrRestore(sessionId).getOrNull() ?: return
        // Ensure that the image loader is set, else the IncomingCallActivity will not be able to render the caller avatar
        SingletonImageLoader.setUnsafe(imageLoaderHolder.get(matrixClient))
    }

    /**
     * Called when the incoming call timed out. It will remove the active call and remove any associated UI, adding a 'missed call' notification.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    suspend fun incomingCallTimedOut(displayMissedCallNotification: Boolean) {
        val notificationData = mutex.withLock {
            Timber.tag(tag).d("Incoming call timed out")
            val previousActiveCall = activeCall.value ?: return@withLock null
            val data = (previousActiveCall.callState as? CallState.Ringing)?.notificationData ?: return@withLock null
            activeCall.value = null
            callTracking = null
            incomingCallRingtonePlayer.stop()
            if (activeWakeLock?.isHeld == true) {
                Timber.tag(tag).d("Releasing partial wakelock after timeout")
                activeWakeLock.release()
            }
            cancelIncomingCallNotification()
            data
        } ?: return
        if (displayMissedCallNotification) displayMissedCallNotification(notificationData)
        persistCallLifecycle(
            LifecycleWrite(
                callData = CallData(
                    notificationData.sessionId,
                    notificationData.roomId,
                    notificationData.audioOnly,
                    notificationData.eventId.value,
                ),
                notificationEventId = notificationData.eventId,
                state = CallLifecycleState.MISSED,
            )
        )
    }

    override suspend fun hangUpCall(
        callData: CallData,
        notificationData: CallNotificationData?,
    ) {
        val completion = prepareHangUpCall(callData, notificationData) ?: return
        runCatching { completion() }
            .onFailure { failure ->
                // Call sites without an LC-20 coordinator can still release local media safely,
                // but must retain the Matrix transition as pending instead of crashing the scope.
                Timber.tag(tag).e(failure, "Local call ended but Matrix lifecycle completion is pending")
            }
    }

    override suspend fun prepareHangUpCall(
        callData: CallData,
        notificationData: CallNotificationData?,
    ): (suspend () -> Unit)? {
        val prepared = mutex.withLock {
            Timber.tag(tag).d("Hang up call: $callData")
            val currentActiveCall = activeCall.value ?: run {
            // activeCall.value can be null if the application has been killed while the call was ringing
            // Build a currentActiveCall with the provided parameters.
            notificationData?.let {
                ActiveCall(
                    callData = callData,
                    callState = CallState.Ringing(
                        notificationData = notificationData,
                    )
                )
            }
            } ?: run {
                Timber.tag(tag).w("No active call, ignoring hang up")
                return@withLock null
            }

            if (!currentActiveCall.callData.sameCallAs(callData)) {
                Timber.tag(tag).w("Call type $callData does not match the active call type, ignoring")
                return@withLock null
            }
            cancelIncomingCallNotification()
            incomingCallRingtonePlayer.stop()
            val ringingNotification = (currentActiveCall.callState as? CallState.Ringing)?.notificationData
            val write = if (ringingNotification != null) {
                LifecycleWrite(
                    callData = currentActiveCall.callData,
                    notificationEventId = ringingNotification.eventId,
                    state = CallLifecycleState.DECLINED,
                )
            } else {
                val tracking = callTracking
                val connectedAt = tracking?.connectedAtMillis
                LifecycleWrite(
                    callData = currentActiveCall.callData,
                    notificationEventId = tracking?.notificationEventId,
                    state = if (connectedAt != null) {
                        CallLifecycleState.ENDED
                    } else if (tracking?.isIncoming == true) {
                        CallLifecycleState.DECLINED
                    } else {
                        CallLifecycleState.CANCELLED
                    },
                    durationMillis = connectedAt?.let { (systemClock.epochMillis() - it).coerceAtLeast(0L) },
                )
            }
            if (activeWakeLock?.isHeld == true) {
                Timber.tag(tag).d("Releasing partial wakelock after hang up")
                activeWakeLock.release()
            }
            timedOutCallJob?.cancel()
            if (nextCalls) currentActiveCall.callData.notificationEventId?.let {
                retiredInvitations += Triple(callData.sessionId, callData.roomId, EventId(it))
            }
            activeCall.value = null
            callTracking = null
            outgoingInvitation.value = null
            PreparedHangUp(ringingNotification, write, writeLifecycle = !currentActiveCall.remoteEnded)
        }
        return prepared?.let { pending ->
            suspend {
                pending.ringingNotification?.let { ringing ->
                    val client = matrixClientProvider.getOrRestore(ringing.sessionId).getOrElse { failure ->
                        throw IllegalStateException("Unable to restore the Matrix session while declining the call", failure)
                    }
                    val room = client.getRoom(ringing.roomId)
                        ?: throw IllegalStateException("Unable to find the Matrix room while declining the call")
                    val declineResult = runCatching { room.declineCall(ringing.eventId) }.getOrElse { failure ->
                        throw IllegalStateException("Failed to invoke the incoming Matrix call decline", failure)
                    }
                    declineResult.getOrElse { failure ->
                        throw IllegalStateException("Failed to decline the incoming Matrix call", failure)
                    }
                }
                if (pending.writeLifecycle) persistCallLifecycle(pending.lifecycleWrite, required = true)
            }
        }
    }

    override suspend fun joinedCall(callData: CallData) {
        val tracking = mutex.withLock {
            if (nextCalls && callData.notificationEventId != null && activeCall.value?.callData?.sameCallAs(callData) != true) {
                throw IncomingCallUnavailableException(true)
            }
            Timber.tag(tag).d("Joined call: $callData")
            cancelIncomingCallNotification()
            incomingCallRingtonePlayer.stop()
            if (activeWakeLock?.isHeld == true) {
                Timber.tag(tag).d("Releasing partial wakelock after joining call")
                activeWakeLock.release()
            }
            timedOutCallJob?.cancel()
            val previousNotificationId = (activeCall.value?.callState as? CallState.Ringing)?.notificationData?.eventId
            activeCall.value = ActiveCall(callData = callData, callState = CallState.InCall)
            outgoingInvitation.value = null
            CallTracking(
                callData = callData,
                notificationEventId = callData.notificationEventId?.let(::EventId) ?: previousNotificationId,
                isIncoming = callData.notificationEventId != null || previousNotificationId != null,
                joinedAtMillis = systemClock.epochMillis(),
                connectedAtMillis = null,
            ).also { callTracking = it }
        }
        if (tracking.notificationEventId == null && !nextCalls) {
            coroutineScope.launch {
                val resolved = resolveCallNotificationEventId(tracking) ?: return@launch
                mutex.withLock {
                    if (callTracking?.callData?.sameCallAs(tracking.callData) == true) {
                        callTracking = callTracking?.copy(notificationEventId = resolved)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun showIncomingCallNotification(original: CallNotificationData) {
        // System notification artwork can wait for a remote avatar. The already
        // authenticated foreground invitation must show its answer controls first.
        showForegroundIncomingCall(original)
        if (nextCalls && appForegroundStateService.isInForeground.value &&
            foregroundInvitation == Triple(original.sessionId, original.roomId, original.eventId)) {
            // The native answer screen already owns foreground ringing. A second
            // heads-up card covers it on MIUI. Backgrounding still hands ringing
            // back to the existing notification service via the observer above.
            return
        }
        val hidden = dataProtectionPolicyProvider.current().policy.notificationExposure ==
            io.element.android.security.dataprotection.api.NotificationExposure.HIDDEN
        val notificationData = if (hidden) original.copy(
            senderName = context.getString(io.element.android.features.call.impl.R.string.call_private_caller_name),
            roomName = null, avatarUrl = null, textContent = null,
        ) else original
        Timber.tag(tag).d("Displaying ringing call notification")
        val notification = ringingCallNotificationCreator.createNotification(
            sessionId = notificationData.sessionId,
            roomId = notificationData.roomId,
            eventId = notificationData.eventId,
            senderId = notificationData.senderId,
            roomName = notificationData.roomName,
            senderDisplayName = notificationData.senderName ?: notificationData.senderId.value,
            roomAvatarUrl = notificationData.avatarUrl,
            notificationChannelId = notificationData.notificationChannelId,
            timestamp = notificationData.timestamp,
            textContent = notificationData.textContent,
            expirationTimestamp = notificationData.expirationTimestamp,
            audioOnly = notificationData.audioOnly,
            fullScreenData = original,
        ) ?: return
        val notificationId = NotificationIdProvider.getForegroundServiceNotificationId(ForegroundServiceType.INCOMING_CALL)
        if (foregroundNotificationHost.showIncomingCall(notificationId, notification)) {
            incomingCallForegroundServiceController.stop()
            notificationManagerCompat.cancel(notificationId)
            return
        }
        foregroundNotificationHost.clearIncomingCall(notificationId)
        runCatchingExceptions {
            incomingCallForegroundServiceController.start(notification)
        }.onFailure {
            Timber.e(it, "Failed to start foreground service for incoming call")
            runCatchingExceptions {
                notificationManagerCompat.notify(
                    NotificationIdProvider.getForegroundServiceNotificationId(ForegroundServiceType.INCOMING_CALL),
                    notification,
                )
            }.onFailure { fallbackFailure ->
                Timber.e(fallbackFailure, "Failed to publish fallback notification for incoming call")
            }
        }
    }

    private fun showForegroundIncomingCall(data: CallNotificationData) {
        if (!nextCalls) return
        if (!appForegroundStateService.isInForeground.value) {
            Timber.d("Incoming call remains on the background notification surface")
            return
        }
        val ringing = (activeCall.value?.callState as? CallState.Ringing)?.notificationData ?: return
        val key = Triple(data.sessionId, data.roomId, data.eventId)
        if (ringing.sessionId != data.sessionId || ringing.eventId != data.eventId || foregroundInvitation == key) return
        // An authenticated invitation already owns this ringing state. Present its
        // native answer/decline screen while this app is visible, including profiles
        // where MIUI suppresses system heads-up notifications. Admission and preview
        // policy remain enforced by IncomingCallActivity and the call presenter.
        runCatchingExceptions {
            context.startActivity(Intent(context, io.element.android.features.call.impl.ui.IncomingCallActivity::class.java).apply {
                this.data = android.net.Uri.parse("yizhe-call:incoming/" + android.net.Uri.encode(data.eventId.value))
                putExtra(io.element.android.features.call.impl.ui.IncomingCallActivity.EXTRA_NOTIFICATION_DATA, data)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            foregroundInvitation = key
            Timber.d("Foreground incoming answer screen requested")
        }.onFailure { Timber.w("Foreground incoming call screen unavailable; system notification remains active") }
    }

    private fun cancelIncomingCallNotification() {
        foregroundInvitation = null
        appForegroundStateService.updateHasRingingCall(false)
        Timber.tag(tag).d("Ringing call notification cancelled")
        val notificationId = NotificationIdProvider.getForegroundServiceNotificationId(ForegroundServiceType.INCOMING_CALL)
        foregroundNotificationHost.clearIncomingCall(notificationId)
        incomingCallForegroundServiceController.stop()
        notificationManagerCompat.cancel(notificationId)
    }

    private fun displayMissedCallNotification(notificationData: CallNotificationData) {
        Timber.tag(tag).d("Displaying missed call notification")
        coroutineScope.launch {
            onMissedCallNotificationHandler.addMissedCallNotification(
                sessionId = notificationData.sessionId,
                roomId = notificationData.roomId,
                eventId = notificationData.eventId,
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeRingingCall() {
        activeCall
            .filterNotNull()
            .filter { it.callState is CallState.Ringing }
            .flatMapLatest { activeCall ->
                val callData = activeCall.callData
                val ringingInfo = activeCall.callState as CallState.Ringing
                val client = matrixClientProvider.getOrRestore(callData.sessionId).getOrNull() ?: run {
                    Timber.tag(tag).d("Couldn't find the session for an incoming call.")
                    return@flatMapLatest flowOf()
                }
                val room = client.getRoom(callData.roomId) ?: run {
                    Timber.tag(tag).d("Couldn't find the room for an incoming call.")
                    return@flatMapLatest flowOf()
                }

                Timber.tag(tag).d("Found room for ringing call: ${room.roomId}")

                // If we have declined from another phone we want to stop ringing.
                room.subscribeToCallDecline(ringingInfo.notificationData.eventId)
                    .filter { decliner ->
                        Timber.tag(tag).d("Incoming call was declined by another session.")
                        // only want to listen if the call was declined from another of my sessions,
                        // (we are ringing for an incoming call in a DM)
                        decliner == client.sessionId
                    }
            }
            .onEach { decliner ->
                Timber.tag(tag).d("Incoming call was declined by the user from another session.")
                // Remove the active call and cancel the notification
                activeCall.value = null
                incomingCallRingtonePlayer.stop()
                if (activeWakeLock?.isHeld == true) {
                    Timber.tag(tag).d("Releasing partial wakelock after call declined from another session")
                    activeWakeLock.release()
                }
                cancelIncomingCallNotification()
            }
            .launchIn(coroutineScope)
        // This will observe ringing calls and ensure they're terminated if the room call is cancelled or if the user
        // has joined the call from another session.
        activeCall
            .filterNotNull()
            .filter { it.callState is CallState.Ringing }
            .flatMapLatest { activeCall ->
                val callData = activeCall.callData
                // Get a flow of updated `hasRoomCall` and `activeRoomCallParticipants` values for the room
                val room = matrixClientProvider.getOrRestore(callData.sessionId).getOrNull()?.getRoom(callData.roomId) ?: run {
                    Timber.tag(tag).d("Couldn't find the room for an incoming call.")
                    return@flatMapLatest flowOf()
                }
                room.roomInfoFlow.map {
                    Timber.tag(tag).d("Has room call status changed for ringing call: ${it.hasRoomCall}")
                    it.hasRoomCall to (callData.sessionId in it.activeRoomCallParticipants)
                }
            }
            // We only want to check if the room active call status changes
            .distinctUntilChanged()
            // Skip the first one, we're not interested in it (if the check below passes, it had to be active anyway)
            .drop(1)
            .onEach { (roomHasActiveCall, userIsInTheCall) ->
                if (!roomHasActiveCall) {
                    // The call was cancelled
                    timedOutCallJob?.cancel()
                    incomingCallTimedOut(displayMissedCallNotification = true)
                } else if (userIsInTheCall) {
                    // The user joined the call from another session
                    timedOutCallJob?.cancel()
                    incomingCallTimedOut(displayMissedCallNotification = false)
                }
            }
            .launchIn(coroutineScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeConnectedCall() {
        activeCall
            .filterNotNull()
            .filter { it.callState is CallState.InCall }
            .flatMapLatest { current ->
                val room = matrixClientProvider.getOrRestore(current.callData.sessionId).getOrNull()
                    ?.getJoinedRoom(current.callData.roomId)
                    ?: return@flatMapLatest flowOf(false)
                if (!room.roomInfoFlow.value.isDm) return@flatMapLatest kotlinx.coroutines.flow.emptyFlow()
                room.roomInfoFlow.map { it.activeRoomCallParticipants.size >= 2 }
            }
            .distinctUntilChanged()
            .mapLatest { hasRemoteParticipant ->
                Timber.tag(tag).d("Connected call participant projection changed: hasRemoteParticipant=$hasRemoteParticipant")
                if (!hasRemoteParticipant) {
                    // Matrix membership can flicker while the RTC session settles. Keep this
                    // below the UI's three-second responsiveness budget but absorb brief sync gaps.
                    delay(REMOTE_HANGUP_GRACE_MILLIS)
                }
                hasRemoteParticipant
            }
            .onEach { hasRemoteParticipant ->
                if (hasRemoteParticipant) {
                    val write = mutex.withLock {
                        val current = activeCall.value?.takeIf { it.callState is CallState.InCall } ?: return@withLock null
                        val tracking = callTracking?.takeIf { it.callData.sameCallAs(current.callData) } ?: return@withLock null
                        if (tracking.connectedAtMillis != null) return@withLock null
                        val connectedAt = systemClock.epochMillis()
                        callTracking = tracking.copy(connectedAtMillis = connectedAt)
                        LifecycleWrite(
                            callData = current.callData,
                            notificationEventId = tracking.notificationEventId,
                            state = CallLifecycleState.CONNECTED,
                        )
                    }
                    write?.let { persistCallLifecycle(it) }
                } else {
                    val remotelyEndedCall = mutex.withLock {
                        val current = activeCall.value?.takeIf { it.callState is CallState.InCall } ?: return@withLock null
                        val tracking = callTracking?.takeIf { it.callData.sameCallAs(current.callData) } ?: return@withLock null
                        current.callData.takeIf { tracking.connectedAtMillis != null }
                    }
                    if (remotelyEndedCall != null) {
                        Timber.tag(tag).d("Remote participant left the connected DM call; ending the local call")
                        hangUpCall(remotelyEndedCall)
                    }
                }
            }
            .launchIn(coroutineScope)
    }

    private suspend fun invitationContent(
        sessionId: SessionId,
        roomId: io.element.android.libraries.matrix.api.core.RoomId,
        eventId: EventId,
        invitationRoom: io.element.android.libraries.matrix.api.room.JoinedRoom? = null,
    ): CallNotifyContent? {
        require(invitationRoom == null || (invitationRoom.sessionId == sessionId && invitationRoom.roomId == roomId))
        val room = invitationRoom ?: matrixClientProvider.getOrRestore(sessionId).getOrNull()?.getJoinedRoom(roomId) ?: return null
        return withTimeoutOrNull(5_000) {
            room.liveTimeline.timelineItems.map { items ->
                val events = items.filterIsInstance<MatrixTimelineItem.Event>().map { it.event }
                val target = events.firstOrNull { it.eventId == eventId } ?: return@map null
                val content = target.content as? CallNotifyContent ?: return@map null
                // A later invitation from the same caller supersedes this one, including when
                // cancellation and push delivery arrive out of order. Never revive an older attempt.
                if (events.any { it.sender == target.sender && it.timestamp > target.timestamp && it.content is CallNotifyContent }) {
                    content.copy(lifecycleState = CallLifecycleState.CANCELLED)
                } else content
            }.filterNotNull().first()
        }
    }

    private fun CallNotifyContent.isFinishedInvitation(recipient: SessionId) =
        lifecycleState != null || recipient in declinedBy

    override suspend fun validateIncomingCall(callData: CallData, invitationRoom: io.element.android.libraries.matrix.api.room.JoinedRoom?) {
        if (!nextCalls || callData.notificationEventId == null) return
        val content = invitationContent(callData.sessionId, callData.roomId, EventId(requireNotNull(callData.notificationEventId)), invitationRoom)
            ?: throw IncomingCallUnavailableException(false)
        if (content.isFinishedInvitation(callData.sessionId)) throw IncomingCallUnavailableException(true)
        mutex.withLock {
            val current = activeCall.value
            if (current?.remoteEnded == true) throw IncomingCallUnavailableException(true)
            if (current?.callState == CallState.InCall && current.callData.sameCallAs(callData)) return
            val ringing = (current?.callState as? CallState.Ringing)?.notificationData
            if (ringing == null || ringing.eventId.value != callData.notificationEventId ||
                ringing.sessionId != callData.sessionId || ringing.roomId != callData.roomId ||
                ringing.expirationTimestamp in 1..systemClock.epochMillis()
            ) throw IncomingCallUnavailableException(true)
        }
    }

    override suspend fun bindOutgoingInvitation(callData: CallData, eventId: EventId) {
        // A joining callee also emits a widget notification. Its call-state target remains
        // the incoming invitation, never that local membership notification.
        if (callData.notificationEventId != null) return
        if (nextCalls) finalizationRecovery.update(callData, invitationId = eventId.value)
        mutex.withLock {
            val tracking = callTracking?.takeIf { it.callData.sameCallAs(callData) && !it.isIncoming } ?: return
            if (tracking.notificationEventId == null) {
                callTracking = tracking.copy(notificationEventId = eventId)
                outgoingInvitation.value = callData to eventId
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeOutgoingDecline() {
        outgoingInvitation.flatMapLatest { binding ->
            if (binding == null) return@flatMapLatest kotlinx.coroutines.flow.emptyFlow<Pair<CallData, EventId>>()
            val (data, id) = binding
            val room = matrixClientProvider.getOrRestore(data.sessionId).getOrNull()?.getJoinedRoom(data.roomId)
                ?: return@flatMapLatest kotlinx.coroutines.flow.emptyFlow()
            val info = room.roomInfoFlow.value
            val peer = info.heroes.singleOrNull()?.userId?.takeUnless { it == data.sessionId }
            if (!info.isDm || peer == null) return@flatMapLatest kotlinx.coroutines.flow.emptyFlow()
            room.subscribeToCallDecline(id).filter { it == peer }.map { binding }
                .catch { Timber.tag(tag).w("Native decline observer unavailable; widget remains active") }
        }.onEach { (data, id) ->
            mutex.withLock {
                val current = activeCall.value?.takeIf { it.callData.sameCallAs(data) } ?: return@withLock
                val tracking = callTracking?.takeIf { it.callData.sameCallAs(data) } ?: return@withLock
                if (tracking.notificationEventId != id || tracking.connectedAtMillis != null) return@withLock
                activeCall.value = current.copy(remoteEnded = true)
                Timber.tag(tag).d("Exact outgoing invitation declined by DM peer; closing call")
            }
        }.launchIn(coroutineScope)
    }

    private suspend fun clearRingingInvitation(data: CallNotificationData): Boolean = mutex.withLock {
        val current = (activeCall.value?.callState as? CallState.Ringing)?.notificationData ?: return@withLock false
        if (current.eventId != data.eventId || current.sessionId != data.sessionId || current.roomId != data.roomId) return@withLock false
        val timer = timedOutCallJob
        timedOutCallJob = null
        timer?.cancel()
        retiredInvitations += Triple(data.sessionId, data.roomId, data.eventId)
        activeCall.value = null
        callTracking = null
        incomingCallRingtonePlayer.stop()
        if (activeWakeLock?.isHeld == true) activeWakeLock.release()
        cancelIncomingCallNotification()
        true
    }

    private suspend fun expireInvitation(data: CallNotificationData) {
        if (!clearRingingInvitation(data)) return
        displayMissedCallNotification(data)
        // Clearing this invitation cancels its timer, including the coroutine executing
        // this timeout. The application scope must own the suspending protected write;
        // a subsequent invitation can then cancel its own timer without losing this fact.
        // Keep the original target and deterministic LC-15 operation; no optimistic state.
        coroutineScope.launch {
            persistCallLifecycle(LifecycleWrite(
                CallData(data.sessionId, data.roomId, data.audioOnly, data.eventId.value, data.eventId.value),
                data.eventId, CallLifecycleState.MISSED,
            ))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeInvitationLifecycle() {
        combine(activeCall, outgoingInvitation) { active, outgoing ->
            val data = active?.callData?.takeIf { nextCalls } ?: return@combine null
            data.notificationEventId?.let { data to EventId(it) }
                ?: outgoing?.takeIf { (owner, _) -> owner.sameCallAs(data) }
        }.flatMapLatest { binding ->
            val (data, invitationId) = binding
                ?: return@flatMapLatest flowOf<Pair<CallData, CallNotifyContent>?>(null)
            val room = matrixClientProvider.getOrRestore(data.sessionId).getOrNull()?.getJoinedRoom(data.roomId)
                ?: return@flatMapLatest flowOf(null)
            room.liveTimeline.timelineItems.map { items ->
                val content = items.filterIsInstance<MatrixTimelineItem.Event>()
                    .firstOrNull { it.event.eventId == invitationId }?.event?.content as? CallNotifyContent
                content?.let { data to it }
            }
        }.filterNotNull().onEach { (data, content) ->
            val terminal = content.lifecycleState?.let { it != CallLifecycleState.CONNECTED } == true
            if (terminal || content.isFinishedInvitation(data.sessionId)) finishIncomingInvitation(data, ringOnly = !terminal)
        }.launchIn(coroutineScope)
    }

    private suspend fun finishIncomingInvitation(data: CallData, ringOnly: Boolean = false) = mutex.withLock {
        val current = activeCall.value?.takeIf { it.callData.sameCallAs(data) } ?: return@withLock
        if (current.callState is CallState.InCall) {
            if (!ringOnly) {
                // Let the owning presenter close media and run LC-20 completion. Clearing this
                // tracking early would lose the operation when prepareHangUpCall runs afterwards.
                activeCall.value = current.copy(remoteEnded = true)
            }
        } else {
            retiredInvitations += Triple(data.sessionId, data.roomId, EventId(requireNotNull(data.notificationEventId)))
            timedOutCallJob?.cancel()
            activeCall.value = null
            callTracking = null
            incomingCallRingtonePlayer.stop()
            if (activeWakeLock?.isHeld == true) activeWakeLock.release()
            cancelIncomingCallNotification()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeInvitationDeparture() {
        activeCall.flatMapLatest { active ->
            val data = active?.takeUnless { it.remoteEnded }?.callData?.takeIf { it.notificationEventId != null }
                ?: return@flatMapLatest kotlinx.coroutines.flow.emptyFlow<Pair<CallData, Boolean>>()
            val room = matrixClientProvider.getOrRestore(data.sessionId).getOrNull()?.getJoinedRoom(data.roomId)
                ?: return@flatMapLatest kotlinx.coroutines.flow.emptyFlow()
            room.observeCallInvitationDeparture(EventId(requireNotNull(data.notificationEventId)))
                .catch { Timber.tag(tag).w("RTC invitation observer unavailable; lifecycle observer remains active") }
                .filter { it }.map { data to !room.roomInfoFlow.value.isDm }
        }.onEach { (data, group) ->
            finishIncomingInvitation(data, ringOnly = group)
            Timber.tag(tag).d("Exact invited RTC member left; incoming call ended")
        }.launchIn(coroutineScope)
    }

    override suspend fun mediaConnected(callData: CallData) {
        if (nextCalls) finalizationRecovery.update(callData, connected = true)
        val write = mutex.withLock {
            val current = activeCall.value?.takeIf { !it.remoteEnded && it.callState is CallState.InCall && it.callData.sameCallAs(callData) }
                ?: return@withLock null
            val tracking = callTracking?.takeIf { it.callData.sameCallAs(callData) && it.connectedAtMillis == null }
                ?: return@withLock null
            callTracking = tracking.copy(connectedAtMillis = systemClock.epochMillis())
            LifecycleWrite(current.callData, tracking.notificationEventId, CallLifecycleState.CONNECTED)
        }
        write?.let { persistCallLifecycle(it) }
    }

    private suspend fun persistCallLifecycle(write: LifecycleWrite, required: Boolean = false) {
        val client = matrixClientProvider.getOrRestore(write.callData.sessionId).getOrElse { failure ->
            if (required) throw IllegalStateException("Unable to restore the Matrix session while persisting call lifecycle", failure)
            Timber.tag(tag).w(failure, "Unable to restore Matrix session while persisting call lifecycle")
            return
        }
        val room = client.getJoinedRoom(write.callData.roomId) ?: run {
            if (required) throw IllegalStateException("Unable to find the joined Matrix room while persisting call lifecycle")
            Timber.tag(tag).w("Unable to find joined room while persisting call lifecycle")
            return
        }
        val roomInfo = runCatching { room.roomInfoFlow.value }.getOrElse { failure ->
            if (required) throw IllegalStateException("Unable to read room metadata while persisting call lifecycle", failure)
            Timber.tag(tag).w(failure, "Unable to read room metadata while persisting call lifecycle")
            return
        }
        // Per-call lifecycle state is defined for protected direct calls. Group calls legitimately
        // have no matching notification lifecycle event, so there is no authority action to write.
        if (!roomInfo.isDm) return
        if (nextCalls && write.notificationEventId == null) return
        val target = write.notificationEventId ?: resolveCallNotificationEventId(
            CallTracking(
                callData = write.callData,
                notificationEventId = null,
                isIncoming = write.callData.notificationEventId != null,
                joinedAtMillis = systemClock.epochMillis() - CALL_NOTIFICATION_LOOKBACK_MILLIS,
                connectedAtMillis = null,
            )
        )
        if (target == null) {
            if (required) throw IllegalStateException("Unable to associate DM call lifecycle with a call notification event")
            Timber.tag(tag).w("Unable to associate DM call lifecycle with a call notification event")
            return
        }
        val lifecycleResult = runCatching {
            if (nextCalls && io.element.android.security.dataprotection.api.currentLifecycleOperationContext() == null) {
                fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
                // Declining/expiring a ringing invitation has no joined RTC owner. The protected
                // CALL_STATE message owns its existing LC-15 metadata operation instead.
                appOperationalStateCoordinator.perform(io.element.android.security.dataprotection.api.lifecycleOperationRequest(
                    lifecycleId = io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_15,
                    trigger = "call_operation",
                    roomIdHash = hash(write.callData.roomId.value), eventIdHash = hash(target.value),
                    operationId = "call-state-" + hash("${write.callData.sessionId.value}|${write.callData.roomId.value}|${target.value}|${write.state}"),
                )) { room.liveTimeline.sendCallLifecycle(target, write.state, write.durationMillis).getOrThrow() }
                Result.success(Unit)
            } else room.liveTimeline.sendCallLifecycle(target, write.state, write.durationMillis)
        }.getOrElse { failure ->
            if (required) throw IllegalStateException("Failed to invoke protected DM call lifecycle", failure)
            Timber.tag(tag).e(failure, "Failed to invoke protected DM call lifecycle")
            return
        }
        lifecycleResult.getOrElse { failure ->
            if (required) throw IllegalStateException("Failed to persist protected DM call lifecycle", failure)
            Timber.tag(tag).e(failure, "Failed to persist protected DM call lifecycle")
            return
        }
    }

    private suspend fun resolveCallNotificationEventId(tracking: CallTracking): EventId? {
        tracking.notificationEventId?.let { return it }
        val client = matrixClientProvider.getOrRestore(tracking.callData.sessionId).getOrNull() ?: return null
        val room = client.getJoinedRoom(tracking.callData.roomId) ?: return null
        val roomInfo = runCatching { room.roomInfoFlow.value }
            .onFailure { Timber.tag(tag).w(it, "Unable to read room metadata while resolving call notification") }
            .getOrNull()
            ?: return null
        if (!roomInfo.isDm) return null
        val expectedIntent = if (tracking.callData.isAudioCall) CallIntent.AUDIO else CallIntent.VIDEO
        val earliestTimestamp = (tracking.joinedAtMillis ?: systemClock.epochMillis()) - CALL_NOTIFICATION_LOOKBACK_MILLIS
        return withTimeoutOrNull(CALL_NOTIFICATION_RESOLVE_TIMEOUT_MILLIS) {
            room.liveTimeline.timelineItems
                .map { items ->
                    items.asReversed().firstNotNullOfOrNull { item ->
                        val event = (item as? MatrixTimelineItem.Event)?.event ?: return@firstNotNullOfOrNull null
                        val content = event.content as? CallNotifyContent ?: return@firstNotNullOfOrNull null
                        event.eventId?.takeIf {
                            event.sender == client.sessionId &&
                                event.timestamp >= earliestTimestamp &&
                                content.callIntent == expectedIntent
                        }
                    }
                }
                .filterNotNull()
                .first()
        }
    }

    private fun observeCurrentCall() {
        activeCall
            .onEach { value ->
                if (value == null) {
                    defaultCurrentCallService.onCallEnded()
                } else {
                    when (value.callState) {
                        is CallState.Ringing -> {
                            // Nothing to do
                        }
                        is CallState.InCall -> {
                            defaultCurrentCallService.onCallStarted(CurrentCall.RoomCall(value.callData.roomId))
                        }
                    }
                }
            }
            .launchIn(coroutineScope)
    }

    private fun CallData.sameCallAs(other: CallData): Boolean =
        sessionId == other.sessionId && roomId == other.roomId && isAudioCall == other.isAudioCall &&
            (!nextCalls ||
                (attemptId == other.attemptId && notificationEventId == other.notificationEventId))

    private data class CallTracking(
        val callData: CallData,
        val notificationEventId: EventId?,
        val isIncoming: Boolean,
        val joinedAtMillis: Long?,
        val connectedAtMillis: Long?,
    )

    private data class LifecycleWrite(
        val callData: CallData,
        val notificationEventId: EventId?,
        val state: CallLifecycleState,
        val durationMillis: Long? = null,
    )

    private data class PreparedHangUp(
        val ringingNotification: CallNotificationData?,
        val lifecycleWrite: LifecycleWrite,
        val writeLifecycle: Boolean = true,
    )

    private companion object {
        const val CALL_NOTIFICATION_LOOKBACK_MILLIS = 10_000L
        const val CALL_NOTIFICATION_RESOLVE_TIMEOUT_MILLIS = 20_000L
        const val REMOTE_HANGUP_GRACE_MILLIS = 1_000L
    }
}

/**
 * Represents an active call.
 */
data class ActiveCall(
    val callData: CallData,
    val callState: CallState,
    val remoteEnded: Boolean = false,
)

/**
 * Represents the state of an active call.
 */
sealed interface CallState {
    /**
     * The call is in a ringing state.
     * @param notificationData The data for the incoming call notification.
     */
    data class Ringing(val notificationData: CallNotificationData, val isAnswering: Boolean = false) : CallState

    /**
     * The call is in an in-call state.
     */
    data object InCall : CallState
}
