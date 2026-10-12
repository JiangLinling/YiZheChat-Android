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
class SegmentedMediaRandomAccessVerifierPrototypeTest {
    private val context = SegmentedMediaReadContextPrototype("owner", "lease", 2L)

    @Test
    fun `actual Media3 fragmented muxer fixture proves first sample random access`() {
        val validated = validatedFixture()

        val result = verify(validated)

        assertThat(result).isInstanceOf(SegmentedMediaRandomAccessResultPrototype.Verified::class.java)
        val proof = (result as SegmentedMediaRandomAccessResultPrototype.Verified).proof
        assertThat(proof.matches(validated.manifest.init, validated.manifest.segments.single(), context)).isTrue()
    }

    @Test
    fun `non sync first sample is rejected from parsed trun flags`() {
        val segment = SegmentedMediaFmp4FixturePrototype.segment.copyOf()
        val trun = segment.indexOfAscii("trun")
        assertThat(trun).isAtLeast(0)
        val firstSampleFlags = trun + 24
        segment[firstSampleFlags] = 0x01
        segment[firstSampleFlags + 1] = 0x01
        segment[firstSampleFlags + 2] = 0x00
        segment[firstSampleFlags + 3] = 0x00

        val result = verify(validatedFixture(), segmentBytes = segment)

        assertThat(result).isEqualTo(
            SegmentedMediaRandomAccessResultPrototype.Rejected(
                SegmentedMediaRandomAccessRejectionPrototype.FIRST_VIDEO_SAMPLE_NOT_INDEPENDENT
            )
        )
    }

    @Test
    fun `tfhd default sample flags are resolved when trun omits sample flags`() {
        val keyDefaultSegment = segmentUsingTfhdDefaultSampleFlags(0x02000000)
        val nonSyncDefaultSegment = segmentUsingTfhdDefaultSampleFlags(0x01010000)

        assertThat(
            verify(validatedFixture(segmentLength = keyDefaultSegment.size), segmentBytes = keyDefaultSegment)
        ).isInstanceOf(SegmentedMediaRandomAccessResultPrototype.Verified::class.java)
        assertThat(
            verify(validatedFixture(segmentLength = nonSyncDefaultSegment.size), segmentBytes = nonSyncDefaultSegment)
        ).isEqualTo(
            SegmentedMediaRandomAccessResultPrototype.Rejected(
                SegmentedMediaRandomAccessRejectionPrototype.FIRST_VIDEO_SAMPLE_NOT_INDEPENDENT
            )
        )
    }

    @Test
    fun `truncated fragment is rejected before parser delivery`() {
        val result = verify(
            validatedFixture(segmentLength = SegmentedMediaFmp4FixturePrototype.segment.size - 1),
            segmentBytes = SegmentedMediaFmp4FixturePrototype.segment.copyOf(
                SegmentedMediaFmp4FixturePrototype.segment.size - 1
            ),
        )

        assertThat(result).isEqualTo(
            SegmentedMediaRandomAccessResultPrototype.Rejected(
                SegmentedMediaRandomAccessRejectionPrototype.INVALID_SEGMENT_BOX_LAYOUT
            )
        )
    }

    @Test
    fun `fragment referencing track absent from init is rejected`() {
        val segment = SegmentedMediaFmp4FixturePrototype.segment.copyOf()
        val tfhd = segment.indexOfAscii("tfhd")
        assertThat(tfhd).isAtLeast(0)
        val trackId = tfhd + 8
        segment[trackId] = 0
        segment[trackId + 1] = 0
        segment[trackId + 2] = 0
        segment[trackId + 3] = 2

        val result = verify(validatedFixture(), segmentBytes = segment)

        assertThat(result).isEqualTo(
            SegmentedMediaRandomAccessResultPrototype.Rejected(
                SegmentedMediaRandomAccessRejectionPrototype.PARSER_REJECTED
            )
        )
    }

    @Test
    fun `fragment cannot be proved against init for another track identity`() {
        val init = SegmentedMediaFmp4FixturePrototype.init.copyOf()
        val tkhd = init.indexOfAscii("tkhd")
        val trex = init.indexOfAscii("trex")
        assertThat(tkhd).isAtLeast(0)
        assertThat(trex).isAtLeast(0)
        init.writeInt32(tkhd + 16, 2)
        init.writeInt32(trex + 8, 2)

        val result = verify(validatedFixture(), initBytes = init)

        assertThat(result).isEqualTo(
            SegmentedMediaRandomAccessResultPrototype.Rejected(
                SegmentedMediaRandomAccessRejectionPrototype.PARSER_REJECTED
            )
        )
    }

    @Test
    fun `unsigned 64 bit box size and undersized extended header fail closed`() {
        val segment = SegmentedMediaFmp4FixturePrototype.segment.copyOf()
        segment[0] = 0
        segment[1] = 0
        segment[2] = 0
        segment[3] = 1
        segment[8] = 0x80.toByte()

        val result = verify(validatedFixture(), segmentBytes = segment)

        assertThat(result).isEqualTo(
            SegmentedMediaRandomAccessResultPrototype.Rejected(
                SegmentedMediaRandomAccessRejectionPrototype.INVALID_SEGMENT_BOX_LAYOUT
            )
        )
    }

    @Test
    fun `authorization revoked before proof delivery rejects`() {
        val authority = FakeSegmentedMediaLeaseAuthorityPrototype(context)
        authority.afterValidation = { purpose, decision ->
            if (
                purpose == SegmentedMediaAuthorizationPurposePrototype.VERIFICATION_PROGRESS &&
                decision == SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED
            ) {
                authority.revoked = true
            }
        }

        val result = SegmentedMediaRandomAccessVerifierPrototype.verify(
            validatedManifest = validatedFixture(),
            segmentIndex = 0,
            verifiedInitBytes = SegmentedMediaFmp4FixturePrototype.init.copyOf(),
            verifiedSegmentBytes = SegmentedMediaFmp4FixturePrototype.segment.copyOf(),
            context = context,
            authorizationGate = authority.gate(),
        )

        assertThat(result).isEqualTo(
            SegmentedMediaRandomAccessResultPrototype.Rejected(
                SegmentedMediaRandomAccessRejectionPrototype.AUTHORIZATION_DENIED
            )
        )
    }

    private fun validatedFixture(
        segmentLength: Int = SegmentedMediaFmp4FixturePrototype.segment.size,
    ): ValidatedSegmentedMediaManifestPrototype = SegmentedMediaManifestValidatorPrototype.validate(
        aFixtureManifest(
            listOf(aSegment(0, 0L, plainLength = segmentLength.toLong()))
        )
    )

    private fun verify(
        validated: ValidatedSegmentedMediaManifestPrototype,
        initBytes: ByteArray = SegmentedMediaFmp4FixturePrototype.init.copyOf(),
        segmentBytes: ByteArray = SegmentedMediaFmp4FixturePrototype.segment.copyOf(),
    ): SegmentedMediaRandomAccessResultPrototype = SegmentedMediaRandomAccessVerifierPrototype.verify(
        validatedManifest = validated,
        segmentIndex = 0,
        verifiedInitBytes = initBytes,
        verifiedSegmentBytes = segmentBytes,
        context = context,
        authorizationGate = alwaysAuthorizedGate(context),
    )

    private fun ByteArray.indexOfAscii(value: String): Int {
        val needle = value.encodeToByteArray()
        return indices.firstOrNull { start ->
            start + needle.size <= size && needle.indices.all { index -> this[start + index] == needle[index] }
        } ?: -1
    }

    private fun ByteArray.writeInt32(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    /** Rewrites the fixture without interpreting samples: tfhd gains default flags; trun loses both flag entries. */
    private fun segmentUsingTfhdDefaultSampleFlags(defaultFlags: Int): ByteArray {
        val source = SegmentedMediaFmp4FixturePrototype.segment
        val result = ByteArray(source.size - 4)
        source.copyInto(result, destinationOffset = 0, startIndex = 0, endIndex = 56)
        result.writeInt32(56, defaultFlags)
        source.copyInto(result, destinationOffset = 60, startIndex = 56, endIndex = 84)
        source.copyInto(result, destinationOffset = 88, startIndex = 88, endIndex = 96)
        source.copyInto(result, destinationOffset = 96, startIndex = 100, endIndex = source.size)

        result.writeInt32(0, 96) // moof
        result.writeInt32(24, 72) // traf
        result.writeInt32(32, 28) // tfhd
        result.writeInt32(40, 0x00000021) // base-data-offset-present + default-sample-flags-present
        result.writeInt32(60, 36) // trun
        result.writeInt32(68, 0x00000301) // data offset + sample duration + sample size
        result.writeInt32(76, 104) // moof plus mdat header
        return result
    }
}
