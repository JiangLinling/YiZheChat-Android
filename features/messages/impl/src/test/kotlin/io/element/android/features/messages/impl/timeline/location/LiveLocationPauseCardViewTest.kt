/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package io.element.android.features.messages.impl.timeline.location

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.location.api.live.PausedLiveLocationShare
import io.element.android.features.messages.impl.timeline.components.event.TimelineItemLocationView
import io.element.android.features.messages.impl.timeline.model.event.aLiveLocationMode
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemLocationContent
import io.element.android.libraries.designsystem.preview.ElementPreview
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveLocationPauseCardViewTest {
    @Test fun `paused card keeps original deadline and Stop performs once`() = runAndroidComposeUiTest<ComponentActivity> {
        var stops = 0
        val content = aTimelineItemLocationContent(mode = aLiveLocationMode(true, endsAt = "Ends at 12:34")
            .copy(protectedAggregateId = "yizhe-live-original", pausedState = PausedLiveLocationShare.RECOVERABLE))
        setContent { ElementPreview {
            TimelineItemLocationView(content, onContentClick = null, onStopLiveLocationClick = { stops++ })
        } }
        onNodeWithText("Location updates paused. Check location permission.").assertIsDisplayed()
        onNodeWithText("Ends at 12:34").assertIsDisplayed()
        onNodeWithContentDescription("Stop").performClick()
        assertThat(stops).isEqualTo(1)
        onNodeWithText("Ending location sharing…").assertIsDisplayed()
    }
}
