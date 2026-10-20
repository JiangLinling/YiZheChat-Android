/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.ProtectedEventOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class ProtectedOriginRecoveryTest {
    private val original = ProtectedEventOrigin("lc13-send-original", FullAppLifecycleId.LC_13)

    @Test fun `concurrent readers share repair but successful lookup is never cached`() = runTest {
        val work = ProtectedOriginRecovery { testScheduler.currentTime }
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = async { work.lookup("exact-carrier") { calls++; gate.await(); original } }
        runCurrent()
        val second = async { work.lookup("exact-carrier") { error("duplicate lookup") } }
        runCurrent()
        gate.complete(Unit)
        assertThat(first.await()).isEqualTo(original)
        assertThat(second.await()).isEqualTo(original)
        assertThat(work.lookup("exact-carrier") { calls++; null }).isNull()
        assertThat(calls).isEqualTo(2)
    }

    @Test fun `unresolved old carrier backs off without blocking a different new carrier`() = runTest {
        val work = ProtectedOriginRecovery { testScheduler.currentTime }
        assertThat(work.lookup("old") { null }).isNull()
        assertThat(work.lookup("old") { error("backoff must avoid transport") }).isNull()
        assertThat(work.lookup("new") { original }).isEqualTo(original)
        advanceTimeBy(4_000)
        assertThat(work.lookup("old") { original }).isEqualTo(original)
    }

    @Test fun `cancelled waiter cannot cancel producer or another reader`() = runTest {
        val work = ProtectedOriginRecovery { testScheduler.currentTime }
        val gate = CompletableDeferred<Unit>()
        val first = async { work.lookup("same") { gate.await(); original } }
        runCurrent()
        val leaving = async { work.lookup("same") { error("duplicate") } }
        val staying = async { work.lookup("same") { error("duplicate") } }
        runCurrent()
        leaving.cancel()
        gate.complete(Unit)
        assertThat(first.await()).isEqualTo(original)
        assertThat(staying.await()).isEqualTo(original)
    }

    @Test fun `capacity never evicts active work and permits repair after completion`() = runTest {
        val work = ProtectedOriginRecovery(capacity = 1) { testScheduler.currentTime }
        val gate = CompletableDeferred<Unit>()
        val first = async { work.lookup("active") { gate.await(); original } }
        runCurrent()
        assertThat(work.lookup("other") { error("capacity must reject") }).isNull()
        gate.complete(Unit)
        first.await()
        assertThat(work.lookup("other") { original }).isEqualTo(original)
    }

    @Test fun `authority failure grants no context and permits later fresh retry`() = runTest {
        val work = ProtectedOriginRecovery { testScheduler.currentTime }
        val failure = runCatching { work.lookup("denied") { throw IOException("authority unavailable") } }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(work.lookup("denied") { error("failure must back off") }).isNull()
        advanceTimeBy(4_000)
        assertThat(work.lookup("denied") { original }).isEqualTo(original)
    }

    @Test fun `cancelled producer releases capacity and cannot publish a successful context`() = runTest {
        val work = ProtectedOriginRecovery(capacity = 1) { testScheduler.currentTime }
        val gate = CompletableDeferred<Unit>()
        val producer = async { work.lookup("old") { gate.await(); original } }
        runCurrent()
        producer.cancel()
        producer.join()
        assertThat(work.lookup("old") { error("cancelled attempt must back off") }).isNull()
        assertThat(work.lookup("new") { original }).isEqualTo(original)
    }

    @Test fun `real producer binding invalidates only its exact metadata cooldown`() = runTest {
        val work = ProtectedOriginRecovery { testScheduler.currentTime }
        work.lookup("first-carrier") { null }
        work.lookup("other-carrier") { null }
        assertThat(work.isDeferred("first-carrier")).isTrue()
        work.invalidate("first-carrier")
        assertThat(work.isDeferred("first-carrier")).isFalse()
        assertThat(work.isDeferred("other-carrier")).isTrue()
        assertThat(work.lookup("first-carrier") { original }).isEqualTo(original)
    }

    @Test fun `late unresolved lookup cannot restore cooldown after real binding publication`() = runTest {
        val work = ProtectedOriginRecovery { testScheduler.currentTime }
        val gate = CompletableDeferred<Unit>()
        val stale = async { work.lookup("bound-carrier") { gate.await(); null } }
        runCurrent()
        work.invalidate("bound-carrier")
        gate.complete(Unit)
        assertThat(stale.await()).isNull()
        assertThat(work.isDeferred("bound-carrier")).isFalse()
        assertThat(work.lookup("bound-carrier") { original }).isEqualTo(original)
    }
}
