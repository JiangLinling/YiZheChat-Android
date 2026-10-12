/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.media

import io.element.android.libraries.core.coroutines.SessionOwnedResourceWork

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.ContinuationInterceptor

class SessionOwnedMediaDownloadsTest {
    @Test
    fun `session ownership preserves explicit download dispatcher and operation without inheriting navigation job`() = runTest {
        val sessionMain = StandardTestDispatcher(testScheduler, "session-main")
        val downloadIo = StandardTestDispatcher(testScheduler, "media-io")
        val sessionJob = Job()
        val navigationJob = coroutineContext[Job]
        val owner = SessionOwnedResourceWork(CoroutineScope(sessionMain + sessionJob))
        val operation = LifecycleOperationContext("real-media-operation", FullAppLifecycleId.LC_16)
        val result = owner.execute(true, downloadIo + operation, {}) {
            assertThat(kotlin.coroutines.coroutineContext[ContinuationInterceptor]).isSameInstanceAs(downloadIo)
            assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(operation.operationId)
            assertThat(kotlin.coroutines.coroutineContext[Job]).isNotSameInstanceAs(navigationJob)
            "fixture"
        }
        assertThat(result).isEqualTo("fixture")
        sessionJob.cancel()
    }

    @Test
    fun `abandoned close failure remains observable and recoverable without redoing product work`() = runTest {
        val owner = SessionOwnedResourceWork(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var allowClose = false
        var failures = 0
        var products = 0
        var closes = 0
        val waiter = async {
            owner.execute(true, EmptyCoroutineContext,
                discard = { check(allowClose); closes++ }, isDiscardVerified = { closes == 1 }, onCleanupFailure = { failures++ }) {
                gate.await(); products++; "leased-resource"
            }
        }
        runCurrent()
        waiter.cancel()
        gate.complete(Unit)
        runCurrent()
        assertThat(failures).isEqualTo(1)
        assertThat(owner.pendingCleanupCount()).isEqualTo(1)
        assertThat(closes).isEqualTo(0)
        allowClose = true
        owner.retryFailedDiscards()
        assertThat(owner.pendingCleanupCount()).isEqualTo(0)
        assertThat(closes).isEqualTo(1)
        assertThat(products).isEqualTo(1)
    }

    @Test
    fun `second no-op close cannot clear failed cleanup or release its admission permit`() = runTest {
        val owner = SessionOwnedResourceWork(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var closed = false
        var physicalCleanup = false
        var failures = 0
        val waiter = async {
            owner.execute(false, EmptyCoroutineContext,
                discard = {
                    if (!closed) {
                        closed = true
                        error("Revoke failed after close CAS")
                    }
                }, isDiscardVerified = { physicalCleanup }, onCleanupFailure = { failures++ }) {
                gate.await(); "failed-lease"
            }
        }
        runCurrent()
        waiter.cancel()
        gate.complete(Unit)
        runCurrent()
        owner.retryFailedDiscards()
        assertThat(failures).isEqualTo(1)
        assertThat(owner.pendingCleanupCount()).isEqualTo(1)
        var admitted = false
        val queued = async {
            owner.execute(false, EmptyCoroutineContext, {}) { admitted = true; "new" }
        }
        runCurrent()
        assertThat(admitted).isFalse()
        // Only independent verified cleanup can release the retained capacity.
        physicalCleanup = true
        owner.retryFailedDiscards()
        runCurrent()
        assertThat(owner.pendingCleanupCount()).isEqualTo(0)
        assertThat(queued.await()).isEqualTo("new")
    }

    @Test
    fun `cancelled response waiter preserves submitted original intent and erases late bytes`() = runTest {
        val owner = SessionOwnedResourceWork(backgroundScope)
        val accepted = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val bytes = byteArrayOf(1, 2, 3)
        var realProductAndReceipts = 0
        val operation = LifecycleOperationContext("real-original-operation", FullAppLifecycleId.LC_16)
        val waiter = async {
            owner.execute(false, operation, { it.fill(0) }) {
                assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(operation.operationId)
                accepted.complete(Unit)
                response.await()
                realProductAndReceipts++
                bytes
            }
        }
        accepted.await()
        waiter.cancel()
        runCurrent()
        assertThat(realProductAndReceipts).isEqualTo(0)
        response.complete(Unit)
        runCurrent()
        assertThat(realProductAndReceipts).isEqualTo(1)
        assertThat(bytes.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `cancelled obsolete background queued before admission does not submit an intent`() = runTest {
        val owner = SessionOwnedResourceWork(backgroundScope)
        val blocked = CompletableDeferred<Unit>()
        var submitted = 0
        val first = async { owner.execute(false, EmptyCoroutineContext, {}) { submitted++; blocked.await(); "first" } }
        runCurrent()
        val obsolete = async { owner.execute(false, EmptyCoroutineContext, {}) { submitted++; "obsolete" } }
        runCurrent()
        obsolete.cancel()
        blocked.complete(Unit)
        runCurrent()
        assertThat(first.await()).isEqualTo("first")
        assertThat(submitted).isEqualTo(1)
    }

    @Test
    fun `session cancellation before owner starts releases permit and never claims product success`() = runTest {
        val sessionJob = Job()
        val owner = SessionOwnedResourceWork(CoroutineScope(coroutineContext + sessionJob))
        var products = 0
        sessionJob.cancel()
        repeat(2) {
            val failure = runCatching {
                owner.execute(false, EmptyCoroutineContext, {}) { products++; "value" }
            }
            assertThat(failure.isFailure).isTrue()
        }
        assertThat(products).isEqualTo(0)
    }

    @Test
    fun `automatic background downloads cannot consume clicked voice admission capacity`() = runTest {
        val owner = SessionOwnedResourceWork(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val first = async { owner.execute(false, EmptyCoroutineContext, {}) { gate.await(); "image" } }
        runCurrent()
        val second = async { owner.execute(false, EmptyCoroutineContext, {}) { "notification" } }
        val voice = async { owner.execute(true, EmptyCoroutineContext, {}) { "voice" } }
        runCurrent()
        assertThat(second.isCompleted).isFalse()
        assertThat(voice.await()).isEqualTo("voice")
        gate.complete(Unit)
        assertThat(first.await()).isEqualTo("image")
        assertThat(second.await()).isEqualTo("notification")
    }

    @Test
    fun `prompt delivery cancellation closes only its own completed result once`() = runTest {
        val owner = SessionOwnedResourceWork(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var closed = 0
        val waiter = async { owner.execute(true, EmptyCoroutineContext, { closed++ }) { gate.await(); "old" } }
        runCurrent()
        gate.complete(Unit)
        waiter.cancel()
        runCurrent()
        assertThat(closed).isEqualTo(1)
        assertThat(owner.execute<String>(true, EmptyCoroutineContext, { error("new result must survive") }) { "new" }).isEqualTo("new")
    }
}
