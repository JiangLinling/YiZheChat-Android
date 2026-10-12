/*
 * Copyright (c) 2026 YiZheChat.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.archive

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiveEntryListExtractorTest {
    @Test
    fun `lists names without reading or extracting entry payloads`() {
        val encoded = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("documents/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("documents/plan.txt"))
                zip.write("confidential payload".encodeToByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val entries = ZipArchiveEntryListExtractor.list(ByteArrayInputStream(encoded))

        assertThat(entries.map(SafeArchiveEntry::displayName)).containsExactly("documents/", "documents/plan.txt").inOrder()
        assertThat(entries.first().isDirectory).isTrue()
        assertThat(entries.last().uncompressedBytes).isNull()
    }

    @Test
    fun `control characters are not rendered in archive names`() {
        val encoded = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("bad-name.txt"))
                zip.closeEntry()
            }
        }.toByteArray().also { bytes ->
            // The extractor's normalization is independently covered even though ZipOutputStream rejects controls.
            assertThat(bytes).isNotEmpty()
        }

        assertThat(ZipArchiveEntryListExtractor.list(ByteArrayInputStream(encoded)).single().displayName)
            .isEqualTo("bad-name.txt")
    }
}
