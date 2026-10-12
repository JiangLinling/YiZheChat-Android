/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview.imageeditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.messages.impl.attachments.preview.resolvedImageMimeType
import io.element.android.libraries.androidutils.bitmap.rotateToExifMetadataOrientation
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeAnimatedImage
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeImage
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.security.securestorage.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours

private const val EDITED_MEDIA_DIR_NAME = "edited-media"

interface AttachmentImageEditor {
    suspend fun canEdit(localMedia: LocalMedia): Boolean

    suspend fun exportEdits(
        localMedia: LocalMedia,
        edits: AttachmentImageEdits,
    ): Result<EditedLocalMedia>
}

data class EditedLocalMedia(
    val localMedia: LocalMedia,
    val file: File? = null,
    val lease: SecureMediaLease? = null,
    val reference: SecureObjectReference? = null,
    val onClose: (() -> Unit)? = null,
) : AutoCloseable {
    override fun close() {
        lease?.close()
        file?.delete()
        onClose?.invoke()
    }
}

@ContributesBinding(SessionScope::class)
class DefaultAttachmentImageEditor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
    private val fileStore: SensitiveFileStore,
    private val leaseManager: SecureMediaLeaseManager,
    private val sessionId: SessionId,
    private val matrixClient: MatrixClient,
    @SessionCoroutineScope private val sessionScope: CoroutineScope,
) : AttachmentImageEditor {
    override suspend fun canEdit(localMedia: LocalMedia): Boolean = withContext(dispatchers.io) {
        canEditStillImage(localMedia.info.resolvedImageMimeType() ?: localMedia.info.mimeType) {
            context.contentResolver.openInputStream(localMedia.uri)?.use { input ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)
                options.outMimeType
            }
        }
    }

    override suspend fun exportEdits(
        localMedia: LocalMedia,
        edits: AttachmentImageEdits,
    ): Result<EditedLocalMedia> = withContext(dispatchers.io) {
        runCatchingExceptions {
            val sourceMimeType = localMedia.info.resolvedImageMimeType() ?: localMedia.info.mimeType
            val exportedMimeType = exportedMimeTypeFor(sourceMimeType)
            val outputName = localMedia.info.filename.substringBeforeLast('.').ifBlank { "photo" } + "-edited." + compressFileExtension(exportedMimeType)
            val exifOrientation = context.contentResolver.openInputStream(localMedia.uri)?.let { input ->
                input.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                }
            } ?: ExifInterface.ORIENTATION_UNDEFINED

            val decodedBitmap = context.contentResolver.openInputStream(localMedia.uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: error("Unable to decode the source image")

            val normalizedBitmap = decodedBitmap.rotateToExifMetadataOrientation(exifOrientation)
            if (normalizedBitmap !== decodedBitmap) {
                decodedBitmap.recycle()
            }

            val transformedBitmap = normalizedBitmap.applyEdits(edits)
            if (transformedBitmap !== normalizedBitmap) {
                normalizedBitmap.recycle()
            }

            val annotatedBitmap = if (edits.annotations.isEmpty()) {
                transformedBitmap
            } else {
                transformedBitmap.copy(Bitmap.Config.ARGB_8888, true).also { result ->
                    renderAnnotations(android.graphics.Canvas(result), transformedBitmap, edits.annotations)
                    transformedBitmap.recycle()
                }
            }

            val cropRect = edits.cropRect.toPixelRect(
                imageWidth = annotatedBitmap.width,
                imageHeight = annotatedBitmap.height,
            )
            val isCropUnchanged = cropRect.left == 0 && cropRect.top == 0 &&
                cropRect.width() == annotatedBitmap.width && cropRect.height() == annotatedBitmap.height
            val croppedBitmap = if (isCropUnchanged) {
                annotatedBitmap
            } else {
                Bitmap.createBitmap(
                    annotatedBitmap,
                    cropRect.left,
                    cropRect.top,
                    cropRect.width(),
                    cropRect.height(),
                )
            }
            if (croppedBitmap !== annotatedBitmap) {
                annotatedBitmap.recycle()
            }

            val reference = try {
                fileStore.write(SecureObjectWriteRequest(
                    objectId = SecureObjectId("media-edit-${UUID.randomUUID()}"),
                    vaultId = deriveAccountVaultId(sessionId.value, matrixClient.deviceId.value),
                    purpose = SecureObjectPurpose.EDITING_STAGING,
                    associatedData = (localMedia.lifecycleOperationId + ":" + localMedia.lifecycleRoomIdHash).toByteArray(),
                )) { output -> check(croppedBitmap.compress(compressFormat(exportedMimeType), 100, output)) }
            } finally {
                croppedBitmap.recycle()
            }
            val lease = try {
                leaseManager.issue(SecureMediaLeaseRequest(
                reference = reference,
                    mimeType = exportedMimeType,
                displayName = outputName,
                    timeToLive = 1.hours,
                revocationScope = "media-edit-${localMedia.lifecycleOperationId}",
                deleteObjectOnRevoke = false,
            ))
            } catch (failure: Throwable) {
                fileStore.delete(reference)
                throw failure
            }

            EditedLocalMedia(
                localMedia = localMedia.copy(
                    uri = lease.uri,
                    info = localMedia.info.copy(
                        mimeType = exportedMimeType,
                        fileSize = reference.plaintextLength,
                        filename = outputName,
                        fileExtension = compressFileExtension(exportedMimeType)
                    ),
                ),
                lease = lease,
                reference = reference,
                onClose = { sessionScope.launch { fileStore.delete(reference) } },
            )
        }
    }
}

internal fun exportedMimeTypeFor(sourceMimeType: String?): String {
    return if (sourceMimeType == MimeTypes.Png) {
        MimeTypes.Png
    } else {
        MimeTypes.Jpeg
    }
}

internal fun Bitmap.applyEdits(edits: AttachmentImageEdits): Bitmap {
    val normalizedTurns = (edits.rotationQuarterTurns % 4 + 4) % 4
    if (normalizedTurns == 0 && !edits.isFlippedHorizontally && !edits.isFlippedVertically) {
        return this
    }
    val centerX = width / 2f
    val centerY = height / 2f
    val matrix = Matrix().apply {
        val scaleX = if (edits.isFlippedHorizontally) -1f else 1f
        val scaleY = if (edits.isFlippedVertically) -1f else 1f
        if (scaleX < 0f || scaleY < 0f) {
            postScale(scaleX, scaleY, centerX, centerY)
        }
        if (normalizedTurns != 0) {
            postRotate(normalizedTurns * 90f, centerX, centerY)
        }
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private data class PixelCropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun width() = right - left
    fun height() = bottom - top
}

private fun NormalizedCropRect.toPixelRect(imageWidth: Int, imageHeight: Int): PixelCropRect {
    val leftPx = (left * imageWidth).roundToInt().coerceIn(0, imageWidth - 1)
    val topPx = (top * imageHeight).roundToInt().coerceIn(0, imageHeight - 1)
    val rightPx = (right * imageWidth).roundToInt().coerceIn(leftPx + 1, imageWidth)
    val bottomPx = (bottom * imageHeight).roundToInt().coerceIn(topPx + 1, imageHeight)
    return PixelCropRect(
        left = leftPx,
        top = topPx,
        right = rightPx,
        bottom = bottomPx,
    )
}

private fun compressFormat(mimeType: String) = when (mimeType) {
    MimeTypes.Png -> Bitmap.CompressFormat.PNG
    else -> Bitmap.CompressFormat.JPEG
}

private fun compressFileExtension(mimeType: String) = when (mimeType) {
    MimeTypes.Png -> "png"
    else -> "jpeg"
}

internal suspend fun canEditStillImage(mimeType: String?, decodeMimeType: suspend () -> String?): Boolean {
    if (mimeType.isEditableStillImageMimeType()) return true
    // Videos never need a bitmap probe, including stale or revoked video URIs.
    if (mimeType?.startsWith("video/") == true) return false
    return runCatchingExceptions { decodeMimeType().isEditableStillImageMimeType() }.getOrDefault(false)
}

private fun String?.isEditableStillImageMimeType(): Boolean {
    return this != null &&
        this.isMimeTypeImage() &&
        !this.isMimeTypeAnimatedImage() &&
        this != MimeTypes.Svg
}
