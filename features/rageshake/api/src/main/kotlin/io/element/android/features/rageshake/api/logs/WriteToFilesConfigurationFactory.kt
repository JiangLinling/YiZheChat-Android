/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.rageshake.api.logs

import io.element.android.features.rageshake.api.reporter.BugReporter
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.tracing.WriteToFilesConfiguration
import java.io.File
import java.io.RandomAccessFile

fun BugReporter.createWriteToFilesConfiguration(allowPlaintextDebugLogs: Boolean): WriteToFilesConfiguration {
    val directory = logDirectory()
    pruneLegacyPlaintextLogs(directory, deleteAll = !allowPlaintextDebugLogs)
    if (!allowPlaintextDebugLogs) return WriteToFilesConfiguration.Disabled
    return WriteToFilesConfiguration.Enabled(
        directory = directory.absolutePath,
        filenamePrefix = "logs",
        // Debug-only escape hatch. Production builds do not persist tracing logs at all.
        numberOfFiles = 24,
    )
}

private fun pruneLegacyPlaintextLogs(directory: File, deleteAll: Boolean) {
    val canonicalRoot = directory.canonicalFile
    val cutoff = System.currentTimeMillis() - DEBUG_LOG_RETENTION_MILLIS
    canonicalRoot.walkBottomUp()
        .filter { it != canonicalRoot }
        .forEach { candidate ->
            val canonicalCandidate = candidate.canonicalFile
            check(canonicalCandidate.path.startsWith(canonicalRoot.path + File.separator)) {
                "Log cleanup escaped the configured log directory."
            }
            when {
                canonicalCandidate.isFile && (deleteAll || canonicalCandidate.lastModified() < cutoff) -> canonicalCandidate.overwriteAndDelete()
                canonicalCandidate.isDirectory && canonicalCandidate.list().isNullOrEmpty() -> canonicalCandidate.delete()
            }
        }
}

private fun File.overwriteAndDelete() {
    runCatchingExceptions {
        RandomAccessFile(this, "rw").use { output ->
            val zeroes = ByteArray(OVERWRITE_BUFFER_BYTES)
            var remaining = output.length()
            output.seek(0)
            while (remaining > 0) {
                val count = minOf(zeroes.size.toLong(), remaining).toInt()
                output.write(zeroes, 0, count)
                remaining -= count
            }
            output.setLength(0)
            output.fd.sync()
        }
    }
    delete()
}

private const val DEBUG_LOG_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
private const val OVERWRITE_BUFFER_BYTES = 64 * 1_024
