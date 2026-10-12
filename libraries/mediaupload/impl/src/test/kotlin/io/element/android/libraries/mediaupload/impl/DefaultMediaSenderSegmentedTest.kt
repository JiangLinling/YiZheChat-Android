/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaupload.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.PreparedSegmentedMediaSend
import io.element.android.libraries.mediaupload.api.SegmentedMediaSendOutcome
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.mockk.mockk
import io.mockk.coEvery
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultMediaSenderSegmentedTest {
    @Test
    fun `prepared segmented media uses the actual room timeline and dedicated sender`() = runTest {
        val timeline = FakeTimeline()
        val room = FakeJoinedRoom(liveTimeline = timeline)
        val request = SignedContractFixture().request
        val nextSender = RecordingNextSender()
        val flags = mockk<FeatureFlagService> {
            coEvery { isFeatureEnabled(FeatureFlags.SegmentedProtectedVideoSending) } returns true
        }
        val sender = DefaultMediaSender(
            preProcessor = FakeMediaPreProcessor(),
            room = room,
            timelineMode = Timeline.Mode.Live,
            mediaOptimizationConfigProvider = mockk<MediaOptimizationConfigProvider>(relaxed = true),
            nextSegmentedMediaSender = nextSender,
            featureFlagService = flags,
        )

        val result = sender.sendPreparedSegmentedMedia(request)

        assertThat(result.getOrThrow()).isEqualTo(SegmentedMediaSendOutcome.Uncertain(request.transactionId))
        assertThat(nextSender.timeline).isSameInstanceAs(timeline)
        assertThat(nextSender.roomId).isEqualTo(room.roomId.value)
        assertThat(nextSender.request).isEqualTo(request)
    }

    @Test
    fun `disabled sending never enters the dedicated sender or queues a new event`() = runTest {
        val nextSender = RecordingNextSender()
        val sender = DefaultMediaSender(
            preProcessor = FakeMediaPreProcessor(),
            room = FakeJoinedRoom(),
            timelineMode = Timeline.Mode.Live,
            mediaOptimizationConfigProvider = mockk(relaxed = true),
            nextSegmentedMediaSender = nextSender,
        )
        val result = sender.sendPreparedSegmentedMedia(SignedContractFixture().request)
        assertThat(result.exceptionOrNull()?.message).isEqualTo("SEGMENTED_VIDEO_SENDING_NOT_ENABLED")
        assertThat(nextSender.request).isNull()
    }
}

private class RecordingNextSender : NextSegmentedMediaSender {
    var timeline: Timeline? = null
    var roomId: String? = null
    var request: PreparedSegmentedMediaSend? = null

    override suspend fun send(
        timeline: Timeline,
        roomId: String,
        request: PreparedSegmentedMediaSend,
    ): Result<SegmentedMediaSendOutcome> {
        this.timeline = timeline
        this.roomId = roomId
        this.request = request
        return Result.success(SegmentedMediaSendOutcome.Uncertain(request.transactionId))
    }
}
