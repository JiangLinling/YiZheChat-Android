/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.emotions

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionPlaybackTimingTest {
    @Test fun `authored gift duration survives faster and slower system transitions`() = runTest {
        for (scale in listOf(.5f, 1f, 2f)) {
            val clock = BroadcastFrameClock()
            var progress = 0f
            val task = launch(clock + object : MotionDurationScale { override val scaleFactor = scale }) {
                animateEmotionProgress(4000) { progress = it }
            }
            runCurrent()
            clock.sendFrame(0); runCurrent()
            clock.sendFrame(2_000_000_000); runCurrent()
            assertEquals(.5f, progress, .001f)
            assertFalse(task.isCompleted)
            clock.sendFrame(4_000_000_000); runCurrent()
            assertEquals(1f, progress, .001f)
            assertTrue(task.isCompleted)
        }
    }

    @Test fun `cancellation stops updates without reporting completion`() = runTest {
        val clock = BroadcastFrameClock()
        val frames = mutableListOf<Float>()
        val task = launch(clock) { animateEmotionProgress(4000, frames::add) }
        runCurrent()
        clock.sendFrame(0); runCurrent()
        clock.sendFrame(1_000_000_000); runCurrent()
        task.cancel(); runCurrent()
        clock.sendFrame(4_000_000_000); runCurrent()
        assertEquals(listOf(.25f), frames)
    }
}
