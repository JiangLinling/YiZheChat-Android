/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local

import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.androidutils.file.getMimeType
import io.element.android.libraries.androidutils.filesize.FakeFileSizeFormatter
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_NAME
import io.element.android.libraries.matrix.test.media.FakeMediaFile
import io.element.android.libraries.mediaviewer.api.MediaInfo
import io.element.android.libraries.mediaviewer.api.anImageMediaInfo
import io.element.android.libraries.mediaviewer.impl.local.image.ImageValidationException
import io.element.android.libraries.mediaviewer.test.util.FileExtensionExtractorWithoutValidation
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class AndroidLocalMediaFactoryTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `test AndroidLocalMediaFactory`() {
        val sut = createAndroidLocalMediaFactory()
        val imageFile = createImageFile("media-file.jpg", 1, 1, Bitmap.CompressFormat.JPEG)
        val result = sut.createFromMediaFile(
            mediaFile = FakeMediaFile(imageFile.absolutePath),
            mediaInfo = anImageMediaInfo(
                senderId = A_USER_ID,
                senderName = A_USER_NAME,
                dateSent = "12:34",
                dateSentFull = "full",
            )
        )
        assertThat(result.uri.toString()).endsWith("media-file.jpg")
        assertThat(result.info).isEqualTo(
            MediaInfo(
                filename = "an image file.jpg",
                fileSize = imageFile.length(),
                caption = null,
                mimeType = MimeTypes.Jpeg,
                formattedFileSize = "4MB",
                fileExtension = "jpg",
                senderId = A_USER_ID,
                senderName = A_USER_NAME,
                senderAvatar = null,
                dateSent = "12:34",
                dateSentFull = "full",
                waveform = null,
                duration = null,
            )
        )
    }

    @Test
    fun `createFromUri detects image mime type from content when picker mime type is generic`() {
        val imageFile = File(context.cacheDir, "picked-media").apply {
            FileOutputStream(this).use { outputStream ->
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
        }

        val result = createAndroidLocalMediaFactory().createFromUri(
            uri = imageFile.toURI().toString().let(android.net.Uri::parse),
            mimeType = MimeTypes.OctetStream,
            name = imageFile.name,
            formattedFileSize = null,
        )

        assertThat(context.getMimeType(result.uri)).isNull()
        assertThat(result.info.mimeType).isEqualTo(MimeTypes.Png)
        assertThat(result.info.fileExtension).isEmpty()
        assertThat(result.imageDecodeWidth).isEqualTo(1)
        assertThat(result.imageDecodeHeight).isEqualTo(1)
        assertThat(result.imageExifOrientation).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
    }

    @Test
    fun `createFromUri applies validated EXIF orientation to decode target`() {
        val imageFile = createImageFile("rotated.jpg", 40, 20, Bitmap.CompressFormat.JPEG)
        ExifInterface(imageFile).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val result = createAndroidLocalMediaFactory().createFromUri(
            uri = Uri.fromFile(imageFile),
            mimeType = MimeTypes.Jpeg,
            name = imageFile.name,
            formattedFileSize = null,
        )

        assertThat(result.imageDecodeWidth).isEqualTo(20)
        assertThat(result.imageDecodeHeight).isEqualTo(40)
        assertThat(result.imageExifOrientation).isEqualTo(ExifInterface.ORIENTATION_ROTATE_90)
    }

    @Test
    fun `createFromUri rejects declared image whose bounds cannot be decoded`() {
        val invalidImage = File(context.cacheDir, "invalid.jpg").apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        }

        assertThrows(ImageValidationException.Unreadable::class.java) {
            createAndroidLocalMediaFactory().createFromUri(
                uri = Uri.fromFile(invalidImage),
                mimeType = MimeTypes.Jpeg,
                name = invalidImage.name,
                formattedFileSize = null,
            )
        }
    }

    @Test
    fun `createFromUri rejects an image whose decoded type differs from the declaration`() {
        val jpegFile = createImageFile("type-mismatch.jpg", 1, 1, Bitmap.CompressFormat.JPEG)

        assertThrows(ImageValidationException.TypeMismatch::class.java) {
            createAndroidLocalMediaFactory().createFromUri(
                uri = Uri.fromFile(jpegFile),
                mimeType = MimeTypes.Png,
                name = jpegFile.name,
                formattedFileSize = null,
            )
        }
    }

    private fun createImageFile(
        name: String,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
    ): File {
        return File(context.cacheDir, name).apply {
            FileOutputStream(this).use { outputStream ->
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    .compress(format, 100, outputStream)
            }
        }
    }

    private fun createAndroidLocalMediaFactory(): AndroidLocalMediaFactory {
        return AndroidLocalMediaFactory(
            context,
            FakeFileSizeFormatter(),
            FileExtensionExtractorWithoutValidation()
        )
    }
}
