/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.voicerecorder.api

import android.Manifest
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.StateFlow

/**
 * Audio recorder which records audio to opus/ogg files.
 */
private val emptyCaptureCleanupRevision: StateFlow<Long> = kotlinx.coroutines.flow.MutableStateFlow(0L)

interface VoiceRecorder {
    /**
     * Start a recording.
     *
     * Call [stopRecord] to stop the recording and release resources.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startRecord()

    /** Reserve durable operation-owned scratch intent before microphone admission. */
    fun prepareCapture(operationId: String) = Unit

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startRecord(operationId: String) = startRecord()

    /** Clean a reserved capture that never reached microphone acquisition. */
    fun discardPreparedCapture(operationId: String) = Unit

    /** Only identities whose scratch and owner lock have actually been removed are returned. */
    fun pendingCaptureCleanups(): Set<String> = emptySet()

    fun captureCleanupResult(operationId: String): String = "VOICE_CAPTURE_CANCELLED_AND_CLEANED"

    /** Remove the non-sensitive intent only after the unified receipt outbox committed it. */
    fun acknowledgeCaptureCleanup(operationId: String) = Unit

    val captureCleanupRevision: StateFlow<Long> get() = emptyCaptureCleanupRevision

    /**
     * Stop the current recording.
     *
     * Call [deleteRecording] to delete any recorded audio.
     *
     * @param cancelled If true, the recording is deleted.
     */
    suspend fun stopRecord(
        cancelled: Boolean = false
    ) = stopRecord(if (cancelled) VoiceRecorderStopReason.CANCELLED else VoiceRecorderStopReason.USER_STOP)

    /** Stop with an explicit lifecycle reason so interruption is not reported as a user send. */
    suspend fun stopRecord(reason: VoiceRecorderStopReason)

    /**
     * Stop the current recording and delete the output file.
     */
    suspend fun deleteRecording()

    /**
     * Delete the exact finished recording supplied by the caller.
     *
     * A protection handoff may finish after another capture has started. Implementations must not
     * delete or reset that newer capture when cleaning up the older scratch file.
     */
    suspend fun deleteRecording(recording: VoiceRecorderState.Finished) = deleteRecording()

    /**
     * The current state of the recorder.
     */
    val state: StateFlow<VoiceRecorderState>
}
