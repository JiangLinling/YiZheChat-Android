/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediapickers.test

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.Composable
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.mediapickers.api.MediaPickBatchResult
import io.element.android.libraries.mediapickers.api.MediaPickCancellation
import io.element.android.libraries.mediapickers.api.MediaPickFailureReason
import io.element.android.libraries.mediapickers.api.MediaPickItemResult
import io.element.android.libraries.mediapickers.api.MediaPickOperation
import io.element.android.libraries.mediapickers.api.MediaPickOperationLauncher
import io.element.android.libraries.mediapickers.api.NoOpPickerLauncher
import io.element.android.libraries.mediapickers.api.PickedMedia
import io.element.android.libraries.mediapickers.api.PickerLauncher
import io.element.android.libraries.mediapickers.api.PickerProvider

class FakePickerProvider : PickerProvider {
    private var mimeType = MimeTypes.Any
    private var result: Uri? = null
    private var operationTransform: (MediaPickOperation) -> MediaPickOperation = { it }
    val completedOperationIds = mutableListOf<String>()
    var completeOperationResult = true
    var operationFailureReason = MediaPickFailureReason.CANCELLED
    val cancellationFacts = mutableListOf<MediaPickCancellation>()
    var acknowledgeCancellationResult = true

    @Composable
    override fun registerOperationGalleryPicker(onResult: (MediaPickBatchResult) -> Unit): MediaPickOperationLauncher {
        return MediaPickOperationLauncher { operation ->
            onResult(operationResult(operation))
        }
    }

    @Composable
    override fun registerOperationCameraPhotoPicker(onResult: (MediaPickBatchResult) -> Unit): MediaPickOperationLauncher {
        return MediaPickOperationLauncher { operation ->
            onResult(operationResult(operation))
        }
    }

    override fun completeOperation(operationId: String): Boolean {
        completedOperationIds += operationId
        return completeOperationResult
    }

    override fun completeCancelledOperation(operation: MediaPickOperation): MediaPickCancellation? {
        if (!completeOperation(operation.operationId)) return null
        return cancellationFacts.firstOrNull { it.operation == operation }
            ?: MediaPickCancellation(operation, System.currentTimeMillis()).also(cancellationFacts::add)
    }

    override fun pendingCancellations(): List<MediaPickCancellation> = cancellationFacts.toList()

    override fun acknowledgeCancellation(cancellation: MediaPickCancellation): Boolean {
        if (!acknowledgeCancellationResult) return false
        cancellationFacts.remove(cancellation)
        return true
    }

    @Composable
    override fun registerGalleryPicker(onResult: (items: List<PickedMedia>) -> Unit): PickerLauncher<PickVisualMediaRequest, List<Uri>> {
        return NoOpPickerLauncher {
            onResult(result?.let { listOf(PickedMedia(it, mimeType)) }.orEmpty())
        }
    }

    @Composable
    override fun registerGalleryImagePicker(onResult: (uri: Uri?) -> Unit): PickerLauncher<PickVisualMediaRequest, Uri?> {
        return NoOpPickerLauncher { onResult(result) }
    }

    @Composable
    override fun registerFilePicker(mimeType: String, onResult: (Uri?, String?) -> Unit): PickerLauncher<String, Uri?> {
        return NoOpPickerLauncher { onResult(result, this.mimeType) }
    }

    @Composable
    override fun registerCameraPhotoPicker(onResult: (Uri?) -> Unit): PickerLauncher<Uri, Boolean> {
        return NoOpPickerLauncher { onResult(result) }
    }

    @Composable
    override fun registerCameraVideoPicker(onResult: (Uri?) -> Unit): PickerLauncher<Uri, Boolean> {
        return NoOpPickerLauncher { onResult(result) }
    }

    fun givenResult(value: Uri?) {
        this.result = value
    }

    fun givenMimeType(mimeType: String) {
        this.mimeType = mimeType
    }

    fun givenOperationTransform(transform: (MediaPickOperation) -> MediaPickOperation) {
        operationTransform = transform
    }

    private fun operationResult(sourceOperation: MediaPickOperation): MediaPickBatchResult {
        val operation = operationTransform(sourceOperation)
        val item = result?.let {
            MediaPickItemResult.Ready(PickedMedia(it, mimeType, "fake-picked-${operation.operationId}-0"))
        } ?: MediaPickItemResult.Failed(null, operationFailureReason)
        return MediaPickBatchResult(operation, listOf(item))
    }
}
