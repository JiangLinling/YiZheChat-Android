/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.prototype

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SegmentedMediaSeekMapperPrototypeTest {
    private val context = SegmentedMediaReadContextPrototype(
        ownerToken = "owner-token",
        leaseId = "lease-id",
        generation = 7L,
    )

    @Test
    fun `cross fragment seek selects exact target while proving current source`() {
        val validated = SegmentedMediaManifestValidatorPrototype.validate(
            aFixtureManifest(
                segments = listOf(
                    aSegment(0, 0L, plainLength = fixtureSegmentLength),
                    aSegment(1, 2_000_000L, plainLength = fixtureSegmentLength),
                    aSegment(2, 4_000_000L, plainLength = fixtureSegmentLength),
                    aSegment(3, 6_000_000L, plainLength = fixtureSegmentLength),
                )
            )
        )
        val proofs = listOf(aRandomAccessProof(validated, 0, context))

        val result = SegmentedMediaSeekMapperPrototype.map(
            validatedManifest = validated,
            targetTimeUs = 5_000_000L,
            context = context,
            authorizationGate = alwaysAuthorizedGate(context),
            sourceSegment = validated.manifest.segments[0],
            randomAccessProofs = proofs,
        ) as SegmentedMediaSeekResultPrototype.Planned

        assertThat(result.plan.randomAccessStartUs).isEqualTo(4_000_000L)
        assertThat(result.plan.segments.map { it.index }).containsExactly(2)
        assertThat(result.plan.context).isEqualTo(context)
    }

    @Test
    fun `sender declaration alone never creates a random access point`() {
        val result = SegmentedMediaSeekMapperPrototype.map(
            validatedManifest = SegmentedMediaManifestValidatorPrototype.validate(aFixtureManifest()),
            targetTimeUs = 1_000_000L,
            context = context,
            authorizationGate = alwaysAuthorizedGate(context),
            sourceSegment = aFixtureManifest().segments.single(),
            randomAccessProofs = emptyList(),
        )

        assertThat(result).isEqualTo(
            SegmentedMediaSeekResultPrototype.Rejected(
                SegmentedMediaSeekRejectionPrototype.NO_VERIFIED_RANDOM_ACCESS_POINT
            )
        )
    }

    @Test
    fun `planned window contains exactly the target fragment`() {
        val validated = SegmentedMediaManifestValidatorPrototype.validate(
            aFixtureManifest(
                segments = (0..4).map { index ->
                    aSegment(index, index * 2_000_000L, plainLength = fixtureSegmentLength)
                }
            )
        )

        val result = SegmentedMediaSeekMapperPrototype.map(
            validatedManifest = validated,
            targetTimeUs = 0L,
            context = context,
            authorizationGate = alwaysAuthorizedGate(context),
            sourceSegment = validated.manifest.segments[0],
            randomAccessProofs = listOf(aRandomAccessProof(validated, 0, context)),
            limits = SegmentedMediaSeekLimitsPrototype(maximumPlainBytes = fixtureSegmentLength * 3),
        ) as SegmentedMediaSeekResultPrototype.Planned

        assertThat(result.plan.segments).containsExactly(validated.manifest.segments[0])
        assertThat(result.plan.plannedPlainBytes).isEqualTo(fixtureSegmentLength)
        val endUs = result.plan.segments.last().let { it.startUs + it.durationUs }
        assertThat(endUs - result.plan.randomAccessStartUs).isAtMost(6_000_000L)
    }

    @Test
    fun `anchor that cannot fit bounded window is rejected`() {
        val validated = SegmentedMediaManifestValidatorPrototype.validate(
            aFixtureManifest(
                segments = listOf(aSegment(0, 0L, durationUs = 7_000_000L, plainLength = fixtureSegmentLength))
            )
        )

        val result = SegmentedMediaSeekMapperPrototype.map(
            validatedManifest = validated,
            targetTimeUs = 0L,
            context = context,
            authorizationGate = alwaysAuthorizedGate(context),
            sourceSegment = validated.manifest.segments[0],
            randomAccessProofs = listOf(aRandomAccessProof(validated, 0, context)),
        )

        assertThat(result).isEqualTo(
            SegmentedMediaSeekResultPrototype.Rejected(SegmentedMediaSeekRejectionPrototype.NO_BOUNDED_WINDOW)
        )
    }

    @Test
    fun `invalid lease ownership context and out of range target fail closed`() {
        val validated = SegmentedMediaManifestValidatorPrototype.validate(aFixtureManifest())
        val proof = aRandomAccessProof(validated, 0, context)

        assertThat(
            SegmentedMediaSeekMapperPrototype.map(
                validated,
                targetTimeUs = 0L,
                context = context.copy(ownerToken = ""),
                authorizationGate = alwaysAuthorizedGate(context),
                sourceSegment = validated.manifest.segments[0],
                randomAccessProofs = listOf(proof),
            )
        ).isEqualTo(
            SegmentedMediaSeekResultPrototype.Rejected(SegmentedMediaSeekRejectionPrototype.INVALID_CONTEXT)
        )
        assertThat(
            SegmentedMediaSeekMapperPrototype.map(
                validated,
                targetTimeUs = validated.manifest.durationUs,
                context = context,
                authorizationGate = alwaysAuthorizedGate(context),
                sourceSegment = validated.manifest.segments[0],
                randomAccessProofs = listOf(proof),
            )
        ).isEqualTo(
            SegmentedMediaSeekResultPrototype.Rejected(SegmentedMediaSeekRejectionPrototype.TARGET_OUT_OF_RANGE)
        )
    }

    @Test
    fun `proof for another segment identity or lease context cannot be reused`() {
        val validated = SegmentedMediaManifestValidatorPrototype.validate(
            aFixtureManifest(
                listOf(
                    aSegment(0, 0L, plainLength = fixtureSegmentLength),
                    aSegment(1, 2_000_000L, plainLength = fixtureSegmentLength),
                )
            )
        )
        val proofForFirstSegment = aRandomAccessProof(validated, 0, context)

        val wrongSegmentResult = SegmentedMediaSeekMapperPrototype.map(
            validatedManifest = validated,
            targetTimeUs = 2_500_000L,
            context = context,
            authorizationGate = alwaysAuthorizedGate(context),
            sourceSegment = validated.manifest.segments[0],
            randomAccessProofs = listOf(proofForFirstSegment),
        ) as SegmentedMediaSeekResultPrototype.Planned
        assertThat(wrongSegmentResult.plan.randomAccessStartUs).isEqualTo(2_000_000L)
        assertThat(wrongSegmentResult.plan.segments).containsExactly(validated.manifest.segments[1])

        val staleSourceProofResult = SegmentedMediaSeekMapperPrototype.map(
            validatedManifest = validated,
            targetTimeUs = 2_500_000L,
            context = context,
            authorizationGate = alwaysAuthorizedGate(context),
            sourceSegment = validated.manifest.segments[1],
            randomAccessProofs = listOf(proofForFirstSegment),
        )
        assertThat(staleSourceProofResult).isEqualTo(
            SegmentedMediaSeekResultPrototype.Rejected(
                SegmentedMediaSeekRejectionPrototype.NO_VERIFIED_RANDOM_ACCESS_POINT
            )
        )

        val wrongLease = context.copy(leaseId = "replacement")
        val wrongLeaseResult = SegmentedMediaSeekMapperPrototype.map(
            validatedManifest = validated,
            targetTimeUs = 0L,
            context = wrongLease,
            authorizationGate = alwaysAuthorizedGate(wrongLease),
            sourceSegment = validated.manifest.segments[0],
            randomAccessProofs = listOf(proofForFirstSegment),
        )
        assertThat(wrongLeaseResult).isEqualTo(
            SegmentedMediaSeekResultPrototype.Rejected(
                SegmentedMediaSeekRejectionPrototype.NO_VERIFIED_RANDOM_ACCESS_POINT
            )
        )
    }

    private val fixtureSegmentLength = SegmentedMediaFmp4FixturePrototype.segment.size.toLong()
}
