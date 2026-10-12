/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediapickers.api

data class MediaPickOperation(
    val operationId: String,
    val source: MediaPickSource,
    val roomIdHash: String,
    val createdAtEpochMillis: Long,
    val deadlineEpochMillis: Long,
) {
    init {
        require(operationId.length in 16..256)
        require(roomIdHash.length == 64)
        require(deadlineEpochMillis > createdAtEpochMillis)
    }
}

/** Original native cancellation fact; contains no selected URI, filename or user content. */
data class MediaPickCancellation(val operation: MediaPickOperation, val completedAtEpochMillis: Long) {
    init {
        require(completedAtEpochMillis >= operation.createdAtEpochMillis)
    }
}

enum class MediaPickSource {
    GALLERY,
    CAMERA_PHOTO,
}

enum class MediaPickFailureReason {
    CANCELLED,
    OPERATION_CONTEXT_LOST,
    OPERATION_EXPIRED,
    OPERATION_IN_PROGRESS,
    PERSISTENCE_FAILED,
    TEMPORARY_FILE_CLEANUP_FAILED,
    LAUNCH_FAILED,
    SOURCE_ACCESS_LOST,
    UNSUPPORTED_MEDIA,
    INSPECTION_FAILED,
}

sealed interface MediaPickItemResult {
    data class Ready(val media: PickedMedia) : MediaPickItemResult
    data class Failed(val index: Int?, val reason: MediaPickFailureReason) : MediaPickItemResult
}

data class MediaPickBatchResult(
    val operation: MediaPickOperation?,
    val items: List<MediaPickItemResult>,
)

fun interface MediaPickOperationLauncher {
    /** The LC-14 operation must already be durably started before this method is called. */
    fun launch(operation: MediaPickOperation)
}
