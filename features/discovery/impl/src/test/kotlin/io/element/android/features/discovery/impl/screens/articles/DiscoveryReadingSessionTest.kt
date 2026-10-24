/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import io.element.android.features.discovery.impl.actions.DiscoveryReadingCapacityException
import io.element.android.features.discovery.impl.actions.DiscoveryReadingHandle
import io.element.android.features.discovery.impl.actions.DiscoveryReadings
import io.element.android.features.discovery.impl.session.AndroidDiscoveryClock
import io.element.android.features.discovery.impl.session.DiscoveryAccountScope
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiscoveryReadingSessionTest {
    private class Fixture {
        var now = 0L
        val identity = DiscoveryAccountScope("a".repeat(64), "b".repeat(64), "install", 1, 100)
        val starts = mutableListOf<Pair<String, String>>()
        val service = mockk<DiscoveryReadings>()
        val clock = mockk<AndroidDiscoveryClock> { every { elapsedMillis() } answers { now } }
        var failBegin = false
        init {
            coEvery { service.begin(any(), any(), any()) } answers {
                starts += secondArg<String>() to thirdArg<String>()
                if (failBegin) { failBegin = false; throw IllegalStateException("test ambiguous local publication") }
                DiscoveryReadingHandle(identity, secondArg())
            }
            coEvery { service.checkpoint(any(), any(), any()) } just Runs
            coEvery { service.synchronize() } returns true
        }
    }

    @Test
    fun `hidden composition and background pause one node reading without creating another visit`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = Fixture()
            val session = DiscoveryReadingSession(7, f.service, f.clock, backgroundScope)
            session.visible(true); runCurrent()
            f.now = 5000
            session.visible(false); runCurrent()
            coVerify { f.service.checkpoint(any(), 5000, false) }
            coVerify(exactly = 0) { f.service.checkpoint(any(), any(), true) }
            f.now = 105000
            session.visible(true); runCurrent()
            f.now = 115000
            session.close(); runCurrent()
            coVerify { f.service.checkpoint(any(), 15000, true) }
            coVerify(exactly = 1) { f.service.begin(7, any(), any()) }
            session.visible(true); session.close(); runCurrent()
            coVerify(exactly = 1) { f.service.checkpoint(any(), any(), true) }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `retry after lost network response clears notice without creating a new reading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = Fixture()
            coEvery { f.service.synchronize() } throws DiscoveryFailure(503)
            val session = DiscoveryReadingSession(7, f.service, f.clock, backgroundScope)
            session.visible(true); runCurrent()
            assertTrue(session.pending.value)
            coEvery { f.service.synchronize() } returns true
            session.retry(); runCurrent()
            assertFalse(session.pending.value)
            coVerify(exactly = 1) { f.service.begin(7, any(), any()) }
            session.close(); runCurrent()
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `ambiguous local begin retry retains both operation IDs`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = Fixture()
            f.failBegin = true
            val session = DiscoveryReadingSession(7, f.service, f.clock, backgroundScope)
            session.visible(true); runCurrent()
            assertTrue(session.pending.value)
            session.retry(); runCurrent()
            assertEquals(2, f.starts.size)
            assertEquals(f.starts.first(), f.starts.last())
            assertFalse(session.pending.value)
            session.close(); runCurrent()
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `full recovery buffer drains older work before retrying the same entry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = Fixture()
            var full = true
            coEvery { f.service.begin(any(), any(), any()) } answers {
                if (full) { full = false; throw DiscoveryReadingCapacityException() }
                DiscoveryReadingHandle(f.identity, secondArg())
            }
            val session = DiscoveryReadingSession(7, f.service, f.clock, backgroundScope)
            session.visible(true); runCurrent()
            assertTrue(session.pending.value)
            coVerify(exactly = 1) { f.service.synchronize() }
            session.retry(); runCurrent()
            assertFalse(session.pending.value)
            session.close(); runCurrent()
        } finally { Dispatchers.resetMain() }
    }
}
