/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class AttachmentDirectory(val uri: Uri, val name: String)
internal data class AttachmentDirectoryListing(
    val uri: Uri,
    val directories: List<AttachmentDirectory>,
    val files: List<AttachmentFile>,
    val truncated: Boolean = false,
)

internal fun attachmentDirectoryUri(source: AttachmentSource): Uri = if (source.uri.scheme == "file") {
    source.uri
} else {
    DocumentsContract.buildDocumentUriUsingTree(source.uri, DocumentsContract.getTreeDocumentId(source.uri))
}

/** Reads one directory level. It never substitutes the global recent projection for folder contents. */
internal suspend fun browseAttachmentDirectory(
    resolver: ContentResolver,
    source: AttachmentSource,
    uri: Uri = attachmentDirectoryUri(source),
    hasSharedAccess: () -> Boolean = ::hasAttachmentSharedAccess,
): AttachmentDirectoryListing = withContext(Dispatchers.IO) {
    check(source.tree)
    if (source.uri.scheme == "file") {
        check(hasSharedAccess())
        val root = checkNotNull(attachmentDiskFile(source.uri)).canonicalFile
        val directory = checkNotNull(attachmentDiskFile(uri)).canonicalFile
        check(directory.toPath().startsWith(root.toPath()) && directory.isDirectory)
        val children = checkNotNull(directory.listFiles())
        val folders = mutableListOf<AttachmentDirectory>()
        val files = mutableListOf<AttachmentFile>()
        children.take(MAX_INDEX_FILES).forEach { child ->
            currentCoroutineContext().ensureActive()
            if (child.name == MANAGED_FOLDER_MARKER || child.name == ".nomedia" || child.name == ".thumbnails" ||
                child.name.startsWith(".pending-") || child.name.startsWith(".trashed-")) {
                    return@forEach
                }
            val resolved = child.canonicalFile
            if (!isAttachmentResolvedSharedPath(resolved, root) || !resolved.canRead()) return@forEach
            if (resolved.isDirectory) {
                folders += AttachmentDirectory(Uri.fromFile(resolved), child.name)
            } else if (resolved.isFile) {
                files += attachmentFileFromDisk(resolved, root).copy(source = directory.name, root = source.uri.toString())
            }
        }
        AttachmentDirectoryListing(
            uri,
            folders.sortedBy { it.name.lowercase(Locale.ROOT) },
            files.sortedWith(ATTACHMENT_NEWEST_FIRST),
            children.size > MAX_INDEX_FILES
        )
    } else {
        check(uri.authority == source.uri.authority && DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(source.uri))
        val parentId = DocumentsContract.getDocumentId(uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(source.uri, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val folders = mutableListOf<AttachmentDirectory>()
        val files = mutableListOf<AttachmentFile>()
        val cursor = checkNotNull(resolver.query(children, columns, null, null, null))
        var truncated = false
        cursor.use {
            while (it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (files.size + folders.size >= MAX_INDEX_FILES) {
                    truncated = true
                    break
                }
                val childUri = DocumentsContract.buildDocumentUriUsingTree(source.uri, it.getString(0))
                val name = it.getString(1).orEmpty()
                val mime = it.getString(2).orEmpty()
                if (name == MANAGED_FOLDER_MARKER) continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders += AttachmentDirectory(childUri, name)
                } else {
                    files += AttachmentFile(
                        childUri,
                        name,
                        mime,
                        if (it.isNull(3)) null else it.getLong(3),
                    if (it.isNull(4)) 0 else it.getLong(4),
                        0,
                        "",
                        source.uri.toString()
                    )
                }
            }
        }
        AttachmentDirectoryListing(uri, folders.sortedBy { it.name.lowercase(Locale.ROOT) }, files.sortedWith(ATTACHMENT_NEWEST_FIRST), truncated)
    }
}
