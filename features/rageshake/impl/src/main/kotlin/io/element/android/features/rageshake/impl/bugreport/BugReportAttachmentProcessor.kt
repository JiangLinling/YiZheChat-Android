/*
 * Copyright (c) 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.rageshake.impl.bugreport

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.rageshake.api.reporter.BugReportAttachment
import io.element.android.libraries.androidutils.file.getFileName
import io.element.android.libraries.androidutils.file.getMimeType
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeImage
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaPreProcessor
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.api.allFiles
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal const val MAX_BUG_REPORT_MEDIA_COUNT = 6
internal const val MAX_BUG_REPORT_MEDIA_ITEM_BYTES = 15 * 1024 * 1024L
internal const val MAX_BUG_REPORT_MEDIA_TOTAL_BYTES = 20 * 1024 * 1024L

enum class BugReportAttachmentKind {
    Image,
    Video,
}

data class ProcessedBugReportAttachment(
    val attachment: BugReportAttachment,
    val displayName: String,
    val kind: BugReportAttachmentKind,
)

sealed class BugReportAttachmentFailure : Exception() {
    data object UnsupportedFormat : BugReportAttachmentFailure()
    data object CompressionFailed : BugReportAttachmentFailure()
    data object CompressedFileTooLarge : BugReportAttachmentFailure()
}

interface BugReportAttachmentProcessor {
    suspend fun process(
        uri: Uri,
        mimeType: String?,
        fallbackName: String? = null,
    ): Result<ProcessedBugReportAttachment>

    fun release(attachment: ProcessedBugReportAttachment)
}

@ContributesBinding(AppScope::class)
class DefaultBugReportAttachmentProcessor(
    @ApplicationContext private val context: Context,
    private val mediaPreProcessor: MediaPreProcessor,
    private val coroutineDispatchers: CoroutineDispatchers,
) : BugReportAttachmentProcessor {
    private val attachmentDirectory = File(context.cacheDir, "bug-report-attachments")
    private var attachmentDirectoryPrepared = false

    override suspend fun process(
        uri: Uri,
        mimeType: String?,
        fallbackName: String?,
    ): Result<ProcessedBugReportAttachment> {
        val resolvedMimeType = mimeType ?: context.getMimeType(uri)
        val kind = when {
            resolvedMimeType in unsupportedImageMimeTypes -> return Result.failure(BugReportAttachmentFailure.UnsupportedFormat)
            resolvedMimeType.isMimeTypeImage() -> BugReportAttachmentKind.Image
            resolvedMimeType.isMimeTypeVideo() -> BugReportAttachmentKind.Video
            else -> return Result.failure(BugReportAttachmentFailure.UnsupportedFormat)
        }
        val processingResult = try {
            mediaPreProcessor.process(
                uri = uri,
                mimeType = resolvedMimeType.orEmpty(),
                deleteOriginal = false,
                mediaOptimizationConfig = MediaOptimizationConfig(
                    compressImages = true,
                    videoCompressionPreset = VideoCompressionPreset.STANDARD,
                ),
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return Result.failure(BugReportAttachmentFailure.CompressionFailed)
        }
        val processed = processingResult.getOrElse {
            return Result.failure(BugReportAttachmentFailure.CompressionFailed)
        }
        val isExpectedCompressedType = when (kind) {
            BugReportAttachmentKind.Image -> processed is MediaUploadInfo.Image
            BugReportAttachmentKind.Video -> processed is MediaUploadInfo.Video
        }
        if (!isExpectedCompressedType) {
            processed.allFiles().forEach(File::safeDelete)
            return Result.failure(BugReportAttachmentFailure.CompressionFailed)
        }

        val outputMimeType = when (kind) {
            BugReportAttachmentKind.Image -> if (processed.file.extension.equals("png", ignoreCase = true)) MimeTypes.Png else MimeTypes.Jpeg
            BugReportAttachmentKind.Video -> MimeTypes.Mp4
        }
        val outputExtension = if (outputMimeType == MimeTypes.Png) "png" else if (kind == BugReportAttachmentKind.Image) "jpg" else "mp4"
        val ownedFile = File(attachmentDirectory, "${UUID.randomUUID()}.$outputExtension")
        try {
            withContext(coroutineDispatchers.io) {
                prepareAttachmentDirectory()
                processed.file.copyTo(ownedFile, overwrite = true)
            }
        } catch (failure: CancellationException) {
            processed.allFiles().forEach(File::safeDelete)
            ownedFile.safeDelete()
            throw failure
        } catch (_: Exception) {
            processed.allFiles().forEach(File::safeDelete)
            ownedFile.safeDelete()
            return Result.failure(BugReportAttachmentFailure.CompressionFailed)
        }
        processed.allFiles().forEach(File::safeDelete)
        if (ownedFile.length() <= 0L || ownedFile.length() > MAX_BUG_REPORT_MEDIA_ITEM_BYTES) {
            ownedFile.safeDelete()
            return Result.failure(BugReportAttachmentFailure.CompressedFileTooLarge)
        }

        val displayName = context.getFileName(uri).orEmpty().ifBlank { fallbackName ?: ownedFile.name }
        val uploadName = when (kind) {
            BugReportAttachmentKind.Image -> "feedback-image-${UUID.randomUUID()}.$outputExtension"
            BugReportAttachmentKind.Video -> "feedback-video-${UUID.randomUUID()}.$outputExtension"
        }
        return Result.success(
            ProcessedBugReportAttachment(
                attachment = BugReportAttachment(
                    file = ownedFile,
                    fileName = uploadName,
                    mimeType = outputMimeType,
                ),
                displayName = displayName,
                kind = kind,
            )
        )
    }

    override fun release(attachment: ProcessedBugReportAttachment) {
        attachment.attachment.file.safeDelete()
    }

    @Synchronized
    private fun prepareAttachmentDirectory() {
        if (!attachmentDirectoryPrepared) {
            attachmentDirectory.listFiles()?.forEach(File::safeDelete)
            attachmentDirectoryPrepared = true
        }
        attachmentDirectory.mkdirs()
    }

    private companion object {
        val unsupportedImageMimeTypes = setOf(MimeTypes.Gif, MimeTypes.WebP, MimeTypes.Svg)
    }
}
