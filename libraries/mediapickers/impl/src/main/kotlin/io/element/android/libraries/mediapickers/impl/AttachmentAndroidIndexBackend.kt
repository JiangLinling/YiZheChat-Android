/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File

internal class AttachmentAndroidIndexBackend(
    private val context: Context,
    private val accountId: String,
    private val store: AttachmentSourceStore,
    private val storageRoots: () -> List<File> = { attachmentStorageRoots(context) },
) : AttachmentIndexBackend {
    private var probeOffset = 0
    private val roots get() = storageRoots()
    override fun hasSharedAccess() = hasAttachmentSharedAccess()
    override fun allowedSources(sources: List<AttachmentSource>): List<AttachmentSource> {
        val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        return sources.filter { source ->
            if (source.managed) attachmentDiskFile(source.uri)?.let { it.isDirectory && it.canRead() } == true else source.uri in grants
        }
    }
    override suspend fun load() = store.loadIndex(accountId)
    override suspend fun save(snapshot: AttachmentIndexSnapshot) {
        store.saveIndex(accountId, snapshot)
    }
    override suspend fun sources(sources: List<AttachmentSource>) = scanAttachmentSources(context.contentResolver, sources)
    override suspend fun mediaStamp(): String = try {
        volumes().sorted().joinToString("|") { volume ->
            "$volume:${MediaStore.getVersion(context, volume)}:${MediaStore.getGeneration(context, volume)}"
        }
    } catch (_: Exception) {
        // Unknown version is never treated as an unchanged authoritative checkpoint.
        "unknown:${System.nanoTime()}"
    }
    private fun volumes(): Set<String> = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context) else setOf("external")

    override suspend fun media(): AttachmentIndex {
        val files = mutableListOf<AttachmentFile>()
        var truncated = false
        val roots = roots.map { it.canonicalFile }
        val parents = mutableMapOf<String, File>()
        val columns = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATA
        )
        for (volume in volumes()) {
            currentCoroutineContext().ensureActive()
            if (files.size >= 50_000) break
            context.contentResolver.query(
                MediaStore.Files.getContentUri(volume),
                columns,
                "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0",
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val name = cursor.getString(1).orEmpty()
                    if (!isAttachmentIndexableName(name)) continue
                    val dataFile = cursor.getString(6)?.let(::File) ?: continue
                    val parentPath = dataFile.parent ?: continue
                    val parent = parents.getOrPut(parentPath) { File(parentPath).canonicalFile }
                    val file = File(parent, dataFile.name)
                    val root = roots.firstOrNull { isAttachmentResolvedSharedPath(file, it) } ?: continue
                    // Resolving each parent once preserves the private-directory
                    // boundary; final-component symlinks are excluded.
                    if (java.nio.file.Files.isSymbolicLink(file.toPath())) continue
                    if (!file.isFile) continue
                    files += AttachmentFile(
                        android.net.Uri.fromFile(file),
                        name,
                        cursor.getString(2) ?: "application/octet-stream",
                        if (cursor.isNull(3)) null else cursor.getLong(3),
                        cursor.getLong(4) * 1000,
                        cursor.getLong(5) * 1000,
                        file.parentFile?.relativeTo(root)?.path.orEmpty(),
                        android.net.Uri.fromFile(root).toString()
                    )
                    if (files.size >= 50_000) {
                        truncated = true
                        break
                    }
                }
            }
        }
        return AttachmentIndex(files.sortedWith(ATTACHMENT_NEWEST_FIRST), emptyList(), 0, truncated)
    }

    override suspend fun baseline(directories: MutableMap<String, Long>, onUpdate: suspend (AttachmentIndex) -> Unit) =
        scanAttachmentSharedFiles(roots, onDirectory = { directory, time -> directories[directory.path] = time }, onUpdate = onUpdate)

    override suspend fun reconcile(snapshot: AttachmentIndexSnapshot, dirty: Set<String>, force: Boolean): AttachmentIndexSnapshot {
        val roots = roots
        val stamps = snapshot.directories.toMutableMap()
        val files = snapshot.disk.files.associateBy { it.uri.toString() }.toMutableMap()
        val paths = stamps.keys.toList()
        val rotating = if (paths.isEmpty()) emptyList() else List(minOf(128, paths.size)) { paths[(probeOffset + it) % paths.size] }
        if (paths.isNotEmpty()) probeOffset = (probeOffset + rotating.size) % paths.size
        val priority = roots.flatMap { listOf(it.path, File(it, "Download").path, File(it, "Documents").path) }
        // Overwriting a file need not change its parent directory stamp.
        val watched = watchDirectories(snapshot).toSet()
        val pending = ArrayDeque((if (force) paths + watched else rotating + priority).plus(dirty).distinct())
        val visited = mutableSetOf<String>()
        val changedPaths = mutableSetOf<String>()
        val deletedPaths = mutableSetOf<String>()
        var changed = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val path = pending.removeFirst()
            if (!visited.add(path)) continue
            val directory = File(path)
            val root = roots.firstOrNull { directory.canonicalFile == it.canonicalFile || isAttachmentSharedPath(directory, it) } ?: continue
            if (!directory.isDirectory) {
                deletedPaths.add(path)
                files.entries.removeAll { attachmentDiskFile(it.value.uri)?.toPath()?.startsWith(directory.toPath()) == true }
                stamps.keys.removeAll { File(it).toPath().startsWith(directory.toPath()) }
                continue
            }
            val modified = directory.lastModified()
            if (path !in dirty && !(force && path in watched) && stamps[path] == modified) continue
            val children = directory.listFiles() ?: continue
            changed++
            changedPaths.add(path)
            val childPaths = children.map { it.path }.toSet()
            val removedDirectories = stamps.keys.filter { File(it).parent == path && it !in childPaths }
            removedDirectories.forEach { removed ->
                deletedPaths.add(removed)
                files.entries.removeAll { attachmentDiskFile(it.value.uri)?.toPath()?.startsWith(File(removed).toPath()) == true }
                stamps.keys.removeAll { File(it).toPath().startsWith(File(removed).toPath()) }
            }
            files.entries.removeAll { attachmentDiskFile(it.value.uri)?.parentFile == directory }
            stamps[path] = modified
            children.forEach { child ->
                if (!isAttachmentSharedPath(child, root) || !isAttachmentIndexableName(child.name)) return@forEach
                if (child.isDirectory) {
                    if (child.path !in stamps && stamps.size < 50_000) pending.add(child.path)
                } else if (child.isFile && child.canRead()) {
                    val entry = attachmentFileFromDisk(child, root)
                    files[entry.uri.toString()] = entry
                }
            }
        }
        Timber.tag("AttachmentIndex").d("event=directory_reconcile probed=%d changed=%d force=%s", visited.size, changed, force)
        val sorted = files.values.sortedWith(ATTACHMENT_NEWEST_FIRST)
        // Provider notifications can lag filesystem notifications. Update only
        // affected cached rows, without traversing unrelated directories.
        val mediaFiles = snapshot.media.files.mapNotNull { item ->
            val file = attachmentDiskFile(item.uri) ?: return@mapNotNull item
            if (deletedPaths.any { file.toPath().startsWith(File(it).toPath()) }) return@mapNotNull null
            if (file.parent !in changedPaths) return@mapNotNull item
            val root = roots.firstOrNull { isAttachmentSharedPath(file, it) } ?: return@mapNotNull null
            if (!file.isFile || !file.canRead()) null else attachmentFileFromDisk(file, root).copy(addedAt = item.addedAt)
        }
        return snapshot.copy(
            disk = snapshot.disk.copy(files = sorted.take(50_000), truncated = snapshot.disk.truncated || sorted.size > 50_000),
            media = snapshot.media.copy(files = mediaFiles.sortedWith(ATTACHMENT_NEWEST_FIRST)),
            directories = stamps
        )
    }

    override fun watchDirectories(snapshot: AttachmentIndexSnapshot): List<String> {
        if (!snapshot.sharedAccess) return emptyList()
        val parents = snapshot.projection().files.filter { matchesAttachmentCategory(it.name, "All") }
            .mapNotNull { attachmentDiskFile(it.uri)?.parent }.distinct()
        val preferred = roots.flatMap { listOf(it.path, File(it, "Download").path, File(it, "Documents").path) }
        return (preferred + snapshot.added.folders.mapNotNull { attachmentDiskFile(android.net.Uri.parse(it.first))?.path } + parents)
            .distinct().filter { File(it).isDirectory }.take(64)
    }
}

internal fun isAttachmentIndexableName(name: String): Boolean = name != MANAGED_FOLDER_MARKER && name != ".nomedia" && name != ".thumbnails" &&
    !name.startsWith(".pending-") && !name.startsWith(".trashed-")
