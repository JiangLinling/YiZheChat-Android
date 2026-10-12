/*
 * Copyright (c) 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.rageshake.impl.bugreport

import android.net.Uri
import io.element.android.features.rageshake.api.reporter.BugReportAttachment
import io.element.android.libraries.core.mimetype.MimeTypes
import java.io.File

internal class FakeBugReportAttachmentProcessor : BugReportAttachmentProcessor {
    var result: Result<ProcessedBugReportAttachment> = Result.success(
        ProcessedBugReportAttachment(
            attachment = BugReportAttachment(File("feedback.jpg"), "feedback.jpg", MimeTypes.Jpeg),
            displayName = "feedback.jpg",
            kind = BugReportAttachmentKind.Image,
        )
    )
    val released = mutableListOf<ProcessedBugReportAttachment>()

    override suspend fun process(
        uri: Uri,
        mimeType: String?,
        fallbackName: String?,
    ): Result<ProcessedBugReportAttachment> = result

    override fun release(attachment: ProcessedBugReportAttachment) {
        released += attachment
    }
}
