/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaReadContextPrototype
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class ReplacementCandidateOwnershipCellTest {
    @Test
    fun `case 25 partial issue is visible and terminal cleanup waits for physical close`() = runBlocking {
        withDispatchers { worker, finalizer, cleanup ->
            val lease = BlockingCloseLease()
            val cell = ReplacementCandidateOwnershipCell(25L, cleanup)
            cell.adoptLease(
                CandidateResourceRole.INIT,
                SegmentedAuthorizedResourceHandle(TEST_MANIFEST.init, lease),
            )

            val waiter = async(start = CoroutineStart.UNDISPATCHED) { cell.closeAndAwaitRelease() }
            assertThat(lease.closeStarted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(cell.auditState()).isEqualTo(
                CandidateOwnershipAuditState(
                    CandidateOwnershipPhase.PARTIAL_LEASES,
                    setOf(CandidateResourceRole.INIT),
                    ownsSession = false,
                    cleanupStatus = CandidateCleanupStatus.UNKNOWN_PENDING,
                )
            )
            assertThat(waiter.isCompleted).isFalse()

            lease.allowClose.countDown()
            assertThat(waiter.await().succeeded).isTrue()
            assertThat(lease.closeCalls.get()).isEqualTo(1)
        }
    }

    @Test
    fun `case 26 cancelled cleanup waiter cannot cancel adopted lease cleanup`() = runBlocking {
        withDispatchers { _, _, cleanup ->
            val lease = BlockingCloseLease()
            val cell = ReplacementCandidateOwnershipCell(26L, cleanup)
            cell.adoptLease(
                CandidateResourceRole.INIT,
                SegmentedAuthorizedResourceHandle(TEST_MANIFEST.init, lease),
            )
            val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) { cell.closeAndAwaitRelease() }
            assertThat(lease.closeStarted.await(5, TimeUnit.SECONDS)).isTrue()
            cancelledWaiter.cancelAndJoin()
            assertThat(cell.auditState().cleanupStatus).isEqualTo(CandidateCleanupStatus.UNKNOWN_PENDING)

            val survivingWaiter = async(start = CoroutineStart.UNDISPATCHED) { cell.closeAndAwaitRelease() }
            lease.allowClose.countDown()
            assertThat(survivingWaiter.await().succeeded).isTrue()
            assertThat(lease.closeCalls.get()).isEqualTo(1)
        }
    }

    @Test
    fun `case 27 allocation callback adopts before provider return failure`() = runBlocking {
        withDispatchers { _, _, cleanup ->
            val lease = ImmediateAuthorizedLease(byteArrayOf(1))
            val cell = ReplacementCandidateOwnershipCell(27L, cleanup)
            val providerFailure = runCatching {
                cell.adoptLease(
                    CandidateResourceRole.INIT,
                    SegmentedAuthorizedResourceHandle(TEST_MANIFEST.init, lease),
                )
                error("provider failed after allocation and adoption")
            }.exceptionOrNull()

            assertThat(providerFailure).isNotNull()
            assertThat(cell.auditState().adoptedRoles).containsExactly(CandidateResourceRole.INIT)
            assertThat(cell.closeAndAwaitRelease().succeeded).isTrue()
            assertThat(lease.closeCalls.get()).isEqualTo(1)
        }
    }

    @Test
    fun `case 28 prepare failure before construction keeps both leases in cell`() = runBlocking {
        withDispatchers { _, _, cleanup ->
            val initLease = ImmediateAuthorizedLease(byteArrayOf(1))
            val segmentLease = ImmediateAuthorizedLease(byteArrayOf(2))
            val cell = fullLeaseCell(28L, cleanup, initLease, segmentLease)

            assertThat(cell.auditState().phase).isEqualTo(CandidateOwnershipPhase.FULL_LEASES)
            val receipt = cell.closeAndAwaitRelease()

            assertThat(receipt.adoptedRoles).containsExactly(
                CandidateResourceRole.INIT,
                CandidateResourceRole.SEGMENT,
            )
            assertThat(initLease.closeCalls.get()).isEqualTo(1)
            assertThat(segmentLease.closeCalls.get()).isEqualTo(1)
        }
    }

    @Test
    fun `case 29 constructed candidate remains discoverable after prepare throws`() = runBlocking {
        withDispatchers { worker, finalizer, cleanup ->
            val installed = installedCell(29L, worker, finalizer, cleanup, RecordingDecoderLifecycle())
            val injected = runCatching { error("after installSession") }.exceptionOrNull()

            assertThat(injected).isNotNull()
            assertThat(installed.cell.auditState().ownsSession).isTrue()
            val receipt = installed.cell.closeAndAwaitRelease()

            assertThat(receipt.sessionCloseReceipt?.cause).isEqualTo(SegmentedPlaybackCloseCause.OWNER_EXIT)
            assertThat(installed.initLease.closeCalls.get()).isEqualTo(1)
            assertThat(installed.segmentLease.closeCalls.get()).isEqualTo(1)
        }
    }

    @Test
    fun `case 30 constructed candidate remains discoverable after prepare cancellation`() = runBlocking {
        withDispatchers { worker, finalizer, cleanup ->
            val installed = installedCell(30L, worker, finalizer, cleanup, RecordingDecoderLifecycle())
            val cancellation = runCatching { throw CancellationException("after installSession") }.exceptionOrNull()

            assertThat(cancellation).isInstanceOf(CancellationException::class.java)
            assertThat(installed.cell.auditState().phase).isEqualTo(CandidateOwnershipPhase.SESSION)
            assertThat(installed.cell.closeAndAwaitRelease().succeeded).isTrue()
            assertThat(installed.initLease.closeCalls.get()).isEqualTo(1)
            assertThat(installed.segmentLease.closeCalls.get()).isEqualTo(1)
        }
    }

    @Test
    fun `case 31 cleanup wins before takeSession and prevents transfer`() = runBlocking {
        withDispatchers { worker, finalizer, cleanup ->
            val decoder = BlockingDecoderLifecycle()
            val installed = installedCell(31L, worker, finalizer, cleanup, decoder)
            val cleanupWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                installed.cell.closeAndAwaitRelease()
            }
            assertThat(decoder.flushStarted.await(5, TimeUnit.SECONDS)).isTrue()

            assertThat(runCatching { installed.cell.takeSession() }.exceptionOrNull()).isNotNull()
            decoder.allowFlush.countDown()
            assertThat(cleanupWaiter.await().succeeded).isTrue()
        }
    }

    @Test
    fun `case 32 takeSession transfers exactly one unpublished candidate`() = runBlocking {
        withDispatchers { worker, finalizer, cleanup ->
            val installed = installedCell(32L, worker, finalizer, cleanup, RecordingDecoderLifecycle())
            val candidate = installed.cell.takeSession()

            assertThat(installed.cell.auditState().phase).isEqualTo(CandidateOwnershipPhase.TRANSFERRED)
            assertThat(runCatching { installed.cell.takeSession() }.exceptionOrNull()).isNotNull()
            assertThat(candidate.closeAndAwaitQuiescence(SegmentedPlaybackCloseCause.OWNER_EXIT).succeeded).isTrue()
            assertThat(installed.cell.closeAndAwaitRelease().transferred).isTrue()
            assertThat(installed.initLease.closeCalls.get()).isEqualTo(1)
            assertThat(installed.segmentLease.closeCalls.get()).isEqualTo(1)
        }
    }

    @Test
    fun `case 33 concurrent cleanup callers share one physical operation and receipt`() = runBlocking {
        withDispatchers { _, _, cleanup ->
            val lease = BlockingCloseLease()
            val cell = ReplacementCandidateOwnershipCell(33L, cleanup)
            cell.adoptLease(
                CandidateResourceRole.INIT,
                SegmentedAuthorizedResourceHandle(TEST_MANIFEST.init, lease),
            )
            val waiters = List(8) {
                async(start = CoroutineStart.UNDISPATCHED) { cell.closeAndAwaitRelease() }
            }
            assertThat(lease.closeStarted.await(5, TimeUnit.SECONDS)).isTrue()
            lease.allowClose.countDown()

            val receipts = waiters.map { it.await() }
            assertThat(receipts.distinct()).hasSize(1)
            assertThat(lease.closeCalls.get()).isEqualTo(1)
        }
    }

    @Test
    fun `case 34 hung partial lease cleanup stays unknown pending`() = runBlocking {
        withDispatchers { _, _, cleanup ->
            val lease = BlockingCloseLease()
            val cell = ReplacementCandidateOwnershipCell(34L, cleanup)
            cell.adoptLease(
                CandidateResourceRole.INIT,
                SegmentedAuthorizedResourceHandle(TEST_MANIFEST.init, lease),
            )
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { cell.closeAndAwaitRelease() }
            assertThat(lease.closeStarted.await(5, TimeUnit.SECONDS)).isTrue()

            assertThat(cell.auditState().cleanupStatus).isEqualTo(CandidateCleanupStatus.UNKNOWN_PENDING)
            assertThat(waiter.isCompleted).isFalse()
            lease.allowClose.countDown()
            assertThat(waiter.await().succeeded).isTrue()
        }
    }

    @Test
    fun `case 35 hung adopted candidate cleanup stays unknown pending`() = runBlocking {
        withDispatchers { worker, finalizer, cleanup ->
            val decoder = BlockingDecoderLifecycle()
            val installed = installedCell(35L, worker, finalizer, cleanup, decoder)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                installed.cell.closeAndAwaitRelease()
            }
            assertThat(decoder.flushStarted.await(5, TimeUnit.SECONDS)).isTrue()

            val pending = installed.cell.auditState()
            assertThat(pending.phase).isEqualTo(CandidateOwnershipPhase.SESSION)
            assertThat(pending.cleanupStatus).isEqualTo(CandidateCleanupStatus.UNKNOWN_PENDING)
            assertThat(waiter.isCompleted).isFalse()

            decoder.allowFlush.countDown()
            assertThat(waiter.await().sessionCloseReceipt?.succeeded).isTrue()
        }
    }

    private fun fullLeaseCell(
        epoch: Long,
        cleanup: kotlinx.coroutines.CoroutineDispatcher,
        initLease: ImmediateAuthorizedLease,
        segmentLease: ImmediateAuthorizedLease,
    ) = ReplacementCandidateOwnershipCell(epoch, cleanup).also { cell ->
        cell.adoptLease(
            CandidateResourceRole.INIT,
            SegmentedAuthorizedResourceHandle(TEST_MANIFEST.init, initLease),
        )
        cell.adoptLease(
            CandidateResourceRole.SEGMENT,
            SegmentedAuthorizedResourceHandle(TEST_MANIFEST.segments.single(), segmentLease),
        )
    }

    private suspend fun installedCell(
        epoch: Long,
        worker: kotlinx.coroutines.CoroutineDispatcher,
        finalizer: kotlinx.coroutines.CoroutineDispatcher,
        cleanup: kotlinx.coroutines.CoroutineDispatcher,
        decoder: SegmentedDecoderLifecycle,
    ): InstalledFixture {
        val planner = preparedFakeSession(worker, finalizer, RecordingDecoderLifecycle())
        val intent = planner.planOneFragmentSeek(0L, alwaysAuthorizedGate())
        val initLease = ImmediateAuthorizedLease(
            io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaFmp4FixturePrototype.init
        )
        val segmentLease = ImmediateAuthorizedLease(
            io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaFmp4FixturePrototype.segment
        )
        val cell = fullLeaseCell(epoch, cleanup, initLease, segmentLease)
        bridge(worker, finalizer).prepareInto(
            SegmentedReplacementPrepareRequest(
                ownership = cell,
                intent = intent,
                context = SegmentedMediaReadContextPrototype(
                    ownerToken = "candidate-owner",
                    leaseId = "candidate-$epoch",
                    generation = 2L,
                ),
                authorizationGate = alwaysAuthorizedGate(),
                decoderLifecycle = decoder,
            )
        )
        assertThat(cell.auditState().phase).isEqualTo(CandidateOwnershipPhase.SESSION)
        assertThat(planner.closeAndAwaitQuiescence().succeeded).isTrue()
        return InstalledFixture(cell, initLease, segmentLease)
    }

    private suspend fun withDispatchers(
        block: suspend (
            kotlinx.coroutines.ExecutorCoroutineDispatcher,
            kotlinx.coroutines.ExecutorCoroutineDispatcher,
            kotlinx.coroutines.ExecutorCoroutineDispatcher,
        ) -> Unit,
    ) {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val cleanup = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            block(worker, finalizer, cleanup)
        } finally {
            worker.close()
            finalizer.close()
            cleanup.close()
        }
    }

    private data class InstalledFixture(
        val cell: ReplacementCandidateOwnershipCell,
        val initLease: ImmediateAuthorizedLease,
        val segmentLease: ImmediateAuthorizedLease,
    )

    private class BlockingCloseLease : ImmediateAuthorizedLease(byteArrayOf(1)) {
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)

        override fun close() {
            closeStarted.countDown()
            check(allowClose.await(5, TimeUnit.SECONDS)) { "Test did not release lease close." }
            super.close()
        }
    }

    private class BlockingDecoderLifecycle : SegmentedDecoderLifecycle {
        val beginStopCalls = AtomicInteger()
        val flushCalls = AtomicInteger()
        val flushStarted = CountDownLatch(1)
        val allowFlush = CountDownLatch(1)

        override fun beginStop() {
            beginStopCalls.incrementAndGet()
        }

        override fun flushAndRelease() {
            flushCalls.incrementAndGet()
            flushStarted.countDown()
            check(allowFlush.await(5, TimeUnit.SECONDS)) { "Test did not release decoder flush." }
        }
    }
}
