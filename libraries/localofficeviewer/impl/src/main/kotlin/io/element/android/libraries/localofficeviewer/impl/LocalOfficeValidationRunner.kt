/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.localofficeviewer.impl

import io.element.android.libraries.localofficeviewer.api.LocalOfficeContentSource
import io.element.android.libraries.localofficeviewer.api.LocalOfficePreviewRequest
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs blocking provider reads outside the coroutine pool and owns their cancellation boundary.
 *
 * Timeout or host cancellation closes the active provider stream, interrupts the worker, rejects
 * late completion, and clears any validated plaintext produced after cancellation.
 */
internal class LocalOfficeValidationRunner(
    request: LocalOfficePreviewRequest,
    private val timeoutMillis: Long = LocalOfficeFormatDetector.VALIDATION_TIMEOUT_MILLIS,
) {
    private val cancelled = AtomicBoolean(false)
    private val activeStream = AtomicReference<InputStream?>()
    private val streamCloseFailed = AtomicBoolean(false)
    private val worker = AtomicReference<Future<*>?>()
    private val continuation = AtomicReference<CancellableContinuation<LocalOfficeDetection>?>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-office-validation").apply { isDaemon = true }
    }
    private val trackedRequest = request.copy(
        contentSource = LocalOfficeContentSource { openTrackedStream(request) },
    )

    suspend fun await(): LocalOfficeDetection = try {
        withTimeout(timeoutMillis) { awaitWorker() }
    } catch (_: TimeoutCancellationException) {
        cancelWorker()
        LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.VALIDATION_TIMEOUT)
    }

    fun cancel() {
        cancelWorker()
        continuation.getAndSet(null)?.cancel(CancellationException("Local Office validation cancelled"))
    }

    internal fun isWorkerTerminated(): Boolean = executor.isTerminated

    suspend fun awaitCleanup(): Boolean = withContext(Dispatchers.IO) {
        executor.awaitTermination(5, TimeUnit.SECONDS) && activeStream.get() == null && !streamCloseFailed.get()
    }

    private suspend fun awaitWorker(): LocalOfficeDetection = suspendCancellableCoroutine { current ->
        check(continuation.compareAndSet(null, current)) { "Validation runner is single-use." }
        if (cancelled.get()) {
            continuation.compareAndSet(current, null)
            current.cancel(CancellationException("Local Office validation already cancelled"))
            return@suspendCancellableCoroutine
        }
        val future = try {
            executor.submit {
                try {
                    val result = LocalOfficeFormatDetector.detect(trackedRequest)
                    val deliver = !cancelled.get() && continuation.compareAndSet(current, null) && current.isActive
                    if (deliver) {
                        current.resume(result) { _, discarded, _ -> discarded.clearValidatedPayload() }
                    } else {
                        result.clearValidatedPayload()
                    }
                } finally {
                    executor.shutdown()
                }
            }
        } catch (_: RejectedExecutionException) {
            continuation.compareAndSet(current, null)
            current.cancel(CancellationException("Local Office validation worker unavailable"))
            return@suspendCancellableCoroutine
        }
        worker.set(future)
        if (cancelled.get()) future.cancel(true)
        current.invokeOnCancellation { cancelWorker() }
    }

    private fun openTrackedStream(request: LocalOfficePreviewRequest): InputStream {
        check(!cancelled.get()) { "Validation was cancelled before the source opened." }
        val rawStream = request.contentSource.openInputStream()
        lateinit var trackedStream: InputStream
        trackedStream = object : FilterInputStream(rawStream) {
            override fun close() {
                try {
                    super.close()
                } catch (failure: Exception) {
                    streamCloseFailed.set(true)
                    throw failure
                } finally {
                    activeStream.compareAndSet(trackedStream, null)
                }
            }
        }
        if (!activeStream.compareAndSet(null, trackedStream)) {
            trackedStream.close()
            error("Only one validation source may be active.")
        }
        if (cancelled.get()) {
            trackedStream.close()
            throw CancellationException("Validation cancelled while opening the source")
        }
        return trackedStream
    }

    private fun cancelWorker() {
        if (!cancelled.compareAndSet(false, true)) return
        activeStream.getAndSet(null)?.let { stream ->
            if (runCatching { stream.close() }.isFailure) streamCloseFailed.set(true)
        }
        worker.getAndSet(null)?.cancel(true)
        executor.shutdownNow()
    }
}

internal fun LocalOfficeDetection.clearValidatedPayload() {
    if (this is LocalOfficeDetection.Accepted) payload.clear()
}
