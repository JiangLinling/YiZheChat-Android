/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.voicemessages.composer

import android.Manifest
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import kotlinx.coroutines.flow.first
import androidx.lifecycle.Lifecycle
import app.cash.turbine.TurbineTestContext
import com.google.common.truth.Truth.assertThat
import im.vector.app.features.analytics.plan.Composer
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerState
import io.element.android.features.messages.impl.messagecomposer.aReplyMode
import io.element.android.features.messages.test.FakeMessageComposerContext
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.audio.api.AudioFocusRequester
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.ProtectedMediaSource
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.media.FakeMediaUploadHandler
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediaplayer.test.FakeAudioFocus
import io.element.android.libraries.mediaplayer.test.FakeMediaPlayer
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaSender
import io.element.android.libraries.mediaupload.impl.DefaultMediaSender
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.mediaupload.test.FakeMediaSender
import io.element.android.libraries.permissions.api.PermissionsPresenter
import io.element.android.libraries.permissions.api.aPermissionsState
import io.element.android.libraries.permissions.test.FakePermissionsPresenter
import io.element.android.libraries.permissions.test.FakePermissionsPresenterFactory
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.VoiceMessagePlayerEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.voiceplayer.api.VoiceMessageException
import io.element.android.libraries.voicerecorder.api.VoiceRecorder
import io.element.android.libraries.voicerecorder.api.VoiceRecorderState
import io.element.android.libraries.voicerecorder.api.VoiceRecorderStopReason
import io.element.android.libraries.voicerecorder.test.FakeVoiceRecorder
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.any
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.test
import io.mockk.mockk
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Suppress("LargeClass")
class DefaultVoiceMessageComposerPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    private val voiceRecorder = FakeVoiceRecorder(
        recordingDuration = RECORDING_DURATION
    )
    private val analyticsService = FakeAnalyticsService()
    private val sendVoiceMessageResult =
        lambdaRecorder<ProtectedMediaSource, AudioInfo, List<Float>, EventId?, Boolean, Result<FakeMediaUploadHandler>> { _, _, _, _, _ ->
            Result.success(FakeMediaUploadHandler())
        }
    private val joinedRoom = FakeJoinedRoom(
        liveTimeline = FakeTimeline().apply {
            sendProtectedVoiceMessageLambda = sendVoiceMessageResult
        },
    )
    private val mediaPreProcessor = FakeMediaPreProcessor().apply { givenAudioResult() }
    private val matrixClient = FakeMatrixClient(sessionId = joinedRoom.sessionId)
    private val mediaSender = DefaultMediaSender(
        preProcessor = mediaPreProcessor,
        room = joinedRoom,
        timelineMode = Timeline.Mode.Live,
        mediaOptimizationConfigProvider = { MediaOptimizationConfig(compressImages = true, videoCompressionPreset = VideoCompressionPreset.STANDARD) },
    )
    private val requestAudioFocusResult = lambdaRecorder<AudioFocusRequester, () -> Unit, Unit> { _, _ -> }
    private val releaseAudioFocusResult = lambdaRecorder<Unit> { }
    private val audioFocus: AudioFocus = FakeAudioFocus(
        requestAudioFocusResult = requestAudioFocusResult,
        releaseAudioFocusResult = releaseAudioFocusResult,
    )
    private val messageComposerContext = FakeMessageComposerContext()
    private val appOperationalStateCoordinator = FakeAppOperationalStateCoordinator()
    private val protectedVoiceDraftStore = FakeProtectedVoiceDraftStore()

    companion object {
        private val RECORDING_DURATION = 1.seconds
        private val RECORDING_STATE = VoiceMessageState.Recording(RECORDING_DURATION, listOf(0.1f, 0.2f).toImmutableList())
    }

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            voiceRecorder.assertCalls(started = 0)

            testPauseAndDestroy(initialState)
        }
    }

    private class PendingCleanupRecorder : VoiceRecorder by FakeVoiceRecorder() {
        val pending = linkedSetOf<String>()
        val acknowledged = mutableListOf<String>()
        val revision = MutableStateFlow(0L)
        override val captureCleanupRevision: StateFlow<Long> = revision
        override fun pendingCaptureCleanups(): Set<String> = pending.toSet()
        override fun acknowledgeCaptureCleanup(operationId: String) {
            check(pending.remove(operationId))
            acknowledged += operationId
        }
    }

    private fun recoveredCaptureId(): String {
        val accountHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(joinedRoom.sessionId.value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val deviceHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(matrixClient.deviceId.value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "lc17-capture-${accountHash.take(16)}-${deviceHash.take(16)}-11111111-1111-4111-a111-111111111111"
    }

    @Test
    fun `recovered recording with current permission denied shows stopped notice and retains original cleanup identity`() = runTest {
        val recorder = PendingCleanupRecorder()
        val original = recoveredCaptureId()
        recorder.pending += original
        val presenter = createDefaultVoiceMessageComposerPresenter(
            voiceRecorder = recorder,
            permissionsPresenter = createFakePermissionsPresenter(recordPermissionGranted = false),
        )
        presenter.test {
            awaitItem()
            runCurrent()
            val warned = expectMostRecentItem()
            assertThat(warned.showRecordingPermissionLostDialog).isTrue()
            assertThat(warned.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            assertThat(recorder.acknowledged).containsExactly(original)
            assertThat(appOperationalStateCoordinator.clientReceipts.map { it.operationId }.distinct()).containsExactly(original)
            warned.eventSink(VoiceMessageComposerEvent.DismissRecordingPermissionLostDialog)
            assertThat(awaitItem().showRecordingPermissionLostDialog).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `settings covered frontend retains native cleanup until actual return and then explains revoked permission`() = runTest {
        val recorder = PendingCleanupRecorder()
        val original = recoveredCaptureId()
        val presenter = createDefaultVoiceMessageComposerPresenter(
            voiceRecorder = recorder,
            permissionsPresenter = createFakePermissionsPresenter(recordPermissionGranted = false),
        )
        presenter.test {
            val initial = awaitItem()
            initial.eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_PAUSE))
            runCurrent()
            recorder.pending += original
            recorder.revision.value++
            runCurrent()
            assertThat(recorder.acknowledged).isEmpty()
            assertThat(appOperationalStateCoordinator.clientReceipts).isEmpty()
            initial.eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_RESUME))
            runCurrent()
            assertThat(expectMostRecentItem().showRecordingPermissionLostDialog).isTrue()
            assertThat(recorder.acknowledged).containsExactly(original)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `current permission loss cancels active recording and releases audio focus without sending`() = runTest {
        val permissions = createFakePermissionsPresenter()
        val presenter = createDefaultVoiceMessageComposerPresenter(permissionsPresenter = permissions)
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            assertThat(awaitItem().voiceMessageState).isEqualTo(RECORDING_STATE)
            permissions.setPermissionDenied()
            runCurrent()
            val stopped = expectMostRecentItem()
            assertThat(stopped.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            assertThat(stopped.showRecordingPermissionLostDialog).isTrue()
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)
            assertThat(protectedVoiceDraftStore.protectedOperationIds).isEmpty()
            assertThat(appOperationalStateCoordinator.startedRequests.none { it.trigger == "record_stop" }).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `release callbacks after settings cover composer cannot send ordinary or flash voice`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            val recording = awaitItem()
            recording.eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_PAUSE))
            recording.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            recording.eventSink(VoiceMessageComposerEvent.SendBurnAfterReadVoiceMessage)
            runCurrent()
            assertThat(appOperationalStateCoordinator.startedRequests.none { it.trigger == "record_stop" || it.trigger == "message_commit" }).isTrue()
            sendVoiceMessageResult.assertions().isNeverCalled()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - recording state`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(RECORDING_STATE)
            voiceRecorder.assertCalls(started = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `source preparation begins after recording without waiting to start microphone or authorizing a send`() = runTest {
        val store = FakeProtectedVoiceDraftStore().apply { suspendPreparation = true }
        val presenter = createDefaultVoiceMessageComposerPresenter(protectedVoiceDraftStore = store)
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            val recording = awaitItem()
            runCurrent()
            assertThat(recording.voiceMessageState).isEqualTo(RECORDING_STATE)
            voiceRecorder.assertCalls(started = 1)
            assertThat(store.preparationStarted.isCompleted).isTrue()
            assertThat(store.protectedOperationIds).isEmpty()
            assertThat(appOperationalStateCoordinator.startedRequests.none { it.trigger == "record_stop" }).isTrue()
            store.allowPreparation.complete(Unit)
            testPauseAndDestroy(recording)
        }
    }

    @Test
    fun `present - recording state - number of levels is limited`() = runTest {
        val numberOfLevels = 200
        val levels = List(numberOfLevels) { it / numberOfLevels.toFloat() }
        val voiceRecorder = FakeVoiceRecorder(
            levels = levels,
            recordingDuration = RECORDING_DURATION,
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(
            voiceRecorder = voiceRecorder,
        )
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            skipItems(numberOfLevels / 2 - 1)
            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isInstanceOf(VoiceMessageState.Recording::class.java)
            val recordingState = finalState.voiceMessageState as VoiceMessageState.Recording
            // The number of levels should be limited to 128 items
            assertThat(recordingState.levels.size).isEqualTo(128)
            assertThat(recordingState.levels).isEqualTo(levels.takeLast(128))
            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - recording keeps screen on`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().apply {
                eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
                assertThat(keepScreenOn).isFalse()
            }

            awaitItem().apply {
                assertThat(keepScreenOn).isTrue()
                eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            }

            val finalState = awaitItem().apply {
                assertThat(keepScreenOn).isFalse()
            }

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - recording requests audio focus and releases on stop`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            val recordingState = awaitItem()
            requestAudioFocusResult.assertions().isCalledOnce()
            releaseAudioFocusResult.assertions().isNeverCalled()

            recordingState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem()
            releaseAudioFocusResult.assertions().isCalledOnce()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - cancelling recording releases audio focus`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Cancel))
            awaitItem()
            requestAudioFocusResult.assertions().isCalledOnce()
            releaseAudioFocusResult.assertions().isCalledOnce()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - audio focus loss during recording finishes gracefully`() = runTest {
        var onFocusLost: (() -> Unit)? = null
        val testAudioFocus = FakeAudioFocus(
            requestAudioFocusResult = { _, callback -> onFocusLost = callback },
            releaseAudioFocusResult = { },
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(audioFocus = testAudioFocus)
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem()

            // simulate focus loss (phone call, etc)
            onFocusLost?.invoke()
            advanceUntilIdle()

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(aPreviewState())
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - abort recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Cancel))
            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)
            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - finish recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(aPreviewState())
            // The plaintext recorder scratch file is gone before preview is exposed.
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - stale protection completion cannot replace or delete a newer recording`() = runTest {
        val voiceRecorder = FakeVoiceRecorder(recordingDuration = RECORDING_DURATION)
        val draftStore = FakeProtectedVoiceDraftStore().apply { suspendFirstProtection = true }
        val presenter = createDefaultVoiceMessageComposerPresenter(
            voiceRecorder = voiceRecorder,
            protectedVoiceDraftStore = draftStore,
        )
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            draftStore.firstProtectionStarted.await()

            expectMostRecentItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            advanceUntilIdle()
            val newerRecordingState = expectMostRecentItem()
            assertThat(newerRecordingState.voiceMessageState).isInstanceOf(VoiceMessageState.Recording::class.java)

            draftStore.allowFirstProtectionToFinish.complete(Unit)
            advanceUntilIdle()

            assertThat(voiceRecorder.state.value).isInstanceOf(VoiceRecorderState.Recording::class.java)
            assertThat(draftStore.discardedOperationIds).containsExactly(draftStore.protectedOperationIds.first())
            assertThat(draftStore.protectedOperationIds).hasSize(1)

            newerRecordingState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Cancel))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - gesture stop followed by pause does not stop and delete the finished scratch twice`() = runTest {
        val voiceRecorder = FakeVoiceRecorder(
            recordingDuration = RECORDING_DURATION,
            deleteOnStopWhenNotRecording = true,
        )
        val draftStore = FakeProtectedVoiceDraftStore().apply { suspendFirstProtection = true }
        val presenter = createDefaultVoiceMessageComposerPresenter(
            voiceRecorder = voiceRecorder,
            protectedVoiceDraftStore = draftStore,
        )

        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            val recordingState = awaitItem()
            recordingState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            recordingState.eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_PAUSE))
            draftStore.firstProtectionStarted.await()
            runCurrent()

            // The first stop owns the finished scratch while protection is suspended. A second
            // physical stop here reproduces DefaultVoiceRecorder's zero-duration deletion path.
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 0)

            draftStore.allowFirstProtectionToFinish.complete(Unit)
            advanceUntilIdle()

            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)
            assertThat(expectMostRecentItem().showSendFailureDialog).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - destroy waits for pause protection writer before deleting scratch`() = runTest {
        val voiceRecorder = FakeVoiceRecorder(recordingDuration = RECORDING_DURATION)
        val draftStore = FakeProtectedVoiceDraftStore().apply { suspendFirstProtection = true }
        val presenter = createDefaultVoiceMessageComposerPresenter(
            voiceRecorder = voiceRecorder,
            protectedVoiceDraftStore = draftStore,
        )

        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_PAUSE))
            draftStore.firstProtectionStarted.await()

            expectMostRecentItem().eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_DESTROY))
            runCurrent()
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 0)

            draftStore.allowFirstProtectionToFinish.complete(Unit)
            advanceUntilIdle()

            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)
            assertThat(draftStore.discardedOperationIds).containsExactly(draftStore.protectedOperationIds.single())
            assertThat(expectMostRecentItem().showSendFailureDialog).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - destroy waits for LaunchedEffect protection writer before recorder cancellation`() = runTest {
        val voiceRecorder = FakeVoiceRecorder(recordingDuration = RECORDING_DURATION)
        val draftStore = FakeProtectedVoiceDraftStore().apply { suspendFirstProtection = true }
        val presenter = createDefaultVoiceMessageComposerPresenter(
            voiceRecorder = voiceRecorder,
            protectedVoiceDraftStore = draftStore,
        )

        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem()
            voiceRecorder.stopRecord(VoiceRecorderStopReason.USER_STOP)
            draftStore.firstProtectionStarted.await()

            expectMostRecentItem().eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_DESTROY))
            runCurrent()
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 0)

            draftStore.allowFirstProtectionToFinish.complete(Unit)
            advanceUntilIdle()

            assertThat(draftStore.discardedOperationIds).containsExactly(draftStore.protectedOperationIds.single())
            assertThat(expectMostRecentItem().showSendFailureDialog).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - old destroy cleanup does not stop or delete a newer recording generation`() = runTest {
        val voiceRecorder = FakeVoiceRecorder(recordingDuration = RECORDING_DURATION)
        val draftStore = FakeProtectedVoiceDraftStore().apply { suspendFirstProtection = true }
        val presenter = createDefaultVoiceMessageComposerPresenter(
            voiceRecorder = voiceRecorder,
            protectedVoiceDraftStore = draftStore,
        )

        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_PAUSE))
            draftStore.firstProtectionStarted.await()

            val oldGenerationState = expectMostRecentItem()
            oldGenerationState.eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_DESTROY))
            oldGenerationState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            runCurrent()
            val newerRecordingState = expectMostRecentItem()
            assertThat(newerRecordingState.voiceMessageState).isInstanceOf(VoiceMessageState.Recording::class.java)

            draftStore.allowFirstProtectionToFinish.complete(Unit)
            advanceUntilIdle()

            assertThat(voiceRecorder.state.value).isInstanceOf(VoiceRecorderState.Recording::class.java)
            assertThat(draftStore.discardedOperationIds).containsExactly(draftStore.protectedOperationIds.single())

            newerRecordingState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Cancel))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - session cleanup survives presenter scope cancellation`() = runTest {
        val voiceRecorder = FakeVoiceRecorder(recordingDuration = RECORDING_DURATION)
        val draftStore = FakeProtectedVoiceDraftStore().apply { suspendFirstProtection = true }
        val presenter = createDefaultVoiceMessageComposerPresenter(
            voiceRecorder = voiceRecorder,
            protectedVoiceDraftStore = draftStore,
        )

        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_PAUSE))
            draftStore.firstProtectionStarted.await()
            expectMostRecentItem().eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_DESTROY))
            cancelAndIgnoreRemainingEvents()
        }

        advanceUntilIdle()
        voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)
    }

    @Test
    fun `present - play recording before it is ready`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            val finalState = awaitItem().apply {
                this.eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Play))
            }

            // Nothing should happen
            assertThat(finalState.voiceMessageState).isEqualTo(RECORDING_STATE)
            voiceRecorder.assertCalls(started = 1, stopped = 0, deleted = 0)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - play recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Play))
            val finalState = awaitItem().also {
                assertThat(it.voiceMessageState).isEqualTo(aPlayingState())
            }
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - pause recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Play))
            awaitItem().eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Pause))
            val finalState = awaitItem().also {
                assertThat(it.voiceMessageState).isEqualTo(aPausedState())
            }
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - seek recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Seek(0.5f)))
            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState(playbackProgress = 0.5f, time = 0.seconds, showCursor = true))
            }
            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState(playbackProgress = 0.5f, time = 5.seconds, showCursor = true))
                eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Seek(0.2f)))
            }
            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState(playbackProgress = 0.2f, time = 5.seconds, showCursor = true))
            }
            val finalState = awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState(playbackProgress = 0.2f, time = 2.seconds, showCursor = true))
            }

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - delete recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.DeleteVoiceMessage)

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - delete while playing`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Play))
            awaitItem().eventSink(VoiceMessageComposerEvent.DeleteVoiceMessage)
            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPausedState())
            }

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `voice bubble exists while encrypted draft handoff is still blocked`() = runTest {
        val store = FakeProtectedVoiceDraftStore().apply { suspendFirstProtection = true }
        val controller = TimelineController(joinedRoom, joinedRoom.liveTimeline)
        val uploadGate = CompletableDeferred<Unit>()
        val sender = FakeMediaSender(beforeVoiceUploadStarted = { uploadGate.await() }, sendVoiceMessageResult = { Result.success(Unit) })
        val presenter = createDefaultVoiceMessageComposerPresenter(protectedVoiceDraftStore = store, mediaSender = sender, timelineController = controller)
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            val recording = awaitItem()
            recording.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            recording.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            runCurrent()
            assertThat(store.firstProtectionStarted.isCompleted).isTrue()
            assertThat(store.allowFirstProtectionToFinish.isCompleted).isFalse()
            val pending = controller.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single()
            assertThat(pending.event.localSendState).isEqualTo(LocalEventSendState.Sending.Encrypting)
            assertThat(((pending.event.content as MessageContent).type as VoiceMessageType).source.safeUrl).isEmpty()
            val displayId = pending.displayUniqueId
            store.allowFirstProtectionToFinish.complete(Unit)
            runCurrent()
            assertThat(controller.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single().event.localSendState)
                .isEqualTo(LocalEventSendState.Sending.Encrypting)
            uploadGate.complete(Unit)
            runCurrent()
            val sending = controller.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single()
            assertThat(sending.displayUniqueId).isEqualTo(displayId)
            assertThat(sending.event.localSendState).isEqualTo(LocalEventSendState.Sending.Event)
            assertThat(store.discardedOperationIds).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
        controller.close()
    }

    @Test
    fun `failed voice bubble retry keeps operation and display identity`() = runTest {
        val controller = TimelineController(joinedRoom, joinedRoom.liveTimeline)
        var fail = true
        val sender = FakeMediaSender(beforeVoiceUploadStarted = {
            if (fail) error("voice transfer unavailable")
        }, sendVoiceMessageResult = { Result.success(Unit) })
        val presenter = createDefaultVoiceMessageComposerPresenter(mediaSender = sender, timelineController = controller)
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            runCurrent()
            val failed = controller.timelineItems().first { items ->
                items.filterIsInstance<MatrixTimelineItem.Event>().single().event.localSendState is LocalEventSendState.Failed
            }.filterIsInstance<MatrixTimelineItem.Event>().single()
            fail = false
            assertThat(controller.retryOptimisticLocalEcho(failed.displayUniqueId).isSuccess).isTrue()
            val retried = controller.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single()
            assertThat(retried.displayUniqueId).isEqualTo(failed.displayUniqueId)
            assertThat(retried.event.localSendState).isEqualTo(LocalEventSendState.Sending.Event)
            assertThat(appOperationalStateCoordinator.startedRequests.filter { it.trigger == "record_stop" }.map { it.operationId }.distinct()).hasSize(1)
            assertThat(appOperationalStateCoordinator.startedRequests.filter { it.trigger == "record_start" }).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
        controller.close()
    }

    @Test
    fun `too short recording settles immediate bubble as failed instead of waiting forever`() = runTest {
        val states = MutableStateFlow<VoiceRecorderState>(VoiceRecorderState.Idle)
        val recorder = object : VoiceRecorder by voiceRecorder {
            override val state: StateFlow<VoiceRecorderState> = states
            override suspend fun startRecord() {
                states.value = VoiceRecorderState.Recording(0.1.seconds, emptyList())
            }
            override suspend fun startRecord(operationId: String) = startRecord()
            override suspend fun stopRecord(reason: VoiceRecorderStopReason) {
                states.value = VoiceRecorderState.Failed(io.element.android.libraries.voicerecorder.api.VoiceRecorderFailure.TOO_SHORT)
            }
        }
        val controller = TimelineController(joinedRoom, joinedRoom.liveTimeline)
        val presenter = createDefaultVoiceMessageComposerPresenter(voiceRecorder = recorder, timelineController = controller)
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            val recording = awaitItem()
            recording.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            recording.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            runCurrent()
            val failed = controller.timelineItems().first { items ->
                items.filterIsInstance<MatrixTimelineItem.Event>().single().event.localSendState is LocalEventSendState.Failed
            }.filterIsInstance<MatrixTimelineItem.Event>().single()
            assertThat(failed.event.localSendState).isInstanceOf(LocalEventSendState.Failed::class.java)
            sendVoiceMessageResult.assertions().isNeverCalled()
            cancelAndIgnoreRemainingEvents()
        }
        controller.close()
    }

    @Test
    fun `present - send recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())
            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            sendVoiceMessageResult.assertions().isCalledOnce()
            val capture = appOperationalStateCoordinator.startedRequests.single { it.trigger == "record_start" }
            assertThat(capture.operationId).startsWith("lc17-capture-")
            assertThat(capture.accountIdHash).isNotNull()
            assertThat(capture.matrixDeviceIdHash).isNotNull()
            val send = appOperationalStateCoordinator.startedRequests.single { it.trigger == "record_stop" }
            assertThat(send.accountIdHash).isEqualTo(capture.accountIdHash)
            assertThat(send.matrixDeviceIdHash).isEqualTo(capture.matrixDeviceIdHash)
            assertThat(send.roomIdHash).isEqualTo(capture.roomIdHash)
            assertThat(appOperationalStateCoordinator.startedRequests.single { it.trigger == "record_stop" }.lifecycleId)
                .isEqualTo(FullAppLifecycleId.LC_17)
            assertThat(appOperationalStateCoordinator.startedRequests.single { it.trigger == "record_stop" }.trigger)
                .isEqualTo("record_stop")
            assertThat(protectedVoiceDraftStore.protectedOperationIds)
                .containsExactly(appOperationalStateCoordinator.startedRequests.single { it.trigger == "record_stop" }.operationId)
            assertThat(protectedVoiceDraftStore.reauthorizedOperationIds)
                .containsExactly(appOperationalStateCoordinator.startedRequests.single { it.trigger == "record_stop" }.operationId)
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send rejects a room from another account before creating a send operation`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter(
            matrixClient = FakeMatrixClient(sessionId = SessionId("@another:example.org")),
        )
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())
            val failure = awaitItem()
            assertThat(failure.voiceMessageState).isEqualTo(aPreviewState().toSendingState())
            assertThat(failure.showSendFailureDialog).isTrue()
            assertThat(appOperationalStateCoordinator.startedRequests.none { it.trigger == "record_stop" }).isTrue()
            sendVoiceMessageResult.assertions().isNeverCalled()
            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState())
                assertThat(showSendFailureDialog).isTrue()
                eventSink(VoiceMessageComposerEvent.DismissSendFailureDialog)
            }
            val finalState = awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState())
                assertThat(showSendFailureDialog).isFalse()
            }
            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - revocation clears protected preview and player immediately`() = runTest {
        val draftStore = FakeProtectedVoiceDraftStore()
        val fakeMediaPlayer = FakeMediaPlayer(fakePrepareDelayMs = 0L)
        val presenter = createDefaultVoiceMessageComposerPresenter(
            protectedVoiceDraftStore = draftStore,
            mediaPlayer = fakeMediaPlayer,
        )

        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState())
            assertThat(fakeMediaPlayer.state.value.mediaId).isNotNull()

            draftStore.revokeComposerDrafts()
            advanceUntilIdle()

            assertThat(expectMostRecentItem().voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            assertThat(fakeMediaPlayer.state.value.mediaId).isNull()
            assertThat(draftStore.discardedOperationIds).containsExactly(draftStore.protectedOperationIds.single())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - revocation cancels an uncommitted send without showing failure`() = runTest {
        val preprocessingGate = CompletableDeferred<Unit>()
        val draftStore = FakeProtectedVoiceDraftStore()
        var sendCalled = false
        val sender = FakeMediaSender(
            beforeVoiceUploadStarted = { preprocessingGate.await() },
            sendVoiceMessageResult = {
                sendCalled = true
                Result.success(Unit)
            },
            cleanUpResult = {},
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(
            protectedVoiceDraftStore = draftStore,
            mediaSender = sender,
        )

        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState(isSending = true))
            runCurrent()

            draftStore.revokeComposerDrafts()
            runCurrent()

            val revoked = expectMostRecentItem()
            assertThat(revoked.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            assertThat(revoked.showSendFailureDialog).isFalse()
            assertThat(sendCalled).isFalse()
            assertThat(draftStore.discardedOperationIds).containsExactly(draftStore.protectedOperationIds.single())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - revocation clears composer but does not cancel committed outbox send`() = runTest {
        val draftStore = FakeProtectedVoiceDraftStore()
        val sendAwaitStarted = CompletableDeferred<Unit>()
        val sendCompletion = CompletableDeferred<Unit>()
        val sender = FakeMediaSender(
            sendVoiceMessageResult = {
                sendAwaitStarted.complete(Unit)
                sendCompletion.await()
                Result.success(Unit)
            },
            cleanUpResult = {},
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(
            protectedVoiceDraftStore = draftStore,
            mediaSender = sender,
        )

        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState(isSending = true))
            runCurrent()
            assertThat(sendAwaitStarted.isCompleted).isTrue()

            draftStore.revokeComposerDrafts()
            runCurrent()

            assertThat(expectMostRecentItem().voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            assertThat(draftStore.discardedOperationIds).containsExactly(draftStore.protectedOperationIds.single())

            sendCompletion.complete(Unit)
            runCurrent()
            assertThat(sendCompletion.isCancelled).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - sending is tracked`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            // Send a normal voice message
            messageComposerContext.composerMode = MessageComposerMode.Normal
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            skipItems(1) // Sending state
            advanceUntilIdle()
            // Now reply with a voice message
            messageComposerContext.composerMode = aReplyMode()
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            val finalState = awaitItem() // Sending state

            assertThat(analyticsService.capturedEvents).containsExactly(
                aVoiceMessageComposerEvent(isReply = false),
                aVoiceMessageComposerEvent(isReply = true)
            )

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send voice message passes reply event ID only when in reply mode`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            // First send in Normal mode (default composerMode).
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())
            val idleAfterFirstSend = awaitItem()
            assertThat(idleAfterFirstSend.voiceMessageState).isEqualTo(VoiceMessageState.Idle)

            // Switching to reply mode does not trigger recomposition, so reuse the prior eventSink.
            messageComposerContext.composerMode = aReplyMode()
            idleAfterFirstSend.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())
            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)

            sendVoiceMessageResult.assertions().isCalledExactly(2)
                .withSequence(
                    listOf(any(), any(), any(), value(null), value(false)),
                    listOf(any(), any(), any(), value(AN_EVENT_ID), value(false)),
                )

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send while playing`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Play))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPlayingState().toSendingState())
            skipItems(1) // Duplicate sending state

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            sendVoiceMessageResult.assertions().isCalledOnce()
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send recording before previous completed, waits`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().run {
                eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
                eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            }
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            sendVoiceMessageResult.assertions().isCalledOnce()
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send failures aren't tracked`() = runTest {
        // Let sending fail due to media preprocessing error
        mediaPreProcessor.givenResult(Result.failure(Exception()))
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState())
                eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            }

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(aPreviewState(isSending = true))
            sendVoiceMessageResult.assertions().isNeverCalled()
            assertThat(analyticsService.trackedErrors).isEmpty()
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send failures can be retried`() = runTest {
        // Let sending fail due to media preprocessing error
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            mediaPreProcessor.givenResult(Result.failure(Exception()))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            val previewState = awaitItem()

            previewState.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())

            ensureAllEventsConsumed()
            assertThat(previewState.voiceMessageState).isEqualTo(aPreviewState())
            sendVoiceMessageResult.assertions().isNeverCalled()

            mediaPreProcessor.givenAudioResult()
            previewState.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            sendVoiceMessageResult.assertions().isCalledOnce()
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send failures are displayed as an error dialog`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            // Let sending fail due to media preprocessing error
            mediaPreProcessor.givenResult(Result.failure(Exception()))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)

            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())

            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState().toSendingState())
                assertThat(showSendFailureDialog).isTrue()
            }

            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState())
                assertThat(showSendFailureDialog).isTrue()
                eventSink(VoiceMessageComposerEvent.DismissSendFailureDialog)
            }

            val finalState = awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState())
                assertThat(showSendFailureDialog).isFalse()
            }

            sendVoiceMessageResult.assertions().isNeverCalled()
            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send error - missing recording is tracked`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            val initialState = awaitItem()
            // Send the message before recording anything
            initialState.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)

            assertThat(initialState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            sendVoiceMessageResult.assertions().isNeverCalled()
            assertThat(analyticsService.trackedErrors).hasSize(1)
            voiceRecorder.assertCalls(started = 0)

            testPauseAndDestroy(initialState)
        }
    }

    @Test
    fun `present - record error - security exceptions are tracked`() = runTest {
        val exception = SecurityException("")
        voiceRecorder.givenThrowsSecurityException(exception)
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            val failureState = awaitItem()

            sendVoiceMessageResult.assertions().isNeverCalled()
            assertThat(failureState.showSendFailureDialog).isTrue()
            assertThat(analyticsService.trackedErrors).containsExactly(
                VoiceMessageException.PermissionMissing(message = "Expected permission to record but none", cause = exception)
            )
            voiceRecorder.assertCalls(started = 1, stopped = 1, deleted = 1)

            testPauseAndDestroy(failureState)
        }
    }

    @Test
    fun `present - permission accepted first time`() = runTest {
        val permissionsPresenter = createFakePermissionsPresenter(
            recordPermissionGranted = false,
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(
            permissionsPresenter = permissionsPresenter,
        )
        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            assertThat(awaitItem().voiceMessageState).isEqualTo(VoiceMessageState.Idle)

            initialState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            voiceRecorder.assertCalls(stopped = 0)

            permissionsPresenter.setPermissionGranted()

            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            advanceUntilIdle()

            val finalState = expectMostRecentItem()
            assertThat(finalState.voiceMessageState).isEqualTo(RECORDING_STATE)
            voiceRecorder.assertCalls(stopped = 0, started = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - permission denied previously`() = runTest {
        val permissionsPresenter = createFakePermissionsPresenter(
            recordPermissionGranted = false,
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(
            permissionsPresenter = permissionsPresenter,
        )
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))

            // See the dialog and accept it
            awaitItem().also {
                assertThat(it.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
                assertThat(it.showPermissionRationaleDialog).isTrue()
                it.eventSink(VoiceMessageComposerEvent.AcceptPermissionRationale)
            }
            skipItems(1)

            // Dialog is hidden, user accepts permissions
            assertThat(awaitItem().showPermissionRationaleDialog).isFalse()

            // Permission is granted, recording starts automatically
            permissionsPresenter.setPermissionGranted()
            advanceUntilIdle()

            val finalState = expectMostRecentItem()
            assertThat(finalState.voiceMessageState).isEqualTo(RECORDING_STATE)
            voiceRecorder.assertCalls(started = 1)

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - permission rationale dismissed`() = runTest {
        val permissionsPresenter = createFakePermissionsPresenter(
            recordPermissionGranted = false,
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(
            permissionsPresenter = permissionsPresenter,
        )
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))

            // See the dialog and accept it
            awaitItem().also {
                assertThat(it.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
                assertThat(it.showPermissionRationaleDialog).isTrue()
                it.eventSink(VoiceMessageComposerEvent.DismissPermissionsRationale)
            }
            skipItems(1)

            // Dialog is hidden, user tries to record again
            awaitItem().also {
                assertThat(it.showPermissionRationaleDialog).isFalse()
                it.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            }
            skipItems(1)

            // Dialog is shown once again
            val finalState = awaitItem().also {
                assertThat(it.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
                assertThat(it.showPermissionRationaleDialog).isTrue()
            }
            voiceRecorder.assertCalls(started = 0)

            cancelAndIgnoreRemainingEvents()
            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `microphone denial dismissed before later grant never starts recording`() = runTest {
        val permissions = createFakePermissionsPresenter(recordPermissionGranted = false)
        val presenter = createDefaultVoiceMessageComposerPresenter(permissionsPresenter = permissions)
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            advanceUntilIdle()
            val denied = expectMostRecentItem()
            assertThat(denied.showPermissionRationaleDialog).isTrue()
            voiceRecorder.assertCalls(started = 0)
            denied.eventSink(VoiceMessageComposerEvent.DismissPermissionsRationale)
            advanceUntilIdle()
            permissions.setPermissionGranted()
            advanceUntilIdle()
            val restored = expectMostRecentItem()
            assertThat(restored.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            assertThat(restored.showPermissionRationaleDialog).isFalse()
            voiceRecorder.assertCalls(started = 0)
            testPauseAndDestroy(restored)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `released hold while microphone settings remain denied cannot record after later grant`() = runTest {
        val permissions = createFakePermissionsPresenter(recordPermissionGranted = false)
        val presenter = createDefaultVoiceMessageComposerPresenter(permissionsPresenter = permissions)
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            advanceUntilIdle()
            val denied = expectMostRecentItem()
            assertThat(denied.showPermissionRationaleDialog).isTrue()
            denied.eventSink(VoiceMessageComposerEvent.AcceptPermissionRationale)
            denied.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            advanceUntilIdle()
            assertThat(expectMostRecentItem().voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            voiceRecorder.assertCalls(started = 0, stopped = 0)
            permissions.setPermissionGranted()
            advanceUntilIdle()
            val restored = expectMostRecentItem()
            assertThat(restored.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            voiceRecorder.assertCalls(started = 0, stopped = 0)
            testPauseAndDestroy(restored)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun TurbineTestContext<VoiceMessageComposerState>.testPauseAndDestroy(
        mostRecentState: VoiceMessageComposerState,
    ) {
        mostRecentState.eventSink(
            VoiceMessageComposerEvent.LifecycleEvent(event = Lifecycle.Event.ON_PAUSE)
        )

        val onPauseState = when (val state = mostRecentState.voiceMessageState) {
            VoiceMessageState.Idle -> mostRecentState
            is VoiceMessageState.Recording -> {
                // If recorder was active, it stops
                awaitItem().apply {
                    assertThat(voiceMessageState).isEqualTo(aPreviewState())
                }
            }
            is VoiceMessageState.Preview -> when (state.isPlaying) {
                // If the preview was playing, it pauses
                true -> awaitItem().apply {
                    assertThat(voiceMessageState).isEqualTo(aPausedState())
                }
                false -> mostRecentState
            }
        }

        onPauseState.eventSink(
            VoiceMessageComposerEvent.LifecycleEvent(event = Lifecycle.Event.ON_DESTROY)
        )

        when (val state = onPauseState.voiceMessageState) {
            VoiceMessageState.Idle ->
                ensureAllEventsConsumed()
            is VoiceMessageState.Recording ->
                assertThat(awaitItem().voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            is VoiceMessageState.Preview -> when (state.isSending) {
                true -> ensureAllEventsConsumed()
                false -> assertThat(awaitItem().voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            }
        }
    }

    private fun TestScope.createDefaultVoiceMessageComposerPresenter(
        matrixClient: MatrixClient = this@DefaultVoiceMessageComposerPresenterTest.matrixClient,
        permissionsPresenter: PermissionsPresenter = createFakePermissionsPresenter(),
        voiceRecorder: VoiceRecorder = this@DefaultVoiceMessageComposerPresenterTest.voiceRecorder,
        audioFocus: AudioFocus = this@DefaultVoiceMessageComposerPresenterTest.audioFocus,
        protectedVoiceDraftStore: ProtectedVoiceDraftStore = this@DefaultVoiceMessageComposerPresenterTest.protectedVoiceDraftStore,
        mediaSender: MediaSender = this@DefaultVoiceMessageComposerPresenterTest.mediaSender,
        mediaPlayer: FakeMediaPlayer = FakeMediaPlayer(fakePrepareDelayMs = 0L),
        timelineController: TimelineController = TimelineController(joinedRoom, joinedRoom.liveTimeline),
    ): DefaultVoiceMessageComposerPresenter {
        kotlinx.coroutines.runBlocking {
            val accountHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(joinedRoom.sessionId.value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val deviceHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(matrixClient.deviceId.value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            appOperationalStateCoordinator.refresh(accountHash, deviceHash)
        }
        return DefaultVoiceMessageComposerPresenter(
            sessionCoroutineScope = backgroundScope,
            timelineMode = Timeline.Mode.Live,
            voiceRecorder = voiceRecorder,
            analyticsService = analyticsService,
            audioFocus = audioFocus,
            mediaSenderFactory = { mediaSender },
            player = VoiceMessageComposerPlayer(mediaPlayer, this),
            messageComposerContext = messageComposerContext,
            permissionsPresenterFactory = FakePermissionsPresenterFactory(permissionsPresenter),
            room = joinedRoom,
            matrixClient = matrixClient,
            appOperationalStateCoordinator = appOperationalStateCoordinator,
            protectedVoiceDraftStore = protectedVoiceDraftStore,
            timelineController = timelineController,
        )
    }

    private fun createFakePermissionsPresenter(
        recordPermissionGranted: Boolean = true,
        recordPermissionShowDialog: Boolean = false,
    ): FakePermissionsPresenter {
        val initialPermissionState = aPermissionsState(
            showDialog = recordPermissionShowDialog,
            permission = Manifest.permission.RECORD_AUDIO,
            permissionGranted = recordPermissionGranted,
        )
        return FakePermissionsPresenter(
            initialState = initialPermissionState
        )
    }

    private fun aPreviewState(
        isPlaying: Boolean = false,
        playbackProgress: Float = 0f,
        isSending: Boolean = false,
        time: Duration = RECORDING_DURATION,
        showCursor: Boolean = false,
        waveform: List<Float> = voiceRecorder.waveform,
    ) = VoiceMessageState.Preview(
        isPlaying = isPlaying,
        playbackProgress = playbackProgress,
        isSending = isSending,
        time = time,
        showCursor = showCursor,
        waveform = waveform.toImmutableList(),
    )

    private fun aPlayingState() =
        aPreviewState(
            isPlaying = true,
            playbackProgress = 0.1f,
            showCursor = true,
            time = RECORDING_DURATION,
        )

    private fun aPausedState() =
        aPlayingState()
            .copy(isPlaying = false)

    private fun VoiceMessageState.Preview.toSendingState() =
        copy(
            isPlaying = false,
            isSending = true,
            showCursor = false,
            time = RECORDING_DURATION,
        )
}

private class FakeProtectedVoiceDraftStore : ProtectedVoiceDraftStore {
    private val mutableRevocationGeneration = MutableStateFlow(0L)
    override val revocationGeneration: StateFlow<Long> = mutableRevocationGeneration.asStateFlow()
    var suspendPreparation = false
    val preparationStarted = CompletableDeferred<Unit>()
    val allowPreparation = CompletableDeferred<Unit>()
    override suspend fun prepareSourceAuthorization() {
        preparationStarted.complete(Unit)
        if (suspendPreparation) allowPreparation.await()
    }
    val protectedOperationIds = mutableListOf<String>()
    val reauthorizedOperationIds = mutableListOf<String>()
    val discardedOperationIds = mutableListOf<String>()
    var suspendFirstProtection = false
    val firstProtectionStarted = CompletableDeferred<Unit>()
    val allowFirstProtectionToFinish = CompletableDeferred<Unit>()

    fun revokeComposerDrafts() {
        mutableRevocationGeneration.value += 1L
    }

    override suspend fun protect(
        operationId: String,
        recording: VoiceRecorderState.Finished,
    ): ProtectedVoiceDraft {
        protectedOperationIds += operationId
        if (suspendFirstProtection && protectedOperationIds.size == 1) {
            firstProtectionStarted.complete(Unit)
            allowFirstProtectionToFinish.await()
        }
        return ProtectedVoiceDraft(
            operationId = operationId,
            uri = mockk(relaxed = true),
            mimeType = recording.mimeType,
            waveform = recording.waveform,
            duration = recording.duration,
            authorizationDeadlineEpochMillis = System.currentTimeMillis() + 60_000,
            reference = mockk(relaxed = true),
            lease = mockk(relaxed = true),
        )
    }

    override suspend fun reauthorize(draft: ProtectedVoiceDraft): ProtectedVoiceDraft {
        reauthorizedOperationIds += draft.operationId
        return draft
    }

    override suspend fun discard(draft: ProtectedVoiceDraft): Boolean {
        discardedOperationIds += draft.operationId
        return true
    }

    override suspend fun recoverAbandonedDrafts(): Int = 0
}

private fun aVoiceMessageComposerEvent(
    isReply: Boolean = false
) = Composer(
    inThread = false,
    isEditing = false,
    isReply = isReply,
    messageType = Composer.MessageType.VoiceMessage,
    startsThread = null
)
