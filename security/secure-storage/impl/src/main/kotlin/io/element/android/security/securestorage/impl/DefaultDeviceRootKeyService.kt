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
import io.element.android.security.securestorage.api.DeviceRootKeyDescriptor
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.DeviceWrappedKey
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultDeviceRootKeyService(
    private val installationIdentityStore: InstallationIdentityStore,
    private val recordStore: DeviceRootKeyRecordStore,
    private val backend: DeviceRootKeyBackend,
) : DeviceRootKeyService {
    private val mutex = Mutex()

    override suspend fun ensureReady(): DeviceRootKeyDescriptor = withContext(Dispatchers.IO) {
        mutex.withLock { ensureReadyLocked().descriptor }
    }

    override suspend fun wrapKey(keyMaterial: ByteArray, binding: ByteArray): DeviceWrappedKey = withContext(Dispatchers.IO) {
        mutex.withLock {
            validateKeyMaterial(keyMaterial)
            validateBinding(binding)
            val ready = ensureReadyLocked()
            val associatedData = associatedData(ready.identity.sha256, ready.record.keyVersion, binding)
            val wrapped = backend.wrap(ready.alias, keyMaterial, associatedData)
            check(wrapped.initializationVector.size == GCM_NONCE_LENGTH_BYTES)
            DeviceWrappedKey(
                formatVersion = DEVICE_WRAPPED_KEY_FORMAT_VERSION,
                rootKeyVersion = ready.record.keyVersion,
                installationIdSha256 = ready.identity.sha256.copyOf(),
                initializationVector = wrapped.initializationVector,
                ciphertext = wrapped.ciphertext,
            )
        }
    }

    override suspend fun unwrapKey(wrappedKey: DeviceWrappedKey, binding: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            validateBinding(binding)
            validateWrappedKey(wrappedKey)
            val ready = ensureReadyLocked()
            if (wrappedKey.rootKeyVersion != ready.record.keyVersion ||
                !MessageDigest.isEqual(wrappedKey.installationIdSha256, ready.identity.sha256)
            ) {
                throw SecureStorageKeyUnavailableException()
            }
            try {
                backend.unwrap(
                    ready.alias,
                    BackendWrappedKey(wrappedKey.initializationVector, wrappedKey.ciphertext),
                    associatedData(ready.identity.sha256, ready.record.keyVersion, binding),
                )
            } catch (failure: SecureStorageKeyUnavailableException) {
                throw failure
            } catch (failure: Exception) {
                throw SecureObjectAuthenticationException(failure)
            }
        }
    }

    override suspend fun destroy(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val identity = installationIdentityStore.loadOrCreate()
            val alias = aliasFor(identity)
            val existing = recordStore.read()
            if (existing == null) {
                val backendDescriptor = backend.load(alias)
                val destroyed = DeviceRootKeyRecord(
                    installationIdSha256 = identity.sha256,
                    keyVersion = DEVICE_ROOT_KEY_VERSION,
                    state = DeviceRootKeyRecordState.DESTROYED,
                    strongBoxRequested = backendDescriptor?.strongBoxRequested == true,
                )
                recordStore.create(destroyed)
            } else {
                verifyInstallation(existing, identity)
                if (existing.state != DeviceRootKeyRecordState.DESTROYED) recordStore.markDestroyed(existing)
            }
            backend.destroy(alias)
        }
    }

    private fun ensureReadyLocked(): ReadyDeviceRootKey {
        val identity = installationIdentityStore.loadOrCreate()
        val alias = aliasFor(identity)
        var record = recordStore.read()
        if (record == null) {
            val backendDescriptor = backend.load(alias) ?: backend.create(alias)
            val candidate = DeviceRootKeyRecord(
                installationIdSha256 = identity.sha256,
                keyVersion = DEVICE_ROOT_KEY_VERSION,
                state = DeviceRootKeyRecordState.ACTIVE,
                strongBoxRequested = backendDescriptor.strongBoxRequested,
            )
            record = if (recordStore.create(candidate)) candidate else requireNotNull(recordStore.read())
        }
        verifyInstallation(record, identity)
        if (record.state == DeviceRootKeyRecordState.DESTROYED) throw SecureStorageKeyUnavailableException()
        val backendDescriptor = backend.load(alias) ?: throw SecureStorageKeyUnavailableException()
        return ReadyDeviceRootKey(
            identity = identity,
            record = record,
            alias = alias,
            descriptor = DeviceRootKeyDescriptor(
                installationId = identity.value,
                keyVersion = record.keyVersion,
                hardwareSecurityLevel = backendDescriptor.hardwareSecurityLevel,
                strongBoxRequested = record.strongBoxRequested,
            ),
        )
    }

    private fun verifyInstallation(record: DeviceRootKeyRecord, identity: InstallationIdentity) {
        if (!MessageDigest.isEqual(record.installationIdSha256, identity.sha256)) throw SecureStorageKeyUnavailableException()
    }

    private fun aliasFor(identity: InstallationIdentity): String = ROOT_KEY_ALIAS_PREFIX + identity.sha256.toHex().take(ALIAS_DIGEST_LENGTH)

    private fun associatedData(installationIdSha256: ByteArray, rootKeyVersion: Long, binding: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(WRAPPED_KEY_MAGIC)
                output.writeInt(DEVICE_WRAPPED_KEY_FORMAT_VERSION)
                output.writeLong(rootKeyVersion)
                output.write(installationIdSha256)
                output.writeInt(binding.size)
                output.write(binding)
            }
            bytes.toByteArray()
        }

    private fun validateKeyMaterial(keyMaterial: ByteArray) {
        require(keyMaterial.size in MIN_KEY_MATERIAL_LENGTH_BYTES..MAX_KEY_MATERIAL_LENGTH_BYTES)
    }

    private fun validateBinding(binding: ByteArray) {
        require(binding.isNotEmpty() && binding.size <= MAX_BINDING_LENGTH_BYTES)
    }

    private fun validateWrappedKey(wrappedKey: DeviceWrappedKey) {
        val valid = wrappedKey.formatVersion == DEVICE_WRAPPED_KEY_FORMAT_VERSION &&
            wrappedKey.rootKeyVersion > 0 &&
            wrappedKey.installationIdSha256.size == SHA256_LENGTH_BYTES &&
            wrappedKey.initializationVector.size == GCM_NONCE_LENGTH_BYTES &&
            wrappedKey.ciphertext.size in MIN_WRAPPED_KEY_LENGTH_BYTES..MAX_WRAPPED_KEY_LENGTH_BYTES
        if (!valid) throw SecureObjectAuthenticationException()
    }

    private data class ReadyDeviceRootKey(
        val identity: InstallationIdentity,
        val record: DeviceRootKeyRecord,
        val alias: String,
        val descriptor: DeviceRootKeyDescriptor,
    )

    private companion object {
        const val DEVICE_WRAPPED_KEY_FORMAT_VERSION = 1
        const val SHA256_LENGTH_BYTES = 32
        const val GCM_NONCE_LENGTH_BYTES = 12
        const val MIN_KEY_MATERIAL_LENGTH_BYTES = 16
        const val MAX_KEY_MATERIAL_LENGTH_BYTES = 4096
        const val MAX_BINDING_LENGTH_BYTES = 4096
        const val MIN_WRAPPED_KEY_LENGTH_BYTES = 32
        const val MAX_WRAPPED_KEY_LENGTH_BYTES = 8192
        const val ALIAS_DIGEST_LENGTH = 32
        const val ROOT_KEY_ALIAS_PREFIX = "yizhe.device-root.v1."
        val WRAPPED_KEY_MAGIC = "YZWK0001".encodeToByteArray()
    }
}
