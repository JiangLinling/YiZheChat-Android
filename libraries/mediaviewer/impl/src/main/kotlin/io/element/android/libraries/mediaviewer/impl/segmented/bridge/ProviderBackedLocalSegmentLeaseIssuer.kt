/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaReadContextPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaResourcePrototype
import io.element.android.security.securestorage.api.AuthorizedSecureMediaLease
import kotlinx.coroutines.CancellationException

internal data class SegmentedAuthorizedResourceRequest(
    val role: CandidateResourceRole,
    val manifestIdentity: SegmentedManifestIdentity,
    val descriptor: SegmentedMediaResourcePrototype,
    val context: SegmentedMediaReadContextPrototype,
)

/**
 * Provides one already-authorized local resource.
 *
 * The provider owns every lease until [acquire] returns normally. If cancellation or failure occurs before return,
 * the provider must close any lease it created. After normal return the issuer owns the handle synchronously and
 * will either adopt it into the D2 ownership cell or close it before returning.
 */
internal fun interface SegmentedAuthorizedResourceProvider {
    suspend fun acquire(request: SegmentedAuthorizedResourceRequest): SegmentedAuthorizedResourceHandle
}

internal enum class ProviderBackedIssuerFailureReason {
    MANIFEST_BINDING_INVALID,
    REPLACEMENT_CONTEXT_INVALID,
    PROVIDER_FAILURE,
    DESCRIPTOR_MISMATCH,
    DUPLICATE_LEASE,
    OWNERSHIP_ADOPTION_FAILED,
}

internal class ProviderBackedIssuerException(
    val reason: ProviderBackedIssuerFailureReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * U1 local-only adapter from an exact authorized-resource provider into D2's synchronous ownership ledger.
 * It performs no Matrix fetch, wire parsing, content authorization, decryption or secure-storage issuance.
 */
internal class ProviderBackedLocalSegmentLeaseIssuer(
    private val provider: SegmentedAuthorizedResourceProvider,
) : LocalSegmentLeaseIssuer {
    override suspend fun issueExactInto(
        ownership: ReplacementCandidateOwnershipCell,
        intent: SegmentedSeekIntent,
        context: SegmentedMediaReadContextPrototype,
    ): CandidateLeaseAcquisitionOutcome {
        validateBindings(intent, context)?.let { return CandidateLeaseAcquisitionOutcome.Rejected(it) }
        return try {
            val initLease = acquireAndAdopt(
                ownership = ownership,
                role = CandidateResourceRole.INIT,
                descriptor = intent.init,
                intent = intent,
                context = context,
                alreadyAdoptedLease = null,
            )
            acquireAndAdopt(
                ownership = ownership,
                role = CandidateResourceRole.SEGMENT,
                descriptor = intent.segment,
                intent = intent,
                context = context,
                alreadyAdoptedLease = initLease,
            )
            CandidateLeaseAcquisitionOutcome.Acquired
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            CandidateLeaseAcquisitionOutcome.Rejected(
                if (failure is ProviderBackedIssuerException) {
                    failure
                } else {
                    ProviderBackedIssuerException(
                        reason = ProviderBackedIssuerFailureReason.PROVIDER_FAILURE,
                        message = "Authorized segmented resource provider failed.",
                        cause = failure,
                    )
                }
            )
        }
    }

    private suspend fun acquireAndAdopt(
        ownership: ReplacementCandidateOwnershipCell,
        role: CandidateResourceRole,
        descriptor: SegmentedMediaResourcePrototype,
        intent: SegmentedSeekIntent,
        context: SegmentedMediaReadContextPrototype,
        alreadyAdoptedLease: AuthorizedSecureMediaLease?,
    ): AuthorizedSecureMediaLease {
        var unadopted: SegmentedAuthorizedResourceHandle? = null
        try {
            val handle = try {
                provider.acquire(
                    SegmentedAuthorizedResourceRequest(
                        role = role,
                        manifestIdentity = intent.manifestIdentity,
                        descriptor = descriptor,
                        context = context,
                    )
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                throw ProviderBackedIssuerException(
                    reason = ProviderBackedIssuerFailureReason.PROVIDER_FAILURE,
                    message = "Provider failed while acquiring $role.",
                    cause = failure,
                )
            }
            unadopted = handle
            if (handle.lease === alreadyAdoptedLease) {
                // The same object is already owned by the cell. Closing this alias would cause double-close.
                unadopted = null
                throw ProviderBackedIssuerException(
                    reason = ProviderBackedIssuerFailureReason.DUPLICATE_LEASE,
                    message = "Init and segment must use distinct authorized leases.",
                )
            }
            if (handle.descriptor != descriptor) {
                throw ProviderBackedIssuerException(
                    reason = ProviderBackedIssuerFailureReason.DESCRIPTOR_MISMATCH,
                    message = "Provider returned a resource that does not match the exact $role descriptor.",
                )
            }
            try {
                ownership.adoptLease(role, handle)
            } catch (failure: Throwable) {
                throw ProviderBackedIssuerException(
                    reason = ProviderBackedIssuerFailureReason.OWNERSHIP_ADOPTION_FAILED,
                    message = "D2 rejected ownership adoption for $role.",
                    cause = failure,
                )
            }
            unadopted = null
            return handle.lease
        } catch (failure: Throwable) {
            unadopted?.let { handle ->
                try {
                    handle.lease.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
            }
            throw failure
        }
    }

    private fun validateBindings(
        intent: SegmentedSeekIntent,
        context: SegmentedMediaReadContextPrototype,
    ): ProviderBackedIssuerException? {
        val manifest = intent.validatedManifest.manifest
        val identityMatches = runCatching {
            SegmentedManifestIdentityFactory.create(intent.validatedManifest) == intent.manifestIdentity
        }.getOrDefault(false)
        if (
            !identityMatches ||
            manifest.init != intent.init ||
            intent.segment !in manifest.segments
        ) {
            return ProviderBackedIssuerException(
                reason = ProviderBackedIssuerFailureReason.MANIFEST_BINDING_INVALID,
                message = "Replacement intent is not bound to its exact validated manifest resources.",
            )
        }
        val source = intent.sourceContext
        val expectedGeneration = runCatching { Math.addExact(source.generation, 1L) }.getOrNull()
        if (
            source.ownerToken.isBlank() ||
            source.leaseId.isBlank() ||
            source.generation <= 0L ||
            context.ownerToken != source.ownerToken ||
            context.leaseId.isBlank() ||
            context.leaseId == source.leaseId ||
            expectedGeneration == null ||
            context.generation != expectedGeneration
        ) {
            return ProviderBackedIssuerException(
                reason = ProviderBackedIssuerFailureReason.REPLACEMENT_CONTEXT_INVALID,
                message = "Replacement resource context is not the next generation for the same owner.",
            )
        }
        return null
    }
}
