/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import android.net.Uri
import androidx.media3.datasource.DataSource
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaReadContextPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaResourcePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.ValidatedSegmentedMediaManifestPrototype
import kotlinx.coroutines.Deferred
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal data class SegmentedManifestIdentity(
    val schema: String,
    val mediaVersion: Int,
    val canonicalSha256: String,
)

internal class SegmentedSeekPlanningException(
    val rejection: String,
) : IllegalStateException("Segmented seek planning was rejected: $rejection.")

internal enum class SegmentedReplacementKind {
    SEEK,
    ADVANCE,
}

internal data class SegmentedSeekIntent(
    val manifestIdentity: SegmentedManifestIdentity,
    val requestedTimeUs: Long,
    val randomAccessStartUs: Long,
    val init: SegmentedMediaResourcePrototype,
    val segment: SegmentedMediaResourcePrototype,
    internal val validatedManifest: ValidatedSegmentedMediaManifestPrototype,
    internal val sourceContext: SegmentedMediaReadContextPrototype,
    val kind: SegmentedReplacementKind = SegmentedReplacementKind.SEEK,
)

internal data class SegmentedFragmentCursor(
    val manifestIdentity: SegmentedManifestIdentity,
    val segmentIndex: Int,
    val publicationEpoch: Long,
    val readGeneration: Long,
    val segmentStartUs: Long,
    val segmentEndUsExclusive: Long,
    val assetDurationUs: Long,
)

internal enum class CandidateResourceRole {
    INIT,
    SEGMENT,
}

internal enum class CandidateCleanupStatus {
    NOT_STARTED,
    UNKNOWN_PENDING,
    COMPLETE,
}

internal enum class CandidateOwnershipPhase {
    EMPTY,
    PARTIAL_LEASES,
    FULL_LEASES,
    SESSION,
    TRANSFERRED,
}

internal data class CandidateOwnershipAuditState(
    val phase: CandidateOwnershipPhase,
    val adoptedRoles: Set<CandidateResourceRole>,
    val ownsSession: Boolean,
    val cleanupStatus: CandidateCleanupStatus,
)

internal data class CandidateCleanupReceipt(
    val adoptedRoles: Set<CandidateResourceRole>,
    val sessionCloseReceipt: SegmentedPlaybackCloseReceipt?,
    val failures: List<Throwable>,
    val transferred: Boolean,
) {
    val succeeded: Boolean get() = failures.isEmpty() && (sessionCloseReceipt?.succeeded != false)
}

internal sealed interface CandidateInstallationOutcome {
    data object Installed : CandidateInstallationOutcome
    data class Rejected(val failure: Throwable) : CandidateInstallationOutcome
}

internal sealed interface CandidateLeaseAcquisitionOutcome {
    data object Acquired : CandidateLeaseAcquisitionOutcome
    data class Rejected(val failure: Throwable) : CandidateLeaseAcquisitionOutcome
}

internal enum class SegmentedReplacementFailurePhase {
    SEEK_PLAN,
    OLD_CLOSE,
    OLD_CLOSE_CAUSE,
    GENERATION,
    LEASE_ISSUE,
    PREPARE,
    CANDIDATE_TRANSFER,
    AUTHORITY_RECHECK,
    PUBLICATION,
    CANDIDATE_CLEANUP,
    OWNER_CLOSE,
}

internal data class SegmentedReplacementFailure(
    val phase: SegmentedReplacementFailurePhase,
    val failure: Throwable,
)

internal enum class SegmentedReplacementOutcome {
    PUBLISHED,
    REJECTED,
    TERMINATED,
}

internal data class SegmentedReplacementPublication(
    val cursor: SegmentedFragmentCursor,
    val uri: Uri,
    val dataSourceFactory: DataSource.Factory,
    val randomAccessStartUs: Long,
) {
    val generation: Long get() = cursor.readGeneration
}

internal data class SegmentedReplacementTerminalReceipt(
    val epoch: Long,
    val outcome: SegmentedReplacementOutcome,
    val generation: Long?,
    val oldCloseReceipt: SegmentedPlaybackCloseReceipt?,
    val candidateCleanupReceipt: CandidateCleanupReceipt?,
    val unpublishedSessionCloseReceipt: SegmentedPlaybackCloseReceipt?,
    val publication: SegmentedReplacementPublication?,
    val failures: List<SegmentedReplacementFailure>,
) {
    val succeeded: Boolean get() = outcome == SegmentedReplacementOutcome.PUBLISHED && failures.isEmpty()
}

internal enum class SegmentedReplacementStartRejection {
    BUSY,
    TERMINATED,
    NO_PUBLISHED_SESSION,
    SEEK_REJECTED,
    STALE_SOURCE,
}

internal sealed interface SegmentedReplacementStart {
    data class Accepted(
        val epoch: Long,
        val terminal: Deferred<SegmentedReplacementTerminalReceipt>,
    ) : SegmentedReplacementStart

    data class Rejected(
        val reason: SegmentedReplacementStartRejection,
        val failure: Throwable? = null,
    ) : SegmentedReplacementStart
}

internal sealed interface SegmentedAdvanceStart {
    data class Accepted(
        val epoch: Long,
        val terminal: Deferred<SegmentedReplacementTerminalReceipt>,
    ) : SegmentedAdvanceStart

    data object FinalFragment : SegmentedAdvanceStart

    data class Rejected(
        val reason: SegmentedReplacementStartRejection,
        val failure: Throwable? = null,
    ) : SegmentedAdvanceStart
}

internal data class ReplacementEpochAuditState(
    val epoch: Long,
    val phase: String,
    val oldGeneration: Long,
    val candidate: CandidateOwnershipAuditState?,
    val coordinatorOwnsUnpublishedSession: Boolean,
    val terminalReceipt: SegmentedReplacementTerminalReceipt?,
)

internal data class SegmentedCoordinatorTerminationReceipt(
    val activeReplacementReceipt: SegmentedReplacementTerminalReceipt?,
    val publishedSessionCloseReceipt: SegmentedPlaybackCloseReceipt?,
    val failures: List<SegmentedReplacementFailure>,
) {
    val succeeded: Boolean get() = failures.isEmpty() &&
        (activeReplacementReceipt?.failures?.isEmpty() != false) &&
        (publishedSessionCloseReceipt?.succeeded != false)
}

internal data class SegmentedReplacementPrepareRequest(
    val ownership: ReplacementCandidateOwnershipCell,
    val intent: SegmentedSeekIntent,
    val context: SegmentedMediaReadContextPrototype,
    val authorizationGate: io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationGatePrototype,
    val decoderLifecycle: SegmentedDecoderLifecycle,
)

internal fun interface LocalSegmentLeaseIssuer {
    suspend fun issueExactInto(
        ownership: ReplacementCandidateOwnershipCell,
        intent: SegmentedSeekIntent,
        context: SegmentedMediaReadContextPrototype,
    ): CandidateLeaseAcquisitionOutcome
}

internal fun interface SegmentedReplacementSessionPreparer {
    suspend fun prepareInto(request: SegmentedReplacementPrepareRequest)
}

internal object SegmentedManifestIdentityFactory {
    fun create(validated: ValidatedSegmentedMediaManifestPrototype): SegmentedManifestIdentity {
        val manifest = validated.manifest
        val canonical = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeCanonical(manifest.schema)
                output.writeCanonical(manifest.assetId)
                output.writeInt(manifest.mediaVersion)
                output.writeCanonical(manifest.roomIdHash)
                output.writeCanonical(manifest.contentContractId)
                output.writeLong(manifest.expiresAtMs)
                output.writeCanonical(manifest.container)
                output.writeInt(manifest.codecs.size)
                manifest.codecs.forEach { codec -> output.writeCanonical(codec) }
                output.writeLong(manifest.durationUs)
                output.writeLong(manifest.totalPlainBytes)
                output.writeInt(manifest.resourceCount)
                output.writeResource(manifest.init)
                output.writeInt(manifest.segments.size)
                manifest.segments.forEach { segment -> output.writeResource(segment) }
            }
            bytes.toByteArray()
        }
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(canonical)
                .joinToString("") { byte -> "%02x".format(byte) }
        } finally {
            canonical.fill(0)
        }
        return SegmentedManifestIdentity(manifest.schema, manifest.mediaVersion, digest)
    }

    private fun DataOutputStream.writeCanonical(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeResource(resource: SegmentedMediaResourcePrototype) {
        writeCanonical(resource.resourceId)
        writeCanonical(resource.contentId)
        writeCanonical(resource.kind.name)
        writeInt(resource.index)
        writeLong(resource.startUs)
        writeLong(resource.durationUs)
        writeLong(resource.plainLength)
        writeCanonical(resource.innerContextDigestSha256)
        writeCanonical(resource.locator.authority)
        writeCanonical(resource.locator.mxcUri)
        writeCanonical(resource.locator.outerCipherSha256)
        writeLong(resource.locator.cipherLength)
        writeBoolean(resource.declaredIndependentRandomAccess)
    }
}
