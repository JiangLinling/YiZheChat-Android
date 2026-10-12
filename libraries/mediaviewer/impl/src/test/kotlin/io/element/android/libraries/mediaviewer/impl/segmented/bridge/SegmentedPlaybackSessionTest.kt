/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaFmp4FixturePrototype
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRejection
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRequest
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class SegmentedPlaybackSessionTest {
    @Test
    fun `close never releases reader before non cancellable C worker physically returns`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val readerExecutor = Executors.newSingleThreadExecutor()
        try {
            val initLease = BarrierAuthorizedLease(SegmentedMediaFmp4FixturePrototype.init)
            val decoder = RecordingDecoderLifecycle()
            val session = preparedFakeSession(worker, finalizer, decoder, initLease = initLease)
            val dataSource = session.dataSourceFactory().createDataSource()
            dataSource.open(DataSpec.Builder().setUri(session.uri).build())
            initLease.barrierEnabled = true
            val destination = ByteArray(16) { 0x5a }

            val read = readerExecutor.submit<Int> { dataSource.read(destination, 0, destination.size) }
            initLease.entered.await()
            session.requestClose(SegmentedPlaybackCloseCause.OWNER_EXIT)
            val closing = session.state() as SegmentedPlaybackSessionState.Closing

            assertThat(read.isDone).isFalse()
            assertThat(closing.waitingFor).containsExactly(
                SegmentedPlaybackCloseWait.C_REQUEST,
                SegmentedPlaybackCloseWait.READER_BORROW,
            )
            assertThat(decoder.flushCalls.get()).isEqualTo(0)

            initLease.allowCopy.complete(Unit)
            initLease.copied.await()
            assertThat(read.isDone).isFalse()
            assertThat(decoder.flushCalls.get()).isEqualTo(0)

            initLease.allowReturn.complete(Unit)
            assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(destination.size)
            val receipt = session.closeAndAwaitQuiescence()
            assertThat(receipt.succeeded).isTrue()
            assertThat(decoder.flushCalls.get()).isEqualTo(1)

            destination.fill(0x33)
            initLease.afterTerminal.complete(Unit)
            assertThat(destination).isEqualTo(ByteArray(destination.size) { 0x33 })
            assertThat(captureFailure { dataSource.read(ByteArray(1), 0, 1) }).isInstanceOf(IOException::class.java)
        } finally {
            readerExecutor.shutdownNow()
            worker.close()
            finalizer.close()
        }
    }

    @Test
    fun `hung C remains closing and neither reader nor flush completes`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val readerExecutor = Executors.newSingleThreadExecutor()
        try {
            val initLease = BarrierAuthorizedLease(SegmentedMediaFmp4FixturePrototype.init)
            val decoder = RecordingDecoderLifecycle()
            val session = preparedFakeSession(worker, finalizer, decoder, initLease = initLease)
            val dataSource = session.dataSourceFactory().createDataSource()
            dataSource.open(DataSpec.Builder().setUri(session.uri).build())
            initLease.barrierEnabled = true
            val read = readerExecutor.submit<Int> { dataSource.read(ByteArray(8), 0, 8) }
            initLease.entered.await()

            session.requestClose(SegmentedPlaybackCloseCause.OWNER_EXIT)
            val close = async { session.closeAndAwaitQuiescence() }

            assertThat(read.isDone).isFalse()
            assertThat(close.isCompleted).isFalse()
            assertThat(session.state()).isInstanceOf(SegmentedPlaybackSessionState.Closing::class.java)
            assertThat(decoder.flushCalls.get()).isEqualTo(0)

            // Deterministic test cleanup: the contract intentionally has no timeout-forced release.
            initLease.allowCopy.complete(Unit)
            initLease.allowReturn.complete(Unit)
            assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(8)
            assertThat(close.await().succeeded).isTrue()
        } finally {
            readerExecutor.shutdownNow()
            worker.close()
            finalizer.close()
        }
    }

    @Test
    fun `thread interruption does not return borrowed destination before worker exit`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val readerExecutor = Executors.newSingleThreadExecutor()
        try {
            val initLease = BarrierAuthorizedLease(SegmentedMediaFmp4FixturePrototype.init)
            val session = preparedFakeSession(worker, finalizer, RecordingDecoderLifecycle(), initLease = initLease)
            val dataSource = session.dataSourceFactory().createDataSource()
            dataSource.open(DataSpec.Builder().setUri(session.uri).build())
            initLease.barrierEnabled = true
            val readerThread = AtomicReference<Thread>()
            val interruptRestored = AtomicBoolean(false)
            val read = readerExecutor.submit<Int> {
                readerThread.set(Thread.currentThread())
                dataSource.read(ByteArray(8), 0, 8).also {
                    interruptRestored.set(Thread.currentThread().isInterrupted)
                }
            }
            initLease.entered.await()

            readerThread.get().interrupt()
            assertThat(read.isDone).isFalse()

            initLease.allowCopy.complete(Unit)
            initLease.allowReturn.complete(Unit)
            assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(8)
            assertThat(interruptRestored.get()).isTrue()
            dataSource.close()
            assertThat(session.closeAndAwaitQuiescence().succeeded).isTrue()
        } finally {
            readerExecutor.shutdownNow()
            worker.close()
            finalizer.close()
        }
    }

    @Test
    fun `second borrower is busy and flush failure is retained exactly once`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val readerExecutor = Executors.newSingleThreadExecutor()
        try {
            val initLease = BarrierAuthorizedLease(SegmentedMediaFmp4FixturePrototype.init)
            val flushFailure = IllegalStateException("flush failed")
            val decoder = RecordingDecoderLifecycle(flushFailure)
            val session = preparedFakeSession(worker, finalizer, decoder, initLease = initLease)
            val dataSource = session.dataSourceFactory().createDataSource()
            dataSource.open(DataSpec.Builder().setUri(session.uri).build())
            initLease.barrierEnabled = true
            val first = readerExecutor.submit<Int> { dataSource.read(ByteArray(8), 0, 8) }
            initLease.entered.await()

            val secondFailure = captureFailure { dataSource.read(ByteArray(1), 0, 1) }
            assertThat(secondFailure).isInstanceOf(IOException::class.java)
            assertThat(secondFailure.message).contains("already in flight")

            initLease.allowCopy.complete(Unit)
            initLease.allowReturn.complete(Unit)
            // The second read requested close after its BUSY failure; the first copy still won its physical race.
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(8)
            val firstReceipt = session.closeAndAwaitQuiescence()
            val secondReceipt = session.closeAndAwaitQuiescence()

            assertThat(firstReceipt).isSameInstanceAs(secondReceipt)
            assertThat(firstReceipt.succeeded).isFalse()
            assertThat(firstReceipt.failures).containsExactly(flushFailure)
            assertThat(decoder.beginStopCalls.get()).isEqualTo(1)
            assertThat(decoder.flushCalls.get()).isEqualTo(1)
            assertThat(session.state()).isEqualTo(SegmentedPlaybackSessionState.Closed(firstReceipt))
        } finally {
            readerExecutor.shutdownNow()
            worker.close()
            finalizer.close()
        }
    }

    private class BarrierAuthorizedLease(plaintext: ByteArray) : ImmediateAuthorizedLease(plaintext) {
        @Volatile
        var barrierEnabled = false
        val entered = CompletableDeferred<Unit>()
        val allowCopy = CompletableDeferred<Unit>()
        val copied = CompletableDeferred<Unit>()
        val allowReturn = CompletableDeferred<Unit>()
        val afterTerminal = CompletableDeferred<Unit>()

        override suspend fun copyAuthenticatedRangeInto(
            request: SecureMediaAuthenticatedReadRequest,
            destination: ByteArray,
            destinationOffset: Int,
        ): SecureMediaAuthenticatedReadResult {
            if (!barrierEnabled || request.byteCount == 0) {
                return super.copyAuthenticatedRangeInto(request, destination, destinationOffset)
            }
            return withContext(NonCancellable) {
                entered.complete(Unit)
                allowCopy.await()
                plaintext.copyInto(
                    destination,
                    destinationOffset,
                    request.offset.toInt(),
                    request.offset.toInt() + request.byteCount,
                )
                copied.complete(Unit)
                allowReturn.await()
                SecureMediaAuthenticatedReadResult.Copied(request.byteCount)
            }
        }
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        error("Expected failure")
    } catch (failure: ExecutionException) {
        failure.cause ?: failure
    } catch (failure: Throwable) {
        failure
    }
}
