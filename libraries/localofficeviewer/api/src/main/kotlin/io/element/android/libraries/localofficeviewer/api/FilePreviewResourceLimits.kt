/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.localofficeviewer.api

/** Local parser admission only; does not grant sending, exporting or online-preview authority. */
object FilePreviewResourceLimits {
    const val PDF_INPUT_BYTES = 256L * 1024L * 1024L
    const val PDF_PAGE_COUNT = 10_000
    const val OFFICE_INPUT_BYTES = 64L * 1024L * 1024L
    const val OFFICE_EXPANDED_BYTES = 256L * 1024L * 1024L
    const val OFFICE_ZIP_ENTRY_BYTES = 64L * 1024L * 1024L
    const val OFFICE_DOCUMENT_UNITS = 10_000L
    const val OFFICE_ZIP_ENTRIES = 16_384
    const val CSV_CELLS = 2_000_000L
    const val VALIDATION_TIMEOUT_MILLIS = 45_000L
    const val RENDER_TIMEOUT_MILLIS = 60_000L
}
