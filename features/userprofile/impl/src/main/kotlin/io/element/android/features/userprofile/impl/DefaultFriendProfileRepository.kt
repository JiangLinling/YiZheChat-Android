/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.userprofile.impl

import android.content.SharedPreferences
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.userprofile.api.FriendPrivateProfile
import io.element.android.features.userprofile.api.FriendProfileRepository
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.security.contentprotection.api.AccountPrivateProfileKey
import io.element.android.security.contentprotection.api.EncryptedPrivateProfileDocument
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.dataprotection.api.DeletedIdentityProjection
import io.element.android.security.dataprotection.api.isDeletedMatrixUserId
import io.element.android.security.dataprotection.api.recordForMatrixUserId
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultFriendProfileRepository(
    private val matrixClient: MatrixClient,
    private val sensitiveFileStore: SensitiveFileStore,
    private val sharedPreferences: SharedPreferences,
    private val kmsShareClient: KmsShareClient,
    private val deletedIdentityProjection: DeletedIdentityProjection,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
) : FriendProfileRepository {
    private val mutex = Mutex()
    private val secureRandom = SecureRandom()
    private val mutableProfiles = MutableStateFlow<Map<UserId, FriendPrivateProfile>>(emptyMap())
    private var records: Map<UserId, ProfileRecord> = emptyMap()
    private var loaded = false

    override val profiles: StateFlow<Map<UserId, FriendPrivateProfile>> = mutableProfiles.asStateFlow()

    init {
        deletedIdentityProjection.revision
            .drop(1)
            .onEach { reconcileDeletedIdentities() }
            .launchIn(sessionCoroutineScope)
    }

    override suspend fun load() {
        mutex.withLock {
            if (loaded) return
            val local = applyDeletedIdentityTombstones(readLocal(), deletedIdentityProjection)
            records = synchronizeOrLocal(local)
            persistLocal(records)
            publish(records)
            loaded = true
        }
    }

    override suspend fun save(profile: FriendPrivateProfile) {
        check(!deletedIdentityProjection.isDeletedMatrixUserId(profile.userId.value)) {
            "A deleted identity profile cannot be recreated."
        }
        mutate { current ->
            current + (profile.userId to ProfileRecord(profile.normalized(), System.currentTimeMillis(), deleted = false))
        }
    }

    override suspend fun delete(userId: UserId) {
        mutate { current ->
            current + (userId to ProfileRecord(FriendPrivateProfile(userId), System.currentTimeMillis(), deleted = true))
        }
    }

    private suspend fun mutate(transform: (Map<UserId, ProfileRecord>) -> Map<UserId, ProfileRecord>) {
        mutex.withLock {
            if (!loaded) {
                val local = readLocal()
                records = synchronizeOrLocal(local)
                loaded = true
            }
            val updated = applyDeletedIdentityTombstones(transform(records), deletedIdentityProjection)
            persistLocal(updated)
            records = updated
            publish(updated)
            runCatching { synchronize(updated) }
                .onSuccess { synchronized ->
                    records = synchronized
                    persistLocal(synchronized)
                    publish(synchronized)
                    markSyncPending(false)
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    markSyncPending(true)
                }
        }
    }

    private suspend fun synchronize(local: Map<UserId, ProfileRecord>): Map<UserId, ProfileRecord> {
        val proposed = ByteArray(PROFILE_KEY_BYTES).also(secureRandom::nextBytes)
        var key: AccountPrivateProfileKey? = null
        try {
            key = kmsShareClient.ensurePrivateProfileKey(accountHash, proposed)
            val remote = kmsShareClient.fetchPrivateProfileDocument(accountHash)
            val remoteRecords = remote?.let { decryptRemote(it, key) }.orEmpty()
            var merged = applyDeletedIdentityTombstones(merge(remoteRecords, local), deletedIdentityProjection)
            if (remote == null || merged != remoteRecords) {
                merged = applyDeletedIdentityTombstones(uploadWithOneConflictRetry(key, remote, merged), deletedIdentityProjection)
            }
            remote?.clear()
            markSyncPending(false)
            return merged
        } finally {
            proposed.fill(0)
            key?.keyMaterial?.fill(0)
        }
    }

    private suspend fun uploadWithOneConflictRetry(
        key: AccountPrivateProfileKey,
        initialRemote: EncryptedPrivateProfileDocument?,
        desired: Map<UserId, ProfileRecord>,
    ): Map<UserId, ProfileRecord> {
        return try {
            upload(key, initialRemote?.documentVersion ?: 0, desired)
            desired
        } catch (firstFailure: Exception) {
            if (firstFailure is CancellationException) throw firstFailure
            val latest = kmsShareClient.fetchPrivateProfileDocument(accountHash) ?: throw firstFailure
            try {
                val merged = applyDeletedIdentityTombstones(merge(decryptRemote(latest, key), desired), deletedIdentityProjection)
                upload(key, latest.documentVersion, merged)
                merged
            } finally {
                latest.clear()
            }
        }
    }

    private suspend fun synchronizeOrLocal(local: Map<UserId, ProfileRecord>): Map<UserId, ProfileRecord> = try {
        synchronize(local)
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        markSyncPending(true)
        local
    }

    private suspend fun upload(key: AccountPrivateProfileKey, expectedVersion: Long, value: Map<UserId, ProfileRecord>) {
        val plaintext = encode(value).encodeToByteArray()
        val nonce = ByteArray(REMOTE_NONCE_BYTES).also(secureRandom::nextBytes)
        val aad = remoteAssociatedData(key.keyVersion)
        val aadHash = MessageDigest.getInstance(SHA256).digest(aad)
        val ciphertext = try {
            Cipher.getInstance(AES_GCM).run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.keyMaterial, AES), GCMParameterSpec(GCM_TAG_BITS, nonce))
                updateAAD(aad)
                doFinal(plaintext)
            }
        } finally {
            plaintext.fill(0)
            aad.fill(0)
        }
        try {
            kmsShareClient.putPrivateProfileDocument(
                accountIdHash = accountHash,
                keyVersion = key.keyVersion,
                expectedDocumentVersion = expectedVersion,
                ciphertext = ciphertext,
                nonce = nonce,
                associatedDataSha256 = aadHash,
            ).clear()
        } finally {
            ciphertext.fill(0)
            nonce.fill(0)
            aadHash.fill(0)
        }
    }

    private fun decryptRemote(remote: EncryptedPrivateProfileDocument, key: AccountPrivateProfileKey): Map<UserId, ProfileRecord> {
        if (remote.keyVersion != key.keyVersion || remote.nonce.size != REMOTE_NONCE_BYTES) {
            throw SecurityException("Private profile key binding is invalid.")
        }
        val aad = remoteAssociatedData(key.keyVersion)
        val aadHash = MessageDigest.getInstance(SHA256).digest(aad)
        if (!MessageDigest.isEqual(aadHash, remote.associatedDataSha256)) {
            aad.fill(0)
            aadHash.fill(0)
            throw SecurityException("Private profile associated data does not match the account binding.")
        }
        val plaintext = try {
            Cipher.getInstance(AES_GCM).run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key.keyMaterial, AES), GCMParameterSpec(GCM_TAG_BITS, remote.nonce))
                updateAAD(aad)
                doFinal(remote.ciphertext)
            }
        } finally {
            aad.fill(0)
            aadHash.fill(0)
        }
        return try {
            decode(plaintext.decodeToString())
        } finally {
            plaintext.fill(0)
        }
    }

    private fun merge(remote: Map<UserId, ProfileRecord>, local: Map<UserId, ProfileRecord>): Map<UserId, ProfileRecord> = buildMap {
        (remote.keys + local.keys).forEach { userId ->
            val remoteRecord = remote[userId]
            val localRecord = local[userId]
            put(userId, when {
                remoteRecord == null -> requireNotNull(localRecord)
                localRecord == null -> remoteRecord
                localRecord.modifiedAtEpochMillis >= remoteRecord.modifiedAtEpochMillis -> localRecord
                else -> remoteRecord
            })
        }
    }

    private suspend fun reconcileDeletedIdentities() {
        mutex.withLock {
            if (!loaded) return
            val updated = applyDeletedIdentityTombstones(records, deletedIdentityProjection)
            if (updated == records) return
            records = updated
            persistLocal(updated)
            publish(updated)
            runCatching { synchronize(updated) }
                .onSuccess { synchronized ->
                    records = synchronized
                    persistLocal(synchronized)
                    publish(synchronized)
                    markSyncPending(false)
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    markSyncPending(true)
                }
        }
    }

    private suspend fun persistLocal(value: Map<UserId, ProfileRecord>) {
        val oldReference = readReference()
        if (value.isEmpty()) {
            sharedPreferences.edit().remove(referenceKey).commit()
            oldReference?.let { runCatching { sensitiveFileStore.delete(it) } }
            return
        }
        val payload = encode(value).encodeToByteArray()
        val reference = sensitiveFileStore.write(
            SecureObjectWriteRequest(
                objectId = SecureObjectId("friend-profiles-v2"),
                vaultId = vaultId,
                purpose = SecureObjectPurpose.CONTACT_PROFILE,
                associatedData = localAssociatedData,
                expectedPlaintextLength = payload.size.toLong(),
            )
        ) { output -> output.write(payload) }
        payload.fill(0)
        check(sharedPreferences.edit().putString(referenceKey, encodeReference(reference)).commit())
        oldReference?.let { runCatching { sensitiveFileStore.delete(it) } }
    }

    private suspend fun readLocal(): Map<UserId, ProfileRecord> = readReference()?.let { reference ->
        runCatching { sensitiveFileStore.read(reference) { decode(it.readBytes().decodeToString()) } }.getOrDefault(emptyMap())
    }.orEmpty()

    private fun publish(value: Map<UserId, ProfileRecord>) {
        mutableProfiles.value = value.values
            .filterNot(ProfileRecord::deleted)
            .filterNot { deletedIdentityProjection.isDeletedMatrixUserId(it.profile.userId.value) }
            .associate { it.profile.userId to it.profile }
    }

    private val accountHash: String get() = accountIdHash(matrixClient.sessionId.value)
    private val vaultId: AccountVaultId get() = deriveAccountVaultId(matrixClient.sessionId.value, matrixClient.deviceId.value)
    private val localAssociatedData: ByteArray
        get() = "yizhe-friend-profiles-v2\u0000${matrixClient.sessionId.value}\u0000${matrixClient.deviceId.value}".encodeToByteArray()
    private val referenceKey: String
        get() = "friend_profiles_ref_" + MessageDigest.getInstance(SHA256)
            .digest(vaultId.value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val syncPendingKey: String get() = "friend_profiles_sync_pending_$accountHash"

    private fun remoteAssociatedData(keyVersion: Long): ByteArray =
        "yizhe-friend-profiles-remote-v1\u0000$accountHash\u0000$keyVersion".encodeToByteArray()

    private fun markSyncPending(pending: Boolean) {
        sharedPreferences.edit().putBoolean(syncPendingKey, pending).apply()
    }

    private fun FriendPrivateProfile.normalized() = copy(
        remarkName = remarkName.trim().take(80),
        phone = phone.trim().take(40),
        tags = tags.trim().take(120),
        description = description.trim().take(500),
    )

    private fun encode(value: Map<UserId, ProfileRecord>): String = JSONObject()
        .put("version", 2)
        .put("records", JSONArray().apply {
            value.values.sortedBy { it.profile.userId.value }.forEach { record ->
                put(JSONObject().apply {
                    put("userId", record.profile.userId.value)
                    put("remarkName", record.profile.remarkName)
                    put("phone", record.profile.phone)
                    put("tags", record.profile.tags)
                    put("description", record.profile.description)
                    put("modifiedAt", record.modifiedAtEpochMillis)
                    put("deleted", record.deleted)
                })
            }
        }).toString()

    private fun decode(payload: String): Map<UserId, ProfileRecord> {
        val root = JSONObject(payload)
        val version = root.optInt("version", 1)
        val array = if (version >= 2) root.getJSONArray("records") else root.getJSONArray("profiles")
        return buildMap {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val userId = UserId(item.getString("userId"))
                put(userId, ProfileRecord(
                    profile = FriendPrivateProfile(
                        userId = userId,
                        remarkName = item.optString("remarkName"),
                        phone = item.optString("phone"),
                        tags = item.optString("tags"),
                        description = item.optString("description"),
                    ),
                    modifiedAtEpochMillis = if (version >= 2) item.optLong("modifiedAt") else 0,
                    deleted = version >= 2 && item.optBoolean("deleted"),
                ))
            }
        }
    }

    private fun encodeReference(reference: SecureObjectReference): String = JSONObject().apply {
        put("objectId", reference.objectId.value)
        put("vaultId", reference.vaultId.value)
        put("purpose", reference.purpose.formatCode)
        put("keyEnvelopeId", reference.keyEnvelopeId.value)
        put("formatVersion", reference.formatVersion)
        put("plaintextLength", reference.plaintextLength)
        put("ciphertextLength", reference.ciphertextLength)
        put("associatedDataSha256", reference.associatedDataSha256)
    }.toString()

    private fun readReference(): SecureObjectReference? = sharedPreferences.getString(referenceKey, null)?.let { value ->
        runCatching {
            JSONObject(value).let { json ->
                SecureObjectReference(
                    objectId = SecureObjectId(json.getString("objectId")),
                    vaultId = AccountVaultId(json.getString("vaultId")),
                    purpose = requireNotNull(SecureObjectPurpose.fromFormatCode(json.getInt("purpose"))),
                    keyEnvelopeId = KeyEnvelopeId(json.getString("keyEnvelopeId")),
                    formatVersion = json.getInt("formatVersion"),
                    plaintextLength = json.getLong("plaintextLength"),
                    ciphertextLength = json.getLong("ciphertextLength"),
                    associatedDataSha256 = json.getString("associatedDataSha256"),
                )
            }
        }.getOrNull()
    }

    private fun EncryptedPrivateProfileDocument.clear() {
        ciphertext.fill(0)
        nonce.fill(0)
        associatedDataSha256.fill(0)
    }

    private companion object {
        const val SHA256 = "SHA-256"
        const val AES = "AES"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PROFILE_KEY_BYTES = 32
        const val REMOTE_NONCE_BYTES = 12
    }
}

/**
 * ACCOUNT tombstones dominate profile LWW timestamps. Applying this before and after remote merge
 * prevents a restored encrypted profile document from resurrecting a deleted peer's private data.
 */
internal fun applyDeletedIdentityTombstones(
    value: Map<UserId, ProfileRecord>,
    deletedIdentityProjection: DeletedIdentityProjection,
): Map<UserId, ProfileRecord> = value.mapValues { (userId, record) ->
    val tombstone = deletedIdentityProjection.recordForMatrixUserId(userId.value)
    if (tombstone == null) {
        record
    } else {
        ProfileRecord(
            profile = FriendPrivateProfile(userId),
            modifiedAtEpochMillis = maxOf(record.modifiedAtEpochMillis, tombstone.destroyedAtEpochMillis),
            deleted = true,
        )
    }
}

internal data class ProfileRecord(
    val profile: FriendPrivateProfile,
    val modifiedAtEpochMillis: Long,
    val deleted: Boolean,
)
