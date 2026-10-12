/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageException
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import io.element.android.security.securestorage.api.VaultDestroyReason
import io.element.android.security.securestorage.api.VaultKeyDomain
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultWrappedKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

interface ObjectKeyEnvelopeService {
    suspend fun wrap(vaultId: AccountVaultId, objectKey: SecretKey, associatedData: ByteArray): WrappedObjectKey

    suspend fun unwrap(vaultId: AccountVaultId, wrappedObjectKey: WrappedObjectKey, associatedData: ByteArray): SecretKey

    suspend fun retireLegacyVaultKey(vaultId: AccountVaultId): Boolean

    suspend fun destroyVaultKey(vaultId: AccountVaultId): Boolean
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultObjectKeyEnvelopeService(
    private val vaultKeyService: VaultKeyService,
    private val legacyVaultKeyService: LegacyVaultKeyService,
) : ObjectKeyEnvelopeService {
    private val mutex = Mutex()

    override suspend fun wrap(vaultId: AccountVaultId, objectKey: SecretKey, associatedData: ByteArray): WrappedObjectKey = mutex.withLock {
        val objectKeyBytes = requireNotNull(objectKey.encoded)
        try {
            val wrapped = vaultKeyService.wrapKey(vaultId, VaultKeyDomain.MEDIA, objectKeyBytes, associatedData)
            WrappedObjectKey(
                initializationVector = wrapped.initializationVector,
                ciphertext = wrapped.ciphertext,
                wrappingScheme = ObjectKeyWrappingScheme.ACCOUNT_VAULT_DOMAIN,
                vaultKeyVersion = wrapped.vaultKeyVersion,
            )
        } finally {
            objectKeyBytes.fill(0)
        }
    }

    override suspend fun unwrap(
        vaultId: AccountVaultId,
        wrappedObjectKey: WrappedObjectKey,
        associatedData: ByteArray,
    ): SecretKey = mutex.withLock {
        when (wrappedObjectKey.wrappingScheme) {
            ObjectKeyWrappingScheme.LEGACY_PER_VAULT_KEYSTORE -> unwrapLegacy(vaultId, wrappedObjectKey, associatedData)
            ObjectKeyWrappingScheme.DEVICE_ROOT_VAULT -> unwrapAccountVault(vaultId, wrappedObjectKey, associatedData, formatVersion = 1)
            ObjectKeyWrappingScheme.ACCOUNT_VAULT_DOMAIN -> unwrapAccountVault(vaultId, wrappedObjectKey, associatedData, formatVersion = 2)
        }
    }

    override suspend fun retireLegacyVaultKey(vaultId: AccountVaultId): Boolean = mutex.withLock {
        legacyVaultKeyService.destroy(vaultId)
    }

    override suspend fun destroyVaultKey(vaultId: AccountVaultId): Boolean = mutex.withLock {
        vaultKeyService.destroy(vaultId, VaultDestroyReason.ACCOUNT_LOGOUT)
    }

    private suspend fun unwrapLegacy(
        vaultId: AccountVaultId,
        wrappedObjectKey: WrappedObjectKey,
        associatedData: ByteArray,
    ): SecretKey {
        if (!vaultKeyService.isUnlocked(vaultId)) throw SecureStorageKeyUnavailableException()
        return try {
            legacyVaultKeyService.unwrap(vaultId, wrappedObjectKey, associatedData)
        } catch (failure: SecureStorageException) {
            throw failure
        } catch (failure: Exception) {
            throw SecureObjectAuthenticationException(failure)
        }
    }

    private suspend fun unwrapAccountVault(
        vaultId: AccountVaultId,
        wrappedObjectKey: WrappedObjectKey,
        associatedData: ByteArray,
        formatVersion: Int,
    ): SecretKey = try {
        val plaintext = vaultKeyService.unwrapKey(
            vaultId = vaultId,
            wrappedKey = VaultWrappedKey(
                formatVersion = formatVersion,
                vaultKeyVersion = wrappedObjectKey.vaultKeyVersion,
                domain = VaultKeyDomain.MEDIA,
                initializationVector = wrappedObjectKey.initializationVector,
                ciphertext = wrappedObjectKey.ciphertext,
            ),
            binding = associatedData,
        )
        try {
            if (plaintext.size != OBJECT_KEY_SIZE_BYTES) throw SecureObjectAuthenticationException()
            SecretKeySpec(plaintext, AES_ALGORITHM)
        } finally {
            plaintext.fill(0)
        }
    } catch (failure: SecureStorageException) {
        throw failure
    } catch (failure: Exception) {
        throw SecureObjectAuthenticationException(failure)
    }

    private companion object {
        const val AES_ALGORITHM = "AES"
        const val OBJECT_KEY_SIZE_BYTES = 256 / Byte.SIZE_BITS
    }
}
