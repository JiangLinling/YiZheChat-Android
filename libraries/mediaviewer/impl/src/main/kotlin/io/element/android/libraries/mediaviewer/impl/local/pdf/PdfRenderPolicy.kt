/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import io.element.android.libraries.localofficeviewer.api.FilePreviewResourceLimits
import kotlin.math.floor
import kotlin.math.sqrt

internal data class PdfPageRenderPlan(
    val width: Int,
    val height: Int,
)

internal sealed class PdfPreviewPolicyException(message: String) : IllegalArgumentException(message) {
    class UnknownSize : PdfPreviewPolicyException("PDF size is unavailable.")
    class EncodedSizeExceeded : PdfPreviewPolicyException("PDF exceeds the encoded-size budget.")
    class PageCountExceeded : PdfPreviewPolicyException("PDF exceeds the page-count budget.")
    class MemoryUnavailable : PdfPreviewPolicyException("Not enough memory to render this PDF page.")
    class InvalidPageGeometry(
        val pageWidth: Int = 0,
        val pageHeight: Int = 0,
        val requestedWidth: Int = 0,
        val reason: String = "unspecified",
    ) : PdfPreviewPolicyException("PDF page geometry is invalid or excessive.")
}

/** Hard admission and bitmap-allocation budgets for the platform PDF renderer. */
internal object PdfRenderPolicy {
    const val MAX_ENCODED_BYTES = FilePreviewResourceLimits.PDF_INPUT_BYTES
    const val MAX_PAGE_COUNT = FilePreviewResourceLimits.PDF_PAGE_COUNT
    const val MAX_PAGE_BITMAP_BYTES = 32L * 1024L * 1024L
    const val MAX_PAGE_ASPECT_RATIO = 100L
    private const val BYTES_PER_PIXEL = 4L
    private const val MAX_PAGE_PIXELS = MAX_PAGE_BITMAP_BYTES / BYTES_PER_PIXEL

    fun validateDocument(encodedBytes: Long, pageCount: Int) {
        validateEncodedSize(encodedBytes)
        validatePageCount(pageCount)
    }

    fun validateEncodedSize(encodedBytes: Long) {
        if (encodedBytes <= 0L) throw PdfPreviewPolicyException.UnknownSize()
        if (encodedBytes > MAX_ENCODED_BYTES) throw PdfPreviewPolicyException.EncodedSizeExceeded()
    }

    fun validatePageCount(pageCount: Int) {
        if (pageCount !in 1..MAX_PAGE_COUNT) throw PdfPreviewPolicyException.PageCountExceeded()
    }

    fun planPage(pageWidth: Int, pageHeight: Int, requestedWidth: Int): PdfPageRenderPlan {
        if (pageWidth <= 0 || pageHeight <= 0 || requestedWidth <= 0) {
            throw PdfPreviewPolicyException.InvalidPageGeometry(pageWidth, pageHeight, requestedWidth, "non_positive")
        }
        val shorter = minOf(pageWidth, pageHeight).toLong()
        val longer = maxOf(pageWidth, pageHeight).toLong()
        if (longer > shorter * MAX_PAGE_ASPECT_RATIO) {
            throw PdfPreviewPolicyException.InvalidPageGeometry(pageWidth, pageHeight, requestedWidth, "aspect_ratio")
        }

        val requestedHeight = ceilDiv(pageHeight.toLong() * requestedWidth, pageWidth)
        val fitsWithoutOverflow = requestedHeight <= MAX_PAGE_PIXELS / requestedWidth
        if (fitsWithoutOverflow) {
            return PdfPageRenderPlan(requestedWidth, requestedHeight.toInt())
        }

        val scale = sqrt(MAX_PAGE_PIXELS.toDouble() / requestedWidth / requestedHeight)
        var boundedWidth = floor(requestedWidth * scale).toInt().coerceAtLeast(1)
        var boundedHeight = ceilDiv(pageHeight.toLong() * boundedWidth, pageWidth).toInt()
        while (boundedWidth.toLong() * boundedHeight > MAX_PAGE_PIXELS && boundedWidth > 1) {
            boundedWidth--
            boundedHeight = ceilDiv(pageHeight.toLong() * boundedWidth, pageWidth).toInt()
        }
        if (boundedWidth.toLong() * boundedHeight > MAX_PAGE_PIXELS) throw PdfPreviewPolicyException.InvalidPageGeometry(pageWidth, pageHeight, requestedWidth, "allocation_budget")
        return PdfPageRenderPlan(boundedWidth, boundedHeight)
    }

    private fun ceilDiv(value: Long, divisor: Int): Long = (value + divisor - 1L) / divisor
}
