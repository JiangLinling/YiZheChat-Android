/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.VaultKeyDomain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

internal enum class DurableVaultState(val formatCode: Int) {
    ACTIVE(1),
    DESTROYED(2),
}

internal data class AccountVaultManifest(
    val accountIdHash: ByteArray,
    val keyVersion: Long,
    val state: DurableVaultState,
    val requiresUserAuthentication: Boolean,
    val requiresTrustedHardware: Boolean,
)

internal data class StoredDomainKeyEnvelope(
    val domain: VaultKeyDomain,
    val keyVersion: Long,
    val initializationVector: ByteArray,
    val ciphertext: ByteArray,
)

@SingleIn(AppScope::class)
@Inject
class AccountVaultRecordStore(
    private val roots: SecureStorageRoots,
) {
    internal fun readManifest(vaultId: AccountVaultId): AccountVaultManifest? =
        AtomicSecureRecordIO.read(manifestFile(vaultId), MAX_MANIFEST_LENGTH_BYTES)?.let { decodeManifest(vaultId, it) }

    internal fun createManifest(vaultId: AccountVaultId, manifest: AccountVaultManifest): Boolean =
        AtomicSecureRecordIO.create(manifestFile(vaultId), encodeManifest(vaultId, manifest))

    internal fun replaceManifest(vaultId: AccountVaultId, manifest: AccountVaultManifest) {
        AtomicSecureRecordIO.replace(manifestFile(vaultId), encodeManifest(vaultId, manifest))
    }

    internal fun readDomainKey(vaultId: AccountVaultId, domain: VaultKeyDomain, keyVersion: Long): StoredDomainKeyEnvelope? =
        AtomicSecureRecordIO.read(domainKeyFile(vaultId, domain, keyVersion), MAX_DOMAIN_KEY_LENGTH_BYTES)
            ?.let { decodeDomainKey(vaultId, it) }

    internal fun createDomainKey(vaultId: AccountVaultId, envelope: StoredDomainKeyEnvelope): Boolean =
        AtomicSecureRecordIO.create(domainKeyFile(vaultId, envelope.domain, envelope.keyVersion), encodeDomainKey(vaultId, envelope))

    internal fun deleteDomainKeys(vaultId: AccountVaultId): Boolean {
        val directory = domainKeyDirectory(vaultId)
        return !directory.exists() || directory.deleteRecursively()
    }

    internal fun domainKeyBinding(vaultId: AccountVaultId, domain: VaultKeyDomain, keyVersion: Long): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(DOMAIN_KEY_BINDING_MAGIC)
                output.write(sha256(vaultId.value.encodeToByteArray()))
                output.writeInt(domain.formatCode)
                output.writeLong(keyVersion)
            }
            bytes.toByteArray()
        }

    private fun manifestFile(vaultId: AccountVaultId): File = File(vaultDirectory(vaultId), MANIFEST_FILE_NAME)

    private fun domainKeyDirectory(vaultId: AccountVaultId): File = File(vaultDirectory(vaultId), DOMAIN_KEYS_DIRECTORY)

    private fun domainKeyFile(vaultId: AccountVaultId, domain: VaultKeyDomain, keyVersion: Long): File {
        require(keyVersion > 0)
        return File(domainKeyDirectory(vaultId), "${domain.name.lowercase()}-v$keyVersion$DOMAIN_KEY_FILE_SUFFIX")
    }

    private fun vaultDirectory(vaultId: AccountVaultId): File {
        require(vaultId.value.isNotBlank() && vaultId.value.length <= MAX_VAULT_ID_LENGTH)
        val root = roots.persistentRoot().canonicalFile
        return File(root, "$ACCOUNT_VAULTS_DIRECTORY/${sha256Hex(vaultId.value)}").canonicalFile.also { directory ->
            require(directory.path.startsWith(root.path + File.separator))
        }
    }

    private fun encodeManifest(vaultId: AccountVaultId, manifest: AccountVaultManifest): ByteArray = withChecksum(
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MANIFEST_MAGIC)
                output.writeInt(MANIFEST_FORMAT_VERSION)
                output.write(sha256(vaultId.value.encodeToByteArray()))
                output.write(manifest.accountIdHash.requireSize(SHA256_LENGTH_BYTES))
                output.writeLong(manifest.keyVersion)
                output.writeInt(manifest.state.formatCode)
                output.writeBoolean(manifest.requiresUserAuthentication)
                output.writeBoolean(manifest.requiresTrustedHardware)
            }
            bytes.toByteArray()
        }
    )

    private fun decodeManifest(vaultId: AccountVaultId, bytes: ByteArray): AccountVaultManifest = authenticateAccountVaultRecord {
        DataInputStream(ByteArrayInputStream(verifyAndStripChecksum(bytes))).use { input ->
            require(input.readExact(MANIFEST_MAGIC.size).contentEquals(MANIFEST_MAGIC))
            require(input.readInt() == MANIFEST_FORMAT_VERSION)
            require(MessageDigest.isEqual(input.readExact(SHA256_LENGTH_BYTES), sha256(vaultId.value.encodeToByteArray())))
            val accountIdHash = input.readExact(SHA256_LENGTH_BYTES)
            val keyVersion = input.readLong()
            require(keyVersion > 0 || accountIdHash.all { it == 0.toByte() })
            val stateCode = input.readInt()
            val requiresUserAuthentication = input.readBoolean()
            val requiresTrustedHardware = input.readBoolean()
            require(input.read() == -1)
            AccountVaultManifest(
                accountIdHash = accountIdHash,
                keyVersion = keyVersion,
                state = DurableVaultState.entries.single { it.formatCode == stateCode },
                requiresUserAuthentication = requiresUserAuthentication,
                requiresTrustedHardware = requiresTrustedHardware,
            )
        }
    }

    private fun encodeDomainKey(vaultId: AccountVaultId, envelope: StoredDomainKeyEnvelope): ByteArray = withChecksum(
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(DOMAIN_KEY_MAGIC)
                output.writeInt(DOMAIN_KEY_FORMAT_VERSION)
                output.write(sha256(vaultId.value.encodeToByteArray()))
                output.writeInt(envelope.domain.formatCode)
                output.writeLong(envelope.keyVersion)
                output.writeInt(envelope.initializationVector.size)
                output.write(envelope.initializationVector)
                output.writeInt(envelope.ciphertext.size)
                output.write(envelope.ciphertext)
            }
            bytes.toByteArray()
        }
    )

    private fun decodeDomainKey(vaultId: AccountVaultId, bytes: ByteArray): StoredDomainKeyEnvelope = authenticateAccountVaultRecord {
        DataInputStream(ByteArrayInputStream(verifyAndStripChecksum(bytes))).use { input ->
            require(input.readExact(DOMAIN_KEY_MAGIC.size).contentEquals(DOMAIN_KEY_MAGIC))
            require(input.readInt() == DOMAIN_KEY_FORMAT_VERSION)
            require(MessageDigest.isEqual(input.readExact(SHA256_LENGTH_BYTES), sha256(vaultId.value.encodeToByteArray())))
            val domainCode = input.readInt()
            val keyVersion = input.readLong()
            require(keyVersion > 0)
            val initializationVectorLength = input.readInt()
            require(initializationVectorLength == GCM_NONCE_LENGTH_BYTES)
            val initializationVector = input.readExact(initializationVectorLength)
            val ciphertextLength = input.readInt()
            require(ciphertextLength in MIN_DOMAIN_CIPHERTEXT_LENGTH_BYTES..MAX_DOMAIN_CIPHERTEXT_LENGTH_BYTES)
            val ciphertext = input.readExact(ciphertextLength)
            require(input.read() == -1)
            StoredDomainKeyEnvelope(
                domain = VaultKeyDomain.entries.single { it.formatCode == domainCode },
                keyVersion = keyVersion,
                initializationVector = initializationVector,
                ciphertext = ciphertext,
            )
        }
    }

    private fun withChecksum(payload: ByteArray): ByteArray = payload + sha256(payload)

    private fun verifyAndStripChecksum(bytes: ByteArray): ByteArray {
        require(bytes.size > CHECKSUM_LENGTH_BYTES)
        val payload = bytes.copyOfRange(0, bytes.size - CHECKSUM_LENGTH_BYTES)
        val checksum = bytes.copyOfRange(bytes.size - CHECKSUM_LENGTH_BYTES, bytes.size)
        require(MessageDigest.isEqual(checksum, sha256(payload)))
        return payload
    }

    private companion object {
        const val ACCOUNT_VAULTS_DIRECTORY = "account-vaults"
        const val MANIFEST_FILE_NAME = "manifest.yzav"
        const val DOMAIN_KEYS_DIRECTORY = "domain-keys"
        const val DOMAIN_KEY_FILE_SUFFIX = ".yzdk"
        const val MANIFEST_FORMAT_VERSION = 1
        const val DOMAIN_KEY_FORMAT_VERSION = 1
        const val MAX_VAULT_ID_LENGTH = 1024
        const val SHA256_LENGTH_BYTES = 32
        const val CHECKSUM_LENGTH_BYTES = 32
        const val GCM_NONCE_LENGTH_BYTES = 12
        const val MIN_DOMAIN_CIPHERTEXT_LENGTH_BYTES = 32
        const val MAX_DOMAIN_CIPHERTEXT_LENGTH_BYTES = 1024
        const val MAX_MANIFEST_LENGTH_BYTES = 4096L
        const val MAX_DOMAIN_KEY_LENGTH_BYTES = 4096L
        val MANIFEST_MAGIC = "YZAV0001".encodeToByteArray()
        val DOMAIN_KEY_MAGIC = "YZDK0001".encodeToByteArray()
        val DOMAIN_KEY_BINDING_MAGIC = "YZDKB001".encodeToByteArray()
    }
}

private fun ByteArray.requireSize(expectedSize: Int): ByteArray = apply { require(size == expectedSize) }

private fun DataInputStream.readExact(length: Int): ByteArray = ByteArray(length).also(::readFully)

private inline fun <T> authenticateAccountVaultRecord(block: () -> T): T = try {
    block()
} catch (failure: SecureObjectAuthenticationException) {
    throw failure
} catch (failure: Exception) {
    throw SecureObjectAuthenticationException(failure)
}
