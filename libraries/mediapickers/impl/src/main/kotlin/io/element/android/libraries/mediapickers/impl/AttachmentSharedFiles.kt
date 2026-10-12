/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale
import java.util.PriorityQueue

internal fun hasAttachmentSharedAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

internal fun attachmentStorageRoots(context: Context): List<File> {
    // Some devices return no volume directory even after the special permission is granted.
    val primary = Environment.getExternalStorageDirectory()
    val volumes = if (Build.VERSION.SDK_INT >= 30) {
        context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }
    } else {
        emptyList()
    }
    return (listOf(primary) + volumes).distinctBy { it.canonicalPath }
}

internal fun isAttachmentSharedPath(file: File, root: File): Boolean {
    return isAttachmentResolvedSharedPath(file.canonicalFile, root.canonicalFile)
}

/** Both arguments have already had their symlinks resolved by discovery. */
internal fun isAttachmentResolvedSharedPath(file: File, root: File): Boolean {
    val path = file.toPath()
    val base = root.toPath()
    if (!path.startsWith(base) || path == base) return false
    val relative = base.relativize(path).toString().replace('\\', '/')
    return relative != "Android/data" && !relative.startsWith("Android/data/") &&
        relative != "Android/obb" && !relative.startsWith("Android/obb/")
}

internal suspend fun scanAttachmentSharedFiles(
    roots: List<File>,
    query: String = "",
    onDirectory: (File, Long) -> Unit = { _, _ -> },
    onUpdate: suspend (AttachmentIndex) -> Unit = {},
): AttachmentIndex {
    val newest = PriorityQueue<AttachmentFile>(compareBy<AttachmentFile> { it.modified }.thenBy { it.name })
    val visited = mutableSetOf<String>()
    val pending = ArrayDeque<Pair<File, File>>()
    roots.forEach { pending.add(it to it) }
    var truncated = false
    var lastPublished = 0L
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (directory, root) = pending.removeFirst()
        if (!visited.add(directory.canonicalPath)) continue
        if (visited.size > MAX_SHARED_DIRECTORIES) {
            truncated = true
            break
        }
        val children = directory.listFiles()
        onDirectory(directory.canonicalFile, directory.lastModified())
        if (children == null) {
            truncated = true
            continue
        }
        children.forEach { file ->
            currentCoroutineContext().ensureActive()
            if (!isAttachmentSharedPath(file, root)) return@forEach
            if (file.name == ".thumbnails" || file.name.startsWith(".pending-") || file.name.startsWith(".trashed-")) return@forEach
            if (file.isDirectory) {
                val relative = file.relativeTo(root).path.replace('\\', '/')
                if (relative == "Download" || relative.startsWith("Download/") || relative == "Documents" || relative.startsWith("Documents/")) {
                    pending.addFirst(file to root)
                } else {
                    pending.add(file to root)
                }
            } else if (file.isFile && file.canRead() && file.name != ".nomedia" && file.name != MANAGED_FOLDER_MARKER) {
                if (!file.name.contains(query.trim(), ignoreCase = true)) return@forEach
                newest.add(attachmentFileFromDisk(file, root))
                if (newest.size > MAX_SHARED_FILES) {
                    newest.poll()
                    truncated = true
                }
                if (System.nanoTime() - lastPublished > 250_000_000L) {
                    onUpdate(AttachmentIndex(newest.sortedWith(ATTACHMENT_NEWEST_FIRST), emptyList(), 0, truncated))
                    lastPublished = System.nanoTime()
                }
            }
        }
    }
    return AttachmentIndex(newest.sortedWith(ATTACHMENT_NEWEST_FIRST), emptyList(), 0, truncated)
}

internal fun attachmentFileFromDisk(file: File, root: File): AttachmentFile = AttachmentFile(
    uri = Uri.fromFile(file.canonicalFile),
    name = file.name,
    mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "application/octet-stream",
    size = file.length(),
    modified = file.lastModified(),
    addedAt = 0,
    source = runCatching { file.parentFile!!.canonicalFile.relativeTo(root.canonicalFile).path }.getOrDefault(file.parentFile?.name.orEmpty()),
    root = Uri.fromFile(root.canonicalFile).toString(),
)

internal val ATTACHMENT_NEWEST_FIRST = compareByDescending<AttachmentFile> { it.modified }.thenByDescending { it.addedAt }.thenBy { it.name }
internal const val MANAGED_FOLDER_MARKER = ".yizhe-folder"
private const val MAX_SHARED_DIRECTORIES = 50_000
private const val MAX_SHARED_FILES = 50_000
