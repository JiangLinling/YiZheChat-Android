/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.androidutils.file.TemporaryUriDeleter
import io.element.android.libraries.androidutils.file.createTmpFile
import io.element.android.libraries.androidutils.file.getFileName
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.androidutils.media.runAndRelease
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.core.extensions.mapFailure
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeAudio
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeImage
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.ProtectedMediaSource
import io.element.android.libraries.matrix.api.media.VideoInfo
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaPreProcessor
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.api.ProtectedVoiceUploadInfo
import io.element.android.libraries.mediaupload.api.allFiles
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@ContributesBinding(AppScope::class)
class AndroidMediaPreProcessor(
    @ApplicationContext private val context: Context,
    private val thumbnailFactory: ThumbnailFactory,
    private val imageCompressor: ImageCompressor,
    private val videoCompressor: VideoCompressor,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val temporaryUriDeleter: TemporaryUriDeleter,
    private val featureFlagService: io.element.android.libraries.featureflag.api.FeatureFlagService,
) : MediaPreProcessor {
    companion object {
        /**
         * Used for calculating `inSampleSize` for bitmaps.
         *
         * *Note*: Ideally, this should result in images of up to (but not included) 1280x1280 being sent. However, images with very different width and height
         * values may surpass this limit. (i.e.: an image of `480x3000px` would have `inSampleSize=1` and be sent as is).
         */
        private const val IMAGE_SCALE_REF_SIZE = 640
        private const val SECURE_MEDIA_AUTHORITY_SUFFIX = "securemedia"
        private const val SECURE_MEDIA_PATH_VERSION = "v1"

        private val notCompressibleImageTypes = listOf(MimeTypes.Gif, MimeTypes.WebP, MimeTypes.Svg)
    }

    private val contentResolver = context.contentResolver

    private val cacheDir = context.cacheDir
    private val baseTmpFileDir = File(cacheDir, "uploads")
    private val protectedTmpFileDir = File(cacheDir, "protected-uploads")

    override suspend fun process(
        uri: Uri,
        mimeType: String,
        deleteOriginal: Boolean,
        mediaOptimizationConfig: MediaOptimizationConfig,
        onProgress: (Float) -> Unit,
    ): Result<MediaUploadInfo> {
        val outputFiles = MediaUploadFileOwnership(emptyList())
        var ownershipTransferred = false
        return try {
            val result = withContext(coroutineDispatchers.computation) {
                runCatchingExceptions {
                    onProgress(0f)
                    val processed = when {
                        // Special case for SVG, since Android can't read its metadata or create a thumbnail, it must be sent as a file
                        mimeType == MimeTypes.Svg -> {
                            processFile(uri, mimeType, onProgress, outputFiles)
                        }
                        mimeType.isMimeTypeImage() -> {
                            val shouldBeCompressed = mediaOptimizationConfig.compressImages && mimeType !in notCompressibleImageTypes
                            processImage(uri, mimeType, shouldBeCompressed, onProgress, outputFiles)
                        }
                        mimeType.isMimeTypeVideo() -> processVideo(
                            uri,
                            mimeType,
                            mediaOptimizationConfig.videoCompressionPreset,
                            mediaOptimizationConfig,
                            onProgress,
                            outputFiles
                        )
                        mimeType.isMimeTypeAudio() -> processAudio(uri, mimeType, onProgress, outputFiles)
                        else -> processFile(uri, mimeType, onProgress, outputFiles)
                    }
                    outputFiles.track(processed.allFiles())
                    val postProcessed = processed.postProcess(uri)
                    outputFiles.track(postProcessed.allFiles())
                    onProgress(1f)
                    postProcessed
                }
            }.mapFailure { MediaPreProcessor.Failure(it) }
            result.onSuccess {
                tryOrNull {
                    if (deleteOriginal) {
                        Timber.w("Deleting original uri $uri")
                        contentResolver.delete(uri, null, null)
                    } else {
                        temporaryUriDeleter.delete(uri)
                    }
                }
                outputFiles.transferOwnership()
                ownershipTransferred = true
            }
            result
        } finally {
            if (!ownershipTransferred) {
                outputFiles.cleanUpUnlessTransferred()
            }
        }
    }

    override suspend fun processProtectedVoice(
        uri: Uri,
        mimeType: String,
        onProgress: (Float) -> Unit,
    ): Result<ProtectedVoiceUploadInfo> = withContext(coroutineDispatchers.computation) {
        runCatchingExceptions {
            require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Protected voice source must be a content URI." }
            require(uri.authority == "${context.packageName}.$SECURE_MEDIA_AUTHORITY_SUFFIX") {
                "Protected voice source must be issued by the in-app secure media provider."
            }
            require(uri.pathSegments.size == 2 && uri.pathSegments.first() == SECURE_MEDIA_PATH_VERSION) {
                "Protected voice source has an invalid lease path."
            }
            onProgress(0.1f)
            val plaintextSize = queryPlaintextSize(uri)
            val audioInfo = MediaMetadataRetriever().runAndRelease {
                setDataSource(context, uri)
                AudioInfo(
                    duration = extractDuration(),
                    size = plaintextSize,
                    mimetype = mimeType,
                )
            }
            onProgress(0.6f)
            check(protectedTmpFileDir.isDirectory || protectedTmpFileDir.mkdirs()) {
                "Unable to create the encrypted voice upload directory."
            }
            protectedTmpFileDir.restrictToOwner()
            ProtectedVoiceUploadInfo(
                source = ProtectedMediaSource(
                    plaintextSize = plaintextSize,
                    encryptedWorkingDirectory = protectedTmpFileDir,
                    inputStreamFactory = {
                        contentResolver.openInputStream(uri)
                            ?: error("Protected voice lease is unavailable.")
                    },
                ),
                audioInfo = audioInfo,
            ).also { onProgress(1f) }
        }
    }.mapFailure { MediaPreProcessor.Failure(it) }

    override fun cleanUp() {
        Timber.d("Cleaning up temporary media files")

        cleanLegacyMediaCacheRoot(cacheDir)
        // Clear temporary files created by this pre-processor in the separate uploads directory
        baseTmpFileDir.listFiles()?.onEach { it.delete() }
        // Protected uploads contain only encrypted transport artifacts. Remove crash leftovers.
        protectedTmpFileDir.listFiles()?.onEach { it.delete() }
    }

    private suspend fun processFile(uri: Uri, mimeType: String, onProgress: (Float) -> Unit, files: MediaUploadFileOwnership): MediaUploadInfo {
        Timber.d("Processing file ${uri.path.orEmpty().hash()}")
        onProgress(0.1f)
        val file = copyToTmpFile(uri, files)
        onProgress(0.9f)
        val info = FileInfo(
            mimetype = mimeType,
            size = file.length(),
            thumbnailInfo = null,
            thumbnailSource = null,
        )
        return MediaUploadInfo.AnyFile(file, info)
    }

    private fun MediaUploadInfo.postProcess(uri: Uri): MediaUploadInfo {
        Timber.d("Finished processing, post-processing ${uri.path.orEmpty().hash()}")
        val name = context.getFileName(uri) ?: return this
        val destination = File(context.cacheDir, name)
        val postProcessedFile = when {
            file.absolutePath == destination.absolutePath -> file
            destination.exists() -> file
            file.renameTo(destination) -> destination
            else -> {
                Timber.w("Unable to rename processed media; retaining its owned temporary file")
                file
            }
        }
        return when (this) {
            is MediaUploadInfo.AnyFile -> copy(file = postProcessedFile)
            is MediaUploadInfo.Audio -> copy(file = postProcessedFile)
            is MediaUploadInfo.Image -> copy(file = postProcessedFile)
            is MediaUploadInfo.Video -> copy(file = postProcessedFile)
            is MediaUploadInfo.VoiceMessage -> copy(file = postProcessedFile)
        }
    }

    private suspend fun processImage(
        uri: Uri,
        mimeType: String,
        shouldBeCompressed: Boolean,
        onProgress: (Float) -> Unit,
        files: MediaUploadFileOwnership,
    ): MediaUploadInfo {
        Timber.d("Processing image ${uri.path.orEmpty().hash()}")
        suspend fun processImageWithCompression(): MediaUploadInfo {
            // Read the orientation metadata from its own stream. Trying to reuse this stream for compression will fail.
            val orientation = contentResolver.openInputStream(uri).use { input ->
                val exifInterface = input?.let { ExifInterface(it) }
                exifInterface?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            } ?: ExifInterface.ORIENTATION_UNDEFINED
            onProgress(0.15f)

            val compressionResult = imageCompressor.compressToTmpFile(
                inputStreamProvider = { contentResolver.openInputStream(uri)!! },
                resizeMode = ResizeMode.Approximate(IMAGE_SCALE_REF_SIZE, IMAGE_SCALE_REF_SIZE),
                mimeType = mimeType,
                orientation = orientation,
                onTemporaryFile = files::track,
            ).getOrThrow()
            onProgress(0.7f)
            val thumbnailResult = thumbnailFactory.createImageThumbnail(
                file = compressionResult.file,
                mimeType = mimeType,
                onTemporaryFile = files::track,
            )
            onProgress(0.9f)
            val imageInfo = compressionResult.toImageInfo(
                mimeType = mimeType,
                thumbnailResult = thumbnailResult
            )
            removeSensitiveImageMetadata(compressionResult.file)
            return MediaUploadInfo.Image(
                file = compressionResult.file,
                imageInfo = imageInfo,
                thumbnailFile = thumbnailResult?.file
            )
        }

        suspend fun processImageWithoutCompression(): MediaUploadInfo {
            val file = copyToTmpFile(uri, files)
            onProgress(0.65f)
            val thumbnailResult = thumbnailFactory.createImageThumbnail(
                file = file,
                mimeType = mimeType,
                onTemporaryFile = files::track,
            )
            onProgress(0.9f)
            val (width, height) = extractOrientedImageDimensions(file)
            val imageInfo = ImageInfo(
                width = width,
                height = height,
                mimetype = mimeType,
                size = file.length(),
                thumbnailInfo = thumbnailResult?.info,
                thumbnailSource = null,
                blurhash = thumbnailResult?.blurhash,
            )
            removeSensitiveImageMetadata(file)
            return MediaUploadInfo.Image(
                file = file,
                imageInfo = imageInfo,
                thumbnailFile = thumbnailResult?.file
            )
        }

        return if (shouldBeCompressed) {
            processImageWithCompression()
        } else {
            processImageWithoutCompression()
        }
    }

    private suspend fun processVideo(
        uri: Uri,
        mimeType: String?,
        videoCompressionPreset: VideoCompressionPreset,
        edits: MediaOptimizationConfig,
        onProgress: (Float) -> Unit,
        outputFiles: MediaUploadFileOwnership,
    ): MediaUploadInfo {
        Timber.d("Processing video ${uri.path.orEmpty().hash()}")
        onProgress(0.05f)
        val resultFile = runCatchingExceptions {
            videoCompressor.compress(uri, videoCompressionPreset, edits)
                .onEach {
                    if (it is VideoTranscodingEvent.Progress) {
                        Timber.d("Video compression progress: ${it.value}%")
                        onProgress(0.05f + it.value.coerceIn(0f, 100f) / 100f * 0.8f)
                    }
                }
                .filterIsInstance<VideoTranscodingEvent.Completed>()
                .map { completion ->
                    completion.claimFile().also { file ->
                        Timber.d("Video compression completed: ${file.path}")
                    }
                }
                .first()
        }
            .onFailure {
                Timber.e(it, "Failed to compress video: $uri")
            }
            .getOrThrow()

        val files = VideoPostProcessFiles(resultFile)
        try {
            if (featureFlagService.isFeatureEnabled(io.element.android.libraries.featureflag.api.FeatureFlags.ProgressiveProtectedVideoPlayback)) {
                withContext(coroutineDispatchers.io) {
                    val operationContext = currentCoroutineContext()
                    ensureStreamableMp4(resultFile) { operationContext.ensureActive() }
                }
            }
            val thumbnailInfo = thumbnailFactory.createVideoThumbnail(resultFile, outputFiles::track)
            files.registerThumbnail(thumbnailInfo?.file)
            onProgress(0.93f)
            val videoInfo = extractVideoMetadata(resultFile, mimeType, thumbnailInfo)
            return MediaUploadInfo.Video(
                file = resultFile,
                videoInfo = videoInfo,
                thumbnailFile = thumbnailInfo?.file
            ).also {
                files.transferOwnership()
            }
        } finally {
            files.cleanUpUnlessTransferred()
        }
    }

    private suspend fun processAudio(uri: Uri, mimeType: String?, onProgress: (Float) -> Unit, files: MediaUploadFileOwnership): MediaUploadInfo {
        Timber.d("Processing audio ${uri.path.orEmpty().hash()}")
        onProgress(0.1f)
        val file = copyToTmpFile(uri, files)
        onProgress(0.85f)
        return MediaMetadataRetriever().runAndRelease {
            setDataSource(context, Uri.fromFile(file))
            val info = AudioInfo(
                duration = extractDuration(),
                size = file.length(),
                mimetype = mimeType,
            )

            MediaUploadInfo.Audio(file, info)
        }
    }

    private fun removeSensitiveImageMetadata(file: File) {
        // Remove GPS info, user comments and subject location tags
        ExifInterface(file).apply {
            // See ExifInterface.TAG_GPS_INFO_IFD_POINTER
            setAttribute("GPSInfoIFDPointer", null)
            setAttribute(ExifInterface.TAG_USER_COMMENT, null)
            setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, null)

            setAttribute(ExifInterface.TAG_GPS_VERSION_ID, null)
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null)
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
            setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, null)
            setAttribute(ExifInterface.TAG_GPS_DATESTAMP, null)
            setAttribute(ExifInterface.TAG_GPS_SATELLITES, null)
            setAttribute(ExifInterface.TAG_GPS_STATUS, null)
            setAttribute(ExifInterface.TAG_GPS_MEASURE_MODE, null)
            setAttribute(ExifInterface.TAG_GPS_DOP, null)
            setAttribute(ExifInterface.TAG_GPS_SPEED_REF, null)
            setAttribute(ExifInterface.TAG_GPS_SPEED, null)
            setAttribute(ExifInterface.TAG_GPS_TRACK_REF, null)
            setAttribute(ExifInterface.TAG_GPS_TRACK, null)
            setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, null)
            setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, null)
            setAttribute(ExifInterface.TAG_GPS_MAP_DATUM, null)
            setAttribute(ExifInterface.TAG_GPS_DEST_BEARING_REF, null)
            setAttribute(ExifInterface.TAG_GPS_DEST_BEARING, null)
            setAttribute(ExifInterface.TAG_GPS_DEST_DISTANCE_REF, null)
            setAttribute(ExifInterface.TAG_GPS_DEST_DISTANCE, null)
            setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, null)
            setAttribute(ExifInterface.TAG_GPS_AREA_INFORMATION, null)
            setAttribute(ExifInterface.TAG_GPS_DIFFERENTIAL, null)
            setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, null)
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null)
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null)
            setAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE, null)
            setAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, null)
            tryOrNull { saveAttributes() }
        }
    }

    private suspend fun createTmpFileWithInput(inputStream: InputStream, files: MediaUploadFileOwnership): File? {
        return withContext(coroutineDispatchers.io) {
            tryOrNull {
                if (!baseTmpFileDir.exists()) {
                    baseTmpFileDir.mkdirs()
                }
                val tmpFile = context.createTmpFile(baseTmpFileDir)
                files.track(tmpFile)
                tmpFile.outputStream().use { inputStream.copyTo(it) }
                tmpFile
            }
        }
    }

    private fun extractVideoMetadata(file: File, mimeType: String?, thumbnailResult: ThumbnailResult?): VideoInfo =
        MediaMetadataRetriever().runAndRelease {
            setDataSource(context, Uri.fromFile(file))

            val rotation = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            val rawWidth = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLong() ?: 0L
            val rawHeight = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLong() ?: 0L

            val (width, height) = if (rotation == 90 || rotation == 270) rawHeight to rawWidth else rawWidth to rawHeight

            VideoInfo(
                duration = extractDuration(),
                width = width,
                height = height,
                mimetype = mimeType,
                size = file.length(),
                thumbnailInfo = thumbnailResult?.info,
                // Will be computed by the rust sdk
                thumbnailSource = null,
                blurhash = thumbnailResult?.blurhash,
            )
        }

    private suspend fun copyToTmpFile(uri: Uri, files: MediaUploadFileOwnership): File {
        return contentResolver.openInputStream(uri)?.use { createTmpFileWithInput(it, files) }
            ?: error("Could not copy the contents of $uri to a temporary file")
    }

    private fun queryPlaintextSize(uri: Uri): Long {
        val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
        }
        return requireNotNull(size).also { require(it >= 0L) { "Protected voice source has an invalid size." } }
    }

    private fun File.restrictToOwner() {
        setReadable(false, false)
        setWritable(false, false)
        setExecutable(false, false)
        setReadable(true, true)
        setWritable(true, true)
        setExecutable(true, true)
    }

    private fun extractOrientedImageDimensions(file: File): Pair<Long, Long> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)

        val rawWidth = options.outWidth.toLong()
        val rawHeight = options.outHeight.toLong()
        val orientation = tryOrNull {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        } ?: ExifInterface.ORIENTATION_UNDEFINED

        return orientedImageDimensions(
            rawWidth = rawWidth,
            rawHeight = rawHeight,
            orientation = orientation,
        )
    }
}

internal fun cleanLegacyMediaCacheRoot(cacheDir: File) {
    cacheDir.listFiles()?.onEach { file ->
        if (!file.isFile || isLegacyRootCameraCaptureFileName(file.name)) return@onEach
        val nameWithoutExtension = file.nameWithoutExtension
        val uuidPrefix = nameWithoutExtension.takeIf { it.length > 36 }?.substring(0, 36) ?: return@onEach
        val isUuidPrefixed = tryOrNull { UUID.fromString(uuidPrefix) } != null
        if (isUuidPrefixed && file.extension.isNotEmpty()) {
            file.safeDelete()
        }
    }
}

internal fun isLegacyRootCameraCaptureFileName(fileName: String): Boolean {
    val suffix = "-video.mp4"
    if (!fileName.endsWith(suffix)) return false
    val id = fileName.removeSuffix(suffix)
    return tryOrNull { UUID.fromString(id).toString() == id } == true
}

internal class MediaUploadFileOwnership(
    files: Iterable<File>,
) {
    private val ownedFiles = files.toMutableSet()
    private var ownershipTransferred = false

    fun track(file: File) {
        ownedFiles.add(file)
    }

    fun track(files: Iterable<File>) {
        ownedFiles.addAll(files)
    }

    fun transferOwnership() {
        ownershipTransferred = true
    }

    fun cleanUpUnlessTransferred() {
        if (!ownershipTransferred) {
            ownedFiles.forEach { it.safeDelete() }
        }
    }
}

internal class VideoPostProcessFiles(
    private val videoFile: File,
) {
    private var thumbnailFile: File? = null
    private var ownershipTransferred = false

    fun registerThumbnail(file: File?) {
        thumbnailFile = file
    }

    fun transferOwnership() {
        ownershipTransferred = true
    }

    fun cleanUpUnlessTransferred() {
        if (!ownershipTransferred) {
            videoFile.safeDelete()
            thumbnailFile?.safeDelete()
        }
    }
}

private fun ImageCompressionResult.toImageInfo(mimeType: String, thumbnailResult: ThumbnailResult?) = ImageInfo(
    width = width.toLong(),
    height = height.toLong(),
    mimetype = mimeType,
    size = size,
    thumbnailInfo = thumbnailResult?.info,
    // Will be computed by the rust sdk
    thumbnailSource = null,
    blurhash = thumbnailResult?.blurhash,
)

private fun MediaMetadataRetriever.extractDuration(): Duration {
    val durationInMs = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    return durationInMs.milliseconds
}

internal fun orientedImageDimensions(rawWidth: Long, rawHeight: Long, orientation: Int): Pair<Long, Long> {
    return if (orientation.rotatesRightAngle()) {
        rawHeight to rawWidth
    } else {
        rawWidth to rawHeight
    }
}

private fun Int.rotatesRightAngle(): Boolean {
    return this == ExifInterface.ORIENTATION_ROTATE_90 ||
        this == ExifInterface.ORIENTATION_ROTATE_270 ||
        this == ExifInterface.ORIENTATION_TRANSPOSE ||
        this == ExifInterface.ORIENTATION_TRANSVERSE
}
