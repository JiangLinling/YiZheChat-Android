/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.prototype

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SegmentedMediaManifestPrototypeTest {
    @Test
    fun `valid frozen resource subset passes structural validation`() {
        val validated = SegmentedMediaManifestValidatorPrototype.validate(aManifest())

        assertThat(validated.manifest.segments.map { it.index }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `reordered or overlapping segments fail closed`() {
        val manifest = aManifest().let { original ->
            original.copy(
                segments = listOf(
                    original.segments[1].copy(index = 0),
                    original.segments[0].copy(index = 1, startUs = 1_000_000L),
                    original.segments[2],
                )
            )
        }

        val exception = assertThrows(SegmentedMediaManifestValidationException::class.java) {
            SegmentedMediaManifestValidatorPrototype.validate(manifest)
        }

        assertThat(exception.issues).contains(SegmentedMediaManifestIssuePrototype.INVALID_SEGMENT_TIME_RANGE)
    }

    @Test
    fun `duplicate resource content and mxc identities fail closed`() {
        val manifest = aManifest().let { original ->
            val duplicate = original.segments[1].copy(
                resourceId = original.segments[0].resourceId,
                contentId = original.segments[0].contentId,
                locator = original.segments[1].locator.copy(mxcUri = original.segments[0].locator.mxcUri),
            )
            original.copy(segments = listOf(original.segments[0], duplicate, original.segments[2]))
        }

        val exception = assertThrows(SegmentedMediaManifestValidationException::class.java) {
            SegmentedMediaManifestValidatorPrototype.validate(manifest)
        }

        assertThat(exception.issues).containsAtLeast(
            SegmentedMediaManifestIssuePrototype.DUPLICATE_RESOURCE_ID,
            SegmentedMediaManifestIssuePrototype.DUPLICATE_CONTENT_ID,
            SegmentedMediaManifestIssuePrototype.DUPLICATE_MXC_URI,
        )
    }

    @Test
    fun `non Matrix authority and malformed digest fail closed`() {
        val manifest = aManifest().let { original ->
            original.copy(
                init = original.init.copy(
                    innerContextDigestSha256 = "not-a-digest",
                    locator = original.init.locator.copy(authority = "FILE", mxcUri = "file:///tmp/plaintext"),
                )
            )
        }

        val exception = assertThrows(SegmentedMediaManifestValidationException::class.java) {
            SegmentedMediaManifestValidatorPrototype.validate(manifest)
        }

        assertThat(exception.issues).containsAtLeast(
            SegmentedMediaManifestIssuePrototype.INVALID_INNER_CONTEXT_DIGEST,
            SegmentedMediaManifestIssuePrototype.INVALID_MATRIX_MEDIA_LOCATOR,
        )
    }

    @Test
    fun `oversized segment fails before any decoder contract can be formed`() {
        val manifest = aManifest().let { original ->
            val oversized = original.segments[0].copy(plainLength = 2L * 1024L * 1024L + 1L)
            original.copy(
                segments = listOf(oversized) + original.segments.drop(1),
                totalPlainBytes = original.totalPlainBytes - original.segments[0].plainLength + oversized.plainLength,
            )
        }

        val exception = assertThrows(SegmentedMediaManifestValidationException::class.java) {
            SegmentedMediaManifestValidatorPrototype.validate(manifest)
        }

        assertThat(exception.issues).contains(SegmentedMediaManifestIssuePrototype.INVALID_PLAIN_LENGTH)
    }

    @Test
    fun `resource count and total plaintext length are exact`() {
        val exception = assertThrows(SegmentedMediaManifestValidationException::class.java) {
            SegmentedMediaManifestValidatorPrototype.validate(
                aManifest().copy(resourceCount = 99, totalPlainBytes = 99L)
            )
        }

        assertThat(exception.issues).containsAtLeast(
            SegmentedMediaManifestIssuePrototype.INVALID_RESOURCE_COUNT,
            SegmentedMediaManifestIssuePrototype.INVALID_TOTAL_PLAIN_LENGTH,
        )
    }

    @Test
    fun `initial middle final gaps and timeline overflow fail closed`() {
        val valid = aManifest()
        val invalid = listOf(
            valid.copy(segments = valid.segments.mapIndexed { index, segment ->
                if (index == 0) segment.copy(startUs = 1L) else segment
            }),
            valid.copy(segments = valid.segments.mapIndexed { index, segment ->
                if (index == 1) segment.copy(startUs = segment.startUs + 1L) else segment
            }),
            valid.copy(durationUs = valid.durationUs + 1L),
            aManifest(
                segments = listOf(
                    aSegment(index = 0, startUs = 0L, durationUs = Long.MAX_VALUE),
                    aSegment(index = 1, startUs = Long.MAX_VALUE, durationUs = 1L),
                )
            ),
        )

        invalid.forEach { manifest ->
            val exception = assertThrows(SegmentedMediaManifestValidationException::class.java) {
                SegmentedMediaManifestValidatorPrototype.validate(manifest)
            }
            assertThat(exception.issues).contains(SegmentedMediaManifestIssuePrototype.INVALID_SEGMENT_TIME_RANGE)
        }
    }
}

internal fun aManifest(
    segments: List<SegmentedMediaResourcePrototype> = listOf(
        aSegment(index = 0, startUs = 0L),
        aSegment(index = 1, startUs = 2_000_000L),
        aSegment(index = 2, startUs = 4_000_000L),
    ),
): SegmentedMediaManifestPrototype {
    val init = aResource(
        resourceId = "resource-init-0001",
        contentId = "content-init-0001",
        kind = SegmentedMediaResourceKindPrototype.MEDIA_INIT,
        index = 0,
        startUs = 0L,
        durationUs = 0L,
        plainLength = 1_024L,
    )
    return SegmentedMediaManifestPrototype(
        schema = SegmentedMediaManifestValidatorPrototype.CANDIDATE_SCHEMA,
        assetId = "asset-0001",
        mediaVersion = 1,
        roomIdHash = "a".repeat(64),
        contentContractId = "content-contract-1",
        expiresAtMs = 1_800_003_600_000L,
        container = "fmp4",
        codecs = listOf("avc1", "mp4a"),
        durationUs = segments.maxOfOrNull { it.startUs + it.durationUs } ?: 1L,
        totalPlainBytes = init.plainLength + segments.sumOf { it.plainLength },
        init = init,
        segments = segments,
        resourceCount = segments.size + 2,
    )
}

internal fun aSegment(
    index: Int,
    startUs: Long,
    durationUs: Long = 2_000_000L,
    plainLength: Long = 512L * 1024L,
    declaredIndependentRandomAccess: Boolean = true,
): SegmentedMediaResourcePrototype = aResource(
    resourceId = "resource-segment-${index.toString().padStart(4, '0')}",
    contentId = "content-segment-${index.toString().padStart(4, '0')}",
    kind = SegmentedMediaResourceKindPrototype.MEDIA_SEGMENT,
    index = index,
    startUs = startUs,
    durationUs = durationUs,
    plainLength = plainLength,
    declaredIndependentRandomAccess = declaredIndependentRandomAccess,
)

private fun aResource(
    resourceId: String,
    contentId: String,
    kind: SegmentedMediaResourceKindPrototype,
    index: Int,
    startUs: Long,
    durationUs: Long,
    plainLength: Long,
    declaredIndependentRandomAccess: Boolean = false,
): SegmentedMediaResourcePrototype = SegmentedMediaResourcePrototype(
    resourceId = resourceId,
    contentId = contentId,
    kind = kind,
    index = index,
    startUs = startUs,
    durationUs = durationUs,
    plainLength = plainLength,
    innerContextDigestSha256 = "b".repeat(64),
    locator = SegmentedMatrixMediaLocatorPrototype(
        authority = "MATRIX_MEDIA",
        mxcUri = "mxc://example.invalid/$resourceId",
        outerCipherSha256 = "c".repeat(64),
        cipherLength = plainLength + 128L,
    ),
    declaredIndependentRandomAccess = declaredIndependentRandomAccess,
)
