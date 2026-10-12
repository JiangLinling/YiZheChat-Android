/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationGatePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaRandomAccessProofPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaRandomAccessResultPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaRandomAccessVerifierPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaReadContextPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaResourcePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.ValidatedSegmentedMediaManifestPrototype
import io.element.android.security.securestorage.api.AuthorizedSecureMediaLease
import io.element.android.security.securestorage.api.SECURE_MEDIA_AUTHENTICATED_HANDOFF_MAX_BYTES
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRequest
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadResult
import io.element.android.security.securestorage.api.SecureMediaReadPurpose
import kotlinx.coroutines.CoroutineDispatcher

internal data class SegmentedAuthorizedResourceHandle(
    val descriptor: SegmentedMediaResourcePrototype,
    val lease: AuthorizedSecureMediaLease,
)

internal enum class SegmentedBridgeFailureReason {
    DESCRIPTOR_MISMATCH,
    RESOURCE_TOO_LARGE,
    AUTHORIZED_READ_REJECTED,
    SHORT_AUTHENTICATED_READ,
    RANDOM_ACCESS_REJECTED,
}

internal class SegmentedBridgeException(
    val reason: SegmentedBridgeFailureReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * D1 local-only bridge. Network fetch, production purpose issuance and product player wiring remain out of scope.
 */
internal class LocalAuthenticatedSegmentBridge(
    private val workerDispatcher: CoroutineDispatcher,
    private val finalizerDispatcher: CoroutineDispatcher,
) : SegmentedReplacementSessionPreparer {
    suspend fun prepare(
        validatedManifest: ValidatedSegmentedMediaManifestPrototype,
        segmentIndex: Int,
        init: SegmentedAuthorizedResourceHandle,
        segment: SegmentedAuthorizedResourceHandle,
        readContext: SegmentedMediaReadContextPrototype,
        authorizationGate: SegmentedMediaAuthorizationGatePrototype,
        decoderLifecycle: SegmentedDecoderLifecycle,
        verifiedFragmentInspector: ((ByteArray, ByteArray) -> Unit)? = null,
    ): SegmentedPlaybackSession {
        var success = false
        var primaryFailure: Throwable? = null
        try {
            val proof = verifyExact(
                validatedManifest,
                segmentIndex,
                init,
                segment,
                readContext,
                authorizationGate,
                verifiedFragmentInspector,
            )
            return newSession(
                validatedManifest,
                init,
                segment,
                readContext,
                proof,
                decoderLifecycle,
            ).also { success = true }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (!success) {
                val cleanupFailures = mutableListOf<Throwable>()
                runCatching { init.lease.close() }.exceptionOrNull()?.let(cleanupFailures::add)
                runCatching { segment.lease.close() }.exceptionOrNull()?.let(cleanupFailures::add)
                val failure = primaryFailure
                if (failure != null) {
                    cleanupFailures.forEach(failure::addSuppressed)
                } else {
                    cleanupFailures.firstOrNull()?.let { cleanupFailure ->
                        cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                        throw cleanupFailure
                    }
                }
            }
        }
    }

    override suspend fun prepareInto(request: SegmentedReplacementPrepareRequest) {
        if (SegmentedManifestIdentityFactory.create(request.intent.validatedManifest) != request.intent.manifestIdentity) {
            throw SegmentedBridgeException(
                SegmentedBridgeFailureReason.DESCRIPTOR_MISMATCH,
                "Replacement intent manifest identity changed after planning.",
            )
        }
        val manifest = request.intent.validatedManifest.manifest
        val segmentIndex = manifest.segments.indexOf(request.intent.segment)
        if (segmentIndex < 0 || manifest.init != request.intent.init) {
            throw SegmentedBridgeException(
                SegmentedBridgeFailureReason.DESCRIPTOR_MISMATCH,
                "Replacement intent resources are not members of the constructor-bound manifest.",
            )
        }
        val (init, segment) = request.ownership.handlesForPreparation(
            request.intent.init,
            request.intent.segment,
        )
        val proof = verifyExact(
            request.intent.validatedManifest,
            segmentIndex,
            init,
            segment,
            request.context,
            request.authorizationGate,
        )
        when (val installation = request.ownership.installSession {
            newSession(
                request.intent.validatedManifest,
                init,
                segment,
                request.context,
                proof,
                request.decoderLifecycle,
            )
        }) {
            CandidateInstallationOutcome.Installed -> Unit
            is CandidateInstallationOutcome.Rejected -> throw installation.failure
        }
    }

    private suspend fun verifyExact(
        validatedManifest: ValidatedSegmentedMediaManifestPrototype,
        segmentIndex: Int,
        init: SegmentedAuthorizedResourceHandle,
        segment: SegmentedAuthorizedResourceHandle,
        readContext: SegmentedMediaReadContextPrototype,
        authorizationGate: SegmentedMediaAuthorizationGatePrototype,
        verifiedFragmentInspector: ((ByteArray, ByteArray) -> Unit)? = null,
    ): SegmentedMediaRandomAccessProofPrototype {
        val manifest = validatedManifest.manifest
        val expectedSegment = manifest.segments.getOrNull(segmentIndex)
            ?: throw SegmentedBridgeException(
                SegmentedBridgeFailureReason.DESCRIPTOR_MISMATCH,
                "Segment index is outside the validated manifest.",
            )
        if (init.descriptor != manifest.init || segment.descriptor != expectedSegment || init.lease === segment.lease) {
            throw SegmentedBridgeException(
                SegmentedBridgeFailureReason.DESCRIPTOR_MISMATCH,
                "Authorized resources do not match the exact init/segment descriptors.",
            )
        }
        ensureD1Size(init.descriptor)
        ensureD1Size(segment.descriptor)
        val initBytes = ByteArray(init.descriptor.plainLength.toInt())
        val segmentBytes = ByteArray(segment.descriptor.plainLength.toInt())
        try {
            probe(init.lease)
            probe(segment.lease)
            readExact(init, initBytes)
            readExact(segment, segmentBytes)
            val proof = when (val result = SegmentedMediaRandomAccessVerifierPrototype.verify(
                validatedManifest = validatedManifest,
                segmentIndex = segmentIndex,
                verifiedInitBytes = initBytes,
                verifiedSegmentBytes = segmentBytes,
                context = readContext,
                authorizationGate = authorizationGate,
            )) {
                is SegmentedMediaRandomAccessResultPrototype.Verified -> result.proof
                is SegmentedMediaRandomAccessResultPrototype.Rejected -> throw SegmentedBridgeException(
                    SegmentedBridgeFailureReason.RANDOM_ACCESS_REJECTED,
                    "Media3 rejected the authenticated fMP4 random-access point: ${result.reason}.",
                )
            }
            // Parsing is CPU work outside C. Revalidate both exact leases before candidate installation.
            probe(init.lease)
            probe(segment.lease)
            verifiedFragmentInspector?.invoke(initBytes, segmentBytes)
            return proof
        } finally {
            initBytes.fill(0)
            segmentBytes.fill(0)
        }
    }

    private fun newSession(
        validatedManifest: ValidatedSegmentedMediaManifestPrototype,
        init: SegmentedAuthorizedResourceHandle,
        segment: SegmentedAuthorizedResourceHandle,
        readContext: SegmentedMediaReadContextPrototype,
        proof: SegmentedMediaRandomAccessProofPrototype,
        decoderLifecycle: SegmentedDecoderLifecycle,
    ) = SegmentedPlaybackSession(
        validatedManifest = validatedManifest,
        init = init,
        segment = segment,
        readContext = readContext,
        proof = proof,
        workerDispatcher = workerDispatcher,
        finalizerDispatcher = finalizerDispatcher,
        decoderLifecycle = decoderLifecycle,
    )

    private fun ensureD1Size(descriptor: SegmentedMediaResourcePrototype) {
        val maximum = when (descriptor.kind) {
            io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaResourceKindPrototype.MEDIA_INIT ->
                1L * 1024L * 1024L
            io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaResourceKindPrototype.MEDIA_SEGMENT ->
                2L * 1024L * 1024L
        }
        if (descriptor.plainLength !in 1L..maximum) {
            throw SegmentedBridgeException(
                SegmentedBridgeFailureReason.RESOURCE_TOO_LARGE,
                "Authenticated init/fragment exceeds the production manifest bound.",
            )
        }
    }

    private suspend fun probe(lease: AuthorizedSecureMediaLease) {
        when (val result = lease.copyAuthenticatedRangeInto(
            request = SecureMediaAuthenticatedReadRequest(
                offset = 0L,
                byteCount = 0,
                purpose = SecureMediaReadPurpose.SEGMENTED_DECODER,
            ),
            destination = EMPTY_BYTES,
        )) {
            is SecureMediaAuthenticatedReadResult.Copied -> if (result.byteCount != 0) {
                throw shortRead(0, result.byteCount)
            }
            is SecureMediaAuthenticatedReadResult.Rejected -> throw rejected(result)
        }
    }

    private suspend fun readExact(handle: SegmentedAuthorizedResourceHandle, destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val count = minOf(SECURE_MEDIA_AUTHENTICATED_HANDOFF_MAX_BYTES, destination.size - offset)
            when (val result = handle.lease.copyAuthenticatedRangeInto(
                request = SecureMediaAuthenticatedReadRequest(
                    offset = offset.toLong(),
                    byteCount = count,
                    purpose = SecureMediaReadPurpose.SEGMENTED_DECODER,
                ),
                destination = destination,
                destinationOffset = offset,
            )) {
                is SecureMediaAuthenticatedReadResult.Copied -> if (result.byteCount != count) {
                    throw shortRead(count, result.byteCount)
                }
                is SecureMediaAuthenticatedReadResult.Rejected -> throw rejected(result)
            }
            offset += count
        }
    }

    private fun rejected(result: SecureMediaAuthenticatedReadResult.Rejected) = SegmentedBridgeException(
        SegmentedBridgeFailureReason.AUTHORIZED_READ_REJECTED,
        "Authorized secure-media read was rejected: ${result.reason}.",
    )

    private fun shortRead(expected: Int, actual: Int) = SegmentedBridgeException(
        SegmentedBridgeFailureReason.SHORT_AUTHENTICATED_READ,
        "Authenticated read returned $actual bytes; expected $expected.",
    )

    private companion object {
        val EMPTY_BYTES = ByteArray(0)
    }
}
