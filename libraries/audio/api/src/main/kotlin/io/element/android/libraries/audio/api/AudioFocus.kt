/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.audio.api

enum class AudioFocusRequester {
    ElementCall,
    VoiceMessage,
    RecordVoiceMessage,
    MediaViewer,
}

/**
 * The mutually exclusive app-level audio role owned by an operation.
 *
 * The priority is intentionally part of the shared contract so a future RTC implementation can
 * use the same arbiter instead of adding a second microphone or playback lock.
 */
enum class AudioSessionRole(val priority: Int) {
    MediaPlayback(priority = 10),
    VoiceMessagePlayback(priority = 20),
    VoiceMessageCapture(priority = 30),
    RtcCall(priority = 40),
}

enum class AudioSessionLossReason {
    PREEMPTED,
    SYSTEM_FOCUS_LOST,
    SYSTEM_FOCUS_LOST_TRANSIENT,
}

data class AudioSessionRequest(
    val ownerId: String,
    val role: AudioSessionRole,
    val operationId: String,
    val onOwnershipLost: (AudioSessionLossReason) -> Unit,
)

data class AudioSessionOwner(
    val ownerId: String,
    val role: AudioSessionRole,
    val operationId: String,
)

interface AudioFocus {
    /**
     * Acquire the single app-level capture/playback owner.
     *
     * A higher-priority role preempts the current owner. Equal-priority explicit user actions
     * replace the previous owner. Lower-priority requests fail closed and must not start audio.
     */
    fun acquire(request: AudioSessionRequest): Boolean

    /** Releases ownership only when [ownerId] is still the current owner. */
    fun release(ownerId: String)

    /** A diagnostic projection; it is not an Android OS audio-focus receipt. */
    fun currentOwner(): AudioSessionOwner?

    /**
     * Request audio focus for the given requester.
     * @param requester The mode for which to request audio focus.
     * @param onFocusLost Callback to be invoked when the audio focus is lost.
     * @return true if the audio focus was successfully requested, false otherwise.
     */
    fun requestAudioFocus(
        requester: AudioFocusRequester,
        onFocusLost: () -> Unit,
    )

    /**
     * Release the audio focus.
     */
    fun releaseAudioFocus()
}
