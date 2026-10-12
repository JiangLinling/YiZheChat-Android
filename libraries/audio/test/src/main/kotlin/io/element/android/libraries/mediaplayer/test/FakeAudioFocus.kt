/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaplayer.test

import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.audio.api.AudioFocusRequester
import io.element.android.libraries.audio.api.AudioSessionOwner
import io.element.android.libraries.audio.api.AudioSessionRequest
import io.element.android.libraries.audio.api.AudioSessionRole
import io.element.android.tests.testutils.lambda.lambdaError

class FakeAudioFocus(
    private val requestAudioFocusResult: (AudioFocusRequester, () -> Unit) -> Unit = { _, _ -> lambdaError() },
    private val releaseAudioFocusResult: () -> Unit = { lambdaError() },
) : AudioFocus {
    private var owner: AudioSessionRequest? = null

    override fun acquire(request: AudioSessionRequest): Boolean {
        owner = request
        requestAudioFocusResult(request.role.toLegacyRequester()) {
            request.onOwnershipLost(io.element.android.libraries.audio.api.AudioSessionLossReason.SYSTEM_FOCUS_LOST)
        }
        return true
    }

    override fun release(ownerId: String) {
        if (owner?.ownerId == ownerId) {
            owner = null
            releaseAudioFocusResult()
        }
    }

    override fun currentOwner(): AudioSessionOwner? = owner?.let {
        AudioSessionOwner(it.ownerId, it.role, it.operationId)
    }

    override fun requestAudioFocus(
        requester: AudioFocusRequester,
        onFocusLost: () -> Unit,
    ) {
        requestAudioFocusResult(requester, onFocusLost)
    }

    override fun releaseAudioFocus() {
        owner = null
        releaseAudioFocusResult()
    }
}

private fun AudioSessionRole.toLegacyRequester(): AudioFocusRequester = when (this) {
    AudioSessionRole.MediaPlayback -> AudioFocusRequester.MediaViewer
    AudioSessionRole.VoiceMessagePlayback -> AudioFocusRequester.VoiceMessage
    AudioSessionRole.VoiceMessageCapture -> AudioFocusRequester.RecordVoiceMessage
    AudioSessionRole.RtcCall -> AudioFocusRequester.ElementCall
}
