/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediapickers.impl

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.FileProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.mediapickers.api.ComposePickerLauncher
import io.element.android.libraries.mediapickers.api.MediaPickBatchResult
import io.element.android.libraries.mediapickers.api.MediaPickFailureReason
import io.element.android.libraries.mediapickers.api.MediaPickItemResult
import io.element.android.libraries.mediapickers.api.MediaPickOperation
import io.element.android.libraries.mediapickers.api.MediaPickOperationLauncher
import io.element.android.libraries.mediapickers.api.MediaPickSource
import io.element.android.libraries.mediapickers.api.NoOpPickerLauncher
import io.element.android.libraries.mediapickers.api.PickedMedia
import io.element.android.libraries.mediapickers.api.PickerLauncher
import io.element.android.libraries.mediapickers.api.PickerProvider
import io.element.android.libraries.mediapickers.api.PickerType
import io.element.android.libraries.mediapickers.api.RecentImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLeaseService
import io.element.android.libraries.mediaviewer.api.local.LocalMediaFactory
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderer
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@ContributesBinding(AppScope::class)
class DefaultPickerProvider(
    @ApplicationContext private val context: Context,
    private val operationJournal: MediaPickOperationJournal,
    private val attachmentCoordinator: AppOperationalStateCoordinator,
    private val attachmentSources: AttachmentSourceStore,
    private val localMediaFactory: LocalMediaFactory,
    private val localMediaRenderer: LocalMediaRenderer,
) : PickerProvider {
    override suspend fun recentImages(nowEpochMillis: Long): List<RecentImage> = readRecentImages(context, nowEpochMillis)

    override suspend fun galleryImages(nowEpochMillis: Long, before: RecentImage?, pageSize: Int): List<RecentImage> =
        readGalleryImages(context, nowEpochMillis, before, pageSize)

    override suspend fun selectRecentImages(operation: MediaPickOperation, uris: List<Uri>): MediaPickBatchResult = withContext(Dispatchers.IO) {
        if (operation.source != MediaPickSource.GALLERY) return@withContext operation.failure(MediaPickFailureReason.OPERATION_CONTEXT_LOST)
        if (operation.isExpired()) return@withContext operation.failure(MediaPickFailureReason.OPERATION_EXPIRED)
        when (operationJournal.persist(operation)) {
            MediaPickJournalWriteResult.ACTIVE_OPERATION_EXISTS -> operation.failure(MediaPickFailureReason.OPERATION_IN_PROGRESS)
            MediaPickJournalWriteResult.FAILED -> operation.failure(MediaPickFailureReason.PERSISTENCE_FAILED)
            MediaPickJournalWriteResult.STORED -> mapGalleryResult(operation, uris.take(10))
        }
    }

    @Composable
    override fun registerOperationGalleryPicker(
        onResult: (MediaPickBatchResult) -> Unit,
    ): MediaPickOperationLauncher = registerOperationGalleryPicker(null, onResult)

    @Composable
    override fun registerOperationGalleryPicker(
        preselected: List<PickedMedia>?,
        onResult: (MediaPickBatchResult) -> Unit,
    ): MediaPickOperationLauncher {
        var pendingOperation by rememberSaveable(stateSaver = mediaPickOperationSaver) {
            mutableStateOf<MediaPickOperation?>(null)
        }
        if (LocalInspectionMode.current) {
            return remember {
                MediaPickOperationLauncher { operation ->
                    onResult(operation.failure(MediaPickFailureReason.CANCELLED))
                }
            }
        }
        val managedLauncher = rememberGalleryPicker({ pickedItems ->
            val operation = pendingOperation ?: operationJournal.read(MediaPickSource.GALLERY)
            pendingOperation = null
            val checked = mapGalleryResult(operation, pickedItems.map { it.uri })
            onResult(checked.copy(items = checked.items.map { result ->
                if (result is MediaPickItemResult.Ready) {
                    val choice = pickedItems.firstOrNull { it.uri == result.media.uri }
                    result.copy(media = result.media.copy(
                        originalPhotos = choice?.originalPhotos == true,
                        sendImmediately = choice?.sendImmediately == true,
                        initiallySelected = choice?.initiallySelected != false,
                        focused = choice?.focused == true,
                        openEditor = choice?.openEditor == true,
                    ))
                } else {
                    result
                }
            }))
        }, preselected = preselected)
        return remember(managedLauncher) {
            MediaPickOperationLauncher { operation ->
                if (operation.source != MediaPickSource.GALLERY) {
                    onResult(operation.failure(MediaPickFailureReason.OPERATION_CONTEXT_LOST))
                } else if (operation.isExpired()) {
                    onResult(operation.failure(MediaPickFailureReason.OPERATION_EXPIRED))
                } else {
                    when (operationJournal.persist(operation)) {
                        MediaPickJournalWriteResult.ACTIVE_OPERATION_EXISTS -> {
                            onResult(operation.failure(MediaPickFailureReason.OPERATION_IN_PROGRESS))
                        }
                        MediaPickJournalWriteResult.FAILED -> {
                            onResult(operation.failure(MediaPickFailureReason.PERSISTENCE_FAILED))
                        }
                        MediaPickJournalWriteResult.STORED -> {
                            pendingOperation = operation
                            try {
                                managedLauncher.launch()
                            } catch (_: Exception) {
                                pendingOperation = null
                                operationJournal.clear(operation.operationId)
                                onResult(operation.failure(MediaPickFailureReason.LAUNCH_FAILED))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun registerOperationCameraPhotoPicker(
        onResult: (MediaPickBatchResult) -> Unit,
    ): MediaPickOperationLauncher {
        var pendingOperation by rememberSaveable(stateSaver = mediaPickOperationSaver) {
            mutableStateOf<MediaPickOperation?>(null)
        }
        if (LocalInspectionMode.current) {
            return remember {
                MediaPickOperationLauncher { operation ->
                    onResult(operation.failure(MediaPickFailureReason.CANCELLED))
                }
            }
        }
        val managedLauncher = rememberLauncherForActivityResult(PickerType.Camera.Photo(Uri.EMPTY).getContract()) { success ->
            val operation = pendingOperation ?: operationJournal.read(MediaPickSource.CAMERA_PHOTO)
            pendingOperation = null
            onResult(mapCameraResult(operation, success))
        }
        return remember(managedLauncher) {
            MediaPickOperationLauncher { operation ->
                if (operation.source != MediaPickSource.CAMERA_PHOTO) {
                    onResult(operation.failure(MediaPickFailureReason.OPERATION_CONTEXT_LOST))
                } else if (operation.isExpired()) {
                    onResult(operation.failure(MediaPickFailureReason.OPERATION_EXPIRED))
                } else {
                    when (operationJournal.persist(operation)) {
                        MediaPickJournalWriteResult.ACTIVE_OPERATION_EXISTS -> {
                            onResult(operation.failure(MediaPickFailureReason.OPERATION_IN_PROGRESS))
                        }
                        MediaPickJournalWriteResult.FAILED -> {
                            onResult(operation.failure(MediaPickFailureReason.PERSISTENCE_FAILED))
                        }
                        MediaPickJournalWriteResult.STORED -> {
                            try {
                                val file = cameraFile(operation)
                                file.delete()
                                file.createNewFile()
                                pendingOperation = operation
                                managedLauncher.launch(getTemporaryUri(file))
                            } catch (_: Exception) {
                                pendingOperation = null
                                cameraFile(operation).delete()
                                operationJournal.clear(operation.operationId)
                                onResult(operation.failure(MediaPickFailureReason.LAUNCH_FAILED))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun registerAttachmentPicker(
        accountId: String,
        roomIdHash: String?,
        previewLeaseService: LocalAttachmentPreviewLeaseService?,
        onResult: (List<PickedMedia>) -> Unit,
    ): PickerLauncher<String, List<Uri>> = rememberAttachmentPicker(
        accountId,
        attachmentSources,
        localMediaFactory,
        localMediaRenderer,
        attachmentCoordinator,
        roomIdHash,
        previewLeaseService,
        onResult,
    )

    @Composable
    override fun registerGalleryPicker(preselected: List<PickedMedia>, onResult: (List<PickedMedia>) -> Unit):
        PickerLauncher<PickVisualMediaRequest, List<Uri>> = rememberGalleryPicker(onResult, preselected)

    override fun completeOperation(operationId: String): Boolean = operationJournal.clear(operationId)

    override fun completeCancelledOperation(operation: MediaPickOperation) =
        operationJournal.completeCancellation(operation, System.currentTimeMillis())

    override fun pendingCancellations() = operationJournal.pendingCancellations()

    override fun acknowledgeCancellation(cancellation: io.element.android.libraries.mediapickers.api.MediaPickCancellation) =
        operationJournal.acknowledgeCancellation(cancellation)

    /**
     * Remembers and returns a [PickerLauncher] for a certain media/file [type].
     */
    @Composable
    internal fun <Input, Output> rememberPickerLauncher(
        type: PickerType<Input, Output>,
        onResult: (Output) -> Unit,
    ): PickerLauncher<Input, Output> {
        return if (LocalInspectionMode.current) {
            NoOpPickerLauncher { }
        } else {
            val contract = type.getContract()
            val managedLauncher = rememberLauncherForActivityResult(contract = contract, onResult = onResult)
            remember(type) { ComposePickerLauncher(managedLauncher, type.getDefaultRequest()) }
        }
    }

    /**
     * Remembers and returns a [PickerLauncher] for a gallery picture.
     * [onResult] will be called with either the selected file's [Uri] or `null` if nothing was selected.
     */
    @Composable
    override fun registerGalleryImagePicker(onResult: (Uri?) -> Unit): PickerLauncher<PickVisualMediaRequest, Uri?> {
        // Tests and UI preview can't handle Contexts, so we might as well disable the whole picker
        return if (LocalInspectionMode.current) {
            NoOpPickerLauncher { onResult(null) }
        } else {
            rememberPickerLauncher(type = PickerType.Image) { uri -> onResult(uri) }
        }
    }

    /**
     * Remembers and returns a [PickerLauncher] for a gallery item, either a picture or a video.
     * [onResult] will be called with either the selected file's [Uri] or `null` if nothing was selected.
     */
    @Composable
    override fun registerGalleryPicker(
        onResult: (items: List<PickedMedia>) -> Unit
    ): PickerLauncher<PickVisualMediaRequest, List<Uri>> {
        // Tests and UI preview can't handle Contexts, so we might as well disable the whole picker
        return if (LocalInspectionMode.current) {
            NoOpPickerLauncher { onResult(emptyList()) }
        } else {
            rememberGalleryPicker(onResult)
        }
    }

    /**
     * Remembers and returns a [PickerLauncher] for a file of a certain [mimeType] (any type of file, by default).
     * [onResult] will be called with either the selected file's [Uri] or `null` if nothing was selected.
     */
    @Composable
    override fun registerFilePicker(
        mimeType: String,
        onResult: (uri: Uri?, mimeType: String?) -> Unit,
    ): PickerLauncher<String, Uri?> {
        // Tests and UI preview can't handle Context or FileProviders, so we might as well disable the whole picker
        return if (LocalInspectionMode.current) {
            NoOpPickerLauncher { onResult(null, null) }
        } else {
            rememberPickerLauncher(type = PickerType.File(mimeType)) { uri ->
                val pickedMimeType = uri?.let { context.contentResolver.getType(it) }
                onResult(uri, pickedMimeType)
            }
        }
    }

    /**
     * Remembers and returns a [PickerLauncher] for taking a photo with a camera app.
     * @param [onResult] will be called with either the photo's [Uri] or `null` if nothing was selected.
     */
    @Composable
    override fun registerCameraPhotoPicker(onResult: (Uri?) -> Unit): PickerLauncher<Uri, Boolean> {
        // Tests and UI preview can't handle Context or FileProviders, so we might as well disable the whole picker
        return if (LocalInspectionMode.current) {
            NoOpPickerLauncher { onResult(null) }
        } else {
            val tmpFile = remember { getTemporaryFile("photo.jpg") }
            val tmpFileUri = remember(tmpFile) { getTemporaryUri(tmpFile) }
            rememberPickerLauncher(type = PickerType.Camera.Photo(tmpFileUri)) { success ->
                // Execute callback
                onResult(if (success) tmpFileUri else null)
            }
        }
    }

    /**
     * Remembers and returns a [PickerLauncher] for recording a video with a camera app.
     * @param [onResult] will be called with either the video's [Uri] or `null` if nothing was selected.
     */
    @Composable
    override fun registerCameraVideoPicker(onResult: (Uri?) -> Unit): PickerLauncher<Uri, Boolean> {
        // Tests and UI preview can't handle Context or FileProviders, so we might as well disable the whole picker
        return if (LocalInspectionMode.current) {
            NoOpPickerLauncher { onResult(null) }
        } else {
            val captureDirectory = remember { File(context.cacheDir, VIDEO_CAPTURE_DIRECTORY) }
            val fileOwner = rememberSaveable(
                saver = VideoCaptureFileOwner.saver(captureDirectory),
            ) {
                VideoCaptureFileOwner(captureDirectory)
            }
            val managedLauncher = rememberLauncherForActivityResult(PickerType.Camera.Video(Uri.EMPTY).getContract()) { success ->
                onResult(fileOwner.complete(success)?.let(::getTemporaryUri))
            }
            remember(managedLauncher, fileOwner) {
                object : PickerLauncher<Uri, Boolean> {
                    override fun launch() = launch(Uri.EMPTY)

                    override fun launch(customInput: Uri) {
                        val file = runCatching { fileOwner.prepare() }
                            .getOrElse {
                                onResult(null)
                                return
                            }
                        try {
                            managedLauncher.launch(getTemporaryUri(file))
                        } catch (_: Exception) {
                            fileOwner.discard()
                            onResult(null)
                        }
                    }
                }
            }
        }
    }

    private fun getTemporaryFile(
        filename: String,
    ): File {
        return File(context.cacheDir, filename)
    }

    private fun getTemporaryUri(
        file: File,
    ): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    private fun mapGalleryResult(operation: MediaPickOperation?, uris: List<Uri>): MediaPickBatchResult {
        if (operation == null) {
            return MediaPickBatchResult(null, listOf(MediaPickItemResult.Failed(null, MediaPickFailureReason.OPERATION_CONTEXT_LOST)))
        }
        if (operation.isExpired()) return operation.failure(MediaPickFailureReason.OPERATION_EXPIRED)
        if (uris.isEmpty()) return operation.failure(MediaPickFailureReason.CANCELLED)
        return MediaPickBatchResult(
            operation = operation,
            items = uris.mapIndexed { index, uri -> inspectPickedUri(operation, index, uri) },
        )
    }

    private fun mapCameraResult(operation: MediaPickOperation?, success: Boolean): MediaPickBatchResult {
        if (operation == null) {
            return MediaPickBatchResult(null, listOf(MediaPickItemResult.Failed(null, MediaPickFailureReason.OPERATION_CONTEXT_LOST)))
        }
        val file = cameraFile(operation)
        if (!success) {
            return operation.failure(clearCancelledCameraCapture(file))
        }
        if (operation.isExpired()) {
            file.delete()
            return operation.failure(MediaPickFailureReason.OPERATION_EXPIRED)
        }
        return MediaPickBatchResult(
            operation = operation,
            items = listOf(inspectPickedUri(operation, 0, getTemporaryUri(file), expectedMimeType = "image/jpeg")),
        )
    }

    private fun inspectPickedUri(
        operation: MediaPickOperation,
        index: Int,
        uri: Uri,
        expectedMimeType: String? = null,
    ): MediaPickItemResult {
        return try {
            val readable = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
            if (!readable) {
                MediaPickItemResult.Failed(index, MediaPickFailureReason.SOURCE_ACCESS_LOST)
            } else {
                val mimeType = expectedMimeType ?: context.contentResolver.getType(uri)
                if (mimeType.isNullOrBlank()) {
                    MediaPickItemResult.Failed(index, MediaPickFailureReason.UNSUPPORTED_MEDIA)
                } else {
                    MediaPickItemResult.Ready(
                        PickedMedia(
                            uri = uri,
                            mimeType = mimeType,
                            operationId = operation.itemOperationId(index),
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            MediaPickItemResult.Failed(index, MediaPickFailureReason.SOURCE_ACCESS_LOST)
        } catch (_: Exception) {
            MediaPickItemResult.Failed(index, MediaPickFailureReason.INSPECTION_FAILED)
        }
    }

    private fun cameraFile(operation: MediaPickOperation): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(operation.operationId.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return getTemporaryFile("photo-${digest.take(24)}.jpg")
    }

    private fun MediaPickOperation.isExpired(): Boolean = System.currentTimeMillis() >= deadlineEpochMillis

    private fun MediaPickOperation.failure(reason: MediaPickFailureReason) = MediaPickBatchResult(
        operation = this,
        items = listOf(MediaPickItemResult.Failed(null, reason)),
    )
}

/** Cancellation is settled only after the operation-owned temporary file is absent. */
internal fun clearCancelledCameraCapture(file: File): MediaPickFailureReason =
    if (!file.exists() || file.delete()) MediaPickFailureReason.CANCELLED else MediaPickFailureReason.TEMPORARY_FILE_CLEANUP_FAILED

// Keep the per-item identity stable across retries while respecting the lifecycle wire contract,
// including its 256-character cap when the parent operation already uses the maximum length.
internal fun MediaPickOperation.itemOperationId(index: Int): String {
    require(index >= 0)
    require(operationId.matches(Regex("^[A-Za-z0-9_-]+$")))
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$operationId:$index".encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "media-$digest"
}

private val mediaPickOperationSaver = listSaver<MediaPickOperation?, Any>(
    save = { operation ->
        operation?.let {
            listOf(it.operationId, it.source.name, it.roomIdHash, it.createdAtEpochMillis, it.deadlineEpochMillis)
        }.orEmpty()
    },
    restore = { values ->
        values.takeIf { it.size == 5 }?.let {
            MediaPickOperation(
                operationId = it[0] as String,
                source = MediaPickSource.valueOf(it[1] as String),
                roomIdHash = it[2] as String,
                createdAtEpochMillis = it[3] as Long,
                deadlineEpochMillis = it[4] as Long,
            )
        }
    },
)

private const val VIDEO_CAPTURE_DIRECTORY = "video-captures"

internal class VideoCaptureFileOwner(
    private val captureDirectory: File,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private var pendingFile: File? = null,
) {
    fun prepare(): File {
        check(pendingFile == null) { "A video capture is already pending." }
        check(captureDirectory.isDirectory || captureDirectory.mkdirs()) {
            "Unable to create the video capture directory."
        }
        val file = File(captureDirectory, "${idFactory()}-video.mp4")
        check(file.createNewFile()) { "Unable to create the video capture destination." }
        pendingFile = file
        return file
    }

    fun complete(success: Boolean): File? {
        val file = pendingFile
        pendingFile = null
        return if (success && file?.isFile == true && file.length() > 0L) {
            file
        } else {
            file?.delete()
            null
        }
    }

    fun discard() {
        pendingFile?.delete()
        pendingFile = null
    }

    internal fun snapshotFileName(): String? = pendingFile?.name

    companion object {
        private const val FILE_SUFFIX = "-video.mp4"

        fun saver(captureDirectory: File): Saver<VideoCaptureFileOwner, String> = Saver(
            save = { owner -> owner.snapshotFileName().orEmpty() },
            restore = { fileName -> restore(captureDirectory, fileName) },
        )

        internal fun restore(
            captureDirectory: File,
            fileName: String,
            idFactory: () -> String = { UUID.randomUUID().toString() },
        ): VideoCaptureFileOwner {
            val restoredFile = fileName
                .takeIf(::isOwnedFileName)
                ?.let { name -> File(captureDirectory, name) }
                ?.takeIf(File::isFile)
            return VideoCaptureFileOwner(captureDirectory, idFactory, restoredFile)
        }

        private fun isOwnedFileName(fileName: String): Boolean {
            if (!fileName.endsWith(FILE_SUFFIX)) return false
            val id = fileName.removeSuffix(FILE_SUFFIX)
            return runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        }
    }
}
