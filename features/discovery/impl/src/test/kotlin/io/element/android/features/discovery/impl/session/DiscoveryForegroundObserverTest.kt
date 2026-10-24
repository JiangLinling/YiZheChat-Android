/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.session

import com.google.common.truth.Truth.assertThat
import io.element.android.features.discovery.api.DiscoveryAccess
import io.element.android.features.discovery.api.DiscoveryAccessState
import io.element.android.features.discovery.api.observeForeground
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryForegroundObserverTest {
    @Test
    fun `disabled discovery does no foreground work`() = runTest {
        val access = Access(false)
        access.observeForeground()
        assertThat(access.probes).isEqualTo(0)
        assertThat(access.exits).isEqualTo(0)
    }

    @Test
    fun `foreground checks recover from guest failure and stop on background`() = runTest {
        val access = Access(true)
        val job = launch { access.observeForeground() }
        runCurrent()
        assertThat(access.probes).isEqualTo(1)
        assertThat(access.exits).isEqualTo(1)
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(access.probes).isEqualTo(2)
        job.cancel()
        job.join()
        assertThat(access.exits).isEqualTo(2)
        advanceTimeBy(60_000)
        assertThat(access.probes).isEqualTo(2)
    }

    private class Access(override val configured: Boolean) : DiscoveryAccess {
        override val state = MutableStateFlow(DiscoveryAccessState())
        var probes = 0
        var exits = 0
        override suspend fun refreshAccess() { if (++probes == 1) error("guest unavailable") }
        override suspend fun enter() = false
        override suspend fun leave() { exits++ }
    }
}
