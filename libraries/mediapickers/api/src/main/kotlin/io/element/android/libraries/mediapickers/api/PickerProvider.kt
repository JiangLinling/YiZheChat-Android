/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediapickers.api

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.Composable
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLeaseService

interface PickerProvider {
    /** Optional, foreground-only discovery. Never requests additional library permissions. */
    suspend fun recentImages(nowEpochMillis: Long): List<RecentImage> = emptyList()

    /** Foreground gallery browsing within existing photo access, newest first; cursor excludes the previous page. */
    suspend fun galleryImages(nowEpochMillis: Long, before: RecentImage? = null, pageSize: Int = 40): List<RecentImage> = emptyList()

    /** Reuses the gallery journal and URI inspection after the caller durably starts LC-14. */
    suspend fun selectRecentImages(operation: MediaPickOperation, uris: List<Uri>): MediaPickBatchResult =
        MediaPickBatchResult(operation, listOf(MediaPickItemResult.Failed(null, MediaPickFailureReason.SOURCE_ACCESS_LOST)))

    @Composable
    fun registerOperationGalleryPicker(
        onResult: (MediaPickBatchResult) -> Unit,
    ): MediaPickOperationLauncher

    @Composable
    fun registerOperationGalleryPicker(
        preselected: List<PickedMedia>?,
        onResult: (MediaPickBatchResult) -> Unit,
    ): MediaPickOperationLauncher = registerOperationGalleryPicker(onResult)

    @Composable
    fun registerOperationCameraPhotoPicker(
        onResult: (MediaPickBatchResult) -> Unit,
    ): MediaPickOperationLauncher

    /** Clears the process-recovery correlation only after the authoritative consumer has handled the result. */
    /** True only after the external-activity correlation record is durably cleared. */
    fun completeOperation(operationId: String): Boolean

    /** Atomically retire native correlation and retain its actual cancellation fact until receipts are durable. */
    fun completeCancelledOperation(operation: MediaPickOperation): MediaPickCancellation? = null

    fun pendingCancellations(): List<MediaPickCancellation> = emptyList()

    /** Remove only the exact fact already handed to the existing lifecycle receipt outbox. */
    fun acknowledgeCancellation(cancellation: MediaPickCancellation): Boolean = false

    @Composable
    fun registerGalleryPicker(
        onResult: (items: List<PickedMedia>) -> Unit
    ): PickerLauncher<PickVisualMediaRequest, List<Uri>>

    @Composable
    fun registerGalleryPicker(
        preselected: List<PickedMedia>,
        onResult: (List<PickedMedia>) -> Unit,
    ): PickerLauncher<PickVisualMediaRequest, List<Uri>> = registerGalleryPicker(onResult)

    @Composable
    fun registerGalleryImagePicker(
        onResult: (Uri?) -> Unit
    ): PickerLauncher<PickVisualMediaRequest, Uri?>

    @Composable
    fun registerFilePicker(
        mimeType: String,
        onResult: (uri: Uri?, mimeType: String?) -> Unit,
    ): PickerLauncher<String, Uri?>

    /** Account-scoped attachment browser. The default keeps existing providers and test fakes compatible. */
    @Composable
    fun registerAttachmentPicker(
        accountId: String,
        roomIdHash: String?,
        previewLeaseService: LocalAttachmentPreviewLeaseService?,
        onResult: (List<PickedMedia>) -> Unit,
    ): PickerLauncher<String, List<Uri>> {
        val delegate = registerFilePicker("*/*") { uri, mime ->
            onResult(uri?.let { listOf(PickedMedia(it, mime)) }.orEmpty())
        }
        return object : PickerLauncher<String, List<Uri>> {
            override fun launch() = delegate.launch()
            override fun launch(customInput: String) = delegate.launch(customInput)
        }
    }

    @Composable
    fun registerCameraPhotoPicker(onResult: (Uri?) -> Unit): PickerLauncher<Uri, Boolean>

    @Composable
    fun registerCameraVideoPicker(onResult: (Uri?) -> Unit): PickerLauncher<Uri, Boolean>
}

data class RecentImage(val uri: Uri, val addedAtEpochMillis: Long)

data class PickedMedia(
    val uri: Uri,
    val mimeType: String?,
    /** Stable per-item identity derived from the already-started LC-14 picker operation. */
    val operationId: String? = null,
    val originalPhotos: Boolean = false,
    val sendImmediately: Boolean = false,
    val initiallySelected: Boolean = true,
    val focused: Boolean = false,
    val openEditor: Boolean = false,
)
