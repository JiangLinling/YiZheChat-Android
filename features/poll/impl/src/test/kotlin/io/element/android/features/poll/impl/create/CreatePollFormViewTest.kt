/*
 * Copyright (c) 2026 YiZhe Chat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.poll.impl.create

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.tests.testutils.clickOn
import org.junit.Test
import org.junit.runner.RunWith
import io.element.android.features.poll.api.R as PollR

@RunWith(AndroidJUnit4::class)
class CreatePollFormViewTest {
    @Test
    fun `leaving an unfinished form never publishes it`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = mutableListOf<CreatePollEvent>()
        setContent {
            ElementPreview {
                CreatePollView(
                    CreatePollStateProvider().values.first().copy(showBackConfirmation = true, eventSink = { events.add(it) })
                )
            }
        }
        clickOn(PollR.string.screen_interaction_discard)
        assertThat(events).containsExactly(CreatePollEvent.NavBack)
    }
}
