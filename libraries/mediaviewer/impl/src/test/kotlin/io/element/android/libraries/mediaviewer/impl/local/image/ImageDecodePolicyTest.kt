/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.image

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageDecodePolicyTest {
    @Test
    fun `12MP image is sampled below the 32MiB decode budget`() {
        val result = ImageDecodePolicy.plan(
            width = 4_000,
            height = 3_000,
            encodedBytes = 4L * 1024L * 1024L,
            exifOrientation = 1,
        )

        assertThat(result.sampleSize).isEqualTo(2)
        assertThat(result.decodeWidth).isEqualTo(2_000)
        assertThat(result.decodeHeight).isEqualTo(1_500)
        assertThat(result.decodeWidth.toLong() * result.decodeHeight * 4L)
            .isAtMost(ImageDecodePolicy.MAX_DECODE_BYTES)
    }

    @Test
    fun `EXIF quarter turn swaps the bounded decode axes`() {
        val result = ImageDecodePolicy.plan(
            width = 4_000,
            height = 3_000,
            encodedBytes = 4L * 1024L * 1024L,
            exifOrientation = 6,
        )

        assertThat(result.decodeWidth).isEqualTo(1_500)
        assertThat(result.decodeHeight).isEqualTo(2_000)
        assertThat(result.exifOrientation).isEqualTo(6)
    }

    @Test
    fun `oversized source pixel count fails closed before decode`() {
        assertThrows(ImageValidationException.PixelCountExceeded::class.java) {
            ImageDecodePolicy.plan(
                width = 10_000,
                height = 7_000,
                encodedBytes = ImageDecodePolicy.MAX_ENCODED_BYTES,
                exifOrientation = 1,
            )
        }
    }

    @Test
    fun `hostile decompression ratio fails closed before decode`() {
        assertThrows(ImageValidationException.DecompressionRatioExceeded::class.java) {
            ImageDecodePolicy.plan(
                width = 4_000,
                height = 3_000,
                encodedBytes = 1L,
                exifOrientation = 1,
            )
        }
    }

    @Test
    fun `encoded image larger than the fixed budget fails closed`() {
        assertThrows(ImageValidationException.EncodedSizeExceeded::class.java) {
            ImageDecodePolicy.plan(
                width = 1,
                height = 1,
                encodedBytes = ImageDecodePolicy.MAX_ENCODED_BYTES + 1L,
                exifOrientation = 1,
            )
        }
    }

    @Test
    fun `unknown EXIF orientation fails closed`() {
        assertThrows(ImageValidationException.InvalidMetadata::class.java) {
            ImageDecodePolicy.plan(
                width = 100,
                height = 100,
                encodedBytes = 1_024L,
                exifOrientation = 9,
            )
        }
    }
}
