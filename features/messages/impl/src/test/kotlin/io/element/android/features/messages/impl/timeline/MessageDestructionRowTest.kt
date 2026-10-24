/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MessageDestructionRowTest {
    @Test
    fun `pending deletion preserves the message composition and next row position`() = runAndroidComposeUiTest<ComponentActivity> {
        val pending = mutableStateOf(false)
        val mounts = AtomicInteger()
        setSafeContent {
            Column {
                MessageDestructionRow(isDestroying = false, isPending = pending.value) {
                    remember { mounts.incrementAndGet() }
                    Text("QA message", Modifier.height(64.dp))
                }
                Text("Next message")
            }
        }
        val nextTop = onNodeWithText("Next message").fetchSemanticsNode().boundsInRoot.top
        runOnIdle { pending.value = true }
        mainClock.advanceTimeBy(300)
        onNodeWithText("QA message").assertIsDisplayed()
        runOnIdle { pending.value = false }
        assertThat(mounts.get()).isEqualTo(1)
        assertThat(onNodeWithText("Next message").fetchSemanticsNode().boundsInRoot.top).isEqualTo(nextTop)
    }

    @Test
    fun `committed destruction animates then removes content without revival on pending changes`() = runAndroidComposeUiTest<ComponentActivity> {
        val destroying = mutableStateOf(false)
        val pending = mutableStateOf(false)
        setSafeContent {
            MessageDestructionRow(isDestroying = destroying.value, isPending = pending.value) {
                Text("QA message", Modifier.height(64.dp))
            }
        }
        mainClock.autoAdvance = false
        runOnIdle { destroying.value = true }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(100)
        onNodeWithText("QA message").assertExists()
        mainClock.advanceTimeBy(600)
        onNodeWithText("QA message").assertDoesNotExist()
        runOnIdle { pending.value = true }
        mainClock.advanceTimeBy(300)
        onNodeWithText("QA message").assertDoesNotExist()
    }
}
