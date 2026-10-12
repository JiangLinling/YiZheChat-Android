/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.api

data class DeviceRootKeyDescriptor(
    val installationId: String,
    val keyVersion: Long,
    val hardwareSecurityLevel: HardwareSecurityLevel,
    val strongBoxRequested: Boolean,
) {
    val hardwareBacked: Boolean = hardwareSecurityLevel == HardwareSecurityLevel.STRONGBOX ||
        hardwareSecurityLevel == HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT
}

data class DeviceWrappedKey(
    val formatVersion: Int,
    val rootKeyVersion: Long,
    val installationIdSha256: ByteArray,
    val initializationVector: ByteArray,
    val ciphertext: ByteArray,
)

/**
 * Owns the non-exportable device root key (DRK). The raw DRK never crosses this API.
 *
 * [binding] must be stable, non-secret context that uniquely identifies the key purpose and owner. It is authenticated
 * with the wrapped key and prevents swapping an envelope between accounts or key domains.
 */
interface DeviceRootKeyService {
    suspend fun ensureReady(): DeviceRootKeyDescriptor

    suspend fun wrapKey(keyMaterial: ByteArray, binding: ByteArray): DeviceWrappedKey

    suspend fun unwrapKey(wrappedKey: DeviceWrappedKey, binding: ByteArray): ByteArray

    /** Permanently invalidates all local key envelopes belonging to this installation. */
    suspend fun destroy(): Boolean
}
