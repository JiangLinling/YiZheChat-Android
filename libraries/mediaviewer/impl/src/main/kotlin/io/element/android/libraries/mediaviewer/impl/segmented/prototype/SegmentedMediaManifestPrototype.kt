/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.prototype

/**
 * Internal WP08 model. This is deliberately not a wire model: CP-MEDIA's full manifest schema is still a candidate.
 * Only fields needed to enforce the frozen resource-set invariants are represented here.
 */
internal data class SegmentedMediaManifestPrototype(
    val schema: String,
    val assetId: String,
    val mediaVersion: Int,
    val roomIdHash: String,
    val contentContractId: String,
    val expiresAtMs: Long,
    val container: String,
    val codecs: List<String>,
    val durationUs: Long,
    /** Init plus media-fragment plaintext bytes. Auxiliary resources are intentionally not modeled yet. */
    val totalPlainBytes: Long,
    val init: SegmentedMediaResourcePrototype,
    val segments: List<SegmentedMediaResourcePrototype>,
    /** Manifest object plus init plus media fragments. */
    val resourceCount: Int,
)

internal data class SegmentedMediaResourcePrototype(
    val resourceId: String,
    val contentId: String,
    val kind: SegmentedMediaResourceKindPrototype,
    val index: Int,
    val startUs: Long,
    val durationUs: Long,
    val plainLength: Long,
    val innerContextDigestSha256: String,
    val locator: SegmentedMatrixMediaLocatorPrototype,
    /** Sender declaration only. A seek point is unusable until an fMP4 parser verifies it independently. */
    val declaredIndependentRandomAccess: Boolean,
)

internal enum class SegmentedMediaResourceKindPrototype {
    MEDIA_INIT,
    MEDIA_SEGMENT,
}

internal data class SegmentedMatrixMediaLocatorPrototype(
    val authority: String,
    val mxcUri: String,
    val outerCipherSha256: String,
    val cipherLength: Long,
)

internal data class SegmentedMediaPrototypeLimits(
    val maximumSegments: Int = 512,
    val maximumInitPlainBytes: Long = 1L * 1024L * 1024L,
    val maximumSegmentPlainBytes: Long = 2L * 1024L * 1024L,
    val maximumOuterCipherBytes: Long = 2_200_000L,
)

internal class ValidatedSegmentedMediaManifestPrototype internal constructor(
    val manifest: SegmentedMediaManifestPrototype,
)

internal enum class SegmentedMediaManifestIssuePrototype {
    UNSUPPORTED_SCHEMA,
    INVALID_ASSET_ID,
    UNSUPPORTED_MEDIA_VERSION,
    INVALID_ROOM_HASH,
    INVALID_CONTENT_CONTRACT_ID,
    INVALID_EXPIRY,
    UNSUPPORTED_CONTAINER,
    INVALID_CODEC_LIST,
    INVALID_DURATION,
    INVALID_RESOURCE_COUNT,
    INVALID_INIT,
    INVALID_SEGMENT_COUNT,
    INVALID_SEGMENT_ORDER,
    INVALID_SEGMENT_TIME_RANGE,
    INVALID_RESOURCE_ID,
    INVALID_CONTENT_ID,
    INVALID_INNER_CONTEXT_DIGEST,
    INVALID_MATRIX_MEDIA_LOCATOR,
    INVALID_PLAIN_LENGTH,
    INVALID_TOTAL_PLAIN_LENGTH,
    DUPLICATE_RESOURCE_ID,
    DUPLICATE_CONTENT_ID,
    DUPLICATE_MXC_URI,
}

internal class SegmentedMediaManifestValidationException(
    val issues: Set<SegmentedMediaManifestIssuePrototype>,
) : IllegalArgumentException("Segmented media manifest failed structural validation: ${issues.joinToString()}")

internal object SegmentedMediaManifestValidatorPrototype {
    const val CANDIDATE_SCHEMA = "io.yizhe.protected.segmented_media.v1"
    private const val FROZEN_MEDIA_VERSION = 1
    private const val FMP4_CONTAINER = "fmp4"
    private const val MATRIX_MEDIA_AUTHORITY = "MATRIX_MEDIA"
    private val sha256Hex = Regex("^[0-9a-f]{64}$")
    private val identifier = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]*$")
    private val mxcUri = Regex("^mxc://[^/\\s]+/[^\\s]+$")

    fun validate(
        manifest: SegmentedMediaManifestPrototype,
        limits: SegmentedMediaPrototypeLimits = SegmentedMediaPrototypeLimits(),
    ): ValidatedSegmentedMediaManifestPrototype {
        val issues = linkedSetOf<SegmentedMediaManifestIssuePrototype>()
        if (manifest.schema != CANDIDATE_SCHEMA) issues += SegmentedMediaManifestIssuePrototype.UNSUPPORTED_SCHEMA
        if (!manifest.assetId.isIdentifier(8, 128)) issues += SegmentedMediaManifestIssuePrototype.INVALID_ASSET_ID
        if (manifest.mediaVersion != FROZEN_MEDIA_VERSION) issues += SegmentedMediaManifestIssuePrototype.UNSUPPORTED_MEDIA_VERSION
        if (!sha256Hex.matches(manifest.roomIdHash)) issues += SegmentedMediaManifestIssuePrototype.INVALID_ROOM_HASH
        if (!manifest.contentContractId.isIdentifier(8, 128)) {
            issues += SegmentedMediaManifestIssuePrototype.INVALID_CONTENT_CONTRACT_ID
        }
        if (manifest.expiresAtMs <= 0L) issues += SegmentedMediaManifestIssuePrototype.INVALID_EXPIRY
        if (manifest.container != FMP4_CONTAINER) issues += SegmentedMediaManifestIssuePrototype.UNSUPPORTED_CONTAINER
        if (manifest.codecs.isEmpty() || manifest.codecs.any(String::isBlank)) {
            issues += SegmentedMediaManifestIssuePrototype.INVALID_CODEC_LIST
        }
        if (manifest.durationUs <= 0L) issues += SegmentedMediaManifestIssuePrototype.INVALID_DURATION
        if (manifest.segments.isEmpty() || manifest.segments.size > limits.maximumSegments) {
            issues += SegmentedMediaManifestIssuePrototype.INVALID_SEGMENT_COUNT
        }
        if (manifest.resourceCount != manifest.segments.size + 2) {
            issues += SegmentedMediaManifestIssuePrototype.INVALID_RESOURCE_COUNT
        }

        validateInit(manifest.init, limits, issues)
        validateSegments(manifest, limits, issues)
        validateUniqueness(listOf(manifest.init) + manifest.segments, issues)

        val calculatedPlainBytes = runCatching {
            manifest.segments.fold(manifest.init.plainLength) { total, segment ->
                Math.addExact(total, segment.plainLength)
            }
        }.getOrNull()
        if (calculatedPlainBytes == null || calculatedPlainBytes != manifest.totalPlainBytes) {
            issues += SegmentedMediaManifestIssuePrototype.INVALID_TOTAL_PLAIN_LENGTH
        }

        if (issues.isNotEmpty()) throw SegmentedMediaManifestValidationException(issues)
        return ValidatedSegmentedMediaManifestPrototype(manifest)
    }

    private fun validateInit(
        init: SegmentedMediaResourcePrototype,
        limits: SegmentedMediaPrototypeLimits,
        issues: MutableSet<SegmentedMediaManifestIssuePrototype>,
    ) {
        if (
            init.kind != SegmentedMediaResourceKindPrototype.MEDIA_INIT ||
            init.index != 0 ||
            init.startUs != 0L ||
            init.durationUs != 0L
        ) {
            issues += SegmentedMediaManifestIssuePrototype.INVALID_INIT
        }
        validateCommonResource(init, limits.maximumInitPlainBytes, limits, issues)
    }

    private fun validateSegments(
        manifest: SegmentedMediaManifestPrototype,
        limits: SegmentedMediaPrototypeLimits,
        issues: MutableSet<SegmentedMediaManifestIssuePrototype>,
    ) {
        var previousEndUs = 0L
        manifest.segments.forEachIndexed { expectedIndex, segment ->
            if (segment.kind != SegmentedMediaResourceKindPrototype.MEDIA_SEGMENT || segment.index != expectedIndex) {
                issues += SegmentedMediaManifestIssuePrototype.INVALID_SEGMENT_ORDER
            }
            val endUs = runCatching { Math.addExact(segment.startUs, segment.durationUs) }.getOrNull()
            if (
                segment.startUs != previousEndUs ||
                segment.durationUs <= 0L ||
                endUs == null ||
                endUs > manifest.durationUs
            ) {
                issues += SegmentedMediaManifestIssuePrototype.INVALID_SEGMENT_TIME_RANGE
            }
            if (endUs != null) previousEndUs = endUs
            validateCommonResource(segment, limits.maximumSegmentPlainBytes, limits, issues)
        }
        if (manifest.segments.isNotEmpty() && previousEndUs != manifest.durationUs) {
            issues += SegmentedMediaManifestIssuePrototype.INVALID_SEGMENT_TIME_RANGE
        }
    }

    private fun validateCommonResource(
        resource: SegmentedMediaResourcePrototype,
        maximumPlainBytes: Long,
        limits: SegmentedMediaPrototypeLimits,
        issues: MutableSet<SegmentedMediaManifestIssuePrototype>,
    ) {
        if (!resource.resourceId.isIdentifier(16, 256)) {
            issues += SegmentedMediaManifestIssuePrototype.INVALID_RESOURCE_ID
        }
        if (!resource.contentId.isIdentifier(16, 256)) {
            issues += SegmentedMediaManifestIssuePrototype.INVALID_CONTENT_ID
        }
        if (!sha256Hex.matches(resource.innerContextDigestSha256)) {
            issues += SegmentedMediaManifestIssuePrototype.INVALID_INNER_CONTEXT_DIGEST
        }
        if (resource.plainLength !in 1L..maximumPlainBytes) {
            issues += SegmentedMediaManifestIssuePrototype.INVALID_PLAIN_LENGTH
        }
        if (
            resource.locator.authority != MATRIX_MEDIA_AUTHORITY ||
            !mxcUri.matches(resource.locator.mxcUri) ||
            !sha256Hex.matches(resource.locator.outerCipherSha256) ||
            resource.locator.cipherLength !in 1L..limits.maximumOuterCipherBytes
        ) {
            issues += SegmentedMediaManifestIssuePrototype.INVALID_MATRIX_MEDIA_LOCATOR
        }
    }

    private fun validateUniqueness(
        resources: List<SegmentedMediaResourcePrototype>,
        issues: MutableSet<SegmentedMediaManifestIssuePrototype>,
    ) {
        if (resources.map(SegmentedMediaResourcePrototype::resourceId).toSet().size != resources.size) {
            issues += SegmentedMediaManifestIssuePrototype.DUPLICATE_RESOURCE_ID
        }
        if (resources.map(SegmentedMediaResourcePrototype::contentId).toSet().size != resources.size) {
            issues += SegmentedMediaManifestIssuePrototype.DUPLICATE_CONTENT_ID
        }
        if (resources.map { it.locator.mxcUri }.toSet().size != resources.size) {
            issues += SegmentedMediaManifestIssuePrototype.DUPLICATE_MXC_URI
        }
    }

    private fun String.isIdentifier(minimumLength: Int, maximumLength: Int): Boolean =
        length in minimumLength..maximumLength && identifier.matches(this)
}
