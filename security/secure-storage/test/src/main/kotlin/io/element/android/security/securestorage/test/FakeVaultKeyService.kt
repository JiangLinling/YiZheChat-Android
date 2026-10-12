/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.test

import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyRotationReason
import io.element.android.security.securestorage.api.VaultDescriptor
import io.element.android.security.securestorage.api.VaultDestroyReason
import io.element.android.security.securestorage.api.VaultKeyDomain
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultProvisioningRequest
import io.element.android.security.securestorage.api.VaultState
import io.element.android.security.securestorage.api.VaultWrappedKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class FakeVaultKeyService : VaultKeyService {
    private val states = mutableMapOf<AccountVaultId, MutableStateFlow<VaultState>>()
    private val descriptors = mutableMapOf<AccountVaultId, VaultDescriptor>()
    private val keys = mutableMapOf<AccountVaultId, SecretKey>()

    override fun state(vaultId: AccountVaultId): Flow<VaultState> = stateFlow(vaultId).asStateFlow()

    override suspend fun provision(request: VaultProvisioningRequest): VaultDescriptor {
        val vaultId = request.vaultId
        val descriptor = VaultDescriptor(
            vaultId = vaultId,
            keyVersion = 1,
            requiresUserAuthentication = request.requireUserAuthentication,
            requiresTrustedHardware = request.requireTrustedHardware,
        )
        descriptors[vaultId] = descriptor
        keys.getOrPut(vaultId) {
            KeyGenerator.getInstance("AES").run {
                init(256)
                generateKey()
            }
        }
        stateFlow(vaultId).value = VaultState.LOCKED
        return descriptor
    }

    override suspend fun unlock(vaultId: AccountVaultId): Boolean {
        if (descriptors[vaultId] == null) return false
        stateFlow(vaultId).value = VaultState.UNLOCKED
        return true
    }

    override suspend fun isUnlocked(vaultId: AccountVaultId): Boolean = stateFlow(vaultId).value == VaultState.UNLOCKED

    override suspend fun lock(vaultId: AccountVaultId) {
        if (descriptors[vaultId] != null) stateFlow(vaultId).value = VaultState.LOCKED
    }

    override suspend fun rotate(vaultId: AccountVaultId, reason: KeyRotationReason): VaultDescriptor {
        val previous = requireNotNull(descriptors[vaultId])
        val rotated = previous.copy(keyVersion = previous.keyVersion + 1)
        descriptors[vaultId] = rotated
        stateFlow(vaultId).value = VaultState.LOCKED
        return rotated
    }

    override suspend fun wrapKey(
        vaultId: AccountVaultId,
        domain: VaultKeyDomain,
        keyMaterial: ByteArray,
        binding: ByteArray,
    ): VaultWrappedKey {
        check(isUnlocked(vaultId))
        val descriptor = requireNotNull(descriptors[vaultId])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, requireNotNull(keys[vaultId]))
            updateAAD(binding)
        }
        return VaultWrappedKey(2, descriptor.keyVersion, domain, cipher.iv, cipher.doFinal(keyMaterial))
    }

    override suspend fun unwrapKey(vaultId: AccountVaultId, wrappedKey: VaultWrappedKey, binding: ByteArray): ByteArray {
        check(isUnlocked(vaultId))
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, requireNotNull(keys[vaultId]), GCMParameterSpec(128, wrappedKey.initializationVector))
            updateAAD(binding)
            doFinal(wrappedKey.ciphertext)
        }
    }

    override suspend fun destroy(vaultId: AccountVaultId, reason: VaultDestroyReason): Boolean {
        descriptors.remove(vaultId) ?: return false
        keys.remove(vaultId)
        stateFlow(vaultId).value = VaultState.DESTROYED
        return true
    }

    private fun stateFlow(vaultId: AccountVaultId): MutableStateFlow<VaultState> =
        states.getOrPut(vaultId) { MutableStateFlow(VaultState.ABSENT) }
}
