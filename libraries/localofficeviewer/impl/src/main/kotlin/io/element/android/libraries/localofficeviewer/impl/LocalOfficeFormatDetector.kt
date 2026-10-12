/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.localofficeviewer.impl

import io.element.android.libraries.localofficeviewer.api.FilePreviewResourceLimits
import io.element.android.libraries.localofficeviewer.api.LocalOfficeFormat
import io.element.android.libraries.localofficeviewer.api.LocalOfficePreviewRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

internal data class LocalOfficeValidationSummary(
    val format: LocalOfficeFormat,
    val actualSize: Long,
    val sha256: String,
)

internal class LocalOfficeValidatedPayload internal constructor(
    private val bytes: ByteArray,
) {
    private val cleared = AtomicBoolean(false)

    val size: Int
        get() = bytes.size

    fun openInputStream(): InputStream {
        check(!cleared.get()) { "Validated local Office payload is no longer available." }
        return ByteArrayInputStream(bytes)
    }

    fun clear() {
        if (cleared.compareAndSet(false, true)) bytes.fill(0)
    }

    internal fun isCleared(): Boolean = cleared.get()
}

internal enum class LocalOfficeBlockReason {
    INPUT_SIZE_LIMIT,
    DECLARED_SIZE_MISMATCH,
    EXPECTED_DIGEST_MISMATCH,
    MIME_MISMATCH,
    SIGNATURE_MISMATCH,
    ARCHIVE_STRUCTURE_INVALID,
    ARCHIVE_ENTRY_LIMIT,
    ARCHIVE_ENTRY_SIZE_LIMIT,
    ARCHIVE_EXPANDED_SIZE_LIMIT,
    ARCHIVE_RATIO_LIMIT,
    ARCHIVE_PATH_INVALID,
    ARCHIVE_EXTERNAL_RELATIONSHIP,
    DOCUMENT_UNIT_LIMIT,
    CSV_STRUCTURE_LIMIT,
    VALIDATION_TIMEOUT,
    READ_FAILED,
    MEMORY_UNAVAILABLE,
}

internal sealed interface LocalOfficeDetection {
    data class Accepted(
        val summary: LocalOfficeValidationSummary,
        val payload: LocalOfficeValidatedPayload,
    ) : LocalOfficeDetection
    data object Unsupported : LocalOfficeDetection
    data class SecurityBlocked(val reason: LocalOfficeBlockReason) : LocalOfficeDetection
}

/**
 * Performs a complete, bounded read before any bytes are handed to the renderer.
 *
 * Matrix/YZMF authentication and secure-at-rest storage are owned by the upstream LC-16 media
 * loader. This validator owns only local parser admission: declared identity, whole-source digest,
 * size and format-specific complexity limits.
 */
internal object LocalOfficeFormatDetector {
    private val oleMagic = byteArrayOf(
        0xD0.toByte(),
        0xCF.toByte(),
        0x11,
        0xE0.toByte(),
        0xA1.toByte(),
        0xB1.toByte(),
        0x1A,
        0xE1.toByte(),
    )
    private val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    fun detect(request: LocalOfficePreviewRequest): LocalOfficeDetection {
        val extension = request.displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        val format = LocalOfficeFormat.entries.firstOrNull { it.extension == extension }
            ?: return LocalOfficeDetection.Unsupported
        val budget = LocalOfficeFormatBudget.forFormat(format)
        if (request.declaredSize != null && request.declaredSize !in 1..budget.maxInputBytes) {
            return LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.INPUT_SIZE_LIMIT)
        }
        if (!mimeMatches(format, request.declaredMimeType)) {
            return LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.MIME_MISMATCH)
        }

        val startedAtNanos = System.nanoTime()
        var snapshot: InputSnapshot? = null
        var ownershipTransferred = false
        return try {
            val currentSnapshot = readBoundedInput(request, budget, startedAtNanos)
            snapshot = currentSnapshot
            when {
                request.declaredSize != null && request.declaredSize != currentSnapshot.actualSize -> {
                    LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.DECLARED_SIZE_MISMATCH)
                }
                request.expectedSha256 != null && !request.expectedSha256.equals(currentSnapshot.sha256, ignoreCase = true) -> {
                    LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.EXPECTED_DIGEST_MISMATCH)
                }
                else -> {
                    when (format) {
                        LocalOfficeFormat.CSV -> validateCsv(currentSnapshot.bytes, budget, startedAtNanos)
                        LocalOfficeFormat.DOC, LocalOfficeFormat.XLS, LocalOfficeFormat.PPT -> validateOle(currentSnapshot.bytes, startedAtNanos)
                        else -> validateOoxml(currentSnapshot.bytes, format, budget, startedAtNanos)
                    }
                    val payload = LocalOfficeValidatedPayload(currentSnapshot.bytes)
                    ownershipTransferred = true
                    LocalOfficeDetection.Accepted(
                        LocalOfficeValidationSummary(
                            format = format,
                            actualSize = currentSnapshot.actualSize,
                            sha256 = currentSnapshot.sha256,
                        ),
                        payload = payload,
                    )
                }
            }
        } catch (_: OutOfMemoryError) {
            LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.MEMORY_UNAVAILABLE)
        } catch (abort: ValidationAbort) {
            LocalOfficeDetection.SecurityBlocked(abort.reason)
        } catch (_: ZipException) {
            LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.ARCHIVE_STRUCTURE_INVALID)
        } catch (_: Exception) {
            LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.READ_FAILED)
        } finally {
            if (!ownershipTransferred) snapshot?.clear()
        }
    }

    private fun readBoundedInput(
        request: LocalOfficePreviewRequest,
        budget: LocalOfficeFormatBudget,
        startedAtNanos: Long,
    ): InputSnapshot {
        val digest = MessageDigest.getInstance("SHA-256")
        val declaredSize = request.declaredSize
        // Known lengths use exactly one source-sized buffer, rather than reserving the entire limit.
        // Unknown lengths grow within the budget. Every discarded array is cleared.
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var storage: ByteArray? = null
        var resultBytes: ByteArray? = null
        var snapshotCompleted = false
        var actualSize = 0L
        var transferred = false
        try {
            storage = ByteArray(declaredSize?.toInt() ?: STREAM_BUFFER_BYTES)
            request.contentSource.openInputStream().use { input ->
                while (true) {
                    checkDeadline(startedAtNanos)
                    val read = input.read(buffer)
                    checkDeadline(startedAtNanos)
                    if (read < 0) break
                    if (read == 0) continue
                    val nextSize = actualSize + read
                    if (nextSize > budget.maxInputBytes) abort(LocalOfficeBlockReason.INPUT_SIZE_LIMIT)
                    if (declaredSize != null && nextSize > declaredSize) {
                        abort(LocalOfficeBlockReason.DECLARED_SIZE_MISMATCH)
                    }
                    val activeStorage = checkNotNull(storage)
                    if (nextSize > activeStorage.size) {
                        val capacity = minOf(budget.maxInputBytes, maxOf(nextSize, activeStorage.size.toLong() * 2)).toInt()
                        val expanded = activeStorage.copyOf(capacity)
                        activeStorage.fill(0)
                        storage = expanded
                    }
                    buffer.copyInto(checkNotNull(storage), destinationOffset = actualSize.toInt(), endIndex = read)
                    actualSize = nextSize
                    digest.update(buffer, 0, read)
                }
            }
            if (actualSize == 0L) abort(LocalOfficeBlockReason.INPUT_SIZE_LIMIT)
            val sha256 = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
            val activeStorage = checkNotNull(storage)
            val sourceBytes = if (activeStorage.size == actualSize.toInt()) activeStorage else activeStorage.copyOf(actualSize.toInt())
            resultBytes = sourceBytes
            val snapshot = InputSnapshot(
                bytes = sourceBytes,
                actualSize = actualSize,
                sha256 = sha256,
            )
            transferred = sourceBytes === storage
            snapshotCompleted = true
            return snapshot
        } finally {
            if (!transferred) storage?.fill(0)
            if (!snapshotCompleted) resultBytes?.fill(0)
            buffer.fill(0)
        }
    }

    private fun validateCsv(
        bytes: ByteArray,
        budget: LocalOfficeFormatBudget,
        startedAtNanos: Long,
    ) {
        ByteArrayInputStream(bytes).use { input ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var lineBytes = 0L
            var cells = 0L
            var currentLineCells = 1L
            var sawContent = false
            while (true) {
                checkDeadline(startedAtNanos)
                val read = input.read(buffer)
                if (read < 0) break
                for (index in 0 until read) {
                    when (val value = buffer[index].toInt() and 0xFF) {
                        0 -> abort(LocalOfficeBlockReason.SIGNATURE_MISMATCH)
                        ','.code -> {
                            currentLineCells++
                            lineBytes++
                        }
                        '\n'.code -> {
                            cells += currentLineCells
                            if (cells > budget.maxDocumentUnits) abort(LocalOfficeBlockReason.CSV_STRUCTURE_LIMIT)
                            currentLineCells = 1
                            lineBytes = 0
                        }
                        '\r'.code -> Unit
                        else -> {
                            if (value < 0x09) abort(LocalOfficeBlockReason.SIGNATURE_MISMATCH)
                            sawContent = true
                            lineBytes++
                        }
                    }
                    if (lineBytes > budget.maxCsvLineBytes) abort(LocalOfficeBlockReason.CSV_STRUCTURE_LIMIT)
                    if (currentLineCells > MAX_CSV_COLUMNS) abort(LocalOfficeBlockReason.CSV_STRUCTURE_LIMIT)
                }
            }
            if (!sawContent) abort(LocalOfficeBlockReason.SIGNATURE_MISMATCH)
            cells += currentLineCells
            if (cells > budget.maxDocumentUnits) abort(LocalOfficeBlockReason.CSV_STRUCTURE_LIMIT)
        }
    }

    private fun validateOle(bytes: ByteArray, startedAtNanos: Long) {
        val prefix = ByteArrayInputStream(bytes).use { input ->
            checkDeadline(startedAtNanos)
            input.readPrefix(oleMagic.size)
        }
        if (!prefix.contentEquals(oleMagic)) abort(LocalOfficeBlockReason.SIGNATURE_MISMATCH)
    }

    private fun validateOoxml(
        bytes: ByteArray,
        format: LocalOfficeFormat,
        budget: LocalOfficeFormatBudget,
        startedAtNanos: Long,
    ) {
        val prefix = ByteArrayInputStream(bytes).use { it.readPrefix(zipMagic.size) }
        if (!prefix.contentEquals(zipMagic)) abort(LocalOfficeBlockReason.SIGNATURE_MISMATCH)

        var family: String? = null
        var contentTypes: String? = null
        var totalExpanded = 0L
        var entryCount = 0
        var documentUnits = 0L
        val seenEntries = HashSet<String>()
        ByteArrayInputStream(bytes).use { source ->
            ZipInputStream(source).use { zip ->
                while (true) {
                    checkDeadline(startedAtNanos)
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) abort(LocalOfficeBlockReason.ARCHIVE_ENTRY_LIMIT)
                    val name = validateEntryName(entry.name)
                    if (!seenEntries.add(name)) abort(LocalOfficeBlockReason.ARCHIVE_STRUCTURE_INVALID)
                    family = family ?: when {
                        name.startsWith("word/") -> "word"
                        name.startsWith("xl/") -> "sheet"
                        name.startsWith("ppt/") -> "presentation"
                        else -> null
                    }
                    if (isDocumentUnit(name)) {
                        documentUnits++
                        if (documentUnits > budget.maxDocumentUnits) abort(LocalOfficeBlockReason.DOCUMENT_UNIT_LIMIT)
                    }
                    val capture = when {
                        name == CONTENT_TYPES_ENTRY -> ByteArrayOutputStream()
                        name.endsWith(".rels", ignoreCase = true) -> ByteArrayOutputStream()
                        else -> null
                    }
                    val entryExpanded = drainEntry(
                        zip = zip,
                        capture = capture,
                        budget = budget,
                        alreadyExpanded = totalExpanded,
                        inputBytes = bytes.size.toLong(),
                        startedAtNanos = startedAtNanos,
                    )
                    totalExpanded += entryExpanded
                    val capturedText = capture?.toByteArray()?.toString(Charsets.UTF_8)
                    if (name == CONTENT_TYPES_ENTRY) contentTypes = capturedText
                    if (name.endsWith(".rels", ignoreCase = true) && capturedText.hasBlockedExternalRelationship()) {
                        abort(LocalOfficeBlockReason.ARCHIVE_EXTERNAL_RELATIONSHIP)
                    }
                    zip.closeEntry()
                }
            }
        }
        if (totalExpanded > MIN_RATIO_CHECK_BYTES && totalExpanded > bytes.size.toLong() * MAX_EXPANSION_RATIO) {
            abort(LocalOfficeBlockReason.ARCHIVE_RATIO_LIMIT)
        }
        val expectedFamily = when (format) {
            LocalOfficeFormat.DOCX, LocalOfficeFormat.DOCM -> "word"
            LocalOfficeFormat.XLSX, LocalOfficeFormat.XLSM -> "sheet"
            LocalOfficeFormat.PPTX -> "presentation"
            else -> error("Not OOXML")
        }
        if (family != expectedFamily || !contentTypes.matchesFormat(format)) {
            abort(LocalOfficeBlockReason.ARCHIVE_STRUCTURE_INVALID)
        }
    }

    private fun drainEntry(
        zip: ZipInputStream,
        capture: ByteArrayOutputStream?,
        budget: LocalOfficeFormatBudget,
        alreadyExpanded: Long,
        inputBytes: Long,
        startedAtNanos: Long,
    ): Long {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var entryExpanded = 0L
        while (true) {
            checkDeadline(startedAtNanos)
            val read = zip.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            entryExpanded += read
            if (entryExpanded > budget.maxZipEntryBytes) abort(LocalOfficeBlockReason.ARCHIVE_ENTRY_SIZE_LIMIT)
            if (alreadyExpanded + entryExpanded > budget.maxExpandedOfficeBytes) {
                abort(LocalOfficeBlockReason.ARCHIVE_EXPANDED_SIZE_LIMIT)
            }
            val currentExpanded = alreadyExpanded + entryExpanded
            if (currentExpanded > MIN_RATIO_CHECK_BYTES && currentExpanded > inputBytes * MAX_EXPANSION_RATIO) {
                abort(LocalOfficeBlockReason.ARCHIVE_RATIO_LIMIT)
            }
            if (capture != null) {
                if (capture.size() + read > MAX_CAPTURED_METADATA_BYTES) {
                    abort(LocalOfficeBlockReason.ARCHIVE_ENTRY_SIZE_LIMIT)
                }
                capture.write(buffer, 0, read)
            }
        }
        return entryExpanded
    }

    private fun validateEntryName(rawName: String): String {
        val name = rawName.replace('\\', '/')
        val pathSegments = name.split('/')
        if (name.isBlank() || name.startsWith('/') || ':' in name || pathSegments.any { it == ".." }) {
            abort(LocalOfficeBlockReason.ARCHIVE_PATH_INVALID)
        }
        return name
    }

    private fun isDocumentUnit(name: String): Boolean =
        (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) ||
            (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))

    private fun String?.hasBlockedExternalRelationship(): Boolean {
        if (this == null) return false
        return RELATIONSHIP_TAG_REGEX.findAll(this).any { match ->
            val tag = match.value
            val targetMode = TARGET_MODE_ATTRIBUTE.find(tag)?.groupValues?.get(1)
            val target = TARGET_ATTRIBUTE.find(tag)?.groupValues?.get(1)
            val type = TYPE_ATTRIBUTE.find(tag)?.groupValues?.get(1)
            val explicitlyExternal = targetMode.equals("external", ignoreCase = true)
            val externalTarget = target?.let(EXTERNAL_TARGET_REGEX::containsMatchIn) == true
            if (!explicitlyExternal && !externalTarget) {
                false
            } else {
                val safeHyperlink = type?.endsWith("/hyperlink", ignoreCase = true) == true &&
                    target?.let(SAFE_HYPERLINK_TARGET_REGEX::containsMatchIn) == true
                !safeHyperlink
            }
        }
    }

    private fun String?.matchesFormat(format: LocalOfficeFormat): Boolean {
        if (this == null) return false
        val normalized = lowercase(Locale.ROOT)
        return when (format) {
            LocalOfficeFormat.DOCX -> WORD_DOCUMENT_CONTENT_TYPE in normalized && WORD_MACRO_CONTENT_TYPE !in normalized
            LocalOfficeFormat.DOCM -> WORD_MACRO_CONTENT_TYPE in normalized
            LocalOfficeFormat.XLSX -> SHEET_DOCUMENT_CONTENT_TYPE in normalized && SHEET_MACRO_CONTENT_TYPE !in normalized
            LocalOfficeFormat.XLSM -> SHEET_MACRO_CONTENT_TYPE in normalized
            LocalOfficeFormat.PPTX -> PRESENTATION_DOCUMENT_CONTENT_TYPE in normalized
            else -> false
        }
    }

    private fun mimeMatches(format: LocalOfficeFormat, mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank() || mimeType == "application/octet-stream") return true
        val normalized = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        return normalized in when (format) {
            LocalOfficeFormat.DOC -> setOf("application/msword")
            LocalOfficeFormat.DOCX -> setOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            LocalOfficeFormat.DOCM -> setOf("application/vnd.ms-word.document.macroenabled.12")
            LocalOfficeFormat.XLS -> setOf("application/vnd.ms-excel")
            LocalOfficeFormat.XLSX -> setOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            LocalOfficeFormat.XLSM -> setOf("application/vnd.ms-excel.sheet.macroenabled.12")
            LocalOfficeFormat.PPT -> setOf("application/vnd.ms-powerpoint")
            LocalOfficeFormat.PPTX -> setOf("application/vnd.openxmlformats-officedocument.presentationml.presentation")
            LocalOfficeFormat.CSV -> setOf("text/csv", "text/plain", "application/csv")
        }
    }

    private fun InputStream.readPrefix(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(result, offset, size - offset)
            if (read < 0) return result.copyOf(offset)
            offset += read
        }
        return result
    }

    private fun checkDeadline(startedAtNanos: Long) {
        if (Thread.currentThread().isInterrupted || System.nanoTime() - startedAtNanos > VALIDATION_TIMEOUT_NANOS) {
            abort(LocalOfficeBlockReason.VALIDATION_TIMEOUT)
        }
    }

    private fun abort(reason: LocalOfficeBlockReason): Nothing = throw ValidationAbort(reason)

    private data class InputSnapshot(
        val bytes: ByteArray,
        val actualSize: Long,
        val sha256: String,
    ) {
        fun clear() = bytes.fill(0)
    }

    internal data class LocalOfficeFormatBudget(
        val maxInputBytes: Long,
        val maxExpandedOfficeBytes: Long,
        val maxZipEntryBytes: Long,
        val maxDocumentUnits: Long,
        val maxCsvLineBytes: Long,
    ) {
        companion object {
            fun forFormat(format: LocalOfficeFormat): LocalOfficeFormatBudget = when (format) {
                LocalOfficeFormat.CSV -> COMMON.copy(
                    maxInputBytes = MAX_CSV_INPUT_BYTES,
                    maxDocumentUnits = MAX_CSV_CELLS,
                )
                else -> COMMON
            }

            private val COMMON = LocalOfficeFormatBudget(
                maxInputBytes = MAX_INPUT_BYTES,
                maxExpandedOfficeBytes = MAX_EXPANDED_OFFICE_BYTES,
                maxZipEntryBytes = MAX_ZIP_ENTRY_BYTES,
                maxDocumentUnits = MAX_DOCUMENT_UNITS,
                maxCsvLineBytes = MAX_CSV_LINE_BYTES,
            )
        }
    }

    private class ValidationAbort(val reason: LocalOfficeBlockReason) : RuntimeException()

    const val MAX_INPUT_BYTES = FilePreviewResourceLimits.OFFICE_INPUT_BYTES
    const val MAX_EXPANDED_OFFICE_BYTES = FilePreviewResourceLimits.OFFICE_EXPANDED_BYTES
    const val MAX_ZIP_ENTRY_BYTES = FilePreviewResourceLimits.OFFICE_ZIP_ENTRY_BYTES
    const val MAX_DOCUMENT_UNITS = FilePreviewResourceLimits.OFFICE_DOCUMENT_UNITS
    const val MAX_CSV_CELLS = FilePreviewResourceLimits.CSV_CELLS
    const val VALIDATION_TIMEOUT_MILLIS = FilePreviewResourceLimits.VALIDATION_TIMEOUT_MILLIS

    private const val MAX_CSV_INPUT_BYTES = FilePreviewResourceLimits.OFFICE_INPUT_BYTES
    private const val MAX_CSV_LINE_BYTES = 1L * 1024L * 1024L
    private const val MAX_CSV_COLUMNS = 16_384L
    private const val MAX_ZIP_ENTRIES = FilePreviewResourceLimits.OFFICE_ZIP_ENTRIES
    private const val MAX_CAPTURED_METADATA_BYTES = 1 * 1024 * 1024
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val MAX_EXPANSION_RATIO = 100L
    private const val MIN_RATIO_CHECK_BYTES = 8L * 1024L * 1024L
    private const val VALIDATION_TIMEOUT_NANOS = VALIDATION_TIMEOUT_MILLIS * 1_000_000L
    private const val CONTENT_TYPES_ENTRY = "[Content_Types].xml"
    private const val WORD_DOCUMENT_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    private const val WORD_MACRO_CONTENT_TYPE = "application/vnd.ms-word.document.macroenabled.main+xml"
    private const val SHEET_DOCUMENT_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
    private const val SHEET_MACRO_CONTENT_TYPE = "application/vnd.ms-excel.sheet.macroenabled.main+xml"
    private const val PRESENTATION_DOCUMENT_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    private val RELATIONSHIP_TAG_REGEX =
        Regex("<(?:[A-Za-z_][\\w.-]*:)?Relationship\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TARGET_MODE_ATTRIBUTE = Regex("\\bTargetMode\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
    private val TARGET_ATTRIBUTE = Regex("\\bTarget\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
    private val TYPE_ATTRIBUTE = Regex("\\bType\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
    private val EXTERNAL_TARGET_REGEX = Regex("^(?:https?|file|ftp|data):", RegexOption.IGNORE_CASE)
    private val SAFE_HYPERLINK_TARGET_REGEX = Regex("^(?:https?|mailto):", RegexOption.IGNORE_CASE)
}
