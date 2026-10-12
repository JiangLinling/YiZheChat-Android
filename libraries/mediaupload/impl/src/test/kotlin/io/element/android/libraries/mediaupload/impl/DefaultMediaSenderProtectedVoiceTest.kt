/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaupload.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.ProtectedMediaSource
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.media.FakeMediaUploadHandler
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class DefaultMediaSenderProtectedVoiceTest {
    @Test
    fun `protected voice is streamed to the protected timeline without ordinary audio preprocessing`() = runTest {
        val plaintext = "authorized voice bytes".encodeToByteArray()
        val preProcessor = FakeMediaPreProcessor().apply { givenProtectedVoiceResult(plaintext) }
        var receivedBytes: ByteArray? = null
        var receivedWaveform: List<Float>? = null
        var receivedReply: EventId? = null
        var receivedBurnAfterRead: Boolean? = null
        var uploadStarted = false
        var identity: String? = null
        val timeline = FakeTimeline().apply {
            sendProtectedVoiceMessageLambda = { source, audioInfo, waveform, reply, burnAfterRead ->
                receivedBytes = source.openInputStream().use { it.readBytes() }
                assertThat(source.plaintextSize).isEqualTo(plaintext.size.toLong())
                assertThat(audioInfo.duration).isEqualTo(1.seconds)
                receivedWaveform = waveform
                receivedReply = reply
                receivedBurnAfterRead = burnAfterRead
                Result.success(FakeMediaUploadHandler(protectedMediaTransportName = "protected-voice-object"))
            }
        }
        val sender = sender(preProcessor, timeline)
        val reply = EventId("\$reply:example.org")

        val result = sender.sendVoiceMessage(
            uri = mockk(relaxed = true),
            mimeType = MimeTypes.Ogg,
            waveForm = listOf(0.1f, 0.7f),
            inReplyToEventId = reply,
            burnAfterRead = true,
            onUploadStarted = { uploadStarted = true },
            onUploadIdentity = { identity = it },
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(receivedBytes).isEqualTo(plaintext)
        assertThat(receivedWaveform).containsExactly(0.1f, 0.7f).inOrder()
        assertThat(receivedReply).isEqualTo(reply)
        assertThat(receivedBurnAfterRead).isTrue()
        assertThat(uploadStarted).isTrue()
        assertThat(identity).isEqualTo("protected-voice-object")
        assertThat(preProcessor.processProtectedVoiceCallCount).isEqualTo(1)
        assertThat(preProcessor.processCallCount).isEqualTo(0)
    }

    @Test
    fun `protected voice preprocessing failure fails closed before timeline send`() = runTest {
        val failure = IllegalStateException("lease revoked")
        val preProcessor = FakeMediaPreProcessor().apply { givenProtectedVoiceFailure(failure) }
        var timelineCalled = false
        val timeline = FakeTimeline().apply {
            sendProtectedVoiceMessageLambda = { _: ProtectedMediaSource, _, _, _, _ ->
                timelineCalled = true
                Result.success(FakeMediaUploadHandler())
            }
        }

        val result = sender(preProcessor, timeline).sendVoiceMessage(
            uri = mockk(relaxed = true),
            mimeType = MimeTypes.Ogg,
            waveForm = emptyList(),
        )

        assertThat(result.exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(timelineCalled).isFalse()
        assertThat(preProcessor.processProtectedVoiceCallCount).isEqualTo(1)
        assertThat(preProcessor.processCallCount).isEqualTo(0)
    }

    private fun sender(preProcessor: FakeMediaPreProcessor, timeline: FakeTimeline) = DefaultMediaSender(
        preProcessor = preProcessor,
        room = FakeJoinedRoom(liveTimeline = timeline),
        timelineMode = Timeline.Mode.Live,
        mediaOptimizationConfigProvider = { error("Voice path must not request ordinary media optimization.") },
    )
}
