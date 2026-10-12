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
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.DeviceWrappedKey
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.KeyRotationReason
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageException
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import io.element.android.security.securestorage.api.SecureVaultDestroyedException
import io.element.android.security.securestorage.api.VaultDescriptor
import io.element.android.security.securestorage.api.VaultDestroyReason
import io.element.android.security.securestorage.api.VaultKeyDomain
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultProvisioningRequest
import io.element.android.security.securestorage.api.VaultState
import io.element.android.security.securestorage.api.VaultWrappedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultVaultKeyService(
    private val deviceRootKeyService: DeviceRootKeyService,
    private val deviceTrustService: DeviceTrustService,
    private val vaultKeyEnvelopeStore: VaultKeyEnvelopeStore,
    private val accountVaultRecordStore: AccountVaultRecordStore,
    private val legacyVaultKeyService: LegacyVaultKeyService,
) : VaultKeyService {
    private val mutex = Mutex()
    private val secureRandom = SecureRandom()
    private val accountKeys = mutableMapOf<AccountVaultId, ByteArray>()
    private val stateFlows = ConcurrentHashMap<AccountVaultId, MutableStateFlow<VaultState>>()

    override fun state(vaultId: AccountVaultId): Flow<VaultState> = stateFlow(vaultId).asStateFlow()

    override suspend fun provision(request: VaultProvisioningRequest): VaultDescriptor = withContext(Dispatchers.IO) {
        mutex.withLock {
            validateRequest(request)
            val vaultId = request.vaultId
            // Reconciliation can repeat on visibility changes. Revalidating an
            // already unlocked Vault is not revocation. Key operations remain
            // serialized by this mutex, and validation failure still revokes it.
            if (!accountKeys.containsKey(vaultId)) stateFlow(vaultId).value = VaultState.PROVISIONING
            try {
                requireHardwareIfRequested(request.requireTrustedHardware)
                val accountIdHash = request.accountIdHash.hexToBytes()
                val existing = accountVaultRecordStore.readManifest(vaultId)
                if (existing?.state == DurableVaultState.DESTROYED) throw SecureVaultDestroyedException()
                if (existing != null && !MessageDigest.isEqual(existing.accountIdHash, accountIdHash)) {
                    throw SecureObjectAuthenticationException()
                }

                val accountKey = loadOrCreateAccountKey(vaultId)
                try {
                    val keyVersion = existing?.keyVersion ?: INITIAL_VAULT_KEY_VERSION
                    ensureDomainKeys(vaultId, keyVersion, accountKey)
                    val manifest = AccountVaultManifest(
                        accountIdHash = accountIdHash,
                        keyVersion = keyVersion,
                        state = DurableVaultState.ACTIVE,
                        requiresUserAuthentication = existing?.requiresUserAuthentication == true || request.requireUserAuthentication,
                        requiresTrustedHardware = existing?.requiresTrustedHardware == true || request.requireTrustedHardware,
                    )
                    if (existing == null) {
                        if (!accountVaultRecordStore.createManifest(vaultId, manifest)) {
                            verifyExistingManifest(vaultId, manifest)
                        }
                    } else if (
                        manifest.keyVersion != existing.keyVersion ||
                        manifest.requiresUserAuthentication != existing.requiresUserAuthentication ||
                        manifest.requiresTrustedHardware != existing.requiresTrustedHardware
                    ) {
                        accountVaultRecordStore.replaceManifest(vaultId, manifest)
                    }
                    stateFlow(vaultId).value = if (accountKeys.containsKey(vaultId)) VaultState.UNLOCKED else VaultState.LOCKED
                    manifest.toDescriptor(vaultId)
                } finally {
                    accountKey.fill(0)
                }
            } catch (failure: SecureVaultDestroyedException) {
                clearCachedAccountKey(vaultId)
                stateFlow(vaultId).value = VaultState.DESTROYED
                throw failure
            } catch (failure: Exception) {
                clearCachedAccountKey(vaultId)
                stateFlow(vaultId).value = VaultState.ERROR
                throw failure
            }
        }
    }

    override suspend fun unlock(vaultId: AccountVaultId): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val manifest = loadActiveManifest(vaultId) ?: return@withLock false
            if (!accountKeys.containsKey(vaultId)) stateFlow(vaultId).value = VaultState.LOCKED
            return@withLock try {
                requireHardwareIfRequested(manifest.requiresTrustedHardware)
                val accountKey = loadExistingAccountKey(vaultId)
                try {
                    ensureDomainKeys(vaultId, manifest.keyVersion, accountKey)
                    replaceCachedAccountKey(vaultId, accountKey)
                } finally {
                    accountKey.fill(0)
                }
                stateFlow(vaultId).value = VaultState.UNLOCKED
                true
            } catch (failure: Exception) {
                clearCachedAccountKey(vaultId)
                stateFlow(vaultId).value = VaultState.ERROR
                false
            }
        }
    }

    override suspend fun isUnlocked(vaultId: AccountVaultId): Boolean = mutex.withLock {
        accountKeys.containsKey(vaultId) && stateFlow(vaultId).value == VaultState.UNLOCKED
    }

    override suspend fun lock(vaultId: AccountVaultId) = withContext(Dispatchers.IO) {
        mutex.withLock {
            clearCachedAccountKey(vaultId)
            val manifest = accountVaultRecordStore.readManifest(vaultId)
            stateFlow(vaultId).value = when (manifest?.state) {
                DurableVaultState.ACTIVE -> VaultState.LOCKED
                DurableVaultState.DESTROYED -> VaultState.DESTROYED
                null -> VaultState.ABSENT
            }
        }
    }

    override suspend fun rotate(vaultId: AccountVaultId, reason: KeyRotationReason): VaultDescriptor = withContext(Dispatchers.IO) {
        mutex.withLock {
            val manifest = requireNotNull(loadActiveManifest(vaultId))
            val accountKey = requireUnlockedAccountKey(vaultId)
            stateFlow(vaultId).value = VaultState.ROTATING
            try {
                val nextVersion = Math.addExact(manifest.keyVersion, 1L)
                ensureDomainKeys(vaultId, nextVersion, accountKey)
                val rotated = manifest.copy(keyVersion = nextVersion)
                accountVaultRecordStore.replaceManifest(vaultId, rotated)
                stateFlow(vaultId).value = VaultState.UNLOCKED
                rotated.toDescriptor(vaultId)
            } catch (failure: Exception) {
                stateFlow(vaultId).value = VaultState.ERROR
                throw failure
            } finally {
                accountKey.fill(0)
            }
        }
    }

    override suspend fun wrapKey(
        vaultId: AccountVaultId,
        domain: VaultKeyDomain,
        keyMaterial: ByteArray,
        binding: ByteArray,
    ): VaultWrappedKey = withContext(Dispatchers.IO) {
        mutex.withLock {
            validateKeyOperation(keyMaterial, binding)
            val manifest = requireNotNull(loadActiveManifest(vaultId))
            val accountKey = requireUnlockedAccountKey(vaultId)
            try {
                val domainKey = loadDomainKey(vaultId, domain, manifest.keyVersion, accountKey)
                try {
                    val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
                        init(Cipher.ENCRYPT_MODE, domainKey)
                        updateAAD(wrappedKeyBinding(vaultId, domain, manifest.keyVersion, binding))
                    }
                    VaultWrappedKey(
                        formatVersion = VAULT_WRAPPED_KEY_FORMAT_VERSION,
                        vaultKeyVersion = manifest.keyVersion,
                        domain = domain,
                        initializationVector = cipher.iv,
                        ciphertext = cipher.doFinal(keyMaterial),
                    )
                } finally {
                    domainKey.encoded?.fill(0)
                }
            } finally {
                accountKey.fill(0)
            }
        }
    }

    override suspend fun unwrapKey(vaultId: AccountVaultId, wrappedKey: VaultWrappedKey, binding: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                validateWrappedKey(wrappedKey, binding)
                val manifest = requireNotNull(loadActiveManifest(vaultId))
                val accountKey = requireUnlockedAccountKey(vaultId)
                try {
                    if (wrappedKey.formatVersion == LEGACY_DIRECT_ACCOUNT_KEY_FORMAT_VERSION) {
                        decrypt(accountKey.toSecretKey(), wrappedKey, binding)
                    } else {
                        val domainKey = loadDomainKey(vaultId, wrappedKey.domain, wrappedKey.vaultKeyVersion, accountKey)
                        try {
                            decrypt(
                                domainKey,
                                wrappedKey,
                                wrappedKeyBinding(vaultId, wrappedKey.domain, wrappedKey.vaultKeyVersion, binding),
                            )
                        } finally {
                            domainKey.encoded?.fill(0)
                        }
                    }
                } finally {
                    accountKey.fill(0)
                }
            }
        }

    override suspend fun destroy(vaultId: AccountVaultId, reason: VaultDestroyReason): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            stateFlow(vaultId).value = VaultState.DESTROYING
            clearCachedAccountKey(vaultId)
            val existing = accountVaultRecordStore.readManifest(vaultId)
            if (existing == null) {
                val destroyed = AccountVaultManifest(
                    accountIdHash = ByteArray(SHA256_LENGTH_BYTES),
                    keyVersion = 0,
                    state = DurableVaultState.DESTROYED,
                    requiresUserAuthentication = true,
                    requiresTrustedHardware = false,
                )
                if (!accountVaultRecordStore.createManifest(vaultId, destroyed)) {
                    val raced = requireNotNull(accountVaultRecordStore.readManifest(vaultId))
                    if (raced.state != DurableVaultState.DESTROYED) accountVaultRecordStore.replaceManifest(vaultId, raced.asDestroyed())
                }
            } else if (existing.state != DurableVaultState.DESTROYED) {
                accountVaultRecordStore.replaceManifest(vaultId, existing.asDestroyed())
            }
            val accountKeyDeleted = vaultKeyEnvelopeStore.delete(vaultId)
            val domainKeysDeleted = accountVaultRecordStore.deleteDomainKeys(vaultId)
            val legacyKeyDeleted = legacyVaultKeyService.destroy(vaultId)
            val destroyed = accountKeyDeleted && domainKeysDeleted && legacyKeyDeleted
            stateFlow(vaultId).value = if (destroyed) VaultState.DESTROYED else VaultState.ERROR
            destroyed
        }
    }

    private suspend fun requireHardwareIfRequested(required: Boolean) {
        val trust = deviceTrustService.current()
        if (!trust.allowsLocalVaultAccess && !trust.managedApprovalPending) {
            throw SecureStorageKeyUnavailableException()
        }
        if (!required) return
        if (trust.managedApprovalPending) {
            // A signed server decision may bootstrap only the local credential Vault while approval is pending.
            // Protected content remains blocked because there is no active managed license or access ticket.
            deviceRootKeyService.ensureReady()
            return
        }
        if (trust.trustState == DeviceTrustState.MANAGED &&
            trust.managedLicense?.isActive(System.currentTimeMillis()) == true
        ) {
            return
        }
        val root = deviceRootKeyService.ensureReady()
        if (!root.hardwareBacked || !trust.hardwareSecurityLevel.isTrustedHardware()) {
            throw SecureStorageKeyUnavailableException()
        }
    }

    private fun HardwareSecurityLevel.isTrustedHardware(): Boolean =
        this == HardwareSecurityLevel.STRONGBOX || this == HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT

    private fun loadActiveManifest(vaultId: AccountVaultId): AccountVaultManifest? {
        val manifest = accountVaultRecordStore.readManifest(vaultId) ?: return null
        if (manifest.state == DurableVaultState.DESTROYED) {
            stateFlow(vaultId).value = VaultState.DESTROYED
            throw SecureVaultDestroyedException()
        }
        return manifest
    }

    private suspend fun loadOrCreateAccountKey(vaultId: AccountVaultId): ByteArray {
        vaultKeyEnvelopeStore.read(vaultId)?.let { return unwrapAccountKey(vaultId, it) }
        val generated = newAesKeyBytes()
        try {
            val wrapped = deviceRootKeyService.wrapKey(generated, vaultKeyEnvelopeStore.binding(vaultId))
            if (vaultKeyEnvelopeStore.create(vaultId, wrapped)) return generated.copyOf()
            return unwrapAccountKey(vaultId, requireNotNull(vaultKeyEnvelopeStore.read(vaultId)))
        } finally {
            generated.fill(0)
        }
    }

    private suspend fun loadExistingAccountKey(vaultId: AccountVaultId): ByteArray {
        val envelope = vaultKeyEnvelopeStore.read(vaultId) ?: throw SecureStorageKeyUnavailableException()
        return unwrapAccountKey(vaultId, envelope)
    }

    private suspend fun unwrapAccountKey(vaultId: AccountVaultId, wrappedKey: DeviceWrappedKey): ByteArray {
        val keyMaterial = deviceRootKeyService.unwrapKey(wrappedKey, vaultKeyEnvelopeStore.binding(vaultId))
        if (keyMaterial.size != AES_KEY_SIZE_BYTES) {
            keyMaterial.fill(0)
            throw SecureObjectAuthenticationException()
        }
        return keyMaterial
    }

    private fun requireUnlockedAccountKey(vaultId: AccountVaultId): ByteArray =
        accountKeys[vaultId]?.copyOf() ?: throw SecureStorageKeyUnavailableException()

    private fun ensureDomainKeys(vaultId: AccountVaultId, keyVersion: Long, accountKey: ByteArray) {
        VaultKeyDomain.entries.forEach { domain ->
            if (accountVaultRecordStore.readDomainKey(vaultId, domain, keyVersion) == null) {
                val domainKey = newAesKeyBytes()
                try {
                    val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
                        init(Cipher.ENCRYPT_MODE, accountKey.toSecretKey())
                        updateAAD(accountVaultRecordStore.domainKeyBinding(vaultId, domain, keyVersion))
                    }
                    val envelope = StoredDomainKeyEnvelope(domain, keyVersion, cipher.iv, cipher.doFinal(domainKey))
                    if (!accountVaultRecordStore.createDomainKey(vaultId, envelope)) {
                        requireNotNull(accountVaultRecordStore.readDomainKey(vaultId, domain, keyVersion))
                    }
                } finally {
                    domainKey.fill(0)
                }
            }
        }
    }

    private fun loadDomainKey(vaultId: AccountVaultId, domain: VaultKeyDomain, keyVersion: Long, accountKey: ByteArray): SecretKey {
        val envelope = accountVaultRecordStore.readDomainKey(vaultId, domain, keyVersion) ?: throw SecureStorageKeyUnavailableException()
        if (envelope.domain != domain || envelope.keyVersion != keyVersion) throw SecureObjectAuthenticationException()
        val plaintext = try {
            Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, accountKey.toSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, envelope.initializationVector))
                updateAAD(accountVaultRecordStore.domainKeyBinding(vaultId, domain, keyVersion))
                doFinal(envelope.ciphertext)
            }
        } catch (failure: Exception) {
            throw SecureObjectAuthenticationException(failure)
        }
        try {
            if (plaintext.size != AES_KEY_SIZE_BYTES) throw SecureObjectAuthenticationException()
            return SecretKeySpec(plaintext, AES_ALGORITHM)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decrypt(key: SecretKey, wrappedKey: VaultWrappedKey, associatedData: ByteArray): ByteArray = try {
        Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrappedKey.initializationVector))
            updateAAD(associatedData)
            doFinal(wrappedKey.ciphertext)
        }
    } catch (failure: SecureStorageException) {
        throw failure
    } catch (failure: Exception) {
        throw SecureObjectAuthenticationException(failure)
    }

    private fun wrappedKeyBinding(
        vaultId: AccountVaultId,
        domain: VaultKeyDomain,
        keyVersion: Long,
        binding: ByteArray,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(WRAPPED_KEY_BINDING_MAGIC)
            output.write(sha256(vaultId.value.encodeToByteArray()))
            output.writeInt(domain.formatCode)
            output.writeLong(keyVersion)
            output.writeInt(binding.size)
            output.write(binding)
        }
        bytes.toByteArray()
    }

    private fun replaceCachedAccountKey(vaultId: AccountVaultId, accountKey: ByteArray) {
        accountKeys.put(vaultId, accountKey.copyOf())?.fill(0)
    }

    private fun clearCachedAccountKey(vaultId: AccountVaultId) {
        accountKeys.remove(vaultId)?.fill(0)
    }

    private fun verifyExistingManifest(vaultId: AccountVaultId, expected: AccountVaultManifest) {
        val actual = requireNotNull(accountVaultRecordStore.readManifest(vaultId))
        if (!MessageDigest.isEqual(actual.accountIdHash, expected.accountIdHash) || actual.state != DurableVaultState.ACTIVE) {
            throw SecureObjectAuthenticationException()
        }
    }

    private fun validateRequest(request: VaultProvisioningRequest) {
        require(request.vaultId.value.isNotBlank() && request.vaultId.value.length <= MAX_VAULT_ID_LENGTH)
        require(ACCOUNT_HASH_REGEX.matches(request.accountIdHash))
    }

    private fun validateKeyOperation(keyMaterial: ByteArray, binding: ByteArray) {
        require(keyMaterial.size in MIN_KEY_MATERIAL_LENGTH_BYTES..MAX_KEY_MATERIAL_LENGTH_BYTES)
        require(binding.size in 1..MAX_BINDING_LENGTH_BYTES)
    }

    private fun validateWrappedKey(wrappedKey: VaultWrappedKey, binding: ByteArray) {
        require(wrappedKey.formatVersion in LEGACY_DIRECT_ACCOUNT_KEY_FORMAT_VERSION..VAULT_WRAPPED_KEY_FORMAT_VERSION)
        require(wrappedKey.vaultKeyVersion > 0)
        require(wrappedKey.initializationVector.size == GCM_NONCE_LENGTH_BYTES)
        require(wrappedKey.ciphertext.size in MIN_WRAPPED_KEY_LENGTH_BYTES..MAX_WRAPPED_KEY_LENGTH_BYTES)
        require(binding.size in 1..MAX_BINDING_LENGTH_BYTES)
    }

    private fun newAesKeyBytes(): ByteArray = KeyGenerator.getInstance(AES_ALGORITHM).run {
        init(AES_KEY_SIZE_BITS, secureRandom)
        requireNotNull(generateKey().encoded)
    }

    private fun ByteArray.toSecretKey(): SecretKey = SecretKeySpec(this, AES_ALGORITHM)

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun AccountVaultManifest.toDescriptor(vaultId: AccountVaultId): VaultDescriptor = VaultDescriptor(
        vaultId = vaultId,
        keyVersion = keyVersion,
        requiresUserAuthentication = requiresUserAuthentication,
        requiresTrustedHardware = requiresTrustedHardware,
    )

    private fun AccountVaultManifest.asDestroyed(): AccountVaultManifest = copy(state = DurableVaultState.DESTROYED)

    private fun stateFlow(vaultId: AccountVaultId): MutableStateFlow<VaultState> =
        stateFlows.getOrPut(vaultId) { MutableStateFlow(VaultState.ABSENT) }

    private companion object {
        const val INITIAL_VAULT_KEY_VERSION = 1L
        const val LEGACY_DIRECT_ACCOUNT_KEY_FORMAT_VERSION = 1
        const val VAULT_WRAPPED_KEY_FORMAT_VERSION = 2
        const val AES_ALGORITHM = "AES"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_SIZE_BITS = 256
        const val AES_KEY_SIZE_BYTES = AES_KEY_SIZE_BITS / Byte.SIZE_BITS
        const val GCM_NONCE_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val SHA256_LENGTH_BYTES = 32
        const val MIN_KEY_MATERIAL_LENGTH_BYTES = 16
        const val MAX_KEY_MATERIAL_LENGTH_BYTES = 4_096
        const val MAX_BINDING_LENGTH_BYTES = 65_536
        const val MIN_WRAPPED_KEY_LENGTH_BYTES = 32
        const val MAX_WRAPPED_KEY_LENGTH_BYTES = 8_192
        const val MAX_VAULT_ID_LENGTH = 1_024
        val ACCOUNT_HASH_REGEX = Regex("^[0-9a-f]{64}$")
        val WRAPPED_KEY_BINDING_MAGIC = "YZVKW001".encodeToByteArray()
    }
}
