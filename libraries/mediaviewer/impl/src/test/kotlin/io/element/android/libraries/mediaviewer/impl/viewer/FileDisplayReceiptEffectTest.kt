/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package io.element.android.libraries.mediaviewer.impl.viewer

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.setSafeContent
import io.element.android.tests.testutils.FakeLifecycleOwner
import io.element.android.tests.testutils.withFakeLifecycleOwner
import androidx.lifecycle.Lifecycle
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileDisplayReceiptEffectTest {
    @Test
    fun `offscreen render is recorded only after the user settles on the ready file`() = runAndroidComposeUiTest {
        val rendered = mutableStateOf(false)
        val displayed = mutableStateOf(false)
        val calls = AtomicInteger()
        val owner = FakeLifecycleOwner(Lifecycle.State.RESUMED)
        setSafeContent { withFakeLifecycleOwner(owner) {
            FileDisplayReceiptEffect(rendered.value, displayed.value, true, false) { calls.incrementAndGet() }
        } }
        runOnIdle { rendered.value = true }
        waitForIdle()
        assertThat(calls.get()).isEqualTo(0)
        runOnIdle { displayed.value = true }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        waitUntil { calls.get() == 1 }
    }

    @Test
    fun `loading failure and one time view cannot create an opened file receipt`() = runAndroidComposeUiTest {
        val loaded = mutableStateOf(false)
        val oneTime = mutableStateOf(false)
        val calls = AtomicInteger()
        val owner = FakeLifecycleOwner(Lifecycle.State.STARTED)
        setSafeContent { withFakeLifecycleOwner(owner) {
            FileDisplayReceiptEffect(true, true, loaded.value, oneTime.value) { calls.incrementAndGet() }
        } }
        waitForIdle()
        assertThat(calls.get()).isEqualTo(0)
        runOnIdle { oneTime.value = true; loaded.value = true }
        runOnIdle { owner.givenState(Lifecycle.State.RESUMED) }
        waitForIdle()
        assertThat(calls.get()).isEqualTo(0)
    }
}
