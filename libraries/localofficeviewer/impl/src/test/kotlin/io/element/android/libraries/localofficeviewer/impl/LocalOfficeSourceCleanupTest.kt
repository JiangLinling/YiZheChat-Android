/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.localofficeviewer.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntime
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntimeCleanup
import io.element.android.libraries.localofficeviewer.api.LocalOfficeCleanupResult
import io.element.android.libraries.localofficeviewer.api.LocalOfficeCloseReason
import io.element.android.libraries.localofficeviewer.api.LocalOfficeContentSource
import io.element.android.libraries.localofficeviewer.api.LocalOfficePreviewRequest
import io.element.android.libraries.localofficeviewer.api.LocalOfficePreviewState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalOfficeSourceCleanupTest {
    @Test
    fun `cancel clears accepted plaintext still owned across delivery`() = runBlocking<Unit> {
        val runner = LocalOfficeValidationRunner(csvRequest())
        val accepted = runner.await() as LocalOfficeDetection.Accepted
        assertThat(accepted.payload.isCleared()).isFalse()
        runner.cancel()
        assertThat(runner.awaitCleanup()).isTrue()
        assertThat(accepted.payload.isCleared()).isTrue()
        assertThrows(IllegalStateException::class.java) { accepted.payload.openInputStream() }
    }

    @Test
    fun `cancel before dispatched continuation refuses late accepted result`() = runBlocking<Unit> {
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(dispatcher)
        val runner = LocalOfficeValidationRunner(csvRequest())
        val delivered = CompletableDeferred<Result<LocalOfficeDetection>>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { delivered.complete(runCatching { runner.await() }) }
        try {
            withTimeout(5_000) { while (!runner.isWorkerTerminated()) delay(10) }
            assertThat(delivered.isCompleted).isFalse()
            runner.cancel()
            assertThat(runner.awaitCleanup()).isTrue()
            dispatcher.scheduler.runCurrent()
            assertThat(delivered.await().exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        } finally {
            runner.cancel()
            scope.cancel()
        }
    }

    private fun csvRequest(): LocalOfficePreviewRequest {
        val bytes = "a,b\n1,2".encodeToByteArray()
        return LocalOfficePreviewRequest(
            operationId = "original-operation",
            attachmentId = "original-attachment",
            displayName = "sample.csv",
            declaredMimeType = "text/csv",
            declaredSize = bytes.size.toLong(),
            contentSource = LocalOfficeContentSource { ByteArrayInputStream(bytes) },
        )
    }

    @Test
    fun `scalar read and skip reject late results while close waits for read exit`() {
        listOf(false, true).forEach { skip ->
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val readExited = CountDownLatch(1)
            val closing = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val handler = SessionContentPathHandler("token") {
                object : InputStream() {
                    override fun read(): Int {
                        entered.countDown()
                        check(proceed.await(5, TimeUnit.SECONDS))
                        return 42
                    }
                    override fun skip(count: Long): Long = read().toLong()
                    override fun close() {
                        closing.countDown()
                        check(readExited.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            handler.install(LocalOfficeValidatedPayload(byteArrayOf(42)), "text/csv")
            val stream = checkNotNull(handler.openPayloadStream("token")).stream
            try {
                val reading = executor.submit<Throwable?> {
                    try {
                        runCatching { if (skip) stream.skip(1) else stream.read() }.exceptionOrNull()
                    } finally {
                        readExited.countDown()
                    }
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                val revoking = executor.submit { handler.revoke() }
                assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue()
                assertThat(handler.isCleanupComplete()).isFalse()
                proceed.countDown()
                assertThat(reading.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException::class.java)
                revoking.get(5, TimeUnit.SECONDS)
                assertThat(handler.isCleanupComplete()).isTrue()
            } finally {
                proceed.countDown()
                readExited.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `failed raw close remains owned until retry closes the same stream`() {
        val bytes = byteArrayOf(1, 2, 3)
        val payload = LocalOfficeValidatedPayload(bytes)
        val closes = AtomicInteger()
        val handler = SessionContentPathHandler("token") {
            object : ByteArrayInputStream(bytes) {
                override fun close() {
                    if (closes.incrementAndGet() == 1) throw IOException("close failed")
                    super.close()
                }
            }
        }
        handler.install(payload, "text/csv")
        val stream = checkNotNull(handler.openPayloadStream("token")).stream
        handler.revoke()
        assertThat(handler.isCleanupComplete()).isFalse()
        assertThat(payload.isCleared()).isFalse()
        assertThrows(IOException::class.java) { stream.read() }

        handler.revoke()
        assertThat(closes.get()).isEqualTo(2)
        assertThat(handler.isCleanupComplete()).isTrue()
        assertThat(payload.isCleared()).isTrue()
        assertThat(bytes).isEqualTo(byteArrayOf(0, 0, 0))
        handler.revoke()
        assertThat(closes.get()).isEqualTo(2)
        assertThat(handler.openPayloadStream("token")).isNull()
    }

    @Test
    fun `revocation waits for late read exit and erases only its caller buffer slice`() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val payload = LocalOfficeValidatedPayload(byteArrayOf(7, 8, 9))
        val handler = SessionContentPathHandler("token") {
            object : InputStream() {
                override fun read(): Int = error("use bulk read")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(proceed.await(5, TimeUnit.SECONDS))
                    buffer.fill(7, offset, offset + length)
                    return length
                }
                // Closing the underlying handle does not imply this read has exited.
                override fun close() = Unit
            }
        }
        handler.install(payload, "text/csv")
        val stream = checkNotNull(handler.openPayloadStream("token")).stream
        val callerBuffer = ByteArray(5) { 42 }
        val executor = Executors.newSingleThreadExecutor()
        val cleanupScope = CoroutineScope(Dispatchers.Default)
        val facts = CopyOnWriteArrayList<FilePreviewRuntimeCleanup>()
        val states = CopyOnWriteArrayList<LocalOfficePreviewState>()
        val closed = CompletableDeferred<LocalOfficeCleanupResult>()
        val gate = LocalOfficeRuntimeGate(
            handler,
            runtime = FilePreviewRuntime { facts += it },
            cleanupScope = cleanupScope,
            onState = states::add,
            onClose = { _, result -> closed.complete(result) },
        )
        try {
            val reading = executor.submit<Throwable?> { runCatching { stream.read(callerBuffer, 1, 3) }.exceptionOrNull() }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            gate.markCreated()
            gate.finishWithoutRuntime(LocalOfficeCloseReason.APP_LOCKED)
            assertThat(states).isEmpty()
            gate.finishAfterWebRuntimeReleased()
            assertThat(states).containsExactly(LocalOfficePreviewState.CLOSED)
            assertThat(facts).isEmpty()
            assertThat(closed.isCompleted).isFalse()
            assertThat(handler.isCleanupComplete()).isFalse()
            assertThat(payload.isCleared()).isFalse()

            proceed.countDown()
            assertThat(reading.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException::class.java)
            assertThat(callerBuffer).isEqualTo(byteArrayOf(42, 0, 0, 0, 42))
            val cleanup = withTimeout(5_000) { closed.await() }
            assertThat(cleanup.sourceClosed).isTrue()
            assertThat(cleanup.pendingRecovery).isFalse()
            assertThat(facts).containsExactly(FilePreviewRuntimeCleanup(true, true, true))
            assertThat(handler.isCleanupComplete()).isTrue()
            assertThat(payload.isCleared()).isTrue()
            gate.finishAfterWebRuntimeReleased()
            assertThat(facts).hasSize(1)
        } finally {
            proceed.countDown()
            executor.shutdownNow()
            cleanupScope.cancel()
        }
    }

    @Test
    fun `validation close failure retries original provider and gate waits for real cleanup`() = runBlocking<Unit> {
        val allowClose = AtomicBoolean(false)
        val closeAttempts = AtomicInteger()
        val opens = AtomicInteger()
        val bytes = "a,b\n1,2".encodeToByteArray()
        val request = LocalOfficePreviewRequest(
            operationId = "original-operation",
            attachmentId = "original-attachment",
            displayName = "sample.csv",
            declaredMimeType = "text/csv",
            declaredSize = bytes.size.toLong(),
            contentSource = LocalOfficeContentSource {
                opens.incrementAndGet()
                object : ByteArrayInputStream(bytes) {
                    override fun close() {
                        closeAttempts.incrementAndGet()
                        if (!allowClose.get()) throw IOException("provider close failed")
                        super.close()
                    }
                }
            },
        )
        val runner = LocalOfficeValidationRunner(request)
        val cleanupScope = CoroutineScope(Dispatchers.Default)
        val facts = CopyOnWriteArrayList<FilePreviewRuntimeCleanup>()
        val closed = CompletableDeferred<LocalOfficeCleanupResult>()
        try {
            assertThat(runner.await()).isInstanceOf(LocalOfficeDetection.SecurityBlocked::class.java)
            assertThat(runner.awaitCleanup()).isFalse()
            assertThat(runner.isWorkerTerminated()).isTrue()
            val gate = LocalOfficeRuntimeGate(
                SessionContentPathHandler("token"),
                validationRunner = runner,
                runtime = FilePreviewRuntime { facts += it },
                cleanupScope = cleanupScope,
                onState = {},
                onClose = { _, result -> closed.complete(result) },
            )
            gate.finishWithoutRuntime(LocalOfficeCloseReason.SECURITY_BLOCKED)
            assertThat(facts).isEmpty()
            assertThat(closed.isCompleted).isFalse()
            allowClose.set(true)
            assertThat(withTimeout(5_000) { closed.await() }.sourceClosed).isTrue()
            assertThat(facts).containsExactly(FilePreviewRuntimeCleanup(true, true, true))
            assertThat(closeAttempts.get()).isAtLeast(2)
            assertThat(opens.get()).isEqualTo(1)
        } finally {
            allowClose.set(true)
            runner.cancel()
            runner.awaitCleanup()
            cleanupScope.cancel()
        }
    }

    @Test
    fun `disposal before validation starts terminates worker without opening a source`() = runBlocking<Unit> {
        val request = LocalOfficePreviewRequest(
            operationId = "original-operation",
            attachmentId = "original-attachment",
            displayName = "sample.csv",
            declaredMimeType = "text/csv",
            declaredSize = 3L,
            contentSource = LocalOfficeContentSource { error("must not open after disposal") },
        )
        val runner = LocalOfficeValidationRunner(request)
        val scope = CoroutineScope(Dispatchers.Default)
        val closed = CompletableDeferred<LocalOfficeCleanupResult>()
        try {
            LocalOfficeRuntimeGate(
                SessionContentPathHandler("token"),
                validationRunner = runner,
                cleanupScope = scope,
                onState = {},
                onClose = { _, result -> closed.complete(result) },
            ).disposeAttempt()
            assertThat(withTimeout(5_000) { closed.await() }.sourceClosed).isTrue()
            assertThat(runner.isWorkerTerminated()).isTrue()
        } finally {
            runner.cancel()
            scope.cancel()
        }
    }
}
