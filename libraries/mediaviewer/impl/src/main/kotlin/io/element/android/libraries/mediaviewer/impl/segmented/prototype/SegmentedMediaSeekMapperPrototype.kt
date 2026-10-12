/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.prototype

internal data class SegmentedMediaReadContextPrototype(
    val ownerToken: String,
    val leaseId: String,
    val generation: Long,
)

internal data class SegmentedMediaSeekLimitsPrototype(
    val maximumForwardDurationUs: Long = 6_000_000L,
    val maximumPlainBytes: Long = 8L * 1024L * 1024L,
    val maximumSegments: Int = 3,
)

internal data class SegmentedMediaSeekPlanPrototype(
    val context: SegmentedMediaReadContextPrototype,
    val requestedTimeUs: Long,
    val randomAccessStartUs: Long,
    val init: SegmentedMediaResourcePrototype,
    val segments: List<SegmentedMediaResourcePrototype>,
    val plannedPlainBytes: Long,
)

internal enum class SegmentedMediaSeekRejectionPrototype {
    AUTHORIZATION_DENIED,
    INVALID_CONTEXT,
    TARGET_OUT_OF_RANGE,
    NO_VERIFIED_RANDOM_ACCESS_POINT,
    NO_BOUNDED_WINDOW,
}

internal sealed interface SegmentedMediaSeekResultPrototype {
    data class Planned(val plan: SegmentedMediaSeekPlanPrototype) : SegmentedMediaSeekResultPrototype
    data class Rejected(val reason: SegmentedMediaSeekRejectionPrototype) : SegmentedMediaSeekResultPrototype
}

/**
 * Maps a seek onto a finite verified fragment window. Sender declarations and caller-supplied indices are never
 * consulted; an opaque parser proof must match the exact init, segment, and current lease context.
 */
internal object SegmentedMediaSeekMapperPrototype {
    fun map(
        validatedManifest: ValidatedSegmentedMediaManifestPrototype,
        targetTimeUs: Long,
        context: SegmentedMediaReadContextPrototype,
        authorizationGate: SegmentedMediaAuthorizationGatePrototype,
        sourceSegment: SegmentedMediaResourcePrototype,
        randomAccessProofs: Collection<SegmentedMediaRandomAccessProofPrototype>,
        limits: SegmentedMediaSeekLimitsPrototype = SegmentedMediaSeekLimitsPrototype(),
    ): SegmentedMediaSeekResultPrototype {
        if (context.ownerToken.isBlank() || context.leaseId.isBlank() || context.generation <= 0L) {
            return SegmentedMediaSeekResultPrototype.Rejected(SegmentedMediaSeekRejectionPrototype.INVALID_CONTEXT)
        }
        if (
            authorizationGate.check(context, SegmentedMediaAuthorizationPurposePrototype.SEEK_ADMISSION) !=
            SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED
        ) {
            return SegmentedMediaSeekResultPrototype.Rejected(SegmentedMediaSeekRejectionPrototype.AUTHORIZATION_DENIED)
        }
        val manifest = validatedManifest.manifest
        if (targetTimeUs < 0L || targetTimeUs >= manifest.durationUs) {
            return SegmentedMediaSeekResultPrototype.Rejected(SegmentedMediaSeekRejectionPrototype.TARGET_OUT_OF_RANGE)
        }
        if (sourceSegment !in manifest.segments || randomAccessProofs.none { proof ->
                proof.matches(manifest.init, sourceSegment, context)
            }
        ) {
            return SegmentedMediaSeekResultPrototype.Rejected(
                SegmentedMediaSeekRejectionPrototype.NO_VERIFIED_RANDOM_ACCESS_POINT
            )
        }
        val targetSegment = manifest.segments.singleOrNull { segment ->
            val endUs = Math.addExact(segment.startUs, segment.durationUs)
            targetTimeUs >= segment.startUs && targetTimeUs < endUs
        } ?: return SegmentedMediaSeekResultPrototype.Rejected(
            SegmentedMediaSeekRejectionPrototype.NO_VERIFIED_RANDOM_ACCESS_POINT
        )

        if (
            limits.maximumSegments < 1 ||
            targetSegment.durationUs > limits.maximumForwardDurationUs ||
            targetSegment.plainLength > limits.maximumPlainBytes
        ) {
            return SegmentedMediaSeekResultPrototype.Rejected(SegmentedMediaSeekRejectionPrototype.NO_BOUNDED_WINDOW)
        }
        if (
            authorizationGate.check(context, SegmentedMediaAuthorizationPurposePrototype.SEEK_DELIVERY) !=
            SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED
        ) {
            return SegmentedMediaSeekResultPrototype.Rejected(SegmentedMediaSeekRejectionPrototype.AUTHORIZATION_DENIED)
        }
        return SegmentedMediaSeekResultPrototype.Planned(
            SegmentedMediaSeekPlanPrototype(
                context = context,
                requestedTimeUs = targetTimeUs,
                randomAccessStartUs = targetSegment.startUs,
                init = manifest.init,
                segments = listOf(targetSegment),
                plannedPlainBytes = targetSegment.plainLength,
            )
        )
    }
}
