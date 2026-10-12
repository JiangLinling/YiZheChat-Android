/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.audio.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.audio.api.AudioSessionLossReason
import io.element.android.libraries.audio.api.AudioSessionRequest
import io.element.android.libraries.audio.api.AudioSessionRole
import org.junit.Test

class AudioOwnershipArbiterTest {
    @Test
    fun `rtc preempts capture and stale release cannot release rtc`() {
        val arbiter = AudioOwnershipArbiter()
        val losses = mutableListOf<AudioSessionLossReason>()
        val capture = request("capture", AudioSessionRole.VoiceMessageCapture) { losses += it }
        val rtc = request("rtc", AudioSessionRole.RtcCall)

        arbiter.commitAcquire((arbiter.planAcquire(capture) as AudioOwnershipArbiter.AcquireDecision.Granted).request)
        arbiter.commitAcquire((arbiter.planAcquire(rtc) as AudioOwnershipArbiter.AcquireDecision.Granted).request)

        assertThat(losses).containsExactly(AudioSessionLossReason.PREEMPTED)
        assertThat(arbiter.release("capture")).isFalse()
        assertThat(arbiter.currentOwner()?.ownerId).isEqualTo("rtc")
    }

    @Test
    fun `playback cannot preempt capture`() {
        val arbiter = AudioOwnershipArbiter()
        val capture = request("capture", AudioSessionRole.VoiceMessageCapture)
        val playback = request("playback", AudioSessionRole.VoiceMessagePlayback)
        arbiter.commitAcquire((arbiter.planAcquire(capture) as AudioOwnershipArbiter.AcquireDecision.Granted).request)

        assertThat(arbiter.planAcquire(playback)).isEqualTo(AudioOwnershipArbiter.AcquireDecision.Denied)
        assertThat(arbiter.currentOwner()?.ownerId).isEqualTo("capture")
    }

    @Test
    fun `equal priority explicit playback replaces previous playback`() {
        val arbiter = AudioOwnershipArbiter()
        val losses = mutableListOf<AudioSessionLossReason>()
        val first = request("first", AudioSessionRole.VoiceMessagePlayback) { losses += it }
        val second = request("second", AudioSessionRole.VoiceMessagePlayback)
        arbiter.commitAcquire((arbiter.planAcquire(first) as AudioOwnershipArbiter.AcquireDecision.Granted).request)
        arbiter.commitAcquire((arbiter.planAcquire(second) as AudioOwnershipArbiter.AcquireDecision.Granted).request)

        assertThat(losses).containsExactly(AudioSessionLossReason.PREEMPTED)
        assertThat(arbiter.currentOwner()?.ownerId).isEqualTo("second")
    }

    @Test
    fun `stale rtc activity cannot release a newer rtc activity`() {
        val arbiter = AudioOwnershipArbiter()
        val firstRtc = request("rtc-first", AudioSessionRole.RtcCall)
        val secondRtc = request("rtc-second", AudioSessionRole.RtcCall)
        arbiter.commitAcquire((arbiter.planAcquire(firstRtc) as AudioOwnershipArbiter.AcquireDecision.Granted).request)
        arbiter.commitAcquire((arbiter.planAcquire(secondRtc) as AudioOwnershipArbiter.AcquireDecision.Granted).request)

        assertThat(arbiter.release("rtc-first")).isFalse()
        assertThat(arbiter.currentOwner()?.ownerId).isEqualTo("rtc-second")
    }

    private fun request(
        ownerId: String,
        role: AudioSessionRole,
        onLoss: (AudioSessionLossReason) -> Unit = {},
    ) = AudioSessionRequest(ownerId, role, "operation-$ownerId", onLoss)
}
