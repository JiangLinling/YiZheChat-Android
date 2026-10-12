/*
 * Copyright (c) 2026 YiZheChat.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.archive

import java.io.InputStream
import java.util.zip.ZipInputStream

internal data class SafeArchiveEntry(
    val displayName: String,
    val uncompressedBytes: Long?,
    val isDirectory: Boolean,
)

internal object ZipArchiveEntryListExtractor {
    private const val MAX_ENTRIES = 1_000
    private const val MAX_NAME_CHARACTERS = 240

    /** Lists central entry metadata only. Entry payloads are never read, executed or extracted. */
    fun list(input: InputStream): List<SafeArchiveEntry> {
        val result = ArrayList<SafeArchiveEntry>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(result.size < MAX_ENTRIES) { "The archive contains too many entries to preview safely." }
                val safeName = entry.name
                    .filterNot(Char::isISOControl)
                    .replace('\\', '/')
                    .take(MAX_NAME_CHARACTERS)
                    .ifBlank { "(unnamed entry)" }
                result += SafeArchiveEntry(
                    displayName = safeName,
                    uncompressedBytes = entry.size.takeIf { it >= 0 },
                    isDirectory = entry.isDirectory,
                )
                zip.closeEntry()
            }
        }
        return result
    }
}
