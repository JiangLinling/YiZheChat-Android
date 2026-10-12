/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.media.segmented.send

import io.element.android.libraries.matrix.impl.media.ProtectedMatrixMedia
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapter
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentLifecycle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Plain fMP4 resources already produced by the capture/transcode owner.
 *
 * This boundary deliberately does not capture, transcode, upload, construct a wire manifest, or finalize an event.
 * [DefaultSegmentedProtectedVideoResourcePreparer] is the independently reviewable per-resource KMS preparation stage
 * for the eventual [io.element.android.libraries.matrix.impl.timeline.RustTimeline.sendVideo] caller.
 */
internal data class SegmentedVideoPlainResource(
    val resourceId: String,
    val kind: SegmentedVideoPlainResourceKind,
    val index: Int,
    val startUs: Long,
    val durationUs: Long,
    val file: File,
    val declaredIndependentRandomAccess: Boolean,
)

internal enum class SegmentedVideoPlainResourceKind {
    MEDIA_INIT,
    MEDIA_SEGMENT,
}

internal data class SegmentedProtectedVideoPreparationRequest(
    val assetId: String,
    val init: SegmentedVideoPlainResource,
    val segments: List<SegmentedVideoPlainResource>,
    val lifecycle: ContentLifecycle,
)

internal data class PreparedSegmentedVideoResource(
    val resourceId: String,
    val kind: SegmentedVideoPlainResourceKind,
    val index: Int,
    val startUs: Long,
    val durationUs: Long,
    val plainLength: Long,
    val declaredIndependentRandomAccess: Boolean,
    val protectedMedia: ProtectedMatrixMedia,
)

/**
 * Server/device capability which must be authenticated by a future production supplier.
 *
 * No implementation in this slice mints this capability. The current message-lifecycle v1 route cannot satisfy the
 * atomic multi-content binding and per-resource outer receipt requirements, so this contract remains fail closed.
 */
internal data class SegmentedProtectedVideoSendCapability(
    val schema: String,
    val maximumResources: Int,
    val supportsAtomicMultiContentEventBinding: Boolean,
    val suppliesAuthenticatedOuterReceipts: Boolean,
)

internal fun requireSegmentedProtectedVideoSendCapability(
    capability: SegmentedProtectedVideoSendCapability,
    resourceCount: Int,
) {
    require(capability.schema == SEGMENTED_VIDEO_SCHEMA)
    require(resourceCount in 3..MAXIMUM_RESOURCE_COUNT)
    require(resourceCount <= capability.maximumResources)
    require(capability.supportsAtomicMultiContentEventBinding)
    require(capability.suppliesAuthenticatedOuterReceipts)
}

/**
 * Owns only the protected temporary files and their per-resource KMS content identities.
 * Plain capture/transcode inputs stay owned by the caller. Until a real sender atomically finalizes the event, failure
 * or cancellation must call [retire] and propagate any destruction failure.
 */
internal class PreparedSegmentedVideoResourceSet internal constructor(
    val assetId: String,
    val init: PreparedSegmentedVideoResource,
    val segments: List<PreparedSegmentedVideoResource>,
    private val destroy: suspend (ProtectedMatrixMedia, ContentAccessDestroyReason) -> Boolean,
    private val deleteProtectedFile: (File) -> Boolean,
) {
    private val retirementMutex = Mutex()
    private val retirementSlots = (listOf(init) + segments).asReversed().map { RetirementSlot(it) }
    private var retired = false

    val resources: List<PreparedSegmentedVideoResource>
        get() = listOf(init) + segments

    suspend fun retire(reason: ContentAccessDestroyReason) = retirementMutex.withLock {
        if (retired) return@withLock
        val failures = mutableListOf<Throwable>()
        retirementSlots.forEach { slot ->
            if (!slot.kmsDestroyed) {
                runCatching {
                    check(destroy(slot.resource.protectedMedia, reason)) {
                        "KMS refused segmented protected-video resource retirement."
                    }
                    slot.kmsDestroyed = true
                }.exceptionOrNull()?.let(failures::add)
            }
            if (!slot.fileDeleted) {
                runCatching {
                    val file = slot.resource.protectedMedia.file
                    check(!file.exists() || deleteProtectedFile(file)) {
                        "Protected segmented-video staging file could not be deleted."
                    }
                    slot.fileDeleted = true
                }.exceptionOrNull()?.let(failures::add)
            }
        }
        retired = retirementSlots.all { it.kmsDestroyed && it.fileDeleted }
        failures.firstOrNull()?.let { primary ->
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
        check(retired) { "Segmented protected-video retirement did not reach a terminal state." }
    }

    private data class RetirementSlot(
        val resource: PreparedSegmentedVideoResource,
        var kmsDestroyed: Boolean = false,
        var fileDeleted: Boolean = false,
    )
}

/**
 * Secure preparation only. It does not claim sender completion because no production capability/finalize supplier is
 * wired in this slice and [ProtectedMatrixMedia] does not expose the authenticated policy/outer locator receipt needed
 * to construct the receive-side manifest.
 */
internal class DefaultSegmentedProtectedVideoResourcePreparer(
    private val protect: suspend (File, ContentLifecycle) -> ProtectedMatrixMedia,
    private val destroy: suspend (ProtectedMatrixMedia, ContentAccessDestroyReason) -> Boolean,
    private val deleteProtectedFile: (File) -> Boolean = File::delete,
) {
    constructor(adapter: MatrixProtectedContentAdapter) : this(
        protect = { file, lifecycle -> adapter.protectMedia(file, ContentKind.VIDEO, lifecycle) },
        destroy = adapter::destroyMedia,
    )

    suspend fun prepare(request: SegmentedProtectedVideoPreparationRequest): PreparedSegmentedVideoResourceSet {
        validate(request)
        val prepared = mutableListOf<PreparedSegmentedVideoResource>()
        try {
            (listOf(request.init) + request.segments).forEach { resource ->
                val protectedMedia = protect(resource.file, request.lifecycle)
                prepared += PreparedSegmentedVideoResource(
                    resourceId = resource.resourceId,
                    kind = resource.kind,
                    index = resource.index,
                    startUs = resource.startUs,
                    durationUs = resource.durationUs,
                    plainLength = resource.file.length(),
                    declaredIndependentRandomAccess = resource.declaredIndependentRandomAccess,
                    protectedMedia = protectedMedia,
                )
            }
            return PreparedSegmentedVideoResourceSet(
                assetId = request.assetId,
                init = prepared.first(),
                segments = prepared.drop(1),
                destroy = destroy,
                deleteProtectedFile = deleteProtectedFile,
            )
        } catch (failure: Throwable) {
            if (prepared.isNotEmpty()) {
                val cleanupFailure = runCatching {
                    PreparedSegmentedVideoResourceSet(
                        assetId = request.assetId,
                        init = prepared.first(),
                        segments = prepared.drop(1),
                        destroy = destroy,
                        deleteProtectedFile = deleteProtectedFile,
                    ).retire(ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)
                }.exceptionOrNull()
                cleanupFailure?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    private fun validate(request: SegmentedProtectedVideoPreparationRequest) {
        require(request.assetId.matches(IDENTIFIER))
        require(request.init.kind == SegmentedVideoPlainResourceKind.MEDIA_INIT)
        require(request.init.index == 0 && request.init.startUs == 0L && request.init.durationUs == 0L)
        require(request.init.file.isFile && request.init.file.length() in 1L..MAXIMUM_INIT_BYTES)
        require(request.segments.size in 1..MAXIMUM_SEGMENTS)
        val ids = HashSet<String>(request.segments.size + 1)
        require(ids.add(request.init.resourceId) && request.init.resourceId.matches(IDENTIFIER))
        var previousEndUs = 0L
        request.segments.forEachIndexed { expectedIndex, segment ->
            require(segment.kind == SegmentedVideoPlainResourceKind.MEDIA_SEGMENT)
            require(segment.index == expectedIndex)
            require(segment.resourceId.matches(IDENTIFIER) && ids.add(segment.resourceId))
            require(segment.file.isFile && segment.file.length() in 1L..MAXIMUM_SEGMENT_BYTES)
            val endUs = Math.addExact(segment.startUs, segment.durationUs)
            require(segment.startUs >= previousEndUs && segment.durationUs > 0L)
            previousEndUs = endUs
        }
    }
}

internal const val SEGMENTED_VIDEO_SCHEMA = "io.yizhe.protected.segmented_media.v1"
private const val MAXIMUM_SEGMENTS = 512
private const val MAXIMUM_RESOURCE_COUNT = MAXIMUM_SEGMENTS + 2
private const val MAXIMUM_INIT_BYTES = 1L * 1024L * 1024L
private const val MAXIMUM_SEGMENT_BYTES = 2L * 1024L * 1024L
private val IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
