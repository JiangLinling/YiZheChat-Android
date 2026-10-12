/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.product

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.item.event.SegmentedVideoCandidate
import io.element.android.libraries.matrix.api.timeline.item.event.SegmentedVideoEncryptedFile
import io.element.android.libraries.matrix.api.timeline.item.event.SegmentedVideoLocator
import io.element.android.libraries.matrix.api.timeline.item.event.SegmentedVideoResource
import io.element.android.libraries.mediaviewer.impl.local.video.closeOwnerThenReleasePlayer
import io.element.android.libraries.mediaviewer.impl.local.video.removeListenerThenReleasePlayer
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaManifestValidationException
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaManifestValidatorPrototype
import org.junit.Test

class SegmentedVideoPlaybackOwnerTest {
    @Test
    fun `production one MiB init and two MiB segment are accepted`() {
        val candidate = candidate(1_048_576L, 2_097_152L)

        val validated = SegmentedMediaManifestValidatorPrototype.validate(candidate.toPrototype())

        assertThat(validated.manifest.totalPlainBytes).isEqualTo(3_145_728L)
    }

    @Test(expected = SegmentedMediaManifestValidationException::class)
    fun `segment above two MiB fails closed`() {
        val candidate = candidate(1_048_576L, 2_097_153L)
        SegmentedMediaManifestValidatorPrototype.validate(candidate.toPrototype())
    }

    @Test
    fun `player release still runs when segmented owner cleanup fails`() {
        var released = false
        val ownerFailure = IllegalStateException("owner cleanup failed")

        val failure = runCatching {
            closeOwnerThenReleasePlayer(
                closeOwner = { throw ownerFailure },
                releasePlayer = { released = true },
            )
        }.exceptionOrNull()

        assertThat(released).isTrue()
        assertThat(failure).isSameInstanceAs(ownerFailure)
    }

    @Test
    fun `player release still runs when listener removal fails`() {
        var released = false
        val removalFailure = IllegalStateException("listener removal failed")

        val failure = runCatching {
            removeListenerThenReleasePlayer(
                removeListener = { throw removalFailure },
                releasePlayer = { released = true },
            )
        }.exceptionOrNull()

        assertThat(released).isTrue()
        assertThat(failure).isSameInstanceAs(removalFailure)
    }

    @Test
    fun `listener removal remains primary when player release also fails`() {
        val removalFailure = IllegalStateException("listener removal failed")
        val releaseFailure = IllegalStateException("player release failed")

        val failure = runCatching {
            removeListenerThenReleasePlayer(
                removeListener = { throw removalFailure },
                releasePlayer = { throw releaseFailure },
            )
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(removalFailure)
        assertThat(failure?.suppressed?.asList()).containsExactly(releaseFailure)
    }

    private fun candidate(initLength: Long, segmentLength: Long) = SegmentedVideoCandidate(
        schema = "io.yizhe.protected.segmented_media.v1",
        assetId = "asset-identifier-0001",
        mediaVersion = 1,
        roomIdSha256Hex = SHA,
        contentContractId = "contract-identifier-0001",
        contentPolicyVersion = 2L,
        expiresAtEpochMillis = Long.MAX_VALUE,
        container = "fmp4",
        codecs = listOf("avc1.640028", "mp4a.40.2"),
        durationUs = 5_000_000L,
        totalPlainBytes = initLength + segmentLength,
        init = resource("resource-init-0001", "content-init-0001", "MEDIA_INIT", 0, 0L, 0L, initLength),
        segments = listOf(resource("resource-segment-0001", "content-segment-0001", "MEDIA_SEGMENT", 0, 0L, 5_000_000L, segmentLength)),
        resourceCount = 3,
    )

    private fun resource(
        resourceId: String,
        contentId: String,
        kind: String,
        index: Int,
        startUs: Long,
        durationUs: Long,
        plainLength: Long,
    ) = SegmentedVideoResource(
        resourceId = resourceId,
        contentId = contentId,
        kind = kind,
        protectedContentKind = "VIDEO",
        index = index,
        startUs = startUs,
        durationUs = durationUs,
        plainLength = plainLength,
        immutableContextSha256Hex = SHA,
        locator = SegmentedVideoLocator(
            authority = "MATRIX_MEDIA",
            mxcUri = "mxc://matrix.example/$resourceId",
            outerCipherSha256Hex = SHA,
            outerCipherLength = minOf(2_200_000L, plainLength + 1024L),
            encryptedFile = SegmentedVideoEncryptedFile(
                version = "v2",
                algorithm = "A256CTR",
                keyType = "oct",
                keyOperations = setOf("encrypt", "decrypt"),
                extractable = true,
                keyBase64Url = "secret-key-material",
                ivBase64 = "initialization-vector",
            ),
        ),
        declaredIndependentRandomAccess = kind == "MEDIA_SEGMENT",
    )

    private companion object {
        val SHA = "a".repeat(64)
    }
}
