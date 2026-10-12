/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.appnav

import io.element.android.appnav.di.SyncOrchestrator
import io.element.android.features.networkmonitor.api.NetworkStatus
import io.element.android.features.networkmonitor.test.FakeNetworkMonitor
import io.element.android.libraries.matrix.api.sync.SyncState
import io.element.android.libraries.matrix.test.sync.FakeSyncService
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.services.appnavstate.test.FakeAppForegroundStateService
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncStartupRecoveryTest {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test
    fun `failed initial Result installs observation and a foreground retry recovers`() = runTest {
        var attempts = 0
        val sync = FakeSyncService(SyncState.Idle).apply {
            startSyncLambda = { attempts++; Result.failure(IllegalStateException("TEST_START_FAILURE")) }
        }
        val foreground = FakeAppForegroundStateService()
        val orchestrator = orchestrator(sync, foreground = foreground)
        orchestrator.start()
        advanceTimeBy(1_000)
        assertEquals(2, attempts) // Initial probe and one observer attempt; no busy retry loop.
        sync.startSyncLambda = { attempts++; backgroundScope.launch { sync.emitSyncState(SyncState.Running) }; Result.success(Unit) }
        foreground.givenIsInForeground(false)
        advanceTimeBy(4_000)
        foreground.givenIsInForeground(true)
        advanceTimeBy(1_000)
        assertEquals(3, attempts)
        assertEquals(SyncState.Running, sync.syncState.value)
    }

    @Test
    fun `explicit Activity retry restarts idle transport without a new observer`() = runTest {
        var attempts = 0
        val sync = FakeSyncService(SyncState.Idle).apply {
            startSyncLambda = { attempts++; Result.failure(IllegalStateException("TEST_START_FAILURE")) }
        }
        val orchestrator = orchestrator(sync)
        orchestrator.start()
        advanceTimeBy(1_000)
        assertEquals(2, attempts)
        sync.startSyncLambda = { attempts++; backgroundScope.launch { sync.emitSyncState(SyncState.Running) }; Result.success(Unit) }
        repeat(10) { orchestrator.start() }
        advanceTimeBy(1_000)
        assertEquals(3, attempts)
        assertEquals(SyncState.Running, sync.syncState.value)
    }

    @Test
    fun `failed initial probe recovers on network return without starting while offline`() = runTest {
        var attempts = 0
        val sync = FakeSyncService(SyncState.Idle).apply {
            startSyncLambda = { attempts++; Result.failure(IllegalStateException("TEST_START_FAILURE")) }
        }
        val network = FakeNetworkMonitor(NetworkStatus.Disconnected)
        val orchestrator = orchestrator(sync, network = network)
        orchestrator.start()
        advanceTimeBy(1_000)
        orchestrator.start()
        advanceTimeBy(1_000)
        assertEquals(1, attempts)
        sync.startSyncLambda = { attempts++; backgroundScope.launch { sync.emitSyncState(SyncState.Running) }; Result.success(Unit) }
        network.connectivity.value = NetworkStatus.Connected
        advanceTimeBy(1_000)
        assertEquals(2, attempts)
        assertEquals(SyncState.Running, sync.syncState.value)
    }

    @Test
    fun `successful start stuck at Idle has a bounded wait then recovery observation`() = runTest {
        var attempts = 0
        val sync = FakeSyncService(SyncState.Idle).apply {
            startSyncLambda = { attempts++; Result.success(Unit) }
        }
        val orchestrator = orchestrator(sync)
        orchestrator.start()
        advanceTimeBy(4_900)
        assertEquals(1, attempts)
        sync.startSyncLambda = { attempts++; backgroundScope.launch { sync.emitSyncState(SyncState.Running) }; Result.success(Unit) }
        advanceTimeBy(1_000)
        assertEquals(2, attempts)
        assertEquals(SyncState.Running, sync.syncState.value)
    }

    @Test
    fun `queued explicit starts do not duplicate a pending successful initial attempt`() = runTest {
        var attempts = 0
        val sync = FakeSyncService(SyncState.Idle).apply {
            startSyncLambda = { attempts++; Result.success(Unit) }
        }
        val orchestrator = orchestrator(sync)
        orchestrator.start()
        runCurrent()
        repeat(10) { orchestrator.start() }
        advanceTimeBy(1_000)
        assertEquals(1, attempts)
        sync.emitSyncState(SyncState.Running)
        advanceTimeBy(1_000)
        assertEquals(1, attempts)
    }

    @Test
    fun `explicit retry does not start inactive session`() = runTest {
        var attempts = 0
        val sync = FakeSyncService(SyncState.Idle).apply {
            startSyncLambda = { attempts++; Result.failure(IllegalStateException("TEST_START_FAILURE")) }
        }
        val orchestrator = orchestrator(sync, foreground = FakeAppForegroundStateService(initialForegroundValue = false))
        orchestrator.start()
        advanceTimeBy(1_000)
        orchestrator.start()
        advanceTimeBy(1_000)
        assertEquals(1, attempts)
    }

    @Test
    fun `thrown start exception does not terminate recovery observation`() = runTest {
        var attempts = 0
        val sync = FakeSyncService(SyncState.Idle).apply {
            startSyncLambda = { attempts++; throw IllegalStateException("TEST_START_EXCEPTION") }
        }
        val network = FakeNetworkMonitor(NetworkStatus.Connected)
        val orchestrator = orchestrator(sync, network = network)
        orchestrator.start()
        advanceTimeBy(1_000)
        assertEquals(2, attempts)
        network.connectivity.value = NetworkStatus.Disconnected
        advanceTimeBy(1_000)
        sync.startSyncLambda = { attempts++; backgroundScope.launch { sync.emitSyncState(SyncState.Running) }; Result.success(Unit) }
        network.connectivity.value = NetworkStatus.Connected
        advanceTimeBy(1_000)
        assertEquals(3, attempts)
        assertEquals(SyncState.Running, sync.syncState.value)
    }

    @Test
    fun `cancelled session neither installs a recovery observer nor restarts transport`() = runTest {
        var attempts = 0
        val sync = FakeSyncService(SyncState.Idle).apply {
            startSyncLambda = { attempts++; Result.success(Unit) }
        }
        val session = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val network = FakeNetworkMonitor(NetworkStatus.Connected)
        val orchestrator = SyncOrchestrator(sync, session, FakeAppForegroundStateService(), network,
            testCoroutineDispatchers(), FakeAnalyticsService())
        orchestrator.start()
        runCurrent()
        assertEquals(1, attempts)
        session.cancel()
        network.connectivity.value = NetworkStatus.Disconnected
        advanceTimeBy(1_000)
        network.connectivity.value = NetworkStatus.Connected
        orchestrator.start()
        advanceTimeBy(10_000)
        assertEquals(1, attempts)
    }

    private fun TestScope.orchestrator(
        sync: FakeSyncService,
        network: FakeNetworkMonitor = FakeNetworkMonitor(NetworkStatus.Connected),
        foreground: FakeAppForegroundStateService = FakeAppForegroundStateService(),
    ) = SyncOrchestrator(sync, backgroundScope, foreground, network, testCoroutineDispatchers(), FakeAnalyticsService())
}
