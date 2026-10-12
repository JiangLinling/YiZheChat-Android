/*
 * Copyright (c) 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.rageshake.api.reporter

import java.io.File

/** A local, already compressed file that can be attached to a bug report. */
data class BugReportAttachment(
    val file: File,
    val fileName: String,
    val mimeType: String,
)
