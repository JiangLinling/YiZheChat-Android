/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local

import io.element.android.libraries.mediaviewer.api.aPdfMediaInfo
import io.element.android.libraries.mediaviewer.impl.local.office.isOfficeSpreadsheetExtension
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeMediaRoutingTest {
    @Test
    fun `all approved modern and legacy Office extensions use Office routing`() {
        listOf("doc", "docx", "docm", "xls", "xlsx", "xlsm", "ppt", "pptx", "csv").forEach { extension ->
            val info = aPdfMediaInfo().copy(
                filename = "attachment.$extension",
                fileExtension = extension,
                mimeType = "application/octet-stream",
            )
            assertTrue("Expected .$extension to use Office routing", info.isOfficeDocument())
        }
    }

    @Test
    fun `unrelated attachments stay on their existing renderer`() {
        assertFalse(aPdfMediaInfo().copy(fileExtension = "zip", mimeType = "application/zip").isOfficeDocument())
        assertFalse(aPdfMediaInfo().copy(fileExtension = "txt", mimeType = "text/plain").isOfficeDocument())
    }

    @Test
    fun `only Excel formats use the interactive spreadsheet viewer`() {
        assertTrue("xls".isOfficeSpreadsheetExtension())
        assertTrue("XLSX".isOfficeSpreadsheetExtension())
        listOf("doc", "docx", "ppt", "pptx", "pdf").forEach { extension ->
            assertFalse("Expected .$extension to stay out of spreadsheet viewer", extension.isOfficeSpreadsheetExtension())
        }
    }
}
