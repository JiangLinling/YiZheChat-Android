/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline.components.layout

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentAvoidingLayoutTest {
    @Test
    fun `empty content and visible overlay remain separate placeables`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            ContentAvoidingLayout(
                overlay = { Text("overlay") },
                content = {},
            )
        }

        waitForIdle()
    }

    @Test
    fun `visible content and empty overlay remain separate placeables`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            ContentAvoidingLayout(
                overlay = {},
                content = { Text("content") },
            )
        }

        waitForIdle()
    }
}
