/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.voicerecorder.impl

import android.Manifest
import androidx.annotation.RequiresPermission
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.appconfig.VoiceMessageConfig
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.coroutine.childScope
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.voicerecorder.api.VoiceRecorder
import io.element.android.libraries.voicerecorder.api.VoiceRecorderFailure
import io.element.android.libraries.voicerecorder.api.VoiceRecorderState
import io.element.android.libraries.voicerecorder.api.VoiceRecorderStopReason
import io.element.android.libraries.voicerecorder.impl.audio.Audio
import io.element.android.libraries.voicerecorder.impl.audio.AudioConfig
import io.element.android.libraries.voicerecorder.impl.audio.AudioLevelCalculator
import io.element.android.libraries.voicerecorder.impl.audio.AudioReader
import io.element.android.libraries.voicerecorder.impl.audio.Encoder
import io.element.android.libraries.voicerecorder.impl.audio.resample
import io.element.android.libraries.voicerecorder.impl.file.VoiceFileConfig
import io.element.android.libraries.voicerecorder.impl.file.VoiceFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import timber.log.Timber
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@SingleIn(RoomScope::class)
@ContributesBinding(RoomScope::class)
class DefaultVoiceRecorder(
    private val dispatchers: CoroutineDispatchers,
    private val timeSource: TimeSource,
    private val audioReaderFactory: AudioReader.Factory,
    private val encoder: Encoder,
    private val fileManager: VoiceFileManager,
    private val config: AudioConfig,
    private val fileConfig: VoiceFileConfig,
    private val audioLevelCalculator: AudioLevelCalculator,
    @SessionCoroutineScope
    sessionCoroutineScope: CoroutineScope,
) : VoiceRecorder {
    private val voiceCoroutineScope by lazy {
        sessionCoroutineScope.childScope(dispatchers.io, "VoiceRecorder-${UUID.randomUUID()}")
    }

    private var outputFile: File? = null
    private var audioReader: AudioReader? = null
    private var recordingJob: Job? = null

    // List of Float between 0 and 1 representing the audio levels
    private val levels: MutableList<Float> = mutableListOf()
    private val lock = Mutex()

    private val _state = MutableStateFlow<VoiceRecorderState>(VoiceRecorderState.Idle)
    override val state: StateFlow<VoiceRecorderState> = _state

    private val cleanupRevision = MutableStateFlow(0L)
    override val captureCleanupRevision: StateFlow<Long> = cleanupRevision

    override fun prepareCapture(operationId: String) {
        fileManager.createFile(operationId)
    }

    override fun discardPreparedCapture(operationId: String) {
        fileManager.discardPreparedCapture(operationId)
        cleanupRevision.value++
    }

    override fun pendingCaptureCleanups(): Set<String> = fileManager.pendingCaptureCleanups()

    override fun captureCleanupResult(operationId: String): String = fileManager.captureCleanupResult(operationId)

    override fun acknowledgeCaptureCleanup(operationId: String) = fileManager.acknowledgeCaptureCleanup(operationId)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun startRecord() = startRecording(null)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun startRecord(operationId: String) = startRecording(operationId)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun startRecording(operationId: String?) {
        Timber.i("Voice recorder started recording")
        if (outputFile != null) deleteRecording()
        val createdFile = try {
            (if (operationId == null) fileManager.createFile() else fileManager.createFile(operationId)).also {
                outputFile = it
                encoder.init(it)
            }
        } catch (failure: Exception) {
            Timber.e(failure, "Voice recorder failed to create controlled scratch output")
            outputFile?.let {
                fileManager.markCaptureCleanup(it, "VOICE_CAPTURE_FAILED_AND_CLEANED")
                fileManager.deleteFile(it)
            }
            outputFile = null
            cleanupRevision.value++
            _state.emit(VoiceRecorderState.Failed(VoiceRecorderFailure.STORAGE_UNAVAILABLE))
            return
        }
        outputFile = createdFile

        lock.withLock {
            levels.clear()
        }

        val audioRecorder = audioReaderFactory.create(config, dispatchers).also { audioReader = it }

        recordingJob = voiceCoroutineScope.launch {
            val startedAt = timeSource.markNow()
            audioRecorder.record { audio ->
                yield()

                val elapsedTime = startedAt.elapsedNow()

                if (elapsedTime > VoiceMessageConfig.maxVoiceMessageDuration) {
                    Timber.w("Voice message time limit reached")
                    stopRecord(VoiceRecorderStopReason.MAX_DURATION)
                    return@record
                }

                when (audio) {
                    is Audio.Data -> {
                        val audioLevel = audioLevelCalculator.calculateAudioLevel(audio.buffer)

                        lock.withLock {
                            levels.add(audioLevel)
                            _state.emit(VoiceRecorderState.Recording(elapsedTime, levels.toList()))
                        }
                        try {
                            encoder.encode(audio.buffer, audio.readSize)
                        } catch (failure: Exception) {
                            Timber.e(failure, "Voice recorder encoder failed")
                            stopAfterFailure(VoiceRecorderFailure.ENCODER_FAILURE)
                            return@record
                        }
                    }
                    is Audio.Error -> {
                        Timber.e("Voice message error: code=${audio.audioRecordErrorCode}")
                        _state.emit(VoiceRecorderState.Recording(elapsedTime, listOf()))
                    }
                }
            }
        }
    }

    /**
     * Stop the current recording.
     *
     * Call [deleteRecording] to delete any recorded audio.
     */
    override suspend fun stopRecord(reason: VoiceRecorderStopReason) {
        if (outputFile == null) {
            _state.emit(VoiceRecorderState.Idle)
            return
        }
        recordingJob?.cancel()?.also {
            Timber.i("Voice recorder stopped recording")
        }
        recordingJob = null

        audioReader?.stop()
        audioReader = null
        val recording = state.value as? VoiceRecorderState.Recording
        val duration = recording?.elapsedTime ?: 0.milliseconds
        val currentLevels = lock.withLock { levels.toList() }

        if (reason == VoiceRecorderStopReason.CANCELLED) {
            runCatching { encoder.release() }
            deleteRecording()
            lock.withLock { levels.clear() }
            return
        }

        _state.emit(VoiceRecorderState.Finalizing(duration, currentLevels, reason))
        try {
            encoder.release()
        } catch (failure: Exception) {
            Timber.e(failure, "Voice recorder encoder finalization failed")
            stopAfterFailure(VoiceRecorderFailure.ENCODER_FAILURE)
            return
        }

        lock.withLock {
            if (duration < MINIMUM_RECORDING_DURATION) {
                outputFile?.let { fileManager.markCaptureCleanup(it, "VOICE_CAPTURE_FAILED_AND_CLEANED") }
                deleteRecording()
                levels.clear()
                _state.emit(VoiceRecorderState.Failed(VoiceRecorderFailure.TOO_SHORT))
                return
            }

            _state.emit(
                when (val file = outputFile) {
                    null -> VoiceRecorderState.Idle
                    else -> {
                        VoiceRecorderState.Finished(
                            file = file,
                            mimeType = fileConfig.mimeType,
                            waveform = levels.resample(100),
                            duration = duration,
                            stopReason = reason,
                        )
                    }
                }
            )
        }
    }

    /**
     * Stop the current recording and delete the output file.
     */
    override suspend fun deleteRecording() {
        outputFile?.let(fileManager::deleteFile)?.also {
            Timber.i("Voice recorder deleted recording")
        }
        outputFile = null
        cleanupRevision.value++
        _state.emit(VoiceRecorderState.Idle)
    }

    override suspend fun deleteRecording(recording: VoiceRecorderState.Finished) {
        val outcome = if (recording.stopReason == VoiceRecorderStopReason.INTERRUPTED || recording.stopReason == VoiceRecorderStopReason.CANCELLED) {
            "VOICE_CAPTURE_CANCELLED_AND_CLEANED"
        } else {
            "VOICE_CAPTURE_RELEASED_AND_CLEANED"
        }
        fileManager.markCaptureCleanup(recording.file, outcome)
        fileManager.deleteFile(recording.file)
        val deletedCurrentOutput = outputFile?.sameFileAs(recording.file) == true
        if (deletedCurrentOutput) {
            outputFile = null
            _state.emit(VoiceRecorderState.Idle)
        }
        cleanupRevision.value++
        Timber.i("Voice recorder deleted exact finished recording")
    }

    private fun File.sameFileAs(other: File): Boolean = runCatching {
        canonicalFile == other.canonicalFile
    }.getOrDefault(absoluteFile == other.absoluteFile)

    private suspend fun stopAfterFailure(reason: VoiceRecorderFailure) {
        recordingJob?.cancel()
        recordingJob = null
        audioReader?.stop()
        audioReader = null
        runCatching { encoder.release() }
        outputFile?.let {
            fileManager.markCaptureCleanup(it, "VOICE_CAPTURE_FAILED_AND_CLEANED")
            fileManager.deleteFile(it)
        }
        outputFile = null
        lock.withLock { levels.clear() }
        cleanupRevision.value++
        _state.emit(VoiceRecorderState.Failed(reason))
    }

    private companion object {
        val MINIMUM_RECORDING_DURATION = 500.milliseconds
    }
}
