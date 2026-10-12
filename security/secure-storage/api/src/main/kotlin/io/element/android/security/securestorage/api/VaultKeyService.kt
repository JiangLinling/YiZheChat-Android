/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.api

import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

enum class VaultState {
    ABSENT,
    PROVISIONING,
    LOCKED,
    UNLOCKED,
    ROTATING,
    DESTROYING,
    DESTROYED,
    ERROR,
}

enum class VaultDestroyReason {
    ACCOUNT_LOGOUT,
    DEVICE_REVOKED,
    ADMINISTRATIVE_REVOCATION,
    MIGRATION_ROLL_FORWARD,
}

enum class KeyRotationReason {
    SCHEDULED,
    ACCOUNT_CHANGE,
    SECURITY_INCIDENT,
    DATABASE_MIGRATION,
}

data class VaultProvisioningRequest(
    val vaultId: AccountVaultId,
    val accountIdHash: String,
    val requireUserAuthentication: Boolean,
    val requireTrustedHardware: Boolean,
)

data class VaultDescriptor(
    val vaultId: AccountVaultId,
    val keyVersion: Long,
    val requiresUserAuthentication: Boolean,
    val requiresTrustedHardware: Boolean,
)

enum class VaultKeyDomain(val formatCode: Int) {
    CREDENTIAL(1),
    MATRIX_STORE(2),
    DATABASE(3),
    MEDIA(4),
    CACHE(5),
    LOG(6),
    OFFLINE_LICENSE(7),
}

data class VaultWrappedKey(
    val formatVersion: Int,
    val vaultKeyVersion: Long,
    val domain: VaultKeyDomain,
    val initializationVector: ByteArray,
    val ciphertext: ByteArray,
)

interface VaultKeyService {
    fun state(vaultId: AccountVaultId): Flow<VaultState>

    suspend fun provision(request: VaultProvisioningRequest): VaultDescriptor

    suspend fun unlock(vaultId: AccountVaultId): Boolean

    suspend fun isUnlocked(vaultId: AccountVaultId): Boolean

    suspend fun lock(vaultId: AccountVaultId)

    suspend fun rotate(vaultId: AccountVaultId, reason: KeyRotationReason): VaultDescriptor

    suspend fun wrapKey(
        vaultId: AccountVaultId,
        domain: VaultKeyDomain,
        keyMaterial: ByteArray,
        binding: ByteArray,
    ): VaultWrappedKey

    suspend fun unwrapKey(vaultId: AccountVaultId, wrappedKey: VaultWrappedKey, binding: ByteArray): ByteArray

    suspend fun destroy(vaultId: AccountVaultId, reason: VaultDestroyReason): Boolean
}

fun deriveAccountVaultId(accountId: String, deviceId: String): AccountVaultId {
    require(accountId.isNotBlank() && deviceId.isNotBlank())
    return AccountVaultId("matrix-device-v1:${sha256Hex("$accountId\u0000$deviceId")}")
}

fun accountIdHash(accountId: String): String {
    require(accountId.isNotBlank())
    return sha256Hex(accountId)
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
