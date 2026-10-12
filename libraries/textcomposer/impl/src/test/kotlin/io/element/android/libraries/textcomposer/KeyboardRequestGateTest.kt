/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KeyboardRequestGateTest {
    @Test
    fun `begin makes the latest request current`() {
        val gate = KeyboardRequestGate()

        val firstRequest = gate.begin()
        val secondRequest = gate.begin()

        assertThat(gate.isCurrent(firstRequest)).isFalse()
        assertThat(gate.isCurrent(secondRequest)).isTrue()
    }

    @Test
    fun `invalidate makes the current request stale`() {
        val gate = KeyboardRequestGate()
        val request = gate.begin()

        gate.invalidate()

        assertThat(gate.isCurrent(request)).isFalse()
    }

    @Test
    fun `completion runs for the current request`() {
        val gate = KeyboardRequestGate()
        var completionCount = 0
        val request = gate.begin()

        val completed = gate.completeIfCurrent(request) { completionCount++ }

        assertThat(completed).isTrue()
        assertThat(completionCount).isEqualTo(1)
    }

    @Test
    fun `completion does not run after a newer request begins`() {
        val gate = KeyboardRequestGate()
        var completionCount = 0
        val staleRequest = gate.begin()
        gate.begin()

        val completed = gate.completeIfCurrent(staleRequest) { completionCount++ }

        assertThat(completed).isFalse()
        assertThat(completionCount).isEqualTo(0)
    }

    @Test
    fun `invalidating a suspended request prevents its completion action`() = runTest {
        val gate = KeyboardRequestGate()
        val focusRequestStarted = CompletableDeferred<Unit>()
        val resumeFocusRequest = CompletableDeferred<Unit>()
        var completionCount = 0
        val request = gate.begin()
        val requestJob = launch {
            if (!gate.isCurrent(request)) return@launch
            focusRequestStarted.complete(Unit)
            resumeFocusRequest.await()
            if (!gate.isCurrent(request)) return@launch
            completionCount++
        }
        focusRequestStarted.await()

        gate.invalidate()
        resumeFocusRequest.complete(Unit)
        requestJob.join()

        assertThat(completionCount).isEqualTo(0)
    }
}
