/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appnav

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartupSessionInitializationTest {
    private val context = LifecycleOperationContext("original-startup-operation", FullAppLifecycleId.LC_06)

    @Test
    fun `activation waits for actual local session setup and Home waits for receipts`() = runTest {
        val startup = StartupSessionInitialization()
        val localFinished = CompletableDeferred<Unit>()
        val transportFinished = CompletableDeferred<Unit>()
        var activated = false
        var receiptBatches = 0
        val initialization = backgroundScope.async {
            startup.initialize(context) { localFinished.await() }
            receiptBatches++
            transportFinished.await()
            startup.complete()
        }
        val activation = backgroundScope.async {
            startup.activate({
                assertThat(currentLifecycleOperationContext()).isSameInstanceAs(context)
                activated = true
            }, { error("Duplicated admission") })
        }
        runCurrent()
        assertThat(activated).isFalse()
        assertThat(receiptBatches).isEqualTo(0)
        localFinished.complete(Unit)
        runCurrent()
        assertThat(activated).isTrue()
        assertThat(receiptBatches).isEqualTo(1)
        assertThat(activation.isCompleted).isFalse()
        transportFinished.complete(Unit)
        runCurrent()
        initialization.await()
        activation.await()
    }

    @Test
    fun `failed activation retries within the original operation without premature receipts`() = runTest {
        val startup = StartupSessionInitialization()
        var receiptBatches = 0
        val initialization = backgroundScope.async {
            startup.initialize(context) {}
            receiptBatches++
            startup.complete()
        }
        runCurrent()
        val failed = runCatching { startup.activate({ error("KMS unavailable") }, { error("Duplicated admission") }) }
        assertThat(failed.isFailure).isTrue()
        assertThat(receiptBatches).isEqualTo(0)
        val retry = backgroundScope.async {
            startup.activate({
                assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(context.operationId)
            }, { error("Duplicated admission") })
        }
        runCurrent()
        initialization.await()
        retry.await()
        assertThat(receiptBatches).isEqualTo(1)
    }

    @Test
    fun `initialization failure releases activation waiter without running protected action`() = runTest {
        val startup = StartupSessionInitialization()
        var activated = false
        val waiting = backgroundScope.async {
            runCatching { startup.activate({ activated = true }, { activated = true }) }
        }
        runCurrent()
        val failure = IllegalStateException("Sending queue setup failed")
        startup.fail(failure)
        runCurrent()
        val observedFailure = waiting.await().exceptionOrNull()
        assertThat(observedFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(observedFailure?.message).isEqualTo(failure.message)
        assertThat(activated).isFalse()
    }

    @Test
    fun `receipt failure cannot report startup completion even after CORE committed`() = runTest {
        val startup = StartupSessionInitialization()
        val transportFinished = CompletableDeferred<Unit>()
        val failure = IllegalStateException("Receipt not acknowledged")
        val initialization = backgroundScope.async {
            startup.initialize(context) {}
            transportFinished.await()
            startup.fail(failure)
        }
        runCurrent()
        val activation = backgroundScope.async {
            runCatching { startup.activate({}, { error("Duplicated admission") }) }
        }
        runCurrent()
        assertThat(activation.isCompleted).isFalse()
        transportFinished.complete(Unit)
        runCurrent()
        initialization.await()
        val observedFailure = activation.await().exceptionOrNull()
        assertThat(observedFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(observedFailure?.message).isEqualTo(failure.message)
    }

    @Test
    fun `cancelled prerequisites preserve original operation for a later valid retry`() = runTest {
        val startup = StartupSessionInitialization()
        var receiptBatches = 0
        val initialization = backgroundScope.async {
            startup.initialize(context) {}
            receiptBatches++
            startup.complete()
        }
        runCurrent()
        val cancelled = backgroundScope.async {
            startup.activate({ CompletableDeferred<Unit>().await() }, { error("Duplicated admission") })
        }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        assertThat(receiptBatches).isEqualTo(0)
        val retry = backgroundScope.async {
            startup.activate({
                assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(context.operationId)
            }, { error("Duplicated admission") })
        }
        runCurrent()
        initialization.await()
        retry.await()
        assertThat(receiptBatches).isEqualTo(1)
    }

    @Test
    fun `authenticated offline restore initializes locally and retains fresh online admission`() = runTest {
        val startup = StartupSessionInitialization()
        startup.initialize(null) {}
        startup.complete()
        var freshAdmission = 0
        startup.activate({ error("Offline path has no shared online operation") }, { freshAdmission++ })
        assertThat(freshAdmission).isEqualTo(1)
    }

    @Test
    fun `later policy change obtains fresh admission instead of reusing completed startup`() = runTest {
        val startup = StartupSessionInitialization()
        val initialization = backgroundScope.async {
            startup.initialize(context) {}
            startup.complete()
        }
        runCurrent()
        val initialActivation = backgroundScope.async { startup.activate({}, { error("Duplicated admission") }) }
        runCurrent()
        initialization.await()
        initialActivation.await()
        var freshAdmission = 0
        startup.activate({ error("Completed context must not be reused") }, { freshAdmission++ })
        assertThat(freshAdmission).isEqualTo(1)
    }
}
