/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.player

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedCoordinatorTerminationReceipt
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedAdvanceStart
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedFragmentCursor
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedManifestIdentity
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedReplacementOutcome
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedReplacementPublication
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedReplacementStart
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedReplacementStartRejection
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedReplacementTerminalReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class SegmentedVideoPlayerControllerTest {
    @Test
    fun `published replacement installs only after D2 terminal with local fragment offset`(): Unit = runBlocking {
        ControllerHarness().use { harness ->
            val start = harness.controller.seek(8_000_000L) as SegmentedVideoSeekStart.Accepted
            assertThat(harness.sink.installCount.get()).isEqualTo(0)

            harness.owner.completePublished(epoch = start.epoch, randomAccessStartUs = 5_000_000L)

            val receipt = withTimeout(TIMEOUT_MS) { start.terminal.await() }
            assertThat(receipt.outcome).isEqualTo(SegmentedVideoSeekOutcome.INSTALLED)
            assertThat(harness.sink.installed.await().startPositionUs).isEqualTo(3_000_000L)
        }
    }

    @Test
    fun `cancelling UI waiter cannot cancel product publication`(): Unit = runBlocking {
        ControllerHarness().use { harness ->
            val start = harness.controller.seek(9_000_000L) as SegmentedVideoSeekStart.Accepted
            start.terminal.cancel()

            harness.owner.completePublished(epoch = start.epoch, randomAccessStartUs = 7_000_000L)

            val installed = withTimeout(TIMEOUT_MS) { harness.sink.installed.await() }
            assertThat(installed.startPositionUs).isEqualTo(2_000_000L)
            assertThat(harness.owner.terminateCount.get()).isEqualTo(0)
        }
    }

    @Test
    fun `successive D2 replacements install successive player generations`(): Unit = runBlocking {
        ControllerHarness().use { harness ->
            val first = harness.controller.seek(3_000_000L) as SegmentedVideoSeekStart.Accepted
            harness.owner.completePublished(epoch = first.epoch, randomAccessStartUs = 2_000_000L)
            assertThat(withTimeout(TIMEOUT_MS) { first.terminal.await() }.succeeded).isTrue()

            harness.sink.installed = CompletableDeferred()
            val second = harness.controller.seek(9_000_000L) as SegmentedVideoSeekStart.Accepted
            harness.owner.completePublished(epoch = second.epoch, randomAccessStartUs = 6_000_000L)
            assertThat(withTimeout(TIMEOUT_MS) { second.terminal.await() }.succeeded).isTrue()

            assertThat(harness.sink.installCount.get()).isEqualTo(2)
            assertThat(harness.sink.installed.await().generation).isEqualTo(3L)
            assertThat(harness.sink.installed.await().startPositionUs).isEqualTo(3_000_000L)
        }
    }

    @Test
    fun `owner exit blocks late publication and clears sink`(): Unit = runBlocking {
        ControllerHarness().use { harness ->
            val start = harness.controller.seek(4_000_000L) as SegmentedVideoSeekStart.Accepted
            val close = async(start = CoroutineStart.UNDISPATCHED) { harness.controller.closeAndAwait() }

            val closeReceipt = withTimeout(TIMEOUT_MS) { close.await() }
            harness.owner.completePublished(epoch = start.epoch, randomAccessStartUs = 4_000_000L)
            val seekReceipt = withTimeout(TIMEOUT_MS) { start.terminal.await() }

            assertThat(closeReceipt.succeeded).isTrue()
            assertThat(seekReceipt.outcome).isEqualTo(SegmentedVideoSeekOutcome.OWNER_EXIT)
            assertThat(harness.sink.installCount.get()).isEqualTo(0)
            assertThat(harness.sink.clearCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `repeated close shares one coordinator termination and one sink clear`(): Unit = runBlocking {
        ControllerHarness().use { harness ->
            val first = async(start = CoroutineStart.UNDISPATCHED) { harness.controller.closeAndAwait() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { harness.controller.closeAndAwait() }

            assertThat(withTimeout(TIMEOUT_MS) { first.await() }.succeeded).isTrue()
            assertThat(withTimeout(TIMEOUT_MS) { second.await() }.succeeded).isTrue()
            assertThat(harness.owner.terminateCount.get()).isEqualTo(1)
            assertThat(harness.sink.clearCount.get()).isEqualTo(1)
            assertThat((harness.controller.seek(0L) as SegmentedVideoSeekStart.Rejected).ownerExiting).isTrue()
        }
    }
    @Test
    fun `cancelling close waiter cannot cancel owner cleanup`(): Unit = runBlocking {
        ControllerHarness().use { harness ->
            val gate = CompletableDeferred<Unit>()
            harness.owner.terminationGate = gate
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { harness.controller.closeAndAwait() }

            waiter.cancelAndJoin()
            gate.complete(Unit)
            withTimeout(TIMEOUT_MS) {
                while (harness.sink.clearCount.get() != 1) yield()
            }

            assertThat(withTimeout(TIMEOUT_MS) { harness.controller.closeAndAwait() }.succeeded).isTrue()
            assertThat(harness.owner.terminateCount.get()).isEqualTo(1)
        }
    }


    @Test
    fun `coordinator termination throw still clears sink and completes failed receipt`(): Unit = runBlocking {
        ControllerHarness(terminationFailure = IllegalStateException("termination failed")).use { harness ->
            val receipt = withTimeout(TIMEOUT_MS) { harness.controller.closeAndAwait() }

            assertThat(receipt.succeeded).isFalse()
            assertThat(receipt.coordinator.failures.single().failure).hasMessageThat().contains("termination failed")
            assertThat(harness.sink.clearCount.get()).isEqualTo(1)
            assertThat(withTimeout(TIMEOUT_MS) { harness.controller.closeAndAwait() }).isSameInstanceAs(receipt)
        }
    }

    @Test
    fun `sink clear throw is retained in terminal receipt`(): Unit = runBlocking {
        ControllerHarness(clearFailure = IllegalStateException("clear failed")).use { harness ->
            val receipt = withTimeout(TIMEOUT_MS) { harness.controller.closeAndAwait() }

            assertThat(receipt.succeeded).isFalse()
            assertThat(receipt.sinkFailure).hasMessageThat().contains("clear failed")
            assertThat(harness.owner.terminateCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `sink install failure terminates D2 owner and clears player source`(): Unit = runBlocking {
        ControllerHarness(sinkFailure = IllegalStateException("player rejected source")).use { harness ->
            val start = harness.controller.seek(2_000_000L) as SegmentedVideoSeekStart.Accepted
            harness.owner.completePublished(epoch = start.epoch, randomAccessStartUs = 1_000_000L)

            val receipt = withTimeout(TIMEOUT_MS) { start.terminal.await() }

            assertThat(receipt.outcome).isEqualTo(SegmentedVideoSeekOutcome.PLAYER_INSTALL_FAILED)
            assertThat(receipt.failure).hasMessageThat().contains("player rejected source")
            assertThat(harness.owner.terminateCount.get()).isEqualTo(1)
            assertThat(harness.sink.clearCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `D2 rejection does not touch player sink`(): Unit = runBlocking {
        ControllerHarness().use { harness ->
            harness.owner.nextRejection = SegmentedReplacementStartRejection.BUSY

            val rejected = harness.controller.seek(1L) as SegmentedVideoSeekStart.Rejected

            assertThat(rejected.replacement?.reason).isEqualTo(SegmentedReplacementStartRejection.BUSY)
            assertThat(harness.sink.installCount.get()).isEqualTo(0)
            assertThat(harness.sink.clearCount.get()).isEqualTo(0)
        }
    }

    @Test
    fun `accepted D2 failure closes owner and clears player source`(): Unit = runBlocking {
        ControllerHarness().use { harness ->
            val start = harness.controller.onFragmentEnded(harness.initialSource.mediaId) as SegmentedVideoAdvanceStart.Accepted
            harness.owner.completeRejected(start.epoch)

            val receipt = withTimeout(TIMEOUT_MS) { start.terminal.await() }

            assertThat(receipt.outcome).isEqualTo(SegmentedVideoSeekOutcome.REJECTED)
            assertThat(harness.owner.terminateCount.get()).isEqualTo(1)
            assertThat(harness.sink.clearCount.get()).isEqualTo(1)
            assertThat(harness.controller.onFragmentEnded(harness.initialSource.mediaId))
                .isEqualTo(SegmentedVideoAdvanceStart.Rejected(ownerExiting = true))
        }
    }

    @Test
    fun `current fragment authorization rejection fails closed and clears player source`(): Unit = runBlocking {
        ControllerHarness().use { harness ->
            val authorizationFailure = IllegalStateException("AUTHORIZATION_DENIED")
            harness.owner.nextRejection = SegmentedReplacementStartRejection.SEEK_REJECTED
            harness.owner.nextRejectionFailure = authorizationFailure

            val rejected = harness.controller.onFragmentEnded(harness.initialSource.mediaId) as SegmentedVideoAdvanceStart.Rejected
            val closeReceipt = withTimeout(TIMEOUT_MS) { harness.controller.closeAndAwait() }

            assertThat(rejected.ownerExiting).isTrue()
            assertThat(rejected.failedClosed).isTrue()
            assertThat(rejected.failure).isSameInstanceAs(authorizationFailure)
            assertThat(closeReceipt.succeeded).isTrue()
            assertThat(harness.owner.terminateCount.get()).isEqualTo(1)
            assertThat(harness.sink.clearCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `current fragment end advances once and stale duplicate cannot install`(): Unit = runBlocking {
        ControllerHarness().use { harness ->
            val accepted = harness.controller.onFragmentEnded(harness.initialSource.mediaId) as SegmentedVideoAdvanceStart.Accepted
            assertThat(harness.controller.onFragmentEnded(harness.initialSource.mediaId))
                .isEqualTo(SegmentedVideoAdvanceStart.IgnoredStaleOrDuplicate)

            harness.owner.completePublished(
                epoch = accepted.epoch,
                randomAccessStartUs = 2_000_000L,
                index = 1,
                endUs = 4_000_000L,
                assetDurationUs = 4_000_000L,
            )
            assertThat(withTimeout(TIMEOUT_MS) { accepted.terminal.await() }.succeeded).isTrue()
            val installed = harness.sink.installed.await()

            assertThat(harness.controller.onFragmentEnded(harness.initialSource.mediaId))
                .isEqualTo(SegmentedVideoAdvanceStart.IgnoredStaleOrDuplicate)
            assertThat(harness.owner.terminateCount.get()).isEqualTo(0)
            assertThat(harness.sink.clearCount.get()).isEqualTo(0)
            assertThat(harness.controller.onFragmentEnded(installed.mediaId))
                .isEqualTo(SegmentedVideoAdvanceStart.FinalFragment)
            assertThat(harness.controller.onFragmentEnded(installed.mediaId))
                .isEqualTo(SegmentedVideoAdvanceStart.IgnoredStaleOrDuplicate)
            assertThat(harness.sink.installCount.get()).isEqualTo(1)
        }
    }

    private class ControllerHarness(
        sinkFailure: Throwable? = null,
        clearFailure: Throwable? = null,
        terminationFailure: Throwable? = null,
    ) : Closeable {
        private val operationExecutor = Executors.newSingleThreadExecutor()
        private val cleanupExecutor = Executors.newSingleThreadExecutor()
        private val operationDispatcher = operationExecutor.asCoroutineDispatcher()
        private val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val owner = FakeReplacementOwner(terminationFailure)
        val sink = FakePlayerSink(sinkFailure, clearFailure)
        val initialSource = source(epoch = 0L, generation = 1L, index = 0, startUs = 0L, endUs = 2_000_000L)
        val controller = SegmentedVideoPlayerController(owner, sink, initialSource, operationDispatcher, cleanupDispatcher)

        override fun close() {
            operationDispatcher.close()
            cleanupDispatcher.close()
            operationExecutor.shutdownNow()
            cleanupExecutor.shutdownNow()
        }
    }

    private class FakeReplacementOwner(
        private val terminationFailure: Throwable? = null,
    ) : SegmentedVideoReplacementOwner {
        private val epochs = AtomicInteger()
        private var terminal = CompletableDeferred<SegmentedReplacementTerminalReceipt>()
        val terminateCount = AtomicInteger()
        var nextRejection: SegmentedReplacementStartRejection? = null
        var nextRejectionFailure: Throwable? = null
        var terminationGate: CompletableDeferred<Unit>? = null

        override fun replace(targetTimeUs: Long): SegmentedReplacementStart {
            nextRejection?.let { return SegmentedReplacementStart.Rejected(it) }
            val epoch = epochs.incrementAndGet().toLong()
            terminal = CompletableDeferred()
            return SegmentedReplacementStart.Accepted(epoch, terminal)
        }

        override fun advance(expectedCursor: SegmentedFragmentCursor): SegmentedAdvanceStart {
            nextRejection?.let { return SegmentedAdvanceStart.Rejected(it, nextRejectionFailure) }
            if (expectedCursor.segmentEndUsExclusive == expectedCursor.assetDurationUs) {
                return SegmentedAdvanceStart.FinalFragment
            }
            val epoch = epochs.incrementAndGet().toLong()
            terminal = CompletableDeferred()
            return SegmentedAdvanceStart.Accepted(epoch, terminal)
        }

        override suspend fun terminateAndAwait(): SegmentedCoordinatorTerminationReceipt {
            terminateCount.incrementAndGet()
            terminationGate?.await()
            terminationFailure?.let { throw it }
            return TERMINATION_RECEIPT
        }

        fun completePublished(
            epoch: Long,
            randomAccessStartUs: Long,
            index: Int = epoch.toInt(),
            endUs: Long = randomAccessStartUs + 4_000_000L,
            assetDurationUs: Long = 20_000_000L,
        ) {
            terminal.complete(
                SegmentedReplacementTerminalReceipt(
                    epoch = epoch,
                    outcome = SegmentedReplacementOutcome.PUBLISHED,
                    generation = epoch,
                    oldCloseReceipt = null,
                    candidateCleanupReceipt = null,
                    unpublishedSessionCloseReceipt = null,
                    publication = SegmentedReplacementPublication(
                        cursor = cursor(
                            epoch = epoch,
                            generation = epoch + 1L,
                            index = index,
                            startUs = randomAccessStartUs,
                            endUs = endUs,
                            assetDurationUs = assetDurationUs,
                        ),
                        uri = Uri.parse("yizhe-segmented://local/$epoch"),
                        dataSourceFactory = DataSource.Factory { ByteArrayDataSource(byteArrayOf(1)) },
                        randomAccessStartUs = randomAccessStartUs,
                    ),
                    failures = emptyList(),
                )
            )
        }

        fun completeRejected(epoch: Long) {
            terminal.complete(
                SegmentedReplacementTerminalReceipt(
                    epoch = epoch,
                    outcome = SegmentedReplacementOutcome.REJECTED,
                    generation = epoch + 1L,
                    oldCloseReceipt = null,
                    candidateCleanupReceipt = null,
                    unpublishedSessionCloseReceipt = null,
                    publication = null,
                    failures = emptyList(),
                )
            )
        }
    }

    private class FakePlayerSink(
        private val installFailure: Throwable?,
        private val clearFailure: Throwable?,
    ) : SegmentedVideoPlayerSink {
        val installCount = AtomicInteger()
        val clearCount = AtomicInteger()
        var installed = CompletableDeferred<SegmentedVideoPlayerSource>()

        override suspend fun install(source: SegmentedVideoPlayerSource) {
            installCount.incrementAndGet()
            installFailure?.let { throw it }
            installed.complete(source)
        }

        override suspend fun clearAndAwait() {
            clearCount.incrementAndGet()
            clearFailure?.let { throw it }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        val TERMINATION_RECEIPT = SegmentedCoordinatorTerminationReceipt(
            activeReplacementReceipt = null,
            publishedSessionCloseReceipt = null,
            failures = emptyList(),
        )

        val MANIFEST_IDENTITY = SegmentedManifestIdentity("schema", 1, "a".repeat(64))

        fun cursor(
            epoch: Long,
            generation: Long,
            index: Int,
            startUs: Long,
            endUs: Long,
            assetDurationUs: Long = 20_000_000L,
        ) = SegmentedFragmentCursor(
            manifestIdentity = MANIFEST_IDENTITY,
            segmentIndex = index,
            publicationEpoch = epoch,
            readGeneration = generation,
            segmentStartUs = startUs,
            segmentEndUsExclusive = endUs,
            assetDurationUs = assetDurationUs,
        )

        fun source(
            epoch: Long,
            generation: Long,
            index: Int,
            startUs: Long,
            endUs: Long,
            assetDurationUs: Long = 20_000_000L,
        ) = SegmentedVideoPlayerSource(
            cursor = cursor(epoch, generation, index, startUs, endUs, assetDurationUs),
            uri = Uri.parse("yizhe-segmented://local/source-$epoch"),
            dataSourceFactory = DataSource.Factory { ByteArrayDataSource(byteArrayOf(1)) },
            startPositionUs = 0L,
        )
    }
}
