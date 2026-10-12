/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Phone-file organization. These remain user-owned shared files, separate from protected message storage. */
internal class AttachmentFileManager(
    private val store: AttachmentSourceStore,
    private val canAccess: () -> Boolean = ::hasAttachmentSharedAccess,
    private val sharedRoot: () -> File = { Environment.getExternalStorageDirectory() },
) {
    suspend fun createFolder(accountId: String, name: String, operationId: String, parentUri: Uri? = null) = withContext(Dispatchers.IO) {
        val parent = parentUri?.let { ownedFolder(accountId, it) } ?: store.managedRoot(accountId)
        val target = File(parent, attachmentSafeName(name))
        if (target.exists()) throw AttachmentNameCollisionException()
        val plan = basePlan(operationId, "folder").put("target", target.path)
        store.persistManagement(accountId, plan)
        execute(accountId, plan)
    }

    suspend fun createText(accountId: String, folder: AttachmentSource, name: String, content: String, operationId: String) =
        withContext(Dispatchers.IO) {
            val parent = ownedFolder(accountId, folder.uri)
            val fileName = attachmentSafeName(name).let { if ('.' in it) it else "$it.txt" }
            val target = File(parent, fileName)
            if (target.exists()) throw AttachmentNameCollisionException()
            check(content.encodeToByteArray().size <= MAX_CREATED_TEXT_BYTES)
            val plan = basePlan(operationId, "text").put("target", target.path).put("content", content)
                .put("sha256", sha256(content.encodeToByteArray()))
            store.persistManagement(accountId, plan)
            execute(accountId, plan)
        }

    suspend fun move(accountId: String, selected: List<AttachmentFile>, folder: AttachmentSource, operationId: String, targetName: String? = null) =
        withContext(Dispatchers.IO) {
            check(selected.isNotEmpty() && selected.size <= 9)
            check(targetName == null || selected.size == 1)
            val parent = ownedFolder(accountId, folder.uri)
            val entries = JSONArray()
            val targets = mutableSetOf<String>()
            selected.forEach { item ->
                currentCoroutineContext().ensureActive()
                val source = attachmentDiskFile(item.uri) ?: error("This provider does not support moving files.")
                check(isAttachmentSharedPath(source, sharedRoot()))
                check(source.isFile && source.canRead())
                val target = File(parent, attachmentSafeName(targetName ?: source.name)).canonicalFile
                if (source.canonicalFile != target) {
                    if (!targets.add(target.path) || target.exists()) throw AttachmentNameCollisionException()
                    entries.put(JSONObject().put("source", source.canonicalPath).put("target", target.path).put("sha256", attachmentDiskHash(source)))
                }
            }
            val plan = basePlan(operationId, "move").put("entries", entries)
            store.persistManagement(accountId, plan)
            execute(accountId, plan)
        }

    suspend fun resume(accountId: String) = withContext(Dispatchers.IO) {
        execute(accountId, checkNotNull(store.pendingManagement(accountId)))
    }

    suspend fun cancel(accountId: String) {
        val plan = checkNotNull(store.pendingManagement(accountId))
        // Retain user files, including already-applied parts of a partial batch.
        store.completeManagement(accountId, plan.getString("operationId"))
    }

    private suspend fun execute(accountId: String, plan: JSONObject) {
        check(canAccess()) { "Shared file access was revoked." }
        val operationId = plan.getString("operationId")
        when (plan.getString("kind")) {
            "folder" -> {
                val root = store.managedRoot(accountId)
                val target = File(plan.getString("target")).canonicalFile
                val topLevel = target.parentFile == root
                if (!topLevel) ownedFolder(accountId, Uri.fromFile(checkNotNull(target.parentFile)))
                check(root.mkdirs() || root.isDirectory)
                val marker = File(target, MANAGED_FOLDER_MARKER)
                if (!target.exists()) {
                    Files.createDirectory(target.toPath())
                    Files.write(marker.toPath(), operationId.encodeToByteArray(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                check(target.isDirectory && marker.isFile && marker.readText() == operationId)
                store.completeManagement(accountId, operationId, target.takeIf { topLevel })
            }
            "text" -> {
                val target = File(plan.getString("target")).canonicalFile
                ownedFolder(accountId, Uri.fromFile(checkNotNull(target.parentFile)))
                if (!target.exists()) {
                    val bytes = plan.getString("content").encodeToByteArray()
                    try {
                        FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                            val buffer = ByteBuffer.wrap(bytes)
                            while (buffer.hasRemaining()) channel.write(buffer)
                            channel.force(true)
                        }
                    } finally {
                        bytes.fill(0)
                    }
                }
                check(target.isFile && attachmentDiskHash(target) == plan.getString("sha256"))
                store.completeManagement(accountId, operationId)
            }
            "move" -> {
                val entries = plan.getJSONArray("entries")
                for (index in 0 until entries.length()) {
                    currentCoroutineContext().ensureActive()
                    val entry = entries.getJSONObject(index)
                    val source = File(entry.getString("source")).canonicalFile
                    val target = File(entry.getString("target")).canonicalFile
                    check(isAttachmentSharedPath(source, sharedRoot()))
                    ownedFolder(accountId, Uri.fromFile(checkNotNull(target.parentFile)))
                    if (source.exists()) {
                        check(source.isFile && attachmentDiskHash(source) == entry.getString("sha256"))
                        if (target.exists()) throw AttachmentNameCollisionException()
                        // Same shared-storage volume only. No unverified copy followed by source deletion.
                        Files.move(source.toPath(), target.toPath())
                    }
                    check(!source.exists() && target.isFile && attachmentDiskHash(target) == entry.getString("sha256"))
                }
                store.completeManagement(accountId, operationId)
            }
            else -> error("Unknown file organization operation.")
        }
    }

    private suspend fun ownedFolder(accountId: String, uri: Uri): File {
        val folder = checkNotNull(attachmentDiskFile(uri)).canonicalFile
        val accountRoot = store.managedRoot(accountId)
        val source = store.load(accountId).firstOrNull { candidate ->
            candidate.managed && attachmentDiskFile(candidate.uri)?.canonicalFile?.let { root ->
                root.parentFile == accountRoot && root.isDirectory && folder.toPath().startsWith(root.toPath())
            } == true
        } ?: error("Folder is not owned by this account.")
        check(source.managed && folder.isDirectory && folder.canWrite())
        return folder
    }

    private fun basePlan(operationId: String, kind: String): JSONObject {
        require(operationId.length in 16..256)
        return JSONObject().put("operationId", operationId).put("kind", kind)
    }
}

internal fun attachmentSafeName(raw: String): String {
    val name = raw.trim()
    require(name.isNotEmpty() && name.length <= 100 && !name.startsWith('.'))
    require(name.none { it == '/' || it == '\\' || it.isISOControl() })
    return name
}

internal fun attachmentDiskFile(uri: Uri): File? = when {
    uri.scheme == "file" -> uri.path?.let(::File)
    uri.authority == "com.android.externalstorage.documents" -> runCatching {
        val id = android.provider.DocumentsContract.getDocumentId(uri)
        if (id.substringBefore(':') == "primary") File(Environment.getExternalStorageDirectory(), id.substringAfter(':')) else null
    }.getOrNull()
    else -> null
}

internal fun attachmentDiskHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    try {
        file.inputStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } finally {
        buffer.fill(0)
    }
}

private const val MAX_CREATED_TEXT_BYTES = 32 * 1024

internal class AttachmentNameCollisionException : IllegalStateException()
