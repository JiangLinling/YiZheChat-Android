/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.test

import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectNotFoundException
import io.element.android.security.securestorage.api.SecureObjectIdentity
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStoreMaintenanceReport
import io.element.android.security.securestorage.api.SecureVaultDeletionReport
import io.element.android.security.securestorage.api.SecureVaultDestroyedException
import io.element.android.security.securestorage.api.SensitiveFileStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

class InMemorySensitiveFileStore : SensitiveFileStore {
    private data class StoredObject(val identity: SecureObjectIdentity, val plaintext: ByteArray) {
        val vaultId: AccountVaultId get() = identity.vaultId
    }

    private val counter = AtomicLong()
    private val objects = mutableMapOf<KeyEnvelopeId, StoredObject>()
    private val destroyedVaults = mutableSetOf<AccountVaultId>()
    val transientMediaRequests = mutableMapOf<SecureObjectIdentity, SecureObjectWriteRequest>()

    override suspend fun registerTransientMediaObject(request: SecureObjectWriteRequest) {
        request.transientMediaOwner ?: return
        val identity = SecureObjectIdentity(request.objectId, request.vaultId, request.purpose)
        transientMediaRequests[identity]?.let { check(it.transientMediaOwner == request.transientMediaOwner) }
        transientMediaRequests[identity] = request
    }

    override suspend fun write(
        request: SecureObjectWriteRequest,
        writePlaintext: suspend (java.io.OutputStream) -> Unit,
    ): SecureObjectReference {
        if (request.vaultId in destroyedVaults) throw SecureVaultDestroyedException()
        registerTransientMediaObject(request)
        val plaintext = ByteArrayOutputStream().also { writePlaintext(it) }.toByteArray()
        request.expectedPlaintextLength?.let { require(it == plaintext.size.toLong()) }
        val keyEnvelopeId = KeyEnvelopeId("fake-${counter.incrementAndGet()}")
        objects[keyEnvelopeId] = StoredObject(
            SecureObjectIdentity(request.objectId, request.vaultId, request.purpose),
            plaintext,
        )
        return SecureObjectReference(
            objectId = request.objectId,
            vaultId = request.vaultId,
            purpose = request.purpose,
            keyEnvelopeId = keyEnvelopeId,
            formatVersion = 1,
            plaintextLength = plaintext.size.toLong(),
            ciphertextLength = plaintext.size.toLong(),
            associatedDataSha256 = MessageDigest.getInstance("SHA-256").digest(request.associatedData).toHex(),
        )
    }

    override suspend fun <T> read(reference: SecureObjectReference, readPlaintext: suspend (java.io.InputStream) -> T): T {
        if (reference.vaultId in destroyedVaults) throw SecureVaultDestroyedException()
        val storedObject = objects[reference.keyEnvelopeId]
            ?.takeIf { it.vaultId == reference.vaultId }
            ?: throw SecureObjectNotFoundException()
        return readPlaintext(ByteArrayInputStream(storedObject.plaintext))
    }

    override suspend fun readRange(reference: SecureObjectReference, offset: Long, byteCount: Int): ByteArray {
        require(offset >= 0)
        require(byteCount >= 0)
        if (reference.vaultId in destroyedVaults) throw SecureVaultDestroyedException()
        val plaintext = objects[reference.keyEnvelopeId]
            ?.takeIf { it.vaultId == reference.vaultId }
            ?.plaintext
            ?: throw SecureObjectNotFoundException()
        if (offset >= plaintext.size || byteCount == 0) return ByteArray(0)
        val start = offset.toInt()
        val end = minOf(plaintext.size.toLong(), offset + byteCount.toLong()).toInt()
        return plaintext.copyOfRange(start, end)
    }

    override suspend fun delete(reference: SecureObjectReference): Boolean = objects.remove(reference.keyEnvelopeId) != null

    override suspend fun deleteObject(identity: SecureObjectIdentity): Boolean {
        objects.entries.removeIf { it.value.identity == identity }
        return true
    }

    override suspend fun deleteVault(vaultId: AccountVaultId): SecureVaultDeletionReport {
        destroyedVaults += vaultId
        objects.entries.removeAll { it.value.vaultId == vaultId }
        return SecureVaultDeletionReport(vaultId, tombstoneWritten = true, keyDestroyed = true, filesDeleted = true)
    }

    override suspend fun runMaintenance(): SecureStoreMaintenanceReport = SecureStoreMaintenanceReport(0, 0, 0)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
}
