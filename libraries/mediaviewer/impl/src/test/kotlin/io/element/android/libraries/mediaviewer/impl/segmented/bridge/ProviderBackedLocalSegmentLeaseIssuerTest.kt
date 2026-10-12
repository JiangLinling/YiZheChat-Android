/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaManifestValidatorPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaReadContextPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.aFixtureManifest
import io.element.android.security.securestorage.api.AuthorizedSecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRequest
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class ProviderBackedLocalSegmentLeaseIssuerTest {
    @Test
    fun `exact init then segment are adopted with unchanged identity and context`(): Unit = runBlocking {
        IssuerHarness().use { harness ->
            val fixture = fixture()
            val requests = mutableListOf<SegmentedAuthorizedResourceRequest>()
            val initLease = RecordingLease("init")
            val segmentLease = RecordingLease("segment")
            val issuer = ProviderBackedLocalSegmentLeaseIssuer { request ->
                requests += request
                val lease = if (request.role == CandidateResourceRole.INIT) initLease else segmentLease
                SegmentedAuthorizedResourceHandle(request.descriptor, lease)
            }

            val outcome = issuer.issueExactInto(harness.cell, fixture.intent, fixture.nextContext)

            assertThat(outcome).isEqualTo(CandidateLeaseAcquisitionOutcome.Acquired)
            assertThat(requests.map { it.role })
                .containsExactly(CandidateResourceRole.INIT, CandidateResourceRole.SEGMENT)
                .inOrder()
            assertThat(requests.map { it.manifestIdentity }.distinct()).containsExactly(fixture.intent.manifestIdentity)
            assertThat(requests.map { it.context }.distinct()).containsExactly(fixture.nextContext)
            assertThat(harness.cell.auditState().phase).isEqualTo(CandidateOwnershipPhase.FULL_LEASES)

            assertThat(harness.cell.closeAndAwaitRelease().succeeded).isTrue()
            assertThat(initLease.closeCount.get()).isEqualTo(1)
            assertThat(segmentLease.closeCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `invalid manifest or replacement context is rejected before provider acquisition`(): Unit = runBlocking {
        IssuerHarness().use { harness ->
            val fixture = fixture()
            val calls = AtomicInteger()
            val issuer = ProviderBackedLocalSegmentLeaseIssuer {
                calls.incrementAndGet()
                error("provider must not be called")
            }

            val wrongManifest = fixture.intent.copy(
                manifestIdentity = fixture.intent.manifestIdentity.copy(canonicalSha256 = "0".repeat(64))
            )
            val manifestOutcome = issuer.issueExactInto(harness.cell, wrongManifest, fixture.nextContext)
            val contextOutcome = issuer.issueExactInto(
                harness.cell,
                fixture.intent,
                fixture.nextContext.copy(ownerToken = "different-owner"),
            )

            assertRejectedReason(manifestOutcome, ProviderBackedIssuerFailureReason.MANIFEST_BINDING_INVALID)
            assertRejectedReason(contextOutcome, ProviderBackedIssuerFailureReason.REPLACEMENT_CONTEXT_INVALID)
            assertThat(calls.get()).isEqualTo(0)
            assertThat(harness.cell.auditState().phase).isEqualTo(CandidateOwnershipPhase.EMPTY)
        }
    }

    @Test
    fun `mismatched unadopted handle is closed exactly once`(): Unit = runBlocking {
        IssuerHarness().use { harness ->
            val fixture = fixture()
            val lease = RecordingLease("mismatch")
            val issuer = ProviderBackedLocalSegmentLeaseIssuer {
                SegmentedAuthorizedResourceHandle(fixture.intent.segment, lease)
            }

            val outcome = issuer.issueExactInto(harness.cell, fixture.intent, fixture.nextContext)

            assertRejectedReason(outcome, ProviderBackedIssuerFailureReason.DESCRIPTOR_MISMATCH)
            assertThat(lease.closeCount.get()).isEqualTo(1)
            assertThat(harness.cell.auditState().phase).isEqualTo(CandidateOwnershipPhase.EMPTY)
            assertThat(harness.cell.closeAndAwaitRelease().failures).isEmpty()
            assertThat(lease.closeCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `second provider failure leaves init registered for awaited cleanup`(): Unit = runBlocking {
        IssuerHarness().use { harness ->
            val fixture = fixture()
            val initLease = RecordingLease("init")
            val issuer = ProviderBackedLocalSegmentLeaseIssuer { request ->
                if (request.role == CandidateResourceRole.INIT) {
                    SegmentedAuthorizedResourceHandle(request.descriptor, initLease)
                } else {
                    error("segment acquisition failed")
                }
            }

            val outcome = issuer.issueExactInto(harness.cell, fixture.intent, fixture.nextContext)

            assertRejectedReason(outcome, ProviderBackedIssuerFailureReason.PROVIDER_FAILURE)
            assertThat(harness.cell.auditState().phase).isEqualTo(CandidateOwnershipPhase.PARTIAL_LEASES)
            assertThat(initLease.closeCount.get()).isEqualTo(0)
            val cleanup = harness.cell.closeAndAwaitRelease()
            assertThat(cleanup.adoptedRoles).containsExactly(CandidateResourceRole.INIT)
            assertThat(initLease.closeCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `duplicate lease alias is rejected without double close`(): Unit = runBlocking {
        IssuerHarness().use { harness ->
            val fixture = fixture()
            val sharedLease = RecordingLease("shared")
            val issuer = ProviderBackedLocalSegmentLeaseIssuer { request ->
                val descriptor = if (request.role == CandidateResourceRole.INIT) {
                    request.descriptor
                } else {
                    fixture.intent.init
                }
                SegmentedAuthorizedResourceHandle(descriptor, sharedLease)
            }

            val outcome = issuer.issueExactInto(harness.cell, fixture.intent, fixture.nextContext)

            assertRejectedReason(outcome, ProviderBackedIssuerFailureReason.DUPLICATE_LEASE)
            assertThat(sharedLease.closeCount.get()).isEqualTo(0)
            assertThat(harness.cell.auditState().phase).isEqualTo(CandidateOwnershipPhase.PARTIAL_LEASES)
            assertThat(harness.cell.closeAndAwaitRelease().succeeded).isTrue()
            assertThat(sharedLease.closeCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `provider suspension occurs after prior handle is registered in ownership cell`(): Unit = runBlocking {
        IssuerHarness().use { harness ->
            val fixture = fixture()
            val initLease = RecordingLease("init")
            val segmentLease = RecordingLease("segment")
            val segmentEntered = CompletableDeferred<Unit>()
            val allowSegment = CompletableDeferred<Unit>()
            val issuer = ProviderBackedLocalSegmentLeaseIssuer { request ->
                if (request.role == CandidateResourceRole.INIT) {
                    SegmentedAuthorizedResourceHandle(request.descriptor, initLease)
                } else {
                    segmentEntered.complete(Unit)
                    allowSegment.await()
                    SegmentedAuthorizedResourceHandle(request.descriptor, segmentLease)
                }
            }
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                issuer.issueExactInto(harness.cell, fixture.intent, fixture.nextContext)
            }
            segmentEntered.await()

            assertThat(harness.cell.auditState().phase).isEqualTo(CandidateOwnershipPhase.PARTIAL_LEASES)
            assertThat(harness.cell.auditState().adoptedRoles).containsExactly(CandidateResourceRole.INIT)

            allowSegment.complete(Unit)
            assertThat(operation.await()).isEqualTo(CandidateLeaseAcquisitionOutcome.Acquired)
            assertThat(harness.cell.closeAndAwaitRelease().succeeded).isTrue()
            assertThat(initLease.closeCount.get()).isEqualTo(1)
            assertThat(segmentLease.closeCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `caller cancellation closes provider-owned handle and cell later closes adopted handle`(): Unit = runBlocking {
        IssuerHarness().use { harness ->
            val fixture = fixture()
            val initLease = RecordingLease("init")
            val unreturnedSegmentLease = RecordingLease("unreturned-segment")
            val segmentEntered = CompletableDeferred<Unit>()
            val issuer = ProviderBackedLocalSegmentLeaseIssuer { request ->
                if (request.role == CandidateResourceRole.INIT) {
                    SegmentedAuthorizedResourceHandle(request.descriptor, initLease)
                } else {
                    segmentEntered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        unreturnedSegmentLease.close()
                    }
                }
            }
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                issuer.issueExactInto(harness.cell, fixture.intent, fixture.nextContext)
            }
            segmentEntered.await()

            operation.cancelAndJoin()

            assertThat(unreturnedSegmentLease.closeCount.get()).isEqualTo(1)
            assertThat(initLease.closeCount.get()).isEqualTo(0)
            assertThat(harness.cell.auditState().phase).isEqualTo(CandidateOwnershipPhase.PARTIAL_LEASES)
            val cleanup = harness.cell.closeAndAwaitRelease()
            assertThat(cleanup.adoptedRoles).containsExactly(CandidateResourceRole.INIT)
            assertThat(initLease.closeCount.get()).isEqualTo(1)
            assertThat(unreturnedSegmentLease.closeCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `provider cancellation propagates unchanged while D2-owned init remains cleanup eligible`(): Unit =
        runBlocking {
            IssuerHarness().use { harness ->
                val fixture = fixture()
                val initLease = RecordingLease("init")
                val cancellation = CancellationException("provider cancelled")
                val issuer = ProviderBackedLocalSegmentLeaseIssuer { request ->
                    if (request.role == CandidateResourceRole.INIT) {
                        SegmentedAuthorizedResourceHandle(request.descriptor, initLease)
                    } else {
                        throw cancellation
                    }
                }

                val observed = try {
                    issuer.issueExactInto(harness.cell, fixture.intent, fixture.nextContext)
                    null
                } catch (failure: CancellationException) {
                    failure
                }

                assertThat(observed).isSameInstanceAs(cancellation)
                assertThat(harness.cell.auditState().phase).isEqualTo(CandidateOwnershipPhase.PARTIAL_LEASES)
                assertThat(initLease.closeCount.get()).isEqualTo(0)
                assertThat(harness.cell.closeAndAwaitRelease().succeeded).isTrue()
                assertThat(initLease.closeCount.get()).isEqualTo(1)
            }
        }

    private class IssuerHarness : AutoCloseable {
        private val cleanupExecutor = Executors.newSingleThreadExecutor()
        private val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val cell = ReplacementCandidateOwnershipCell(epoch = 1L, cleanupDispatcher = cleanupDispatcher)

        override fun close() {
            cleanupDispatcher.close()
            cleanupExecutor.shutdownNow()
        }
    }

    private class RecordingLease(
        id: String,
    ) : AuthorizedSecureMediaLease {
        override val uri: Uri = Uri.parse("content://local/$id")
        val closeCount = AtomicInteger()

        override fun openInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())

        override suspend fun copyAuthenticatedRangeInto(
            request: SecureMediaAuthenticatedReadRequest,
            destination: ByteArray,
            destinationOffset: Int,
        ): SecureMediaAuthenticatedReadResult = SecureMediaAuthenticatedReadResult.Copied(0)

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private data class Fixture(
        val intent: SegmentedSeekIntent,
        val nextContext: SegmentedMediaReadContextPrototype,
    )

    private fun fixture(): Fixture {
        val validated = SegmentedMediaManifestValidatorPrototype.validate(aFixtureManifest())
        val sourceContext = SegmentedMediaReadContextPrototype("owner", "old-lease", 1L)
        val nextContext = SegmentedMediaReadContextPrototype("owner", "new-lease", 2L)
        return Fixture(
            intent = SegmentedSeekIntent(
                manifestIdentity = SegmentedManifestIdentityFactory.create(validated),
                requestedTimeUs = 0L,
                randomAccessStartUs = 0L,
                init = validated.manifest.init,
                segment = validated.manifest.segments.single(),
                validatedManifest = validated,
                sourceContext = sourceContext,
            ),
            nextContext = nextContext,
        )
    }

    private fun assertRejectedReason(
        outcome: CandidateLeaseAcquisitionOutcome,
        expected: ProviderBackedIssuerFailureReason,
    ) {
        val rejection = outcome as CandidateLeaseAcquisitionOutcome.Rejected
        assertThat((rejection.failure as ProviderBackedIssuerException).reason).isEqualTo(expected)
    }
}
