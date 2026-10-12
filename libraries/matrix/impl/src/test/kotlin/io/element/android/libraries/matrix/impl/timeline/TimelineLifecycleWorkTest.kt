/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class TimelineLifecycleWorkTest {
    @Test
    fun `canonical readers join original mapping and do not block registration transport`() = runTest {
        val work = TimelineLifecycleWork { testScheduler.currentTime }
        assertThat(work.claimRegistration("original-send-mapping")).isTrue()
        val readers = List(3) { async { work.awaitRegistration("original-send-mapping") } }
        runCurrent()
        assertThat(readers.all { !it.isCompleted }).isTrue()
        assertThat(work.registrationTransport { "registered-with-original-send" }).isEqualTo("registered-with-original-send")
        work.finishRegistration("original-send-mapping", true)
        assertThat(readers.awaitAll()).containsExactly(true, true, true)
    }

    @Test
    fun `cancelled page waiter cannot cancel mapping or another reader`() = runTest {
        val work = TimelineLifecycleWork { testScheduler.currentTime }
        work.claimRegistration("mapping")
        val departing = async { work.awaitRegistration("mapping") }
        val staying = async { work.awaitRegistration("mapping") }
        runCurrent()
        departing.cancel()
        work.finishRegistration("mapping", true)
        assertThat(staying.await()).isTrue()
    }

    @Test
    fun `aborted mapping wakes pending readers without claiming success`() = runTest {
        val work = TimelineLifecycleWork { testScheduler.currentTime }
        work.claimRegistration("mapping")
        val reader = async { work.awaitRegistration("mapping") }
        runCurrent()
        work.abortRegistration("mapping")
        assertThat(reader.await()).isFalse()
        assertThat(work.awaitRegistration("unknown-original-operation")).isNull()
    }

    private val request = lifecycleOperationRequest(FullAppLifecycleId.LC_13, "receive", operationId = "lc13-receive-test-identity")

    @Test
    fun `history receive flood leaves physical start capacity for foreground`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val backing = FakeAppOperationalStateCoordinator()
        val coordinator = object : AppOperationalStateCoordinator by backing {
            override suspend fun start(request: io.element.android.security.dataprotection.api.LifecycleOperationRequest): LifecycleOperation {
                started += request.operationId
                if (request.operationId.startsWith("old-")) gate.await()
                return backing.start(request)
            }
        }
        val work = TimelineLifecycleWork(System::currentTimeMillis)
        val old = List(100) { i -> launch {
            work.receive(coordinator, request.copy(operationId = "old-history-operation-$i"), isForeground = false) { Unit }
        } }
        runCurrent()
        assertThat(started).hasSize(1)
        val foreground = async { work.receive(coordinator, request) { "fresh" } }
        runCurrent()
        assertThat(foreground.isCompleted).isTrue()
        assertThat(foreground.await()).isEqualTo("fresh")
        assertThat(started).hasSize(2)
        assertThat(backing.clientReceipts).hasSize(2)
        old.forEach { it.cancel() }
        old.forEach { it.join() }
        assertThat(backing.clientReceipts).hasSize(2)
        gate.complete(Unit)
    }

    @Test
    fun `authorized read returns with queued facts without invoking receipt transport`() = runTest {
        val backing = FakeAppOperationalStateCoordinator()
        val queued = mutableListOf<LifecycleClientReceipt>()
        val coordinator = object : AppOperationalStateCoordinator by backing {
            override suspend fun enqueueClientReceipts(receipts: List<LifecycleClientReceipt>) { queued += receipts }
            override suspend fun recordClientReceipt(receipt: LifecycleClientReceipt): LifecycleOperation = error("network must not be awaited")
        }
        val work = TimelineLifecycleWork(System::currentTimeMillis)
        repeat(5) {
            assertThat(work.receive(coordinator, request) { "authorized" }).isEqualTo("authorized")
        }
        assertThat(queued).hasSize(2)
        assertThat(backing.clientReceipts).isEmpty()
        assertThat(backing.startedRequests).hasSize(1)
    }

    @Test
    fun `concurrent repeated reads share transport but each rechecks protected access`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val work = TimelineLifecycleWork { System.currentTimeMillis() + testScheduler.currentTime }
        var reads = 0
        List(50) { async {
            work.receive(coordinator, request) {
                assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(request.operationId)
                reads++
            }
        } }.awaitAll()
        assertThat(reads).isEqualTo(50)
        assertThat(coordinator.startedRequests).hasSize(1)
        assertThat(coordinator.clientReceipts).hasSize(2)
        val locked = runCatching { work.receive(coordinator, request) { throw SecurityException("Vault locked") } }
        assertThat(locked.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(coordinator.clientReceipts).hasSize(2)
    }

    @Test
    fun `failed read never attests success and retry keeps the existing operation`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val work = TimelineLifecycleWork(System::currentTimeMillis)
        runCatching { work.receive(coordinator, request) { error("registration refused") } }
        assertThat(coordinator.clientReceipts).isEmpty()
        work.receive(coordinator, request) { Unit }
        assertThat(coordinator.startedRequests).hasSize(1)
        assertThat(coordinator.clientReceipts).hasSize(2)
    }

    @Test
    fun `429 start failure backs off without releasing protected content`() = runTest {
        var now = 0L
        var reject = true
        var reads = 0
        val coordinator = FakeAppOperationalStateCoordinator(startFailure = { if (reject) IOException("HTTP 429") else null })
        val work = TimelineLifecycleWork { System.currentTimeMillis() + now }
        repeat(100) { runCatching { work.receive(coordinator, request) { reads++ } } }
        assertThat(reads).isEqualTo(0)
        assertThat(coordinator.startedRequests).hasSize(1)
        reject = false
        now = 30_000
        work.receive(coordinator, request) { reads++ }
        assertThat(reads).isEqualTo(1)
        assertThat(coordinator.startedRequests).hasSize(2)
    }

    @Test
    fun `registration is single flight and failure cannot retry in a projection loop`() {
        var now = 0L
        val work = TimelineLifecycleWork { now }
        assertThat(work.claimRegistration("hash")).isTrue()
        assertThat(work.claimRegistration("hash")).isFalse()
        work.finishRegistration("hash", false)
        repeat(100) { assertThat(work.claimRegistration("hash")).isFalse() }
        now = 4_000
        assertThat(work.claimRegistration("hash")).isTrue()
        work.finishRegistration("hash", true)
        now = 1_000_000
        assertThat(work.claimRegistration("hash")).isFalse()
        assertThat(work.claimRegistration("another-account-hash")).isTrue()
    }

    @Test
    fun `historical repair backlog cannot fill transport ahead of a new receive`() = runTest {
        val work = TimelineLifecycleWork { System.currentTimeMillis() + testScheduler.currentTime }
        val firstRepairGate = CompletableDeferred<Unit>()
        val repairsStarted = mutableListOf<String>()
        val backing = FakeAppOperationalStateCoordinator()
        val coordinator = object : AppOperationalStateCoordinator by backing {
            override suspend fun start(request: io.element.android.security.dataprotection.api.LifecycleOperationRequest): LifecycleOperation {
                if (request.operationId.startsWith("lc13-repair")) {
                    repairsStarted += request.operationId
                    if (request.operationId == "lc13-repair-test-0") firstRepairGate.await()
                }
                return backing.start(request)
            }
        }
        val repairs = List(100) { index -> launch {
            work.registrationWork(isLiveSend = false) {
                work.receive(coordinator, request.copy(operationId = "lc13-repair-test-$index")) {
                    work.registrationTransport { Unit }
                }
            }
        } }
        runCurrent()
        assertThat(repairsStarted).containsExactly("lc13-repair-test-0")
        val fresh = async { work.receive(coordinator, request) { "fresh-authorized" } }
        runCurrent()
        assertThat(fresh.isCompleted).isTrue()
        assertThat(fresh.await()).isEqualTo("fresh-authorized")
        assertThat(repairsStarted).containsExactly("lc13-repair-test-0")
        firstRepairGate.complete(Unit)
        repairs.forEach { it.join() }
        assertThat(repairsStarted).hasSize(100)
        assertThat(backing.startedRequests).hasSize(101)
        assertThat(backing.clientReceipts).hasSize(202)
    }

    @Test
    fun `persisted live send mapping bypasses repair backlog without raising transport concurrency`() = runTest {
        val work = TimelineLifecycleWork { System.currentTimeMillis() + testScheduler.currentTime }
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        val old = launch { work.registrationWork(isLiveSend = false) {
            work.registrationTransport {
                active++
                maximum = maxOf(maximum, active)
                release.await()
                active--
            }
        } }
        runCurrent()
        val waitingRepair = launch { work.registrationWork(isLiveSend = false) {
            work.registrationTransport { error("repair should still wait") }
        } }
        val live = async { work.registrationWork(isLiveSend = true) {
            work.registrationTransport {
                active++
                maximum = maxOf(maximum, active)
                active--
                "live-mapping"
            }
        } }
        runCurrent()
        assertThat(live.isCompleted).isTrue()
        assertThat(live.await()).isEqualTo("live-mapping")
        assertThat(maximum).isEqualTo(2)
        waitingRepair.cancel()
        release.complete(Unit)
        old.join()
    }

    @Test
    fun `cancelled or failed repair releases scheduling gate and does not attest protected success`() = runTest {
        val work = TimelineLifecycleWork { System.currentTimeMillis() + testScheduler.currentTime }
        val coordinator = FakeAppOperationalStateCoordinator()
        val old = launch { work.registrationWork(isLiveSend = false) {
            work.receive(coordinator, request) { CompletableDeferred<Unit>().await() }
        } }
        runCurrent()
        old.cancel()
        old.join()
        assertThat(coordinator.clientReceipts).isEmpty()
        runCatching { work.registrationWork(isLiveSend = false) { throw IOException("repair rejected") } }
        val next = async { work.registrationWork(isLiveSend = false) {
            work.receive(coordinator, request.copy(operationId = "lc13-repair-test-next")) { "authorized" }
        } }
        runCurrent()
        assertThat(next.await()).isEqualTo("authorized")
        assertThat(coordinator.clientReceipts).hasSize(2)
    }

    @Test
    fun `shared adapters use the same originating hint and another scope cannot claim it`() {
        val work = TimelineLifecycleWork(System::currentTimeMillis)
        val operation = LifecycleOperationContext(request.operationId, request.lifecycleId)
        work.rememberOriginatingOperation("account-device-room-content-hash", operation)
        assertThat(work.originatingOperation("account-device-room-content-hash")).isSameInstanceAs(operation)
        assertThat(work.originatingOperation("other-account-device-room-content-hash")).isNull()
        work.forgetOriginatingOperation("account-device-room-content-hash", operation)
        assertThat(work.originatingOperation("account-device-room-content-hash")).isNull()
    }

    @Test
    fun `stale registration completion cannot discard a newer originating operation hint`() {
        val work = TimelineLifecycleWork(System::currentTimeMillis)
        val old = LifecycleOperationContext(request.operationId, request.lifecycleId)
        val replacement = LifecycleOperationContext("lc13-replacement-operation", request.lifecycleId)
        work.rememberOriginatingOperation("scope-key", old)
        work.rememberOriginatingOperation("scope-key", replacement)
        work.forgetOriginatingOperation("scope-key", old)
        assertThat(work.originatingOperation("scope-key")).isSameInstanceAs(replacement)
        work.forgetOriginatingOperation("scope-key", null)
        assertThat(work.originatingOperation("scope-key")).isSameInstanceAs(replacement)
    }

    @Test
    fun `originating hints have a bounded metadata-only lifetime and eviction grants no access`() {
        val work = TimelineLifecycleWork(System::currentTimeMillis)
        val operation = LifecycleOperationContext(request.operationId, request.lifecycleId)
        repeat(4097) { work.rememberOriginatingOperation("scope-$it", operation) }
        assertThat(work.originatingOperation("scope-0")).isNull()
        assertThat(work.originatingOperation("scope-4096")).isSameInstanceAs(operation)
    }

    @Test
    fun `sensitive runtime invalidation clears shared originating hints`() {
        val work = TimelineLifecycleWork(System::currentTimeMillis)
        work.rememberOriginatingOperation("scope-key", LifecycleOperationContext(request.operationId, request.lifecycleId))
        work.clearOriginatingOperations()
        assertThat(work.originatingOperation("scope-key")).isNull()
    }

    @Test
    fun `blocked restored bound send and legacy lookups leave a physical slot for current binding`() = runTest {
        val work = TimelineLifecycleWork { testScheduler.currentTime }
        val release = CompletableDeferred<Unit>()
        val completed = mutableListOf<String>()
        val repairs = List(100) { index -> launch {
            work.registrationWork(isLiveSend = false) {
                work.registrationTransport(isForeground = false) {
                    if (index == 0) release.await()
                    completed += "bound-original-$index"
                }
            }
        } }
        val lookups = List(100) { index -> launch {
            work.registrationTransport(isForeground = false) { completed += "legacy-origin-$index" }
        } }
        runCurrent()
        val original = LifecycleOperationContext(request.operationId, request.lifecycleId)
        val current = async { kotlinx.coroutines.withContext(original) {
            work.registrationWork(isLiveSend = true) {
                work.registrationTransport(isLiveSend = true) {
                    assertThat(currentLifecycleOperationContext()).isSameInstanceAs(original)
                    completed += "current-original-binding"
                }
            }
        } }
        runCurrent()
        assertThat(current.isCompleted).isTrue()
        current.await()
        assertThat(completed).containsExactly("current-original-binding")
        release.complete(Unit)
        repairs.forEach { it.join() }
        lookups.forEach { it.join() }
        assertThat(completed).hasSize(201)
    }

    @Test
    fun `original binding keeps send identity and precedes queued own origin lookups`() = runTest {
        val work = TimelineLifecycleWork { testScheduler.currentTime }
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val occupied = List(2) { launch { work.registrationTransport { release.await() } } }
        runCurrent()
        val lookups = List(100) { index -> launch {
            work.registrationTransport { order += "origin-$index" }
        } }
        runCurrent()
        val original = LifecycleOperationContext(request.operationId, request.lifecycleId)
        val binding = async {
            work.registrationWork(isLiveSend = true) {
                kotlinx.coroutines.withContext(original) {
                    work.registrationTransport(isLiveSend = true) {
                        assertThat(currentLifecycleOperationContext()).isSameInstanceAs(original)
                        order += "original-binding"
                        "mapped"
                    }
                }
            }
        }
        runCurrent()
        assertThat(binding.isCompleted).isFalse()
        release.complete(Unit)
        occupied.forEach { it.join() }
        assertThat(binding.await()).isEqualTo("mapped")
        lookups.forEach { it.join() }
        assertThat(order.first()).isEqualTo("original-binding")
        assertThat(order.drop(1)).containsExactlyElementsIn((0 until 100).map { "origin-$it" }).inOrder()
    }
}
