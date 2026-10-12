/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

internal data class AttachmentFile(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long?,
    val modified: Long,
    val addedAt: Long,
    val source: String,
    val root: String,
)

internal data class AttachmentIndex(
    val files: List<AttachmentFile>,
    val folders: List<Pair<String, String>>,
    val failedSources: Int,
    val truncated: Boolean,
)

internal suspend fun scanAttachmentSources(resolver: ContentResolver, sources: List<AttachmentSource>): AttachmentIndex {
    val files = linkedMapOf<String, AttachmentFile>()
    val folders = mutableListOf<Pair<String, String>>()
    var failed = 0
    var truncated = false
    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )
    for (source in sources) {
        currentCoroutineContext().ensureActive()
        try {
            if (source.managed) {
                val directory = checkNotNull(attachmentDiskFile(source.uri)).canonicalFile
                check(directory.isDirectory)
                folders += source.uri.toString() to directory.name
                val local = scanAttachmentSharedFiles(listOf(directory))
                local.files.forEach { item -> files[item.uri.toString()] = item.copy(source = directory.name, root = source.uri.toString()) }
                truncated = truncated || local.truncated
                continue
            }
            if (!source.tree) {
                readAttachmentFile(resolver, source)?.let { files[attachmentIdentity(it.uri)] = it }
                continue
            }
            val rootId = DocumentsContract.getTreeDocumentId(source.uri)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(source.uri, rootId)
            val rootName = resolver.query(rootUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: error("Attachment directory disappeared.")
            folders += source.uri.toString() to rootName
            val pending = ArrayDeque<Pair<String, Int>>()
            val visited = mutableSetOf<String>()
            pending.add(rootId to 0)
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (id, depth) = pending.removeFirst()
                if (!visited.add(id)) continue
                if (files.size >= MAX_INDEX_FILES || visited.size > MAX_INDEX_FILES || depth > MAX_INDEX_DEPTH) {
                    truncated = true
                    break
                }
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(source.uri, id)
                val cursor = resolver.query(children, columns, null, null, null) ?: error("Attachment directory unavailable.")
                cursor.use {
                    while (it.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val childId = it.getString(0)
                        val name = it.getString(1).orEmpty()
                        val mime = it.getString(2).orEmpty()
                        if (name == MANAGED_FOLDER_MARKER) continue
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(childId to depth + 1)
                        } else {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(source.uri, childId)
                            files[attachmentIdentity(uri)] = AttachmentFile(
                                uri,
                                name,
                                mime,
                                if (it.isNull(3)) null else it.getLong(3),
                                if (it.isNull(4)) 0 else it.getLong(4),
                                0,
                                rootName,
                                source.uri.toString()
                            )
                            if (files.size >= MAX_INDEX_FILES) {
                                truncated = true
                                break
                            }
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            failed++
        }
    }
    return AttachmentIndex(files.values.sortedWith(compareByDescending<AttachmentFile> { it.modified }
        .thenByDescending { it.addedAt }.thenBy { it.name }), folders, failed, truncated)
}

internal fun mergeAttachmentIndexes(shared: AttachmentIndex, added: AttachmentIndex): AttachmentIndex {
    val files = linkedMapOf<String, AttachmentFile>()
    (shared.files + added.files).forEach { file ->
        // Indexed disk paths were validated when discovered. Projection/search
        // must not resolve every path through the filesystem again.
        val identity = if (file.uri.scheme == "file") {
            file.uri.path ?: file.uri.toString()
        } else {
            attachmentDiskFile(file.uri)?.absoluteFile?.normalize()?.path ?: attachmentIdentity(file.uri)
        }
        files[identity] = file
    }
    return AttachmentIndex(
        files.values.sortedWith(ATTACHMENT_NEWEST_FIRST),
        added.folders,
        shared.failedSources + added.failedSources,
        shared.truncated || added.truncated
    )
}

internal fun readAttachmentFile(resolver: ContentResolver, source: AttachmentSource): AttachmentFile? {
    val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    fun query(projection: Array<String>) = resolver.query(source.uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) error("Attachment file disappeared.")
        val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        AttachmentFile(
            source.uri,
            cursor.getString(0).orEmpty(),
            resolver.getType(source.uri) ?: "application/octet-stream",
            if (cursor.isNull(1)) null else cursor.getLong(1),
            if (modifiedColumn < 0 || cursor.isNull(modifiedColumn)) 0 else cursor.getLong(modifiedColumn),
            source.addedAt,
            runCatching {
                DocumentsContract.getDocumentId(source.uri).substringBeforeLast('/', "").substringAfter(':')
            }.getOrDefault(""),
            source.uri.toString()
        )
    }
    return try {
        query(columns)
    } catch (_: IllegalArgumentException) {
        query(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
    }
        ?: error("Attachment file unavailable.")
}

internal fun attachmentCategory(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "doc", "docx", "docm" -> "Word"
    "xls", "xlsx", "xlsm", "csv" -> "Excel"
    "pdf" -> "PDF"
    "ppt", "pptx", "pptm" -> "PPT"
    "txt" -> "Text"
    "zip", "rar", "7z", "tar", "gz" -> "Archive"
    else -> "Other"
}

internal fun matchesAttachmentCategory(name: String, category: String): Boolean {
    val actual = attachmentCategory(name)
    val primary = actual in setOf("Word", "Excel", "PDF", "PPT", "Text", "Archive")
    return when (category) {
        "All" -> primary
        "Other" -> !primary
        else -> actual == category
    }
}

internal const val MAX_INDEX_FILES = 10_000
private const val MAX_INDEX_DEPTH = 32

private fun attachmentIdentity(uri: Uri): String = runCatching {
    "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
}.getOrDefault(uri.toString())
