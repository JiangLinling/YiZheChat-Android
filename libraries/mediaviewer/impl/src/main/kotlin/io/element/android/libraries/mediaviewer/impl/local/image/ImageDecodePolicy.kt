/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.image

internal data class ImageDecodePlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val decodeWidth: Int,
    val decodeHeight: Int,
    val sampleSize: Int,
    val exifOrientation: Int,
)

internal sealed class ImageValidationException(message: String) : IllegalArgumentException(message) {
    class Unreadable : ImageValidationException("Image bounds could not be decoded.")
    class InvalidMetadata : ImageValidationException("Image metadata is invalid.")
    class TypeMismatch : ImageValidationException("Declared and decoded image types do not match.")
    class EncodedSizeExceeded : ImageValidationException("Encoded image exceeds the allowed size.")
    class PixelCountExceeded : ImageValidationException("Image pixel count exceeds the allowed size.")
    class DecompressionRatioExceeded : ImageValidationException("Image decompression ratio exceeds the allowed size.")
}

/**
 * Produces a decode target before any bitmap allocation.
 *
 * R12 fixes the per-image decode budget at 32 MiB. Four bytes per ARGB pixel leaves 8,388,608 decoded pixels. A
 * source may be sampled, but sources larger than eight decode budgets are rejected so hostile dimensions cannot use
 * sampling as an unlimited bypass. The encoded-size and decompression-ratio guards are intentionally fail-closed.
 */
internal object ImageDecodePolicy {
    const val MAX_DECODE_BYTES: Long = 32L * 1024L * 1024L
    const val MAX_ENCODED_BYTES: Long = 32L * 1024L * 1024L
    const val MAX_DECOMPRESSION_RATIO: Long = 512L
    private const val BYTES_PER_PIXEL = 4L
    private const val MAX_SOURCE_BUDGET_MULTIPLIER = 8L
    val maxSourcePixels: Long = MAX_DECODE_BYTES / BYTES_PER_PIXEL * MAX_SOURCE_BUDGET_MULTIPLIER

    fun plan(
        width: Int,
        height: Int,
        encodedBytes: Long,
        exifOrientation: Int,
    ): ImageDecodePlan {
        if (width <= 0 || height <= 0 || encodedBytes <= 0L) throw ImageValidationException.Unreadable()
        if (encodedBytes > MAX_ENCODED_BYTES) throw ImageValidationException.EncodedSizeExceeded()

        val sourcePixels = width.toLong() * height.toLong()
        if (sourcePixels > maxSourcePixels) throw ImageValidationException.PixelCountExceeded()
        val decodedSourceBytes = sourcePixels * BYTES_PER_PIXEL
        if (decodedSourceBytes > encodedBytes * MAX_DECOMPRESSION_RATIO) {
            throw ImageValidationException.DecompressionRatioExceeded()
        }

        val normalizedOrientation = when (exifOrientation) {
            0 -> 1
            in 1..8 -> exifOrientation
            else -> throw ImageValidationException.InvalidMetadata()
        }
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth.toLong() * sampledHeight.toLong() * BYTES_PER_PIXEL > MAX_DECODE_BYTES) {
            sampleSize *= 2
            sampledWidth = ceilDiv(width, sampleSize)
            sampledHeight = ceilDiv(height, sampleSize)
        }
        val swapsAxes = normalizedOrientation in 5..8
        return ImageDecodePlan(
            sourceWidth = width,
            sourceHeight = height,
            decodeWidth = if (swapsAxes) sampledHeight else sampledWidth,
            decodeHeight = if (swapsAxes) sampledWidth else sampledHeight,
            sampleSize = sampleSize,
            exifOrientation = normalizedOrientation,
        )
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
}
