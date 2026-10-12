/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class AttachmentSource(val uri: Uri, val tree: Boolean, val addedAt: Long, val managed: Boolean = false)

/** Only external source capabilities are retained. Names and contents never enter plaintext preferences. */
@Inject
@SingleIn(AppScope::class)
class AttachmentSourceStore(
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val files: SensitiveFileStore,
) {
    private val mutex = Mutex()
    private val preferences get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    internal suspend fun load(accountId: String): List<AttachmentSource> = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked(vault(accountId)) }
    }

    internal suspend fun loadIndex(accountId: String): AttachmentIndexSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val vaultId = vault(accountId)
            val reference = reference(vaultId, index = true) ?: return@withLock null
            check(reference.vaultId == vaultId && reference.purpose == SecureObjectPurpose.ATTACHMENT)
            check(reference.associatedDataSha256 == sha256(indexBinding(vaultId)))
            check(reference.plaintextLength in 0..MAX_FILE_INDEX_BYTES)
            val decoded = files.read(reference) { stream ->
                val payload = stream.readBytes()
                try {
                    AttachmentIndexSnapshot.decode(payload)
                } catch (_: JSONException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } finally {
                    payload.fill(0)
                }
            }
            // Only authenticated metadata/schema corruption is recoverable. Vault
            // or secure-store failures still propagate and cannot recreate authority.
            if (decoded == null) {
                check(preferences.edit().remove(indexKey(vaultId)).commit())
                files.delete(reference)
            }
            decoded
        }
    }

    internal suspend fun saveIndex(accountId: String, snapshot: AttachmentIndexSnapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val vaultId = vault(accountId)
            val payload = snapshot.encode()
            val next = try {
                check(payload.size <= MAX_FILE_INDEX_BYTES)
                files.write(SecureObjectWriteRequest(
                    objectId = SecureObjectId("attachment-index:${UUID.randomUUID()}"),
                    vaultId = vaultId,
                    purpose = SecureObjectPurpose.ATTACHMENT,
                    associatedData = indexBinding(vaultId),
                    expectedPlaintextLength = payload.size.toLong(),
                )) { it.write(payload) }
            } finally {
                payload.fill(0)
            }
            val old = reference(vaultId, index = true)
            if (!preferences.edit().putString(indexKey(vaultId), encode(next, emptyList())).commit()) {
                files.delete(next)
                error("Attachment index persistence failed.")
            }
            old?.let { files.delete(it) }
        }
    }

    internal suspend fun add(accountId: String, uris: List<Uri>, tree: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(uris.all {
                it.scheme == "content" && it.authority != "${context.packageName}.securemedia" &&
                !it.authority.orEmpty().startsWith(context.packageName)
            })
            val vaultId = vault(accountId)
            val previous = loadLocked(vaultId)
            val additions = uris.distinct().map { uri -> AttachmentSource(uri, tree, System.currentTimeMillis()) }
            val next = (previous.filterNot { old -> additions.any { it.uri == old.uri } } + additions)
            check(next.size <= MAX_SOURCES) { "Attachment source limit reached." }
            val existingGrants = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
            var committed = false
            try {
                additions.forEach { context.contentResolver.takePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                persistLocked(vaultId, next, registryLocked(vaultId).optJSONObject("pending")) { committed = true }
            } catch (failure: Exception) {
                additions.filterNot { committed || it.uri in existingGrants }.forEach {
                    runCatching { context.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                throw failure
            }
        }
    }

    internal suspend fun managedRoot(accountId: String): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "YiZheChat/${sha256(vault(accountId).value.encodeToByteArray()).take(16)}",
    ).canonicalFile

    internal suspend fun pendingManagement(accountId: String): JSONObject? = withContext(Dispatchers.IO) {
        mutex.withLock { registryLocked(vault(accountId)).optJSONObject("pending") }
    }

    /** This is the encrypted local intent for the existing LC-16 operation, not another coordinator. */
    internal suspend fun persistManagement(accountId: String, plan: JSONObject) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val vaultId = vault(accountId)
            val existing = registryLocked(vaultId).optJSONObject("pending")
            check(existing == null || existing.getString("operationId") == plan.getString("operationId"))
            persistLocked(vaultId, loadLocked(vaultId), plan)
        }
    }

    internal suspend fun completeManagement(accountId: String, operationId: String, folder: File? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val vaultId = vault(accountId)
            check(registryLocked(vaultId).getJSONObject("pending").getString("operationId") == operationId)
            val previous = loadLocked(vaultId)
            val next = if (folder == null) {
                previous
            } else {
                val root = managedRoot(accountId)
                val directory = folder.canonicalFile
                check(directory.parentFile == root && directory.isDirectory)
                val uri = Uri.fromFile(directory)
                previous.filterNot { it.uri == uri } + AttachmentSource(uri, true, System.currentTimeMillis(), managed = true)
            }
            check(next.size <= MAX_SOURCES)
            persistLocked(vaultId, next, null)
        }
    }

    private suspend fun persistLocked(
        vaultId: AccountVaultId,
        sources: List<AttachmentSource>,
        pending: JSONObject?,
        onCommitted: () -> Unit = {},
    ) {
        val payload = JSONObject().put("sources", JSONArray().apply {
            sources.forEach {
                put(JSONObject().put("uri", it.uri.toString()).put("tree", it.tree).put("added", it.addedAt).put("managed", it.managed))
            }
        }).apply { if (pending != null) put("pending", pending) }.toString().encodeToByteArray()
        val reference = try {
            check(payload.size <= MAX_INDEX_BYTES)
            files.write(SecureObjectWriteRequest(
                objectId = SecureObjectId("attachment-sources:${UUID.randomUUID()}"),
                vaultId = vaultId,
                purpose = SecureObjectPurpose.ATTACHMENT,
                associatedData = binding(vaultId),
                expectedPlaintextLength = payload.size.toLong(),
            )) { it.write(payload) }
        } finally {
            payload.fill(0)
        }
        val oldReference = reference(vaultId)
        if (!preferences.edit().putString(key(vaultId), encode(reference, sources)).commit()) {
            files.delete(reference)
            error("Attachment source persistence failed.")
        }
        onCommitted()
        oldReference?.let { files.delete(it) }
    }

    private suspend fun vault(accountId: String): AccountVaultId {
        val session = checkNotNull(sessions.getSession(accountId)) { "Attachment account is unavailable." }
        return deriveAccountVaultId(session.userId, session.deviceId)
    }

    private suspend fun loadLocked(vaultId: AccountVaultId): List<AttachmentSource> {
        val array = registryLocked(vaultId).getJSONArray("sources")
        check(array.length() <= MAX_SOURCES)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            AttachmentSource(Uri.parse(item.getString("uri")), item.getBoolean("tree"), item.getLong("added"), item.optBoolean("managed"))
        }
    }

    private suspend fun registryLocked(vaultId: AccountVaultId): JSONObject {
        val reference = reference(vaultId) ?: return JSONObject().put("sources", JSONArray())
        check(reference.vaultId == vaultId && reference.purpose == SecureObjectPurpose.ATTACHMENT)
        check(reference.associatedDataSha256 == sha256(binding(vaultId)))
        check(reference.plaintextLength in 0..MAX_INDEX_BYTES)
        return files.read(reference) { stream ->
            val bytes = stream.readBytes()
            try {
                val text = bytes.decodeToString()
                if (text.startsWith("[")) JSONObject().put("sources", JSONArray(text)) else JSONObject(text)
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun reference(vaultId: AccountVaultId, index: Boolean = false): SecureObjectReference? {
        val raw = preferences.getString(if (index) indexKey(vaultId) else key(vaultId), null) ?: return null
        val json = JSONObject(raw)
        check(json.getString("vault_id") == vaultId.value)
        return SecureObjectReference(
            SecureObjectId(json.getString("object_id")),
            vaultId,
            SecureObjectPurpose.ATTACHMENT,
            KeyEnvelopeId(json.getString("key_id")),
            json.getInt("version"),
            json.getLong("length"),
            json.getLong("cipher_length"),
            json.getString("aad"),
        )
    }

    private fun encode(reference: SecureObjectReference, sources: List<AttachmentSource>): String = JSONObject()
        .put("vault_id", reference.vaultId.value).put("object_id", reference.objectId.value)
        .put("key_id", reference.keyEnvelopeId.value).put("version", reference.formatVersion)
        .put("length", reference.plaintextLength).put("cipher_length", reference.ciphertextLength)
        .put("aad", reference.associatedDataSha256)
        .put("uri_hashes", JSONArray(sources.map { sha256(it.uri.toString().encodeToByteArray()) })).toString()

    private fun binding(vaultId: AccountVaultId) = "yizhe-attachment-sources-v1:${vaultId.value}".encodeToByteArray()
    private fun key(vaultId: AccountVaultId) = sha256(vaultId.value.encodeToByteArray())
    private fun indexKey(vaultId: AccountVaultId) = "${key(vaultId)}:index"
    private fun indexBinding(vaultId: AccountVaultId) = "yizhe-attachment-index-v1:${vaultId.value}".encodeToByteArray()

    companion object {
        const val PREFERENCES = "encrypted_attachment_sources_v1"
        const val MAX_SOURCES = 128
        private const val MAX_INDEX_BYTES = 128L * 1024
        private const val MAX_FILE_INDEX_BYTES = 32L * 1024 * 1024
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
