/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfRenderPolicyTest {
    @Test
    fun `real 642 page office document passes admission`() {
        PdfRenderPolicy.validateDocument(encodedBytes = 6_760_644L, pageCount = 642)
    }

    @Test
    fun `document admission accepts exact byte and page limits`() {
        PdfRenderPolicy.validateDocument(
            encodedBytes = PdfRenderPolicy.MAX_ENCODED_BYTES,
            pageCount = PdfRenderPolicy.MAX_PAGE_COUNT,
        )
    }

    @Test
    fun `document admission rejects unavailable or excessive inputs`() {
        assertThrows(PdfPreviewPolicyException.UnknownSize::class.java) {
            PdfRenderPolicy.validateDocument(encodedBytes = 0L, pageCount = 1)
        }
        assertThrows(PdfPreviewPolicyException.EncodedSizeExceeded::class.java) {
            PdfRenderPolicy.validateDocument(
                encodedBytes = PdfRenderPolicy.MAX_ENCODED_BYTES + 1L,
                pageCount = 1,
            )
        }
        assertThrows(PdfPreviewPolicyException.PageCountExceeded::class.java) {
            PdfRenderPolicy.validateDocument(encodedBytes = 1L, pageCount = 0)
        }
        assertThrows(PdfPreviewPolicyException.PageCountExceeded::class.java) {
            PdfRenderPolicy.validateDocument(
                encodedBytes = 1L,
                pageCount = PdfRenderPolicy.MAX_PAGE_COUNT + 1,
            )
        }
    }

    @Test
    fun `page plan downscales within the bitmap allocation budget`() {
        val plan = PdfRenderPolicy.planPage(
            pageWidth = 10_000,
            pageHeight = 10_000,
            requestedWidth = 10_000,
        )

        assertThat(plan.width).isLessThan(10_000)
        assertThat(plan.width.toLong() * plan.height * 4L)
            .isAtMost(PdfRenderPolicy.MAX_PAGE_BITMAP_BYTES)

        val extremeWidthPlan = PdfRenderPolicy.planPage(
            pageWidth = 1,
            pageHeight = PdfRenderPolicy.MAX_PAGE_ASPECT_RATIO.toInt(),
            requestedWidth = Int.MAX_VALUE,
        )
        assertThat(extremeWidthPlan.width.toLong() * extremeWidthPlan.height * 4L)
            .isAtMost(PdfRenderPolicy.MAX_PAGE_BITMAP_BYTES)
    }

    @Test
    fun `page plan rejects invalid and excessive geometry`() {
        assertThrows(PdfPreviewPolicyException.InvalidPageGeometry::class.java) {
            PdfRenderPolicy.planPage(pageWidth = 0, pageHeight = 1, requestedWidth = 1)
        }
        assertThrows(PdfPreviewPolicyException.InvalidPageGeometry::class.java) {
            PdfRenderPolicy.planPage(pageWidth = 1, pageHeight = 101, requestedWidth = 1)
        }
    }

    @Test
    fun `policy failures are security blocked while renderer failures are unavailable`() {
        assertThat(PdfPreviewPolicyException.PageCountExceeded().toFilePreviewLeaseReleaseReason())
            .isEqualTo(FilePreviewLeaseReleaseReason.SECURITY_BLOCKED)
        assertThat(IllegalStateException("renderer failed").toFilePreviewLeaseReleaseReason())
            .isEqualTo(FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE)
    }
}
