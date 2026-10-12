/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SecureVaultDestroyedException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DefaultSensitiveFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val roots by lazy {
        object : SecureStorageRoots {
            override fun persistentRoot(): File = File(temporaryFolder.root, "persistent")
            override fun runtimeRoot(): File = File(temporaryFolder.root, "runtime")
        }
    }
    private val keyEnvelopeService = TestObjectKeyEnvelopeService()
    private val store by lazy { DefaultSensitiveFileStore(roots, keyEnvelopeService) }

    private fun transientRequest(id: String = "transient-one") = aWriteRequest(null).copy(
        objectId = SecureObjectId(id),
        transientMediaOwner = io.element.android.security.securestorage.api.SecureTransientMediaOwner(
            "lc16-original-operation-0001", "a".repeat(64), "b".repeat(64), "installation-original",
            "c".repeat(64), "d".repeat(64)),
    )

    @Test
    fun `acknowledged cleanup retires pending transport but keeps original identity single use after restart`() = runTest {
        val request = transientRequest()
        val reference = store.write(request) { it.write(byteArrayOf(1)) }
        store.recordTransientMediaLeaseQuiescent(reference)
        store.delete(reference)
        val fact = store.recoverTransientMediaObjects().single()
        val receipt = "android-media-object-cleanup:v1:" + "e".repeat(64)
        store.acknowledgeTransientMediaDeletion(fact, receipt)
        val reopened = DefaultSensitiveFileStore(roots, keyEnvelopeService)
        reopened.acknowledgeTransientMediaDeletion(fact, receipt)
        assertThat(reopened.recoverTransientMediaObjects()).isEmpty()
        assertThat(captureFailure { reopened.registerTransientMediaObject(request) }).isNotNull()
        assertThat(captureFailure { reopened.write(request.copy(transientMediaOwner = null)) { it.write(1) } }).isNotNull()
        val retired = File(roots.persistentRoot(), "transient-media-objects-v1/acknowledged").listFiles()!!.single()
        assertThat(File(retired, "intent").isFile).isTrue()
        assertThat(File(retired, "server-acknowledgement").isFile).isTrue()
    }

    @Test
    fun `another fact or missing quiescence cannot retire the original obligation`() = runTest {
        val reference = store.write(transientRequest()) { it.write(1) }
        store.delete(reference)
        val incomplete = store.recoverTransientMediaObjects().single()
        val receipt = "android-media-object-cleanup:v1:" + "e".repeat(64)
        assertThat(captureFailure { store.acknowledgeTransientMediaDeletion(incomplete, receipt) }).isNotNull()
        store.recordTransientMediaLeaseQuiescent(reference)
        val fact = store.recoverTransientMediaObjects().single()
        for (changed in listOf(fact.copy(recordSha256 = "f".repeat(64)),
            fact.copy(confirmedAbsentAtEpochMillis = fact.confirmedAbsentAtEpochMillis + 1),
            fact.copy(owner = fact.owner.copy(operationId = "another-operation-0001")))) {
            assertThat(captureFailure { store.acknowledgeTransientMediaDeletion(changed, receipt) }).isNotNull()
        }
        assertThat(store.recoverTransientMediaObjects()).containsExactly(fact)
    }

    @Test
    fun `failed acknowledgement retirement retains original fact for exact retry`() = runTest {
        val reference = store.write(transientRequest()) { it.write(1) }
        store.recordTransientMediaLeaseQuiescent(reference)
        store.delete(reference)
        val fact = store.recoverTransientMediaObjects().single()
        val receipt = "android-media-object-cleanup:v1:" + "e".repeat(64)
        val blocker = File(roots.persistentRoot(), "transient-media-objects-v1/acknowledged").apply { writeText("test-blocker") }
        assertThat(captureFailure { store.acknowledgeTransientMediaDeletion(fact, receipt) }).isNotNull()
        val reopened = DefaultSensitiveFileStore(roots, keyEnvelopeService)
        assertThat(reopened.recoverTransientMediaObjects()).containsExactly(fact)
        check(blocker.delete())
        reopened.acknowledgeTransientMediaDeletion(fact, receipt)
        assertThat(reopened.recoverTransientMediaObjects()).isEmpty()
    }

    @Test
    fun `unreadable journal does not suppress independent recoverable objects`() = runTest {
        store.transientMediaProcessIdentity = "old-process"
        store.write(transientRequest("bad-original")) { it.write(1) }
        val bad = File(roots.persistentRoot(), "transient-media-objects-v1").listFiles()!!.single()
        File(bad, "intent").writeBytes(byteArrayOf(0))
        store.write(transientRequest("good-original")) { it.write(2) }
        val reopened = DefaultSensitiveFileStore(roots, keyEnvelopeService).apply { transientMediaProcessIdentity = "new-process" }
        assertThat(reopened.recoverTransientMediaObjects().single().identity.objectId.value).isEqualTo("good-original")
        assertThat(File(bad, "intent").readBytes()).isEqualTo(byteArrayOf(0))
    }

    @Test
    fun `cold recovery removes only old transient objects and keeps current and persistent content`() = runTest {
        store.transientMediaProcessIdentity = "old-process"
        val request = transientRequest()
        val plaintext = "media-cleanup-plaintext-canary".encodeToByteArray()
        val old = store.write(request) { it.write(plaintext) }
        val persistent = store.write(aWriteRequest(null)) { it.write(plaintext) }
        val reopened = DefaultSensitiveFileStore(roots, keyEnvelopeService).apply { transientMediaProcessIdentity = "new-process" }
        val current = reopened.write(transientRequest("transient-two")) { it.write(plaintext) }
        val facts = reopened.recoverTransientMediaObjects()
        assertThat(facts).hasSize(1)
        assertThat(facts.single().owner).isEqualTo(request.transientMediaOwner)
        assertThat(facts.single().identity.objectId).isEqualTo(old.objectId)
        assertThat(facts.single().leaseQuiescentAtEpochMillis).isNotNull()
        assertThat(reopened.read(current) { it.readBytes() }).isEqualTo(plaintext)
        assertThat(reopened.read(persistent) { it.readBytes() }).isEqualTo(plaintext)
        assertThat(captureFailure { reopened.read(old) { it.readBytes() } }).isNotNull()
        assertThat(reopened.recoverTransientMediaObjects()).isEqualTo(facts)
        val journalFiles = File(roots.persistentRoot(), "transient-media-objects-v1").walkTopDown().filter { it.isFile }.toList()
        assertThat(journalFiles.any { it.readBytes().containsSubsequence(plaintext) }).isFalse()
    }

    @Test
    fun `reading a current transient object never releases or deletes it`() = runTest {
        val reference = store.write(transientRequest()) { it.write(byteArrayOf(1, 2)) }
        repeat(2) {
            assertThat(store.read(reference) { it.readBytes() }).isEqualTo(byteArrayOf(1, 2))
            assertThat(store.recoverTransientMediaObjects()).isEmpty()
            store.runMaintenance()
        }
        assertThat(store.read(reference) { it.readBytes() }).hasLength(2)
    }

    @Test
    fun `a nonexistent generation cannot schedule whole object deletion of an active media lease`() = runTest {
        val reference = store.write(transientRequest()) { it.write(byteArrayOf(1, 2)) }
        val otherKeyId = reference.keyEnvelopeId.value.let { (if (it.first() == 'a') "b" else "a") + it.drop(1) }
        val wrongGeneration = reference.copy(keyEnvelopeId = io.element.android.security.securestorage.api.KeyEnvelopeId(otherKeyId))
        assertThat(store.delete(wrongGeneration)).isTrue()
        assertThat(store.recoverTransientMediaObjects()).isEmpty()
        assertThat(store.read(reference) { it.readBytes() }).isEqualTo(byteArrayOf(1, 2))
    }

    @Test
    fun `object absence alone is not a read quiescence fact and a real quiescence fact is immutable`() = runTest {
        val reference = store.write(transientRequest()) { it.write(byteArrayOf(1, 2)) }
        val otherKeyId = reference.keyEnvelopeId.value.let { (if (it.first() == 'a') "b" else "a") + it.drop(1) }
        assertThat(captureFailure { store.recordTransientMediaLeaseQuiescent(reference.copy(
            keyEnvelopeId = io.element.android.security.securestorage.api.KeyEnvelopeId(otherKeyId))) }).isNotNull()
        assertThat(store.delete(reference)).isTrue()
        val absent = store.recoverTransientMediaObjects().single()
        assertThat(absent.leaseQuiescentAtEpochMillis).isNull()
        store.recordTransientMediaLeaseQuiescent(reference)
        val complete = store.recoverTransientMediaObjects().single()
        assertThat(complete.leaseQuiescentAtEpochMillis).isNotNull()
        assertThat(complete.confirmedAbsentAtEpochMillis).isEqualTo(absent.confirmedAbsentAtEpochMillis)
        repeat(2) {
            store.recordTransientMediaLeaseQuiescent(reference)
            assertThat(store.recoverTransientMediaObjects()).containsExactly(complete)
        }
    }

    @Test
    fun `durable intent survives failure before object publication without inventing application`() = runTest {
        store.transientMediaProcessIdentity = "old-process"
        val request = transientRequest()
        assertThat(captureFailure { store.write(request) { error("Injected prepublication failure") } }).isNotNull()
        assertThat(store.recoverTransientMediaObjects()).isEmpty()
        val reopened = DefaultSensitiveFileStore(roots, keyEnvelopeService).apply { transientMediaProcessIdentity = "new-process" }
        val fact = reopened.recoverTransientMediaObjects().single()
        assertThat(fact.owner.operationId).isEqualTo(request.transientMediaOwner!!.operationId)
        assertThat(fact.confirmedAbsentAtEpochMillis).isGreaterThan(0L)
        assertThat(secureFiles()).isEmpty()
    }

    @Test
    fun `deletion result publication failure retains original obligation for exact retry`() = runTest {
        val request = transientRequest()
        val reference = store.write(request) { it.write(byteArrayOf(1)) }
        val journalDirectory = File(roots.persistentRoot(), "transient-media-objects-v1").listFiles()!!.single()
        val blocker = File(journalDirectory, "deleted").apply { check(mkdir()) }
        assertThat(captureFailure { store.delete(reference) }).isNotNull()
        assertThat(File(journalDirectory, "intent").isFile).isTrue()
        check(blocker.delete())
        val fact = store.recoverTransientMediaObjects().single()
        assertThat(fact.owner).isEqualTo(request.transientMediaOwner)
        assertThat(store.recoverTransientMediaObjects()).containsExactly(fact)
    }

    @Test
    fun `transient identity rejects scope replacement descriptor replacement and recreation after deletion`() = runTest {
        val request = transientRequest()
        store.registerTransientMediaObject(request)
        assertThat(captureFailure { store.registerTransientMediaObject(request.copy(
            transientMediaOwner = request.transientMediaOwner!!.copy(operationId = "lc16-different-operation-0002"))) }).isNotNull()
        assertThat(captureFailure { store.registerTransientMediaObject(request.copy(associatedData = byteArrayOf(5))) }).isNotNull()
        val reference = store.write(request) { it.write(byteArrayOf(1)) }
        assertThat(captureFailure { store.write(request) { it.write(byteArrayOf(2)) } }).isNotNull()
        assertThat(store.delete(reference)).isTrue()
        assertThat(captureFailure { store.write(request) { it.write(byteArrayOf(3)) } }).isNotNull()
        assertThat(captureFailure { store.write(request.copy(transientMediaOwner = null)) { it.write(byteArrayOf(4)) } }).isNotNull()
    }

    @Test
    fun `binary payload larger than one chunk round trips`() = runTest {
        val plaintext = ByteArray(SECURE_FILE_CHUNK_SIZE * 2 + 731).also(SecureRandom()::nextBytes)

        val reference = store.write(aWriteRequest(plaintext.size.toLong())) { it.write(plaintext) }

        val restored = store.read(reference) { it.readBytes() }
        assertThat(restored).isEqualTo(plaintext)
        assertThat(reference.plaintextLength).isEqualTo(plaintext.size.toLong())
        assertThat(reference.ciphertextLength).isGreaterThan(plaintext.size.toLong())
    }

    @Test
    fun `random access authenticates and restores a range spanning encrypted chunks`() = runTest {
        val plaintext = ByteArray(SECURE_FILE_CHUNK_SIZE * 2 + 731).also(SecureRandom()::nextBytes)
        val reference = store.write(aWriteRequest(plaintext.size.toLong())) { it.write(plaintext) }
        val offset = SECURE_FILE_CHUNK_SIZE - 37L
        val length = SECURE_FILE_CHUNK_SIZE + 149

        val restored = store.readRange(reference, offset, length)

        assertThat(restored).isEqualTo(plaintext.copyOfRange(offset.toInt(), offset.toInt() + length))
    }

    @Test
    fun `random access rejects a tampered requested chunk`() = runTest {
        val plaintext = ByteArray(SECURE_FILE_CHUNK_SIZE + 19).also(SecureRandom()::nextBytes)
        val reference = store.write(aWriteRequest(plaintext.size.toLong())) { it.write(plaintext) }
        dataFile(reference.keyEnvelopeId.value).flipByteAt(SECURE_FILE_CHUNK_SIZE / 2L)

        val failure = captureFailure {
            store.readRange(reference, 0, 1024)
        }

        assertThat(failure).isInstanceOf(SecureObjectAuthenticationException::class.java)
    }

    @Test
    fun `empty payload round trips`() = runTest {
        val reference = store.write(aWriteRequest(expectedLength = 0)) { }

        assertThat(store.read(reference) { it.readBytes() }).isEmpty()
    }

    @Test
    fun `published files never contain the plaintext canary`() = runTest {
        val plaintext = "YIZHE_SECURITY_CANARY::batch1::VOICE".repeat(512).encodeToByteArray()

        store.write(aWriteRequest(plaintext.size.toLong())) { it.write(plaintext) }

        val publishedFiles = roots.persistentRoot().walkTopDown().filter(File::isFile).toList()
        assertThat(publishedFiles).isNotEmpty()
        assertThat(publishedFiles.none { it.readBytes().containsSubsequence(plaintext) }).isTrue()
        assertThat(publishedFiles.none { it.name.startsWith(".partial-") }).isTrue()
    }

    @Test
    fun `identical plaintext is randomized for every object version`() = runTest {
        val plaintext = "same plaintext".repeat(10_000).encodeToByteArray()

        val first = store.write(aWriteRequest(plaintext.size.toLong())) { it.write(plaintext) }
        val second = store.write(aWriteRequest(plaintext.size.toLong())) { it.write(plaintext) }

        assertThat(first.keyEnvelopeId).isNotEqualTo(second.keyEnvelopeId)
        assertThat(dataFile(first.keyEnvelopeId.value).readBytes()).isNotEqualTo(dataFile(second.keyEnvelopeId.value).readBytes())
    }

    @Test
    fun `tampered terminal record is rejected even when consumer reads only a prefix`() = runTest {
        val plaintext = ByteArray(SECURE_FILE_CHUNK_SIZE + 19).also(SecureRandom()::nextBytes)
        val reference = store.write(aWriteRequest(plaintext.size.toLong())) { it.write(plaintext) }
        dataFile(reference.keyEnvelopeId.value).flipLastByte()

        val failure = captureFailure {
            store.read(reference) { input -> input.read() }
        }

        assertThat(failure).isInstanceOf(SecureObjectAuthenticationException::class.java)
    }

    @Test
    fun `reference metadata is authenticated`() = runTest {
        val plaintext = "authenticated metadata".encodeToByteArray()
        val reference = store.write(aWriteRequest(plaintext.size.toLong())) { it.write(plaintext) }

        val failure = captureFailure {
            store.read(reference.copy(associatedDataSha256 = "0".repeat(64))) { it.readBytes() }
        }

        assertThat(failure).isInstanceOf(SecureObjectAuthenticationException::class.java)
    }

    @Test
    fun `tampered key envelope is rejected`() = runTest {
        val plaintext = "wrapped object key".encodeToByteArray()
        val reference = store.write(aWriteRequest(plaintext.size.toLong())) { it.write(plaintext) }
        keyEnvelopeFile(reference.keyEnvelopeId.value).flipLastByte()

        val failure = captureFailure {
            store.read(reference) { it.readBytes() }
        }

        assertThat(failure).isInstanceOf(SecureObjectAuthenticationException::class.java)
    }

    @Test
    fun `fresh installation key cannot decrypt copied secure files`() = runTest {
        val plaintext = "installation-bound".encodeToByteArray()
        val reference = store.write(aWriteRequest(plaintext.size.toLong())) { it.write(plaintext) }
        val reinstalledStore = DefaultSensitiveFileStore(roots, TestObjectKeyEnvelopeService())

        val failure = captureFailure {
            reinstalledStore.read(reference) { it.readBytes() }
        }

        assertThat(failure).isInstanceOf(SecureStorageKeyUnavailableException::class.java)
    }

    @Test
    fun `legacy object key envelope is upgraded before plaintext is returned`() = runTest {
        val migratingEnvelopeService = MigratingTestObjectKeyEnvelopeService()
        val migratingStore = DefaultSensitiveFileStore(roots, migratingEnvelopeService)
        val plaintext = "legacy encrypted object".encodeToByteArray()
        migratingEnvelopeService.writeLegacy = true
        val reference = migratingStore.write(aWriteRequest(plaintext.size.toLong())) { it.write(plaintext) }
        assertThat(decodeKeyEnvelopeWrappingScheme(keyEnvelopeFile(reference.keyEnvelopeId.value).readBytes()))
            .isEqualTo(ObjectKeyWrappingScheme.LEGACY_PER_VAULT_KEYSTORE)

        migratingEnvelopeService.writeLegacy = false
        assertThat(migratingStore.read(reference) { it.readBytes() }).isEqualTo(plaintext)

        assertThat(decodeKeyEnvelopeWrappingScheme(keyEnvelopeFile(reference.keyEnvelopeId.value).readBytes()))
            .isEqualTo(ObjectKeyWrappingScheme.ACCOUNT_VAULT_DOMAIN)
        assertThat(migratingEnvelopeService.legacyRetired).isTrue()
    }

    @Test
    fun `failed or length mismatched write publishes no object`() = runTest {
        val failure = captureFailure {
            store.write(aWriteRequest(expectedLength = 999)) { it.write(byteArrayOf(1, 2, 3)) }
        }

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(secureFiles()).isEmpty()
    }

    @Test
    fun `writer failure leaves no published or partial object`() = runTest {
        val failure = captureFailure {
            store.write(aWriteRequest(expectedLength = null)) {
                it.write("partial plaintext".encodeToByteArray())
                error("simulated writer failure")
            }
        }

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(secureFiles()).isEmpty()
    }

    @Test
    fun `delete removes key envelope before encrypted data becomes unreachable`() = runTest {
        val reference = store.write(aWriteRequest(expectedLength = 4)) { it.write(byteArrayOf(1, 2, 3, 4)) }

        assertThat(store.delete(reference)).isTrue()
        assertThat(secureFiles()).isEmpty()
    }

    @Test
    fun `vault deletion writes a tombstone and destroys its wrapping key`() = runTest {
        val reference = store.write(aWriteRequest(expectedLength = 4)) { it.write(byteArrayOf(1, 2, 3, 4)) }

        val report = store.deleteVault(reference.vaultId)

        assertThat(report.cryptographicEraseCompleted).isTrue()
        assertThat(report.filesDeleted).isTrue()
        assertThat(keyEnvelopeService.destroyedVaults).contains(reference.vaultId)
        val failure = captureFailure {
            store.write(aWriteRequest(expectedLength = 0)) { }
        }
        assertThat(failure).isInstanceOf(SecureVaultDestroyedException::class.java)
    }

    @Test
    fun `maintenance removes partial and orphan object files`() = runTest {
        val directory = File(roots.persistentRoot(), "objects/test").apply { mkdirs() }
        File(directory, ".partial-failed").writeText("partial")
        File(directory, "orphan-data.yzsf").writeText("ciphertext")
        File(directory, "orphan-key.yzkey").writeText("wrapped-key")

        val report = store.runMaintenance()

        assertThat(report.partialFilesDeleted).isEqualTo(1)
        assertThat(report.orphanDataFilesDeleted).isEqualTo(1)
        assertThat(report.orphanKeyEnvelopesDeleted).isEqualTo(1)
        assertThat(directory.listFiles()).isEmpty()
    }

    private fun aWriteRequest(expectedLength: Long?) = SecureObjectWriteRequest(
        objectId = SecureObjectId("event-123"),
        vaultId = AccountVaultId("account-vault-456"),
        purpose = SecureObjectPurpose.VOICE_MESSAGE,
        associatedData = "room-and-event-binding".encodeToByteArray(),
        expectedPlaintextLength = expectedLength,
    )

    private fun secureFiles(): List<File> = roots.persistentRoot().walkTopDown()
        .filter { it.isFile && (it.name.endsWith(".yzsf") || it.name.endsWith(".yzkey") || it.name.startsWith(".partial-")) }
        .toList()

    private fun dataFile(keyEnvelopeId: String): File = roots.persistentRoot().walkTopDown()
        .first { it.isFile && it.name == "$keyEnvelopeId.yzsf" }

    private fun keyEnvelopeFile(keyEnvelopeId: String): File = roots.persistentRoot().walkTopDown()
        .first { it.isFile && it.name == "$keyEnvelopeId.yzkey" }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }
}

private class TestObjectKeyEnvelopeService : ObjectKeyEnvelopeService {
    private val keys = mutableMapOf<AccountVaultId, SecretKey>()
    val destroyedVaults = mutableSetOf<AccountVaultId>()

    override suspend fun wrap(vaultId: AccountVaultId, objectKey: SecretKey, associatedData: ByteArray): WrappedObjectKey {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, vaultKey(vaultId))
            updateAAD(associatedData)
        }
        return WrappedObjectKey(cipher.iv, cipher.doFinal(objectKey.encoded))
    }

    override suspend fun unwrap(vaultId: AccountVaultId, wrappedObjectKey: WrappedObjectKey, associatedData: ByteArray): SecretKey {
        val vaultKey = keys[vaultId] ?: throw SecureStorageKeyUnavailableException()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, vaultKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrappedObjectKey.initializationVector))
            updateAAD(associatedData)
        }
        return SecretKeySpec(cipher.doFinal(wrappedObjectKey.ciphertext), AES_ALGORITHM)
    }

    override suspend fun destroyVaultKey(vaultId: AccountVaultId): Boolean {
        keys.remove(vaultId)
        destroyedVaults += vaultId
        return true
    }

    override suspend fun retireLegacyVaultKey(vaultId: AccountVaultId): Boolean = true

    private fun vaultKey(vaultId: AccountVaultId): SecretKey = keys.getOrPut(vaultId) {
        KeyGenerator.getInstance(AES_ALGORITHM).run {
            init(256)
            generateKey()
        }
    }

    private companion object {
        const val AES_ALGORITHM = "AES"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

private class MigratingTestObjectKeyEnvelopeService : ObjectKeyEnvelopeService {
    private var legacyKey: SecretKey? = newAesKey()
    private val deviceRootVaultKey = newAesKey()
    var writeLegacy = false
    var legacyRetired = false

    override suspend fun wrap(vaultId: AccountVaultId, objectKey: SecretKey, associatedData: ByteArray): WrappedObjectKey {
        val scheme = if (writeLegacy) ObjectKeyWrappingScheme.LEGACY_PER_VAULT_KEYSTORE else ObjectKeyWrappingScheme.ACCOUNT_VAULT_DOMAIN
        val wrappingKey = if (writeLegacy) requireNotNull(legacyKey) else deviceRootVaultKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey)
            updateAAD(associatedData)
        }
        return WrappedObjectKey(cipher.iv, cipher.doFinal(objectKey.encoded), scheme)
    }

    override suspend fun unwrap(vaultId: AccountVaultId, wrappedObjectKey: WrappedObjectKey, associatedData: ByteArray): SecretKey {
        val wrappingKey = when (wrappedObjectKey.wrappingScheme) {
            ObjectKeyWrappingScheme.LEGACY_PER_VAULT_KEYSTORE -> legacyKey ?: throw SecureStorageKeyUnavailableException()
            ObjectKeyWrappingScheme.DEVICE_ROOT_VAULT,
            ObjectKeyWrappingScheme.ACCOUNT_VAULT_DOMAIN,
            -> deviceRootVaultKey
        }
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, wrappedObjectKey.initializationVector))
            updateAAD(associatedData)
            doFinal(wrappedObjectKey.ciphertext)
        }
        return SecretKeySpec(plaintext, "AES")
    }

    override suspend fun retireLegacyVaultKey(vaultId: AccountVaultId): Boolean {
        legacyKey = null
        legacyRetired = true
        return true
    }

    override suspend fun destroyVaultKey(vaultId: AccountVaultId): Boolean {
        legacyKey = null
        return true
    }

    private fun newAesKey(): SecretKey = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }
}

private fun File.flipLastByte() {
    val bytes = readBytes()
    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
    writeBytes(bytes)
}

private fun File.flipByteAt(offset: Long) {
    java.io.RandomAccessFile(this, "rw").use { file ->
        file.seek(offset)
        val value = file.read()
        check(value >= 0)
        file.seek(offset)
        file.write(value xor 1)
    }
}

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
    if (candidate.isEmpty()) return true
    return indices.any { start ->
        start + candidate.size <= size && candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
    }
}
