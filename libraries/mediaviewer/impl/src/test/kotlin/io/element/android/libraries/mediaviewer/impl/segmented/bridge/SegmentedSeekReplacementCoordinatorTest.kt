/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaFmp4FixturePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationDecisionPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationGatePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationPurposePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaElapsedClockPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaLeaseAuthorizationValidatorPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaManifestValidatorPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaReadContextPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaWallClockPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.aFixtureManifest
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.aSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class SegmentedSeekReplacementCoordinatorTest {
    @Test
    fun `exact fragment advance publishes next generation and rejects duplicate stale EOF`(): Unit = runBlocking {
        CoordinatorHarness().use { harness ->
            val manifest = aFixtureManifest(
                listOf(
                    aSegment(0, 0L, plainLength = SegmentedMediaFmp4FixturePrototype.segment.size.toLong()),
                    aSegment(1, 2_000_000L, plainLength = SegmentedMediaFmp4FixturePrototype.segment.size.toLong()),
                )
            )
            val validated = SegmentedMediaManifestValidatorPrototype.validate(manifest)
            val oldDecoder = GateDecoderLifecycle()
            val old = bridge(harness.worker, harness.finalizer).prepare(
                validatedManifest = validated,
                segmentIndex = 0,
                init = SegmentedAuthorizedResourceHandle(
                    manifest.init,
                    ImmediateAuthorizedLease(SegmentedMediaFmp4FixturePrototype.init),
                ),
                segment = SegmentedAuthorizedResourceHandle(
                    manifest.segments[0],
                    ImmediateAuthorizedLease(SegmentedMediaFmp4FixturePrototype.segment),
                ),
                readContext = TEST_READ_CONTEXT,
                authorizationGate = alwaysAuthorizedGate(),
                decoderLifecycle = oldDecoder,
            )
            val coordinator = harness.coordinator(old, fixtureIssuer())
            val initialCursor = old.cursor(0L)

            val accepted = coordinator.advance(initialCursor) as SegmentedAdvanceStart.Accepted
            assertThat(oldDecoder.flushStarted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat((coordinator.advance(initialCursor) as SegmentedAdvanceStart.Rejected).reason)
                .isEqualTo(SegmentedReplacementStartRejection.BUSY)
            assertThat((coordinator.replace(1_000_000L) as SegmentedReplacementStart.Rejected).reason)
                .isEqualTo(SegmentedReplacementStartRejection.BUSY)
            oldDecoder.allowFlush.countDown()

            val terminal = accepted.terminal.await()
            assertThat(terminal.succeeded).isTrue()
            assertThat(terminal.publication?.cursor?.segmentIndex).isEqualTo(1)
            assertThat(terminal.publication?.cursor?.readGeneration).isEqualTo(2L)
            assertThat(terminal.publication?.cursor?.publicationEpoch).isEqualTo(1L)
            assertThat((coordinator.advance(initialCursor) as SegmentedAdvanceStart.Rejected).reason)
                .isEqualTo(SegmentedReplacementStartRejection.STALE_SOURCE)
            assertThat(coordinator.advance(requireNotNull(terminal.publication).cursor))
                .isEqualTo(SegmentedAdvanceStart.FinalFragment)
            assertThat(coordinator.terminateAndAwait().succeeded).isTrue()
        }
    }

    @Test
    fun `owner close racing accepted advance prevents next fragment publication`(): Unit = runBlocking {
        CoordinatorHarness().use { harness ->
            val manifest = aFixtureManifest(
                listOf(
                    aSegment(0, 0L, plainLength = SegmentedMediaFmp4FixturePrototype.segment.size.toLong()),
                    aSegment(1, 2_000_000L, plainLength = SegmentedMediaFmp4FixturePrototype.segment.size.toLong()),
                )
            )
            val oldDecoder = GateDecoderLifecycle()
            val old = bridge(harness.worker, harness.finalizer).prepare(
                validatedManifest = SegmentedMediaManifestValidatorPrototype.validate(manifest),
                segmentIndex = 0,
                init = SegmentedAuthorizedResourceHandle(
                    manifest.init,
                    ImmediateAuthorizedLease(SegmentedMediaFmp4FixturePrototype.init),
                ),
                segment = SegmentedAuthorizedResourceHandle(
                    manifest.segments[0],
                    ImmediateAuthorizedLease(SegmentedMediaFmp4FixturePrototype.segment),
                ),
                readContext = TEST_READ_CONTEXT,
                authorizationGate = alwaysAuthorizedGate(),
                decoderLifecycle = oldDecoder,
            )
            val coordinator = harness.coordinator(old, fixtureIssuer())
            val initialCursor = old.cursor(0L)
            val accepted = coordinator.advance(initialCursor) as SegmentedAdvanceStart.Accepted
            assertThat(oldDecoder.flushStarted.await(5, TimeUnit.SECONDS)).isTrue()

            val close = async(start = CoroutineStart.UNDISPATCHED) { coordinator.terminateAndAwait() }
            oldDecoder.allowFlush.countDown()
            val replacement = accepted.terminal.await()
            val termination = close.await()

            assertThat(replacement.outcome).isEqualTo(SegmentedReplacementOutcome.TERMINATED)
            assertThat(replacement.publication).isNull()
            assertThat(termination.activeReplacementReceipt).isSameInstanceAs(replacement)
            assertThat(coordinator.advance(initialCursor) as SegmentedAdvanceStart.Rejected)
                .isEqualTo(SegmentedAdvanceStart.Rejected(SegmentedReplacementStartRejection.TERMINATED))
        }
    }

    @Test
    fun `authority loss at final publication closes candidate and publishes nothing`(): Unit = runBlocking {
        CoordinatorHarness().use { harness ->
            val old = preparedFakeSession(harness.worker, harness.finalizer, RecordingDecoderLifecycle())
            val gate = SegmentedMediaAuthorizationGatePrototype(
                validator = SegmentedMediaLeaseAuthorizationValidatorPrototype { context, _, purpose ->
                    if (purpose == SegmentedMediaAuthorizationPurposePrototype.PUBLICATION && context.generation == 2L) {
                        SegmentedMediaAuthorizationDecisionPrototype.REVOKED
                    } else {
                        SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED
                    }
                },
                wallClock = SegmentedMediaWallClockPrototype { 1_000L },
                elapsedClock = SegmentedMediaElapsedClockPrototype { 1_000L },
            )
            val coordinator = harness.coordinator(old, fixtureIssuer(), authorizationGate = gate)

            val accepted = coordinator.replace(0L) as SegmentedReplacementStart.Accepted
            val receipt = accepted.terminal.await()

            assertThat(receipt.outcome).isEqualTo(SegmentedReplacementOutcome.REJECTED)
            assertThat(receipt.publication).isNull()
            assertThat(receipt.failures.map { it.phase })
                .containsExactly(SegmentedReplacementFailurePhase.AUTHORITY_RECHECK)
            assertThat(receipt.candidateCleanupReceipt?.succeeded).isTrue()
            assertThat(coordinator.terminateAndAwait().succeeded).isTrue()
        }
    }

    @Test
    fun `case 25 partial issuer failure has no terminal until adopted lease physically closes`(): Unit = runBlocking {
        CoordinatorHarness().use { harness ->
            val old = preparedFakeSession(harness.worker, harness.finalizer, RecordingDecoderLifecycle())
            val partialLease = GateCloseLease(SegmentedMediaFmp4FixturePrototype.init)
            val coordinator = harness.coordinator(
                old = old,
                issuer = LocalSegmentLeaseIssuer { ownership, intent, _ ->
                    ownership.adoptLease(
                        CandidateResourceRole.INIT,
                        SegmentedAuthorizedResourceHandle(intent.init, partialLease),
                    )
                    error("second lease allocation failed")
                },
            )
            val accepted = coordinator.replace(0L) as SegmentedReplacementStart.Accepted
            assertThat(partialLease.closeStarted.await(5, TimeUnit.SECONDS)).isTrue()

            assertThat(coordinator.terminalReceipt(accepted.epoch)).isNull()
            val pending = coordinator.auditState(accepted.epoch)
            assertThat(pending.candidate?.adoptedRoles).containsExactly(CandidateResourceRole.INIT)
            assertThat(pending.candidate?.cleanupStatus).isEqualTo(CandidateCleanupStatus.UNKNOWN_PENDING)

            partialLease.allowClose.countDown()
            val terminal = coordinator.awaitTerminal(accepted.epoch)
            assertThat(terminal.failures.map { it.phase })
                .containsExactly(SegmentedReplacementFailurePhase.LEASE_ISSUE)
            assertThat(partialLease.closeCalls.get()).isEqualTo(1)
            coordinator.terminateAndAwait()
        }
    }

    @Test
    fun `cases 1 and 2 old physical quiescence precedes candidate issuance`(): Unit = runBlocking {
        CoordinatorHarness().use { harness ->
            val oldDecoder = GateDecoderLifecycle()
            val old = preparedFakeSession(harness.worker, harness.finalizer, oldDecoder)
            val issueCalls = AtomicInteger()
            val coordinator = harness.coordinator(
                old = old,
                issuer = LocalSegmentLeaseIssuer { ownership, intent, _ ->
                    assertThat(old.state()).isInstanceOf(SegmentedPlaybackSessionState.Closed::class.java)
                    issueCalls.incrementAndGet()
                    ownership.adoptFixture(intent)
                    CandidateLeaseAcquisitionOutcome.Acquired
                },
            )

            val accepted = coordinator.replace(0L) as SegmentedReplacementStart.Accepted
            assertThat(oldDecoder.flushStarted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(issueCalls.get()).isEqualTo(0)
            assertThat(coordinator.auditState(accepted.epoch).phase).isEqualTo("REPLACING_QUIESCING")

            oldDecoder.allowFlush.countDown()
            val receipt = accepted.terminal.await()
            assertThat(receipt.outcome).isEqualTo(SegmentedReplacementOutcome.PUBLISHED)
            assertThat(issueCalls.get()).isEqualTo(1)
            assertThat(receipt.oldCloseReceipt?.cause).isEqualTo(SegmentedPlaybackCloseCause.SEEK_REPLACED)
            assertThat(coordinator.terminateAndAwait().succeeded).isTrue()
        }
    }

    @Test
    fun `case 7 owner exit after candidate construction prevents publication and drains cell`(): Unit = runBlocking {
        CoordinatorHarness().use { harness ->
            val old = preparedFakeSession(harness.worker, harness.finalizer, RecordingDecoderLifecycle())
            val prepared = CompletableDeferred<Unit>()
            val allowPrepareReturn = CompletableDeferred<Unit>()
            val bridge = bridge(harness.worker, harness.finalizer)
            val coordinator = harness.coordinator(
                old = old,
                issuer = fixtureIssuer(),
                preparer = SegmentedReplacementSessionPreparer { request ->
                    bridge.prepareInto(request)
                    prepared.complete(Unit)
                    allowPrepareReturn.await()
                },
            )
            val accepted = coordinator.replace(0L) as SegmentedReplacementStart.Accepted
            prepared.await()

            val termination = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.terminateAndAwait()
            }
            allowPrepareReturn.complete(Unit)
            val terminal = coordinator.awaitTerminal(accepted.epoch)

            assertThat(terminal.outcome).isEqualTo(SegmentedReplacementOutcome.TERMINATED)
            assertThat(terminal.candidateCleanupReceipt?.sessionCloseReceipt?.cause)
                .isEqualTo(SegmentedPlaybackCloseCause.OWNER_EXIT)
            assertThat(terminal.publication).isNull()
            assertThat(termination.await().activeReplacementReceipt).isEqualTo(terminal)
        }
    }

    @Test
    fun `case 8 second seek is BUSY and cannot alter active epoch`(): Unit = runBlocking {
        CoordinatorHarness().use { harness ->
            val old = preparedFakeSession(harness.worker, harness.finalizer, RecordingDecoderLifecycle())
            val issuerEntered = CompletableDeferred<Unit>()
            val allowIssue = CompletableDeferred<Unit>()
            val coordinator = harness.coordinator(
                old = old,
                issuer = LocalSegmentLeaseIssuer { ownership, intent, _ ->
                    ownership.adoptLease(
                        CandidateResourceRole.INIT,
                        SegmentedAuthorizedResourceHandle(
                            intent.init,
                            ImmediateAuthorizedLease(SegmentedMediaFmp4FixturePrototype.init),
                        ),
                    )
                    issuerEntered.complete(Unit)
                    allowIssue.await()
                    ownership.adoptLease(
                        CandidateResourceRole.SEGMENT,
                        SegmentedAuthorizedResourceHandle(
                            intent.segment,
                            ImmediateAuthorizedLease(SegmentedMediaFmp4FixturePrototype.segment),
                        ),
                    )
                    CandidateLeaseAcquisitionOutcome.Acquired
                },
            )
            val first = coordinator.replace(0L) as SegmentedReplacementStart.Accepted
            issuerEntered.await()

            val second = coordinator.replace(0L) as SegmentedReplacementStart.Rejected
            assertThat(second.reason).isEqualTo(SegmentedReplacementStartRejection.BUSY)
            assertThat(coordinator.pendingEpochs()).containsExactly(first.epoch)

            allowIssue.complete(Unit)
            assertThat(first.terminal.await().outcome).isEqualTo(SegmentedReplacementOutcome.PUBLISHED)
            assertThat(coordinator.terminateAndAwait().succeeded).isTrue()
        }
    }

    @Test
    fun `case 11 generation overflow happens after old close and before issuer`(): Unit = runBlocking {
        CoordinatorHarness().use { harness ->
            val old = bridge(harness.worker, harness.finalizer).prepare(
                validatedManifest = validatedFixtureManifest(),
                segmentIndex = 0,
                init = SegmentedAuthorizedResourceHandle(
                    TEST_MANIFEST.init,
                    ImmediateAuthorizedLease(SegmentedMediaFmp4FixturePrototype.init),
                ),
                segment = SegmentedAuthorizedResourceHandle(
                    TEST_MANIFEST.segments.single(),
                    ImmediateAuthorizedLease(SegmentedMediaFmp4FixturePrototype.segment),
                ),
                readContext = SegmentedMediaReadContextPrototype("overflow-owner", "overflow-lease", Long.MAX_VALUE),
                authorizationGate = alwaysAuthorizedGate(),
                decoderLifecycle = RecordingDecoderLifecycle(),
            )
            val issueCalls = AtomicInteger()
            val coordinator = harness.coordinator(
                old = old,
                issuer = LocalSegmentLeaseIssuer { _, _, _ ->
                    issueCalls.incrementAndGet()
                    CandidateLeaseAcquisitionOutcome.Acquired
                },
            )
            val accepted = coordinator.replace(0L) as SegmentedReplacementStart.Accepted
            val terminal = accepted.terminal.await()

            assertThat(terminal.outcome).isEqualTo(SegmentedReplacementOutcome.REJECTED)
            assertThat(terminal.oldCloseReceipt?.succeeded).isTrue()
            assertThat(terminal.failures.map { it.phase })
                .containsExactly(SegmentedReplacementFailurePhase.GENERATION)
            assertThat(issueCalls.get()).isEqualTo(0)
            assertThat(coordinator.terminateAndAwait().succeeded).isTrue()
        }
    }

    @Test
    fun `cases 12 and 24 owner exit race yields one terminal and one candidate close`(): Unit = runBlocking {
        CoordinatorHarness().use { harness ->
            val old = preparedFakeSession(harness.worker, harness.finalizer, RecordingDecoderLifecycle())
            val candidateDecoder = RecordingDecoderLifecycle()
            val prepared = CompletableDeferred<Unit>()
            val allowReturn = CompletableDeferred<Unit>()
            val bridge = bridge(harness.worker, harness.finalizer)
            val coordinator = harness.coordinator(
                old = old,
                issuer = fixtureIssuer(),
                decoderFactory = { candidateDecoder },
                preparer = SegmentedReplacementSessionPreparer { request ->
                    bridge.prepareInto(request)
                    prepared.complete(Unit)
                    allowReturn.await()
                },
            )
            val accepted = coordinator.replace(0L) as SegmentedReplacementStart.Accepted
            prepared.await()
            val termination = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.terminateAndAwait()
            }
            allowReturn.complete(Unit)

            val terminal = coordinator.awaitTerminal(accepted.epoch)
            assertThat(coordinator.terminalReceipt(accepted.epoch)).isSameInstanceAs(terminal)
            assertThat(terminal.outcome).isEqualTo(SegmentedReplacementOutcome.TERMINATED)
            assertThat(candidateDecoder.beginStopCalls.get()).isEqualTo(1)
            assertThat(candidateDecoder.flushCalls.get()).isEqualTo(1)
            assertThat(termination.await().activeReplacementReceipt).isSameInstanceAs(terminal)
        }
    }

    @Test
    fun `case 18 cancelled external waiter does not cancel epoch or receipt registry`(): Unit = runBlocking {
        CoordinatorHarness().use { harness ->
            val old = preparedFakeSession(harness.worker, harness.finalizer, RecordingDecoderLifecycle())
            val issuerEntered = CompletableDeferred<Unit>()
            val allowIssue = CompletableDeferred<Unit>()
            val coordinator = harness.coordinator(
                old = old,
                issuer = LocalSegmentLeaseIssuer { ownership, intent, _ ->
                    issuerEntered.complete(Unit)
                    allowIssue.await()
                    ownership.adoptFixture(intent)
                    CandidateLeaseAcquisitionOutcome.Acquired
                },
            )
            val accepted = coordinator.replace(0L) as SegmentedReplacementStart.Accepted
            issuerEntered.await()
            accepted.terminal.cancel()
            allowIssue.complete(Unit)

            val terminal = coordinator.awaitTerminal(accepted.epoch)
            assertThat(terminal.outcome).isEqualTo(SegmentedReplacementOutcome.PUBLISHED)
            assertThat(coordinator.terminalReceipt(accepted.epoch)).isSameInstanceAs(terminal)
            assertThat(coordinator.terminateAndAwait().succeeded).isTrue()
        }
    }

    @Test
    fun `cases 29 and 30 post construction throw and cancellation drain adopted session`(): Unit = runBlocking {
        listOf<Throwable>(
            IllegalStateException("post-construction throw"),
            kotlinx.coroutines.CancellationException("post-construction cancellation"),
        ).forEach { injected ->
            CoordinatorHarness().use { harness ->
                val old = preparedFakeSession(harness.worker, harness.finalizer, RecordingDecoderLifecycle())
                val candidateDecoder = RecordingDecoderLifecycle()
                val bridge = bridge(harness.worker, harness.finalizer)
                val coordinator = harness.coordinator(
                    old = old,
                    issuer = fixtureIssuer(),
                    decoderFactory = { candidateDecoder },
                    preparer = SegmentedReplacementSessionPreparer { request ->
                        bridge.prepareInto(request)
                        throw injected
                    },
                )
                val accepted = coordinator.replace(0L) as SegmentedReplacementStart.Accepted
                val terminal = coordinator.awaitTerminal(accepted.epoch)

                assertThat(terminal.outcome).isEqualTo(SegmentedReplacementOutcome.REJECTED)
                assertThat(terminal.failures.map { it.phase })
                    .containsExactly(SegmentedReplacementFailurePhase.PREPARE)
                assertThat(terminal.candidateCleanupReceipt?.sessionCloseReceipt?.succeeded).isTrue()
                assertThat(candidateDecoder.beginStopCalls.get()).isEqualTo(1)
                assertThat(candidateDecoder.flushCalls.get()).isEqualTo(1)
                coordinator.terminateAndAwait()
            }
        }
    }

    @Test
    fun `old close with non seek cause is retained and rejects candidate issuance`(): Unit = runBlocking {
        val operationExecutor = Executors.newSingleThreadExecutor()
        val operation = operationExecutor.asCoroutineDispatcher()
        CoordinatorHarness(operationOverride = operation).use { harness ->
            val old = preparedFakeSession(harness.worker, harness.finalizer, RecordingDecoderLifecycle())
            val blockerStarted = CountDownLatch(1)
            val allowOperation = CountDownLatch(1)
            operationExecutor.submit {
                blockerStarted.countDown()
                check(allowOperation.await(5, TimeUnit.SECONDS))
            }
            assertThat(blockerStarted.await(5, TimeUnit.SECONDS)).isTrue()
            val issueCalls = AtomicInteger()
            val coordinator = harness.coordinator(
                old = old,
                issuer = LocalSegmentLeaseIssuer { _, _, _ ->
                    issueCalls.incrementAndGet()
                    CandidateLeaseAcquisitionOutcome.Acquired
                },
            )
            val accepted = coordinator.replace(0L) as SegmentedReplacementStart.Accepted
            old.requestClose(SegmentedPlaybackCloseCause.OWNER_EXIT)
            allowOperation.countDown()
            val terminal = accepted.terminal.await()

            assertThat(terminal.outcome).isEqualTo(SegmentedReplacementOutcome.REJECTED)
            assertThat(terminal.oldCloseReceipt?.cause).isEqualTo(SegmentedPlaybackCloseCause.OWNER_EXIT)
            assertThat(terminal.failures.map { it.phase })
                .containsExactly(SegmentedReplacementFailurePhase.OLD_CLOSE_CAUSE)
            assertThat(issueCalls.get()).isEqualTo(0)
            coordinator.terminateAndAwait()
        }
        operation.close()
        operationExecutor.shutdownNow()
    }

    private fun fixtureIssuer() = LocalSegmentLeaseIssuer { ownership, intent, _ ->
        ownership.adoptFixture(intent)
        CandidateLeaseAcquisitionOutcome.Acquired
    }

    private fun ReplacementCandidateOwnershipCell.adoptFixture(intent: SegmentedSeekIntent) {
        adoptLease(
            CandidateResourceRole.INIT,
            SegmentedAuthorizedResourceHandle(
                intent.init,
                ImmediateAuthorizedLease(SegmentedMediaFmp4FixturePrototype.init),
            ),
        )
        adoptLease(
            CandidateResourceRole.SEGMENT,
            SegmentedAuthorizedResourceHandle(
                intent.segment,
                ImmediateAuthorizedLease(SegmentedMediaFmp4FixturePrototype.segment),
            ),
        )
    }

    private class CoordinatorHarness(
        operationOverride: kotlinx.coroutines.ExecutorCoroutineDispatcher? = null,
    ) : AutoCloseable {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val ownedOperation = operationOverride ?: Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val operation = ownedOperation
        val cleanup = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val ownsOperation = operationOverride == null

        fun coordinator(
            old: SegmentedPlaybackSession,
            issuer: LocalSegmentLeaseIssuer,
            preparer: SegmentedReplacementSessionPreparer = bridge(worker, finalizer),
            decoderFactory: () -> SegmentedDecoderLifecycle = { RecordingDecoderLifecycle() },
            authorizationGate: SegmentedMediaAuthorizationGatePrototype = alwaysAuthorizedGate(),
        ) = SegmentedSeekReplacementCoordinator(
            initialSession = old,
            leaseIssuer = issuer,
            sessionPreparer = preparer,
            authorizationGate = authorizationGate,
            decoderLifecycleFactory = decoderFactory,
            operationDispatcher = operation,
            cleanupDispatcher = cleanup,
            freshLeaseId = { "replacement-${UUID.randomUUID()}" },
        )

        override fun close() {
            worker.close()
            finalizer.close()
            cleanup.close()
            if (ownsOperation) ownedOperation.close()
        }
    }

    private class GateDecoderLifecycle : SegmentedDecoderLifecycle {
        val flushStarted = CountDownLatch(1)
        val allowFlush = CountDownLatch(1)

        override fun beginStop() = Unit

        override fun flushAndRelease() {
            flushStarted.countDown()
            check(allowFlush.await(5, TimeUnit.SECONDS)) { "Test did not release old decoder flush." }
        }
    }

    private class GateCloseLease(
        plaintext: ByteArray,
    ) : ImmediateAuthorizedLease(plaintext) {
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)

        override fun close() {
            closeStarted.countDown()
            check(allowClose.await(5, TimeUnit.SECONDS)) { "Test did not release partial lease close." }
            super.close()
        }
    }
}
