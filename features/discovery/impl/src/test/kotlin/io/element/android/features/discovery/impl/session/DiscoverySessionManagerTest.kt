/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class DiscoverySessionManagerTest {
    private val phoneA = DiscoveryAccountScope("a".repeat(64), "b".repeat(64), "installation-a", 1, 100)
    private val phoneB = phoneA.copy(accountHash = "c".repeat(64), installationId = "installation-b")

    private inner class Fixture(val test: TestScope) {
        val identity = MutableStateFlow<DiscoveryAccountScope?>(phoneA)
        var hostReadFails = false
        val gate = object : DiscoveryIdentityGate {
            override val scope = identity
            override suspend fun current(): DiscoveryAccountScope? {
                if (hostReadFails) error("test host read unavailable")
                return scope.value
            }
        }
        val clock = object : DiscoveryClock {
            override fun wallMillis() = 100_000 + test.testScheduler.currentTime
            override fun elapsedMillis() = test.testScheduler.currentTime
        }
        var issues = 0
        var exchanges = 0
        var revokes = 0
        var allowed = true
        var failExchange = false
        var waitForIssue: CompletableDeferred<Unit>? = null
        val subjects = mutableListOf<DiscoveryAccountScope>()
        val seenCodes = mutableListOf<DiscoveryCode>()
        val authority = object : DiscoveryAuthorityClient {
            override suspend fun issue(scope: DiscoveryAccountScope): DiscoveryCode {
                issues++
                subjects += scope
                // Deliberately simulate a transport which completes after cancellation.
                waitForIssue?.let { withContext(NonCancellable) { it.await() } }
                return DiscoveryCode(DiscoverySecret("code-$issues"), "operation-$issues", DiscoverySecret("verifier-$issues"),
                    clock.wallMillis() + 30_000, clock.wallMillis() + 60_000, clock.elapsedMillis())
            }
        }
        val gateway = object : DiscoveryGatewayClient {
            override suspend fun allowed(code: DiscoveryCode) = allowed
            override suspend fun exchange(code: DiscoveryCode): DiscoverySession {
                exchanges++
                seenCodes += code
                if (failExchange) throw DiscoveryFailure(503)
                return DiscoverySession(DiscoverySecret("session"), code.exchangeId, code.authorizationExpiresAtMillis,
                    code.startedAtElapsedMillis + 60_000)
            }
            override suspend fun revoke(session: DiscoverySession) { revokes++ }
        }
        val manager = DiscoverySessionManager(gate, authority, gateway, clock, test.backgroundScope, StandardTestDispatcher(test.testScheduler))
    }

    @Test
    fun `capability probe does not create session and concurrent entry shares handoff`() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.manager.refreshAccess()
        assertTrue(f.manager.state.value.entryVisible)
        assertEquals(0, f.exchanges)
        val first = async { f.manager.enter() }
        val second = async { f.manager.enter() }
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, f.issues)
        assertEquals(1, f.exchanges)
    }

    @Test
    fun `unknown exchange result retries same operation without extending expiry`() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.failExchange = true
        assertFalse(f.manager.enter())
        advanceTimeBy(10_000)
        f.failExchange = false
        assertTrue(f.manager.enter())
        assertSame(f.seenCodes[0], f.seenCodes[1])
        advanceTimeBy(50_000)
        runCurrent()
        assertFalse(f.manager.state.value.entered)
        assertFalse(f.manager.state.value.entryVisible)
    }

    @Test
    fun `late reply after account change cannot be saved or shown`() = runTest {
        val f = Fixture(this)
        runCurrent()
        val release = CompletableDeferred<Unit>()
        f.waitForIssue = release
        val pending = async { f.manager.enter() }
        runCurrent()
        f.identity.value = phoneB
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertTrue(pending.isCancelled)
        assertFalse(f.manager.state.value.entered)
        assertEquals(0, f.exchanges)
        f.waitForIssue = null
        assertTrue(f.manager.enter())
        assertEquals(listOf(phoneA, phoneB), f.subjects)
        assertEquals("code-2", f.seenCodes.single().secret.value)
    }

    @Test
    fun `locked host and permission denial clear guest without logging out host`() = runTest {
        val f = Fixture(this)
        runCurrent()
        assertTrue(f.manager.enter())
        f.allowed = false
        f.manager.refreshAccess()
        assertFalse(f.manager.state.value.entryVisible)
        f.identity.value = null
        runCurrent()
        assertFalse(f.manager.enter())
        assertEquals(1, f.issues)
    }

    @Test
    fun `leaving clears locally before asynchronous guest revoke`() = runTest {
        val f = Fixture(this)
        runCurrent()
        assertTrue(f.manager.enter())
        f.manager.leave()
        assertFalse(f.manager.state.value.entered)
        runCurrent()
        assertEquals(1, f.revokes)
    }

    @Test
    fun `host observer failure hides discovery without escaping into host scope`() = runTest {
        val f = Fixture(this)
        runCurrent()
        assertTrue(f.manager.enter())
        f.hostReadFails = true
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(f.manager.state.value.entered)
        assertFalse(f.manager.enter())
        f.hostReadFails = false
        assertTrue(f.manager.enter())
    }

    @Test
    fun `business response from an old identity cannot reach the new screen`() = runTest {
        val f = Fixture(this)
        runCurrent()
        assertTrue(f.manager.enter())
        val release = CompletableDeferred<Unit>()
        val read = async {
            f.manager.withSession { withContext(NonCancellable) { release.await() }; "account A content" }
        }
        runCurrent()
        f.identity.value = phoneB
        runCurrent()
        assertTrue(f.manager.enter())
        release.complete(Unit)
        runCurrent()
        assertTrue(read.isCancelled)
        assertTrue(f.manager.state.value.entered)
    }

    @Test
    fun `business response after absolute expiry is discarded`() = runTest {
        val f = Fixture(this)
        runCurrent()
        assertTrue(f.manager.enter())
        val release = CompletableDeferred<Unit>()
        val read = async { f.manager.withSession { release.await(); "expired content" } }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertTrue(read.isCancelled)
        assertFalse(f.manager.state.value.entered)
    }

    @Test
    fun `late rejection of an old session cannot clear a refreshed session`() = runTest {
        for (status in listOf(401, 403, 409)) {
            val f = Fixture(this)
            runCurrent()
            assertTrue(f.manager.enter())
            val release = CompletableDeferred<Unit>()
            val pending = async {
                runCatching { f.manager.withSession { release.await(); throw DiscoveryFailure(status) } }
            }
            runCurrent()
            advanceTimeBy(31_000)
            f.manager.refreshAccess()
            assertEquals(2, f.issues)
            release.complete(Unit)
            assertEquals(status, (pending.await().exceptionOrNull() as DiscoveryFailure).status)
            assertTrue(f.manager.state.value.entered)
            assertEquals("new session remains usable", f.manager.withSession { "new session remains usable" })
            f.manager.leave()
        }
    }

    @Test
    fun `expired response is discarded without evicting a still valid replacement`() = runTest {
        val f = Fixture(this)
        runCurrent()
        assertTrue(f.manager.enter())
        val release = CompletableDeferred<Unit>()
        val pending = async {
            runCatching { f.manager.withSession { release.await(); "expired content" } }
        }
        runCurrent()
        advanceTimeBy(31_000)
        f.manager.refreshAccess()
        advanceTimeBy(29_000)
        runCurrent()
        release.complete(Unit)
        assertEquals(401, (pending.await().exceptionOrNull() as DiscoveryFailure).status)
        assertTrue(f.manager.state.value.entered)
        advanceTimeBy(31_000)
        runCurrent()
        assertFalse(f.manager.state.value.entered)
    }

    @Test
    fun `current session rejection still closes discovery`() = runTest {
        val f = Fixture(this)
        runCurrent()
        assertTrue(f.manager.enter())
        val result = runCatching { f.manager.withSession { throw DiscoveryFailure(403) } }
        assertEquals(403, (result.exceptionOrNull() as DiscoveryFailure).status)
        assertFalse(f.manager.state.value.entered)
    }
}
