/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.composer

import android.Manifest
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.libraries.matrix.api.core.UniqueId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import im.vector.app.features.analytics.plan.Composer
import io.element.android.features.messages.api.MessageComposerContext
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerPresenter
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerState
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.audio.api.AudioSessionRequest
import io.element.android.libraries.audio.api.AudioSessionRole
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.permissions.api.PermissionsEvent
import io.element.android.libraries.permissions.api.PermissionsPresenter
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.VoiceMessagePlayerEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.voiceplayer.api.VoiceMessageException
import io.element.android.libraries.voicerecorder.api.VoiceRecorder
import io.element.android.libraries.voicerecorder.api.VoiceRecorderState
import io.element.android.libraries.voicerecorder.api.VoiceRecorderStopReason
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.services.analytics.api.AnalyticsService
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private data class PendingVoicePreview(
    val operationId: String,
    val duration: Duration,
    val waveform: List<Float>,
)

internal data class VoiceDraftHandoff(
    val operationId: String,
    val generation: Long,
    val deferred: CompletableDeferred<ProtectedVoiceDraft>,
)

internal class VoiceDraftHandoffOwner {
    private var generation = 0L
    private var current: VoiceDraftHandoff? = null

    @Synchronized
    fun replace(operationId: String): VoiceDraftHandoff {
        current?.deferred?.cancel()
        return VoiceDraftHandoff(
            operationId = operationId,
            generation = ++generation,
            deferred = CompletableDeferred(),
        ).also { current = it }
    }

    @Synchronized
    fun current(): VoiceDraftHandoff? = current

    @Synchronized
    fun isCurrent(handoff: VoiceDraftHandoff): Boolean = current === handoff

    @Synchronized
    fun clear(handoff: VoiceDraftHandoff): Boolean {
        if (current !== handoff) return false
        current = null
        return true
    }

    @Synchronized
    fun cancelCurrent(): VoiceDraftHandoff? = current.also {
        current = null
        it?.deferred?.cancel()
    }
}

private class VoiceSendOwner(
    val operationId: String,
) {
    val committedToOutbox = AtomicBoolean(false)
    val sourceReleased = AtomicBoolean(false)
    var job: Job? = null
}

@AssistedInject
class DefaultVoiceMessageComposerPresenter(
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
    @Assisted private val timelineMode: Timeline.Mode,
    private val voiceRecorder: VoiceRecorder,
    private val analyticsService: AnalyticsService,
    private val audioFocus: AudioFocus,
    mediaSenderFactory: MediaSenderFactory,
    private val player: VoiceMessageComposerPlayer,
    private val messageComposerContext: MessageComposerContext,
    permissionsPresenterFactory: PermissionsPresenter.Factory,
    private val room: JoinedRoom,
    private val matrixClient: MatrixClient,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val protectedVoiceDraftStore: ProtectedVoiceDraftStore,
    private val timelineController: TimelineController,
) : VoiceMessageComposerPresenter {
    @ContributesBinding(RoomScope::class)
    @AssistedFactory
    interface Factory : VoiceMessageComposerPresenter.Factory {
        override fun create(timelineMode: Timeline.Mode): DefaultVoiceMessageComposerPresenter
    }

    private val permissionsPresenter = permissionsPresenterFactory.create(Manifest.permission.RECORD_AUDIO)
    private var pendingEvent: VoiceMessageRecorderEvent.Start? = null
    private var captureOwnerId: String? = null
    private val draftHandoffOwner = VoiceDraftHandoffOwner()
    private val captureLifecycle = VoiceCaptureLifecycle(voiceRecorder, appOperationalStateCoordinator, room.roomId.value.sha256Hex(), room.sessionId.value.sha256Hex())
    private val voiceRecorderMutex = Mutex()
    private val draftHandoffMutex = Mutex()
    private val mediaSender = mediaSenderFactory.create(timelineMode)

    @Composable
    override fun present(): VoiceMessageComposerState {
        val localCoroutineScope = rememberCoroutineScope()
        val recorderState by voiceRecorder.state.collectAsState(initial = VoiceRecorderState.Idle)
        val playerState by player.state.collectAsState(initial = VoiceMessageComposerPlayer.State.Initial)
        val keepScreenOn by remember { derivedStateOf { recorderState is VoiceRecorderState.Recording } }
        val permissionState by rememberUpdatedState(permissionsPresenter.present())
        var isSending by remember { mutableStateOf(false) }
        var showSendFailureDialog by remember { mutableStateOf(false) }
        var showRecordingPermissionLostDialog by remember { mutableStateOf(false) }
        val captureFrontendVisible = remember { MutableStateFlow(true) }
        var protectedDraft by remember { mutableStateOf<ProtectedVoiceDraft?>(null) }
        var pendingPreview by remember { mutableStateOf<PendingVoicePreview?>(null) }
        var voiceEcho by remember { mutableStateOf<Pair<String, UniqueId>?>(null) }
        var activeSendOwner by remember { mutableStateOf<VoiceSendOwner?>(null) }

        suspend fun protectFinishedRecordingLocked(
            recording: VoiceRecorderState.Finished,
            handoff: VoiceDraftHandoff,
        ) {
            if (!draftHandoffOwner.isCurrent(handoff) || handoff.deferred.isCompleted || protectedDraft != null) return
            var createdDraft: ProtectedVoiceDraft? = null
            try {
                val draft = protectedVoiceDraftStore.protect(handoff.operationId, recording).also { createdDraft = it }
                // Delete only this handoff's scratch. An older completion must never reset a newer capture.
                voiceRecorder.deleteRecording(recording)
                if (!draftHandoffOwner.isCurrent(handoff)) {
                    protectedVoiceDraftStore.discard(draft)
                    return
                }
                pendingPreview = PendingVoicePreview(
                    operationId = handoff.operationId,
                    duration = recording.duration,
                    waveform = recording.waveform,
                )
                player.setMedia(draft.uri.toString())
                if (!draftHandoffOwner.isCurrent(handoff)) {
                    if (pendingPreview?.operationId == handoff.operationId) pendingPreview = null
                    player.pause()
                    protectedVoiceDraftStore.discard(draft)
                    return
                }
                protectedDraft = draft
                pendingPreview = null
                handoff.deferred.complete(draft)
            } catch (failure: Exception) {
                Timber.e(failure, "Voice draft protection failed")
                withContext(NonCancellable) {
                    createdDraft?.let { draft -> runCatching { protectedVoiceDraftStore.discard(draft) } }
                    runCatching { voiceRecorder.deleteRecording(recording) }
                }
                if (draftHandoffOwner.isCurrent(handoff)) {
                    if (pendingPreview?.operationId == handoff.operationId) pendingPreview = null
                    handoff.deferred.completeExceptionally(failure)
                    showSendFailureDialog = true
                }
                if (failure is CancellationException) throw failure
            }
        }

        suspend fun protectFinishedRecording(
            recording: VoiceRecorderState.Finished,
            handoff: VoiceDraftHandoff,
        ) {
            draftHandoffMutex.withLock {
                protectFinishedRecordingLocked(recording, handoff)
            }
        }

        fun finishAndProtectRecording(reason: VoiceRecorderStopReason) {
            val handoff = draftHandoffOwner.current() ?: return
            localCoroutineScope.launch {
                val finished = voiceRecorderMutex.withLock {
                    if (!draftHandoffOwner.isCurrent(handoff)) return@withLock null
                    when (val currentRecorderState = voiceRecorder.state.value) {
                        is VoiceRecorderState.Recording -> {
                            val ownerId = captureOwnerId
                            voiceRecorder.stopRecord(reason)
                            ownerId?.let(audioFocus::release)
                            if (captureOwnerId == ownerId) captureOwnerId = null
                            voiceRecorder.state.value as? VoiceRecorderState.Finished
                        }
                        // A gesture release and ON_PAUSE can enqueue two stop requests for the same
                        // capture. DefaultVoiceRecorder treats a second stop after Finished as a
                        // zero-duration capture and deletes its scratch, so reuse the first result.
                        is VoiceRecorderState.Finished -> currentRecorderState
                        else -> null
                    }
                }
                if (finished == null) {
                    if (draftHandoffOwner.isCurrent(handoff) && !handoff.deferred.isCompleted) {
                        handoff.deferred.completeExceptionally(VoiceMessageException.FileException("Recording did not produce a sendable voice draft"))
                    }
                    return@launch
                }
                draftHandoffMutex.withLock {
                    if (!draftHandoffOwner.isCurrent(handoff)) {
                        voiceRecorder.deleteRecording(finished)
                        return@withLock
                    }
                    protectFinishedRecordingLocked(finished, handoff)
                }
            }
        }

        val cleanupRevision by voiceRecorder.captureCleanupRevision.collectAsState()
        LaunchedEffect(recorderState, cleanupRevision, permissionState.permissionGranted) {
            // Retain the existing native intent while settings cover this UI. On return, the frontend
            // observes the stopped capture and current permission before its fact is durably queued.
            captureFrontendVisible.collectLatest { visible ->
                if (visible && (recorderState is VoiceRecorderState.Idle || recorderState is VoiceRecorderState.Failed)) {
                    if (!permissionState.permissionGranted && captureLifecycle.hasOwnedInterruptedCleanup()) {
                        showRecordingPermissionLostDialog = true
                    }
                    while (isActive && voiceRecorder.pendingCaptureCleanups().isNotEmpty()) {
                        voiceRecorderMutex.withLock {
                            if (captureOwnerId == null) captureLifecycle.completeCleanups()
                        }
                        delay(15_000L)
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            protectedVoiceDraftStore.recoverAbandonedDrafts()
        }

        LaunchedEffect(protectedVoiceDraftStore) {
            var observedGeneration = protectedVoiceDraftStore.revocationGeneration.value
            protectedVoiceDraftStore.revocationGeneration.collect { generation ->
                if (generation == observedGeneration) return@collect
                observedGeneration = generation

                // Make revoked composer content inaccessible before waiting for any in-flight work.
                val draft = protectedDraft
                val owner = activeSendOwner?.takeIf { active -> draft == null || active.operationId == draft.operationId }
                voiceEcho?.second?.let { timelineController.discardOptimisticLocalEcho(it) }
                voiceEcho = null
                protectedDraft = null
                pendingPreview = null
                pendingEvent = null
                val revokedHandoff = draftHandoffOwner.cancelCurrent()
                player.clear()

                if (revokedHandoff != null) {
                    draftHandoffMutex.withLock {
                        if (draftHandoffOwner.current()?.generation?.let { it > revokedHandoff.generation } == true) return@withLock
                        voiceRecorderMutex.withLock {
                            if (draftHandoffOwner.current()?.generation?.let { it > revokedHandoff.generation } == true) return@withLock
                            cancelRecordingLocked(revokedHandoff)
                            (voiceRecorder.state.value as? VoiceRecorderState.Finished)?.let { voiceRecorder.deleteRecording(it) }
                        }
                    }
                }

                if (owner != null && !owner.committedToOutbox.get()) {
                    owner.job?.cancelAndJoin()
                }
                if (draft != null && (owner == null || owner.sourceReleased.compareAndSet(false, true))) {
                    protectedVoiceDraftStore.discard(draft)
                }
            }
        }

        LaunchedEffect(recorderState) {
            when (val recording = recorderState) {
                is VoiceRecorderState.Finished -> draftHandoffOwner.current()?.let { handoff ->
                    protectFinishedRecording(recording, handoff)
                }
                is VoiceRecorderState.Failed -> {
                    draftHandoffOwner.current()?.deferred?.let { deferred ->
                        if (!deferred.isCompleted) deferred.completeExceptionally(VoiceMessageException.FileException("Voice recording failed"))
                    }
                    Timber.w("Voice recorder failed: reason=%s", recording.reason)
                    captureOwnerId?.let(audioFocus::release)
                    captureOwnerId = null
                    showSendFailureDialog = true
                }
                else -> Unit
            }
        }

        LaunchedEffect(permissionState.permissionGranted) {
            if (permissionState.permissionGranted) {
                pendingEvent?.let {
                    val handoff = draftHandoffOwner.current() ?: return@let
                    localCoroutineScope.startRecording(
                        handoff = handoff,
                        onFailure = { showSendFailureDialog = true },
                    )
                    pendingEvent = null
                }
            } else if (voiceRecorder.state.value is VoiceRecorderState.Recording) {
                pendingEvent = null
                draftHandoffOwner.cancelCurrent()?.let { interruptedHandoff ->
                    voiceRecorderMutex.withLock {
                        cancelRecordingLocked(interruptedHandoff)
                        if (voiceRecorder.state.value !is VoiceRecorderState.Recording && captureOwnerId == null) {
                            showRecordingPermissionLostDialog = true
                        }
                    }
                }
            }
        }

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    captureFrontendVisible.value = false
                    finishAndProtectRecording(VoiceRecorderStopReason.INTERRUPTED)
                    player.pause()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    if (!isSending) {
                        val draft = protectedDraft
                        val destroyedHandoff = draftHandoffOwner.cancelCurrent()
                        pendingPreview = null
                        if (draft != null) {
                            protectedDraft = null
                        }
                        sessionCoroutineScope.launch {
                            if (draft != null) protectedVoiceDraftStore.discard(draft)
                            if (destroyedHandoff == null) return@launch
                            draftHandoffMutex.withLock {
                                // A newer generation owns the shared recorder now; old cleanup must not touch it.
                                if (draftHandoffOwner.current()?.generation?.let { it > destroyedHandoff.generation } == true) return@withLock
                                voiceRecorderMutex.withLock {
                                    if (draftHandoffOwner.current()?.generation?.let { it > destroyedHandoff.generation } == true) return@withLock
                                    cancelRecordingLocked(destroyedHandoff)
                                    (voiceRecorder.state.value as? VoiceRecorderState.Finished)?.let { voiceRecorder.deleteRecording(it) }
                                }
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_RESUME -> captureFrontendVisible.value = true
                else -> {}
            }
        }

        fun handleVoiceMessageRecorderEvent(event: VoiceMessageRecorderEvent) {
            when (event) {
                VoiceMessageRecorderEvent.Start -> {
                    pendingEvent = null
                    Timber.v("Voice message record button pressed")
                    val operationId = UUID.randomUUID().toString()
                    val handoff = draftHandoffOwner.replace(operationId)
                    when {
                        permissionState.permissionGranted -> {
                            localCoroutineScope.startRecording(
                                handoff = handoff,
                                onFailure = { showSendFailureDialog = true },
                            )
                        }
                        else -> {
                            Timber.i("Voice message permission needed")
                            pendingEvent = VoiceMessageRecorderEvent.Start
                            permissionState.eventSink(PermissionsEvent.RequestPermissions)
                        }
                    }
                }
                VoiceMessageRecorderEvent.Stop -> {
                    pendingEvent = null
                    Timber.v("Voice message stop button pressed")
                    finishAndProtectRecording(VoiceRecorderStopReason.USER_STOP)
                }
                VoiceMessageRecorderEvent.Cancel -> {
                    pendingEvent = null
                    Timber.v("Voice message cancel button tapped")
                    draftHandoffOwner.cancelCurrent()?.let { cancelledHandoff ->
                        localCoroutineScope.launch {
                            draftHandoffMutex.withLock {
                                if (draftHandoffOwner.current()?.generation?.let { it > cancelledHandoff.generation } == true) return@withLock
                                voiceRecorderMutex.withLock {
                                    if (draftHandoffOwner.current()?.generation?.let { it > cancelledHandoff.generation } == true) return@withLock
                                    cancelRecordingLocked(cancelledHandoff)
                                }
                            }
                        }
                    }
                }
            }
        }

        fun handleVoiceMessagePlayerEvent(event: VoiceMessagePlayerEvent) {
            val handoff = draftHandoffOwner.current()
            val mustWaitForHandoff = protectedDraft == null &&
                (recorderState is VoiceRecorderState.Finished || pendingPreview != null)
            localCoroutineScope.launch {
                var handedOffDraft: ProtectedVoiceDraft? = null
                if (mustWaitForHandoff && handoff != null) {
                    handedOffDraft = runCatching { handoff.deferred.await() }.getOrElse { return@launch }
                }
                when (event) {
                    VoiceMessagePlayerEvent.Play -> {
                        val currentDraft = protectedDraft ?: handedOffDraft
                        if (currentDraft == null) {
                            player.play()
                        } else {
                            runCatching { protectedVoiceDraftStore.reauthorize(currentDraft) }
                                .onSuccess { authorizedDraft ->
                                    if (authorizedDraft !== currentDraft) {
                                        if (protectedDraft === currentDraft) protectedDraft = authorizedDraft
                                        player.setMedia(authorizedDraft.uri.toString())
                                    }
                                    player.play()
                                }
                                .onFailure { failure ->
                                    Timber.e(failure, "Voice draft playback authorization failed")
                                    showSendFailureDialog = true
                                }
                        }
                    }
                    VoiceMessagePlayerEvent.Pause -> player.pause()
                    is VoiceMessagePlayerEvent.Seek -> player.seek(event.position)
                }
            }
        }

        fun sendVoiceMessage(inReplyToEventId: EventId?, burnAfterRead: Boolean, onResult: (Result<Unit>) -> Unit = {}): Job? {
            val immediateDraft = protectedDraft
            val handoff = draftHandoffOwner.current()
            if (immediateDraft == null && handoff == null) {
                val exception = VoiceMessageException.FileException("No protected voice draft to send")
                analyticsService.trackError(exception)
                Timber.e(exception)
                onResult(Result.failure(exception))
                return null
            }
            if (isSending) {
                return activeSendOwner?.job
            }
            isSending = true
            player.pause()
            analyticsService.captureComposerEvent()
            val owner = VoiceSendOwner(immediateDraft?.operationId ?: requireNotNull(handoff).operationId)
            val duration = immediateDraft?.duration ?: pendingPreview?.duration ?: when (val state = voiceRecorder.state.value) {
                is VoiceRecorderState.Finished -> state.duration
                is VoiceRecorderState.Recording -> state.elapsedTime
                is VoiceRecorderState.Finalizing -> state.elapsedTime
                else -> Duration.ZERO
            }
            val waveform = immediateDraft?.waveform ?: pendingPreview?.waveform ?: emptyList()
            val displayId = voiceEcho?.takeIf { it.first == owner.operationId }?.second
                ?: timelineController.addOptimisticVoiceEcho(owner.operationId, duration, waveform, burnAfterRead).also {
                    voiceEcho = owner.operationId to it
                }
            timelineController.markOptimisticVoiceSending(displayId, encrypting = true)
            timelineController.registerOptimisticMediaActions(
                uniqueId = displayId,
                retry = {
                    val completion = CompletableDeferred<Result<Unit>>()
                    sendVoiceMessage(inReplyToEventId, burnAfterRead) { completion.complete(it) }
                    completion.await()
                },
                discard = { sessionCoroutineScope.launch {
                    val source = protectedDraft?.takeIf { it.operationId == owner.operationId } ?: immediateDraft
                    if (owner.sourceReleased.compareAndSet(false, true)) source?.let { protectedVoiceDraftStore.discard(it) }
                    if (voiceEcho?.second == displayId) voiceEcho = null
                    if (protectedDraft?.operationId == owner.operationId) {
                        protectedDraft = null
                        player.clear()
                    }
                    if (pendingPreview?.operationId == owner.operationId) pendingPreview = null
                    handoff?.let { draftHandoffOwner.clear(it) }
                } },
                onUserDiscard = { owner.job?.cancel() },
            )
            val sendJob = sessionCoroutineScope.launch(start = CoroutineStart.LAZY) {
                // Preserve the observable SENDING transition even when a local handoff and the
                // test/disk-backed sender both complete without suspension.
                yield()
                val draftResult = runCatching { immediateDraft ?: requireNotNull(handoff).deferred.await() }
                val sourceDraft = draftResult.getOrNull()
                val authorizedResult = sourceDraft?.let { draft ->
                    runCatching { protectedVoiceDraftStore.reauthorize(draft) }
                } ?: Result.failure(
                    draftResult.exceptionOrNull() ?: IllegalStateException("Voice draft handoff failed")
                )
                val authorizedDraft = authorizedResult.getOrNull()
                authorizedDraft?.let { timelineController.updateOptimisticVoiceInfo(displayId, it.duration, it.waveform) }
                if (sourceDraft != null && authorizedDraft != null && authorizedDraft !== sourceDraft) {
                    if (protectedDraft === sourceDraft) protectedDraft = authorizedDraft
                    player.setMedia(authorizedDraft.uri.toString())
                }
                val result = if (authorizedDraft == null) {
                    Result.failure(authorizedResult.exceptionOrNull() ?: IllegalStateException("Voice draft authorization failed"))
                } else {
                    sendMessage(
                        draft = authorizedDraft,
                        inReplyToEventId = inReplyToEventId.takeUnless { burnAfterRead },
                        burnAfterRead = burnAfterRead,
                        onUploadStarted = {
                            owner.committedToOutbox.set(true)
                            timelineController.markOptimisticVoiceSending(displayId)
                        },
                        onUploadIdentity = { timelineController.bindOptimisticMediaTransport(displayId, it) },
                    )
                }
                timelineController.resolveOptimisticLocalEcho(displayId, result.exceptionOrNull())
                if (result.isFailure) {
                    if (result.exceptionOrNull() is CancellationException) {
                        onResult(result)
                        throw requireNotNull(result.exceptionOrNull())
                    }
                    showSendFailureDialog = true
                } else if (authorizedDraft != null) {
                    if (owner.sourceReleased.compareAndSet(false, true)) {
                        protectedVoiceDraftStore.discard(authorizedDraft)
                    }
                    if (protectedDraft === authorizedDraft) protectedDraft = null
                    if (handoff != null) draftHandoffOwner.clear(handoff)
                }
                onResult(result)
            }
            owner.job = sendJob
            activeSendOwner = owner
            sendJob.invokeOnCompletion {
                if (activeSendOwner === owner) activeSendOwner = null
                isSending = false
            }
            sendJob.start()
            return sendJob
        }

        fun handleEvent(event: VoiceMessageComposerEvent) {
            when (event) {
                is VoiceMessageComposerEvent.RecorderEvent -> handleVoiceMessageRecorderEvent(event.recorderEvent)
                is VoiceMessageComposerEvent.PlayerEvent -> handleVoiceMessagePlayerEvent(event.playerEvent)
                is VoiceMessageComposerEvent.SendVoiceMessage -> {
                    if (!captureFrontendVisible.value) return
                    // Capture reply info eagerly before any coroutine dispatch, since CloseSpecialMode
                    // may reset composerMode before the coroutine runs.
                    val inReplyToEventId = (messageComposerContext.composerMode as? MessageComposerMode.Reply)?.eventId
                    localCoroutineScope.launch {
                        sendVoiceMessage(inReplyToEventId, burnAfterRead = false)
                    }
                }
                is VoiceMessageComposerEvent.SendBurnAfterReadVoiceMessage -> {
                    if (!captureFrontendVisible.value) return
                    localCoroutineScope.launch {
                        sendVoiceMessage(inReplyToEventId = null, burnAfterRead = true)
                    }
                }
                VoiceMessageComposerEvent.DeleteVoiceMessage -> {
                    player.pause()
                    val draft = protectedDraft
                    val handoff = draftHandoffOwner.current()
                    val finishedRecording = recorderState as? VoiceRecorderState.Finished
                    draft?.let {
                        protectedDraft = null
                        localCoroutineScope.launch { protectedVoiceDraftStore.discard(it) }
                    }
                    when {
                        draft != null -> Unit
                        handoff != null -> {
                            val cancelledHandoff = draftHandoffOwner.cancelCurrent()
                            pendingPreview = null
                            if (cancelledHandoff != null) {
                                localCoroutineScope.launch {
                                    draftHandoffMutex.withLock {
                                        if (draftHandoffOwner.current()?.generation?.let { it > cancelledHandoff.generation } == true) return@withLock
                                        voiceRecorderMutex.withLock {
                                            if (draftHandoffOwner.current()?.generation?.let { it > cancelledHandoff.generation } == true) return@withLock
                                            cancelRecordingLocked(cancelledHandoff)
                                            (voiceRecorder.state.value as? VoiceRecorderState.Finished)?.let { voiceRecorder.deleteRecording(it) }
                                        }
                                    }
                                }
                            }
                        }
                        finishedRecording != null -> localCoroutineScope.launch { voiceRecorder.deleteRecording(finishedRecording) }
                    }
                }
                VoiceMessageComposerEvent.DismissPermissionsRationale -> {
                    pendingEvent = null
                    permissionState.eventSink(PermissionsEvent.CloseDialog)
                }
                VoiceMessageComposerEvent.AcceptPermissionRationale -> {
                    permissionState.eventSink(PermissionsEvent.OpenSystemSettingAndCloseDialog)
                }
                is VoiceMessageComposerEvent.LifecycleEvent -> handleLifecycleEvent(event.event)
                VoiceMessageComposerEvent.DismissSendFailureDialog -> {
                    showSendFailureDialog = false
                }
                VoiceMessageComposerEvent.DismissRecordingPermissionLostDialog -> {
                    showRecordingPermissionLostDialog = false
                }
            }
        }

        return VoiceMessageComposerState(
            voiceMessageState = when (val state = recorderState) {
                is VoiceRecorderState.Recording -> VoiceMessageState.Recording(
                    duration = state.elapsedTime,
                    levels = state.levels
                        // Keep only the last 128 samples for display, else we can have a crash
                        .takeLast(128)
                        .toImmutableList(),
                )
                is VoiceRecorderState.Finalizing -> VoiceMessageState.Recording(
                    duration = state.elapsedTime,
                    levels = state.levels.takeLast(128).toImmutableList(),
                )
                is VoiceRecorderState.Finished -> protectedDraft?.let { draft ->
                    previewState(
                        playerState = playerState,
                        draft = draft,
                        isSending = isSending,
                    )
                } ?: previewState(
                    playerState = playerState,
                    duration = state.duration,
                    waveform = state.waveform,
                    isSending = isSending,
                )
                else -> protectedDraft?.let { draft ->
                    previewState(
                        playerState = playerState,
                        draft = draft,
                        isSending = isSending
                    )
                } ?: pendingPreview?.let { pending ->
                    previewState(
                        playerState = playerState,
                        duration = pending.duration,
                        waveform = pending.waveform,
                        isSending = isSending,
                    )
                } ?: VoiceMessageState.Idle
            },
            showPermissionRationaleDialog = permissionState.showDialog,
            showSendFailureDialog = showSendFailureDialog,
            showRecordingPermissionLostDialog = showRecordingPermissionLostDialog,
            keepScreenOn = keepScreenOn,
            eventSink = ::handleEvent,
        )
    }

    @Composable
    private fun previewState(
        playerState: VoiceMessageComposerPlayer.State,
        draft: ProtectedVoiceDraft,
        isSending: Boolean,
    ): VoiceMessageState = previewState(
        playerState = playerState,
        duration = draft.duration,
        waveform = draft.waveform,
        isSending = isSending,
    )

    @Composable
    private fun previewState(
        playerState: VoiceMessageComposerPlayer.State,
        duration: Duration,
        waveform: List<Float>,
        isSending: Boolean,
    ): VoiceMessageState {
        val showCursor by remember(playerState.isStopped, isSending) { derivedStateOf { !playerState.isStopped && !isSending } }
        val playerTime by remember(playerState, duration) { derivedStateOf { displayTime(playerState, duration) } }
        val immutableWaveform by remember(waveform) { derivedStateOf { waveform.toImmutableList() } }

        return VoiceMessageState.Preview(
            isSending = isSending,
            isPlaying = playerState.isPlaying,
            showCursor = showCursor,
            playbackProgress = playerState.progress,
            time = playerTime,
            waveform = immutableWaveform,
        )
    }

    private fun CoroutineScope.startRecording(
        handoff: VoiceDraftHandoff,
        onFailure: () -> Unit,
    ) = launch {
        voiceRecorderMutex.withLock {
            if (!draftHandoffOwner.isCurrent(handoff)) return@withLock
            val ownerId = captureOwnerId(handoff)
            val captureOperationId = runCatching { captureLifecycle.operationId(handoff.operationId) }.getOrElse {
                Timber.w(it, "Voice capture operational scope is unavailable")
                onFailure()
                return@withLock
            }
            var prepared = false
            try {
                voiceRecorder.prepareCapture(captureOperationId)
                prepared = true
                captureLifecycle.admit(captureOperationId)
                if (!draftHandoffOwner.isCurrent(handoff)) {
                    voiceRecorder.discardPreparedCapture(captureOperationId)
                    return@withLock
                }
                val acquired = audioFocus.acquire(
                    AudioSessionRequest(
                        ownerId = ownerId,
                        role = AudioSessionRole.VoiceMessageCapture,
                        operationId = handoff.operationId,
                        onOwnershipLost = {
                            sessionCoroutineScope.finishRecording(handoff, VoiceRecorderStopReason.INTERRUPTED, ownerId)
                        },
                    )
                )
                if (!acquired || !draftHandoffOwner.isCurrent(handoff)) {
                    if (acquired) audioFocus.release(ownerId)
                    voiceRecorder.discardPreparedCapture(captureOperationId)
                    onFailure()
                    return@withLock
                }
                captureOwnerId = ownerId
                voiceRecorder.startRecord(captureOperationId)
                // Start the microphone first. Resolve the same signed source observation while
                // recording, so a short send can join it instead of starting a second refresh.
                // No lease or send operation is issued here; the protected handoff rechecks both.
                launch {
                    try {
                        if (draftHandoffOwner.isCurrent(handoff)) protectedVoiceDraftStore.prepareSourceAuthorization()
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        Timber.w(failure, "Voice source preparation unavailable; handoff will recheck authorization")
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    audioFocus.release(ownerId)
                    if (captureOwnerId == ownerId) {
                        voiceRecorder.stopRecord(VoiceRecorderStopReason.CANCELLED)
                        captureOwnerId = null
                    } else if (prepared) voiceRecorder.discardPreparedCapture(captureOperationId)
                }
                throw e
            } catch (e: Exception) {
                audioFocus.release(ownerId)
                if (captureOwnerId == ownerId) {
                    voiceRecorder.stopRecord(VoiceRecorderStopReason.CANCELLED)
                    captureOwnerId = null
                } else if (prepared) voiceRecorder.discardPreparedCapture(captureOperationId)
                Timber.e(e, "Voice capture admission or recording failed")
                if (e is SecurityException) {
                    analyticsService.trackError(VoiceMessageException.PermissionMissing("Expected permission to record but none", e))
                }
                onFailure()
            }
        }
    }

    private fun CoroutineScope.finishRecording(
        handoff: VoiceDraftHandoff,
        reason: VoiceRecorderStopReason,
        ownerId: String? = captureOwnerId,
    ) = launch {
        voiceRecorderMutex.withLock {
            if (!draftHandoffOwner.isCurrent(handoff) || captureOwnerId != ownerId) return@withLock
            voiceRecorder.stopRecord(reason)
            ownerId?.let(audioFocus::release)
            if (captureOwnerId == ownerId) captureOwnerId = null
        }
    }

    private suspend fun cancelRecordingLocked(handoff: VoiceDraftHandoff) {
        val expectedOwnerId = captureOwnerId(handoff)
        if (captureOwnerId != expectedOwnerId) return
        voiceRecorder.stopRecord(VoiceRecorderStopReason.CANCELLED)
        audioFocus.release(expectedOwnerId)
        if (captureOwnerId == expectedOwnerId) captureOwnerId = null
    }

    private fun captureOwnerId(handoff: VoiceDraftHandoff): String = "voice-capture:${handoff.operationId}"

    private suspend fun sendMessage(
        draft: ProtectedVoiceDraft,
        inReplyToEventId: EventId? = null,
        burnAfterRead: Boolean = false,
        onUploadStarted: () -> Unit = {},
        onUploadIdentity: (String) -> Unit = {},
    ): Result<Unit> {
        val lifecycleId = if (burnAfterRead) FullAppLifecycleId.LC_21 else FullAppLifecycleId.LC_17
        val trigger = if (burnAfterRead) "message_commit" else "record_stop"
        val result = runCatching {
            check(matrixClient.sessionId == room.sessionId) { "Voice send account scope changed." }
            appOperationalStateCoordinator.perform(
                lifecycleOperationRequest(
                    lifecycleId = lifecycleId,
                    trigger = trigger,
                    accountIdHash = matrixClient.sessionId.value.sha256Hex(),
                    matrixDeviceIdHash = matrixClient.deviceId.value.sha256Hex(),
                    roomIdHash = room.roomId.value.sha256Hex(),
                    operationId = draft.operationId,
                    deadlineEpochMillis = draft.authorizationDeadlineEpochMillis,
                )
            ) {
                mediaSender.sendVoiceMessage(
                    uri = draft.uri,
                    mimeType = draft.mimeType,
                    waveForm = draft.waveform,
                    inReplyToEventId = inReplyToEventId,
                    burnAfterRead = burnAfterRead,
                    onUploadStarted = onUploadStarted,
                    onUploadIdentity = onUploadIdentity,
                ).getOrThrow()
            }
        }

        if (result.isFailure) {
            Timber.e(result.exceptionOrNull(), "Voice message error")
            return result
        }

        return result
    }

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun AnalyticsService.captureComposerEvent() =
        capture(
            Composer(
                inThread = messageComposerContext.composerMode.inThread,
                isEditing = messageComposerContext.composerMode.isEditing,
                isReply = messageComposerContext.composerMode.isReply,
                messageType = Composer.MessageType.VoiceMessage,
            )
        )
}

/**
 * The time to display depending on the player state.
 *
 * Either the current position or total duration.
 */
private fun displayTime(
    playerState: VoiceMessageComposerPlayer.State,
    recordingDuration: Duration,
): Duration = when {
    !playerState.isStopped ->
        playerState.currentPosition.milliseconds
    else -> recordingDuration
}
