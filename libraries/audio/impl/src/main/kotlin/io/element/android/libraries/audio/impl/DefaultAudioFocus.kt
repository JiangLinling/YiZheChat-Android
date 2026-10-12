/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.audio.impl

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.getSystemService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.audio.api.AudioFocusRequester
import io.element.android.libraries.audio.api.AudioSessionLossReason
import io.element.android.libraries.audio.api.AudioSessionOwner
import io.element.android.libraries.audio.api.AudioSessionRequest
import io.element.android.libraries.audio.api.AudioSessionRole
import io.element.android.libraries.di.annotations.ApplicationContext
import timber.log.Timber

@ContributesBinding(AppScope::class)
class DefaultAudioFocus(
    @ApplicationContext private val context: Context,
) : AudioFocus {
    private val audioManager = requireNotNull(context.getSystemService<AudioManager>())

    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
    private val ownership = AudioOwnershipArbiter()

    @Synchronized
    override fun acquire(request: AudioSessionRequest): Boolean {
        val decision = ownership.planAcquire(request)
        if (decision is AudioOwnershipArbiter.AcquireDecision.Denied) return false

        val granted = requestSystemAudioFocus(request.role) { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> ownership.loseCurrent(AudioSessionLossReason.SYSTEM_FOCUS_LOST)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    ownership.loseCurrent(AudioSessionLossReason.SYSTEM_FOCUS_LOST_TRANSIENT)
                AudioManager.AUDIOFOCUS_GAIN -> Timber.d("AudioFocus: AUDIOFOCUS_GAIN")
            }
        }
        if (!granted) return false

        ownership.commitAcquire((decision as AudioOwnershipArbiter.AcquireDecision.Granted).request)
        return true
    }

    @Synchronized
    override fun release(ownerId: String) {
        if (ownership.release(ownerId)) {
            abandonSystemAudioFocus()
        }
    }

    override fun currentOwner(): AudioSessionOwner? = ownership.currentOwner()

    @Suppress("DEPRECATION")
    override fun requestAudioFocus(
        requester: AudioFocusRequester,
        onFocusLost: () -> Unit,
    ) {
        acquire(
            AudioSessionRequest(
                ownerId = requester.legacyOwnerId(),
                role = requester.toSessionRole(),
                operationId = requester.legacyOwnerId(),
                onOwnershipLost = { onFocusLost() },
            )
        )
    }

    override fun releaseAudioFocus() {
        ownership.currentOwner()
            ?.takeIf { it.ownerId.startsWith(LEGACY_OWNER_PREFIX) }
            ?.let { release(it.ownerId) }
    }

    @Suppress("DEPRECATION")
    private fun requestSystemAudioFocus(
        role: AudioSessionRole,
        onFocusChange: (Int) -> Unit,
    ): Boolean {
        val listener = AudioManager.OnAudioFocusChangeListener(onFocusChange)
        val requester = role.toRequester()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(requester.toAudioUsage())
                .build()
            val focusGain = if (requester == AudioFocusRequester.RecordVoiceMessage) {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            } else {
                AudioManager.AUDIOFOCUS_GAIN
            }
            val request = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(listener)
                .setWillPauseWhenDucked(requester.willPausedWhenDucked())
                .build()
            val result = audioManager.requestAudioFocus(request)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
            audioFocusRequest = request
        } else {
            val result = audioManager.requestAudioFocus(
                listener,
                requester.toAudioStream(),
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
            audioFocusChangeListener = listener
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun abandonSystemAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            audioFocusChangeListener?.let { audioManager.abandonAudioFocus(it) }
        }
        audioFocusRequest = null
        audioFocusChangeListener = null
    }

    private companion object {
        const val LEGACY_OWNER_PREFIX = "legacy-audio:"
    }
}

internal class AudioOwnershipArbiter {
    private var activeRequest: AudioSessionRequest? = null

    sealed interface AcquireDecision {
        data class Granted(val request: AudioSessionRequest) : AcquireDecision
        data object Denied : AcquireDecision
    }

    @Synchronized
    fun planAcquire(request: AudioSessionRequest): AcquireDecision {
        require(request.ownerId.isNotBlank())
        require(request.operationId.isNotBlank())
        val active = activeRequest
        return when {
            active == null -> AcquireDecision.Granted(request)
            active.ownerId == request.ownerId -> AcquireDecision.Granted(request)
            request.role.priority >= active.role.priority -> AcquireDecision.Granted(request)
            else -> AcquireDecision.Denied
        }
    }

    fun commitAcquire(request: AudioSessionRequest) {
        val previous = synchronized(this) {
            val old = activeRequest
            activeRequest = request
            old?.takeUnless { it.ownerId == request.ownerId }
        }
        previous?.onOwnershipLost?.invoke(AudioSessionLossReason.PREEMPTED)
    }

    fun loseCurrent(reason: AudioSessionLossReason) {
        val lost = synchronized(this) {
            activeRequest.also { activeRequest = null }
        }
        lost?.onOwnershipLost?.invoke(reason)
    }

    @Synchronized
    fun release(ownerId: String): Boolean {
        if (activeRequest?.ownerId != ownerId) return false
        activeRequest = null
        return true
    }

    @Synchronized
    fun currentOwner(): AudioSessionOwner? = activeRequest?.let {
        AudioSessionOwner(it.ownerId, it.role, it.operationId)
    }
}

private fun AudioFocusRequester.toAudioUsage(): Int {
    return when (this) {
        AudioFocusRequester.ElementCall,
        AudioFocusRequester.VoiceMessage,
        AudioFocusRequester.RecordVoiceMessage -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioFocusRequester.MediaViewer -> AudioAttributes.USAGE_MEDIA
    }
}

private fun AudioFocusRequester.toSessionRole(): AudioSessionRole = when (this) {
    AudioFocusRequester.ElementCall -> AudioSessionRole.RtcCall
    AudioFocusRequester.RecordVoiceMessage -> AudioSessionRole.VoiceMessageCapture
    AudioFocusRequester.VoiceMessage -> AudioSessionRole.VoiceMessagePlayback
    AudioFocusRequester.MediaViewer -> AudioSessionRole.MediaPlayback
}

private fun AudioSessionRole.toRequester(): AudioFocusRequester = when (this) {
    AudioSessionRole.RtcCall -> AudioFocusRequester.ElementCall
    AudioSessionRole.VoiceMessageCapture -> AudioFocusRequester.RecordVoiceMessage
    AudioSessionRole.VoiceMessagePlayback -> AudioFocusRequester.VoiceMessage
    AudioSessionRole.MediaPlayback -> AudioFocusRequester.MediaViewer
}

private fun AudioFocusRequester.legacyOwnerId(): String = "legacy-audio:$name"

private fun AudioFocusRequester.toAudioStream(): Int {
    return when (this) {
        AudioFocusRequester.ElementCall,
        AudioFocusRequester.VoiceMessage,
        AudioFocusRequester.RecordVoiceMessage -> AudioManager.STREAM_VOICE_CALL
        AudioFocusRequester.MediaViewer -> AudioManager.STREAM_MUSIC
    }
}

private fun AudioFocusRequester.willPausedWhenDucked(): Boolean {
    return when (this) {
        // (note that for Element Call, there is no action when the focus is lost)
        AudioFocusRequester.ElementCall,
        AudioFocusRequester.VoiceMessage,
        AudioFocusRequester.RecordVoiceMessage -> true
        // For the MediaViewer, we let the system automatically handle the ducking
        // https://developer.android.com/media/optimize/audio-focus#automatic-ducking
        AudioFocusRequester.MediaViewer -> false
    }
}
