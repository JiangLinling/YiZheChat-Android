/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.media.segmented.send

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.impl.media.ProtectedMatrixMedia
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentLifecycle
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SegmentedProtectedVideoResourcePreparationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `preparation protects init then every segment with independent content identities`() = runBlocking {
        val protectedInputs = mutableListOf<String>()
        val preparer = DefaultSegmentedProtectedVideoResourcePreparer(
            protect = { file, _ ->
                protectedInputs += file.name
                protectedMedia(file, protectedInputs.size)
            },
            destroy = { _, _ -> true },
        )

        val prepared = preparer.prepare(request(segmentCount = 2))

        assertThat(protectedInputs).containsExactly("init.mp4", "segment-0.m4s", "segment-1.m4s").inOrder()
        assertThat(prepared.resources.map { it.protectedMedia.contentId.value }).containsExactly(
            "content-identifier-0001",
            "content-identifier-0002",
            "content-identifier-0003",
        ).inOrder()
    }

    @Test
    fun `protection failure retires every completed resource and preserves cleanup failure`() = runBlocking {
        val destroyed = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val primaryFailure = IOException("third resource protection failed")
        var protectionCount = 0
        val preparer = DefaultSegmentedProtectedVideoResourcePreparer(
            protect = { file, _ ->
                protectionCount += 1
                if (protectionCount == 3) throw primaryFailure
                protectedMedia(file, protectionCount)
            },
            destroy = { media, _ ->
                destroyed += media.contentId.value
                media.contentId.value != "content-identifier-0002"
            },
            deleteProtectedFile = { file ->
                deleted += file.name
                file.delete()
            },
        )

        val failure = runCatching { preparer.prepare(request(segmentCount = 2)) }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(primaryFailure)
        val capturedFailure = requireNotNull(failure)
        assertThat(capturedFailure.suppressed).hasLength(1)
        assertThat(capturedFailure.suppressed.single().message).contains("KMS refused")
        assertThat(destroyed).containsExactly("content-identifier-0002", "content-identifier-0001").inOrder()
        assertThat(deleted).hasSize(2)
    }

    @Test
    fun `retirement is exact once and attempts every KMS and file cleanup`() = runBlocking {
        val destroyed = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val preparer = DefaultSegmentedProtectedVideoResourcePreparer(
            protect = { file, _ -> protectedMedia(file, destroyed.size + file.name.length) },
            destroy = { media, _ ->
                destroyed += media.contentId.value
                true
            },
            deleteProtectedFile = { file ->
                deleted += file.name
                file.delete()
            },
        )
        val prepared = preparer.prepare(request(segmentCount = 1))

        prepared.retire(ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)
        prepared.retire(ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)

        assertThat(destroyed).hasSize(2)
        assertThat(deleted).hasSize(2)
    }

    @Test
    fun `failed retirement retains only incomplete cleanup for retry`() = runBlocking {
        val attempts = linkedMapOf<String, Int>()
        val preparer = DefaultSegmentedProtectedVideoResourcePreparer(
            protect = { file, _ -> protectedMedia(file, file.name.length) },
            destroy = { media, _ ->
                val attempt = attempts.getOrDefault(media.contentId.value, 0) + 1
                attempts[media.contentId.value] = attempt
                media.contentId.value != "content-identifier-0008" || attempt > 1
            },
        )
        val prepared = preparer.prepare(request(segmentCount = 1))

        assertThat(runCatching { prepared.retire(ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION) }.isFailure).isTrue()
        prepared.retire(ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)
        prepared.retire(ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)

        assertThat(attempts["content-identifier-0008"]).isEqualTo(2)
        assertThat(attempts["content-identifier-0013"]).isEqualTo(1)
    }

    @Test
    fun `capability fails closed without atomic binding and authenticated outer receipts`() {
        val incomplete = SegmentedProtectedVideoSendCapability(
            schema = SEGMENTED_VIDEO_SCHEMA,
            maximumResources = 514,
            supportsAtomicMultiContentEventBinding = false,
            suppliesAuthenticatedOuterReceipts = false,
        )

        assertThat(runCatching { requireSegmentedProtectedVideoSendCapability(incomplete, 3) }.isFailure).isTrue()
    }

    private fun request(segmentCount: Int): SegmentedProtectedVideoPreparationRequest {
        val init = plainResource(
            name = "init.mp4",
            resourceId = "resource-init-0001",
            kind = SegmentedVideoPlainResourceKind.MEDIA_INIT,
            index = 0,
            startUs = 0L,
            durationUs = 0L,
        )
        val segments = List(segmentCount) { index ->
            plainResource(
                name = "segment-$index.m4s",
                resourceId = "resource-segment-${index.toString().padStart(4, '0')}",
                kind = SegmentedVideoPlainResourceKind.MEDIA_SEGMENT,
                index = index,
                startUs = index * 1_000_000L,
                durationUs = 1_000_000L,
            )
        }
        return SegmentedProtectedVideoPreparationRequest(
            assetId = "asset-identifier-0001",
            init = init,
            segments = segments,
            lifecycle = ContentLifecycle(),
        )
    }

    private fun plainResource(
        name: String,
        resourceId: String,
        kind: SegmentedVideoPlainResourceKind,
        index: Int,
        startUs: Long,
        durationUs: Long,
    ): SegmentedVideoPlainResource {
        val file = temporaryFolder.newFile(name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return SegmentedVideoPlainResource(
            resourceId = resourceId,
            kind = kind,
            index = index,
            startUs = startUs,
            durationUs = durationUs,
            file = file,
            declaredIndependentRandomAccess = kind == SegmentedVideoPlainResourceKind.MEDIA_SEGMENT,
        )
    }

    private fun protectedMedia(source: File, identity: Int): ProtectedMatrixMedia {
        val output = File(requireNotNull(source.parentFile), ".protected-${source.name}-$identity.yzm")
            .apply { writeBytes(byteArrayOf(9, 8, 7)) }
        return ProtectedMatrixMedia(
            file = output,
            contentId = ContentId("content-identifier-${identity.toString().padStart(4, '0')}"),
        )
    }
}
