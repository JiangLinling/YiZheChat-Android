/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import java.io.IOException
import timber.log.Timber

internal class PdfSourceUnavailableException : IOException("Protected PDF source is unavailable.")

/** Rechecks the same local provider authority. A rendered bitmap is never a fresh read capability. */
internal class PdfProtectedSourceAccess(
    encodedSize: () -> Long,
    private val currentSize: () -> Long,
) {
    private val encodedBytes by lazy(encodedSize)

    fun check() {
        val available = try {
            encodedBytes > 0L && currentSize() == encodedBytes
        } catch (_: Exception) {
            false
        }
        if (!available) {
            // No URI, token, payload, filename, exception message or authority credential.
            Timber.w("PDF source admission rejected stage=provider_metadata")
            throw PdfSourceUnavailableException()
        }
    }
}

internal fun checkedPdfPagePlan(
    pageWidth: Int,
    pageHeight: Int,
    requestedWidth: Int,
    pageIndex: Int,
    stage: String,
    sourceAccessCheck: (() -> Unit)?,
): PdfPageRenderPlan {
    // Native parsers may return unusable geometry after an underlying read fails. Check the source
    // after opening the page, before classifying the dimensions as a document-policy failure.
    checkPdfSourceAccess(sourceAccessCheck, stage, pageIndex, requestedWidth)
    return try {
        PdfRenderPolicy.planPage(pageWidth, pageHeight, requestedWidth)
    } catch (failure: PdfPreviewPolicyException.InvalidPageGeometry) {
        Timber.w(
            "PDF geometry rejected stage=%s pageIndex=%d pageWidth=%d pageHeight=%d requestedWidth=%d reason=%s",
            stage, pageIndex, pageWidth, pageHeight, requestedWidth, failure.reason,
        )
        throw failure
    }
}

internal fun checkPdfSourceAccess(check: (() -> Unit)?, stage: String, pageIndex: Int, requestedWidth: Int) {
    try {
        check?.invoke()
    } catch (failure: PdfSourceUnavailableException) {
        Timber.w("PDF source rejected stage=%s pageIndex=%d requestedWidth=%d", stage, pageIndex, requestedWidth)
        throw failure
    }
}
