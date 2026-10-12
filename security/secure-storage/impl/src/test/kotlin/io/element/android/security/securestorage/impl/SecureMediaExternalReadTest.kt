/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.impl

import android.system.ErrnoException
import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRequest
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadResult
import io.element.android.security.securestorage.api.SecureMediaAuthorizationRejection
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidation
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidator
import io.element.android.security.securestorage.api.SecureMediaAuthorizedLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaExternalReadBinding
import io.element.android.security.securestorage.api.SecureMediaExternalReadPlan
import io.element.android.security.securestorage.api.SecureMediaExternalRecipient
import io.element.android.security.securestorage.api.SecureMediaLogicalResourceDescriptor
import io.element.android.security.securestorage.api.SecureMediaReadPurpose
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.securestorage.api.SecureMediaValidationTime
import io.element.android.security.securestorage.api.SecureMediaVerifiedAuthorization
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SecureTransientMediaOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.minutes

/** Real encrypted store and callback delivery. Android Binder/OS grant/OEM acceptance remains separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SecureMediaExternalReadTest {
    private val leaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stopScope() {
        leaseScope.cancel()
    }
    private suspend fun awaitCleanup(plan: SecureMediaExternalReadPlan) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { plan.observation.first { it.cleanupComplete || it.cleanupPending } }
    }
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val bytes = "EXTERNAL-FIRST-READ-ENCRYPTED-CANARY".encodeToByteArray()
    private val owner = SecureTransientMediaOwner(
        "source-operation-original-0001",
        "a".repeat(64),
        "b".repeat(64),
        "external-installation-original",
        "c".repeat(64),
        "d".repeat(64)
    )
    private val recipient = SecureMediaExternalRecipient(22001, "test.reader", "test.reader.Viewer")
    private val descriptor = SecureMediaLogicalResourceDescriptor(
        assetId = "asset",
        mediaVersion = 7,
        roomIdSha256Hex = "c".repeat(64),
        contentContractId = "signed-policy",
        resourceId = "source",
        contentId = "content",
        kind = "FILE",
        index = 0,
        expectedPlaintextLength = bytes.size.toLong(),
        immutableContextSha256Hex = "2".repeat(64),
        matrixLocatorSha256Hex = "3".repeat(64),
        outerFrameSha256Hex = "4".repeat(64),
        outerFrameLength = 100,
        vaultId = AccountVaultId("vault"),
        objectId = SecureObjectId("source-object"),
        purpose = SecureObjectPurpose.DOCUMENT_PREVIEW
    )
    private val roots by lazy {
        object : SecureStorageRoots {
        override fun persistentRoot() = File(temporaryFolder.root, "persistent")
        override fun runtimeRoot() = File(temporaryFolder.root, "runtime")
    }
    }
    private val store by lazy { DefaultSensitiveFileStore(roots, ExternalTestEnvelopeService()) }
    private val validator = Validator()

    private suspend fun TestScope.fixture(): Pair<DefaultSecureMediaLeaseManager, io.element.android.security.securestorage.api.AuthorizedSecureMediaLease> {
        val staging = DefaultSecureObjectStagingStore(roots, store)
        val manager = DefaultSecureMediaLeaseManager(RuntimeEnvironment.getApplication(), leaseScope, store, validator, staging)
        val request = SecureObjectWriteRequest(
            descriptor.objectId,
            descriptor.vaultId,
            descriptor.purpose,
            descriptor.canonicalBytes(),
            bytes.size.toLong(),
            owner
        )
        val transaction = staging.stage(request) { it.write(bytes) }
        val parent = manager.issueAuthorized(SecureMediaAuthorizedLeaseRequest(
            transaction,
            descriptor,
            validator.parent,
            "application/octet-stream",
            "sample.bin",
            5.minutes
        ))
        return manager to parent
    }

    @Test fun `parent preview close permits selected UID first read from separate encrypted object`() = runTest {
        val (manager, parent) = fixture()
        val plan = manager.prepareExternalRead(parent.uri, owner.operationId, owner.copy(operationId = "external-new-operation-0001"), recipient)
        val child = manager.issueExternalRead(plan)
        val token = requireNotNull(child.uri.lastPathSegment)
        assertThat(roots.persistentRoot().walkTopDown().filter(File::isFile).any { it.readBytes().hasBytes(bytes) }).isFalse()
        parent.close()
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (store.transientMediaObjectDeletion(
                        io.element.android.security.securestorage.api.SecureObjectIdentity(descriptor.objectId, descriptor.vaultId, descriptor.purpose)
                    )?.leaseQuiescentAtEpochMillis == null) {
                        delay(10)
                    }
            }
        }
        assertThat(plan.objectRecord.identity.objectId).isNotEqualTo(descriptor.objectId)
        assertThat(validator.parent.closed).isTrue()
        assertThat(child.observation.value.bytesCopied).isEqualTo(0)
        val callback = SecureMediaContentProvider().externalReadCallback(token, recipient.uid)
        assertThat(callback.onGetSize()).isEqualTo(bytes.size.toLong())
        val destination = ByteArray(bytes.size)
        assertThat(callback.onRead(0, bytes.size, destination)).isEqualTo(bytes.size)
        assertThat(destination).isEqualTo(bytes)
        assertThat(child.observation.value.firstReadAtEpochMillis).isNotNull()
        child.close()
        runCurrent()
        awaitCleanup(plan)
        assertThat(child.observation.value.cleanupComplete).isTrue()
        assertThat(store.transientMediaObjectDeletion(plan.objectRecord.identity)?.recordSha256).isEqualTo(plan.objectRecord.recordSha256)
        assertThrows(ErrnoException::class.java) { callback.onRead(0, bytes.size, destination) }
        assertThat(destination.all { it == 0.toByte() }).isTrue()
    }

    @Test fun `external descriptor short reads at EOF and still enforces recipient and revocation`() = runTest {
        val (manager, parent) = fixture()
        val child = manager.issueExternalRead(manager.prepareExternalRead(
            parent.uri, owner.operationId, owner.copy(operationId = "external-eof-operation-0001"), recipient
        ))
        val token = requireNotNull(child.uri.lastPathSegment)
        val callback = SecureMediaContentProvider().externalReadCallback(token, recipient.uid)
        val destination = ByteArray(128 * 1024)
        assertThat(callback.onRead(0, destination.size, destination)).isEqualTo(bytes.size)
        assertThat(destination.copyOf(bytes.size)).isEqualTo(bytes)
        assertThat(callback.onRead(bytes.size - 3L, destination.size, destination)).isEqualTo(3)
        assertThat(destination.copyOf(3)).isEqualTo(bytes.takeLast(3).toByteArray())
        assertThat(callback.onRead(bytes.size.toLong(), destination.size, destination)).isEqualTo(0)
        assertThat(callback.onRead(bytes.size + 10L, destination.size, destination)).isEqualTo(0)
        assertThrows(ErrnoException::class.java) { callback.onRead(-1, destination.size, destination) }
        assertThrows(ErrnoException::class.java) {
            SecureMediaContentProvider().externalReadCallback(token, recipient.uid + 1)
                .onRead(bytes.size.toLong(), destination.size, destination)
        }
        child.close()
        assertThrows(ErrnoException::class.java) { callback.onRead(bytes.size.toLong(), destination.size, destination) }
        parent.close()
        runCurrent()
    }

    @Test fun `wrong UID and wrong purpose do not consume the valid recipient capability`() = runTest {
        val (manager, parent) = fixture()
        val child = manager.issueExternalRead(manager.prepareExternalRead(
            parent.uri,
            owner.operationId,
            owner.copy(operationId = "external-new-operation-0002"),
            recipient
        ))
        val token = requireNotNull(child.uri.lastPathSegment)
        assertThat(SecureMediaLeaseRegistry.acquireForCaller(token, recipient.uid + 1)).isNull()
        assertThat(SecureMediaLeaseRegistry.acquireForCaller(token, recipient.uid)).isNotNull()
        val destination = ByteArray(bytes.size)
        assertThat(SecureMediaLeaseRegistry.copyAuthenticatedRangeInto(
            token,
            SecureMediaAuthenticatedReadRequest(0, bytes.size, SecureMediaReadPurpose.LEASE_STREAM, recipient.uid),
            destination,
            0
        ))
            .isInstanceOf(SecureMediaAuthenticatedReadResult.Rejected::class.java)
        assertThat(SecureMediaLeaseRegistry.acquireForCaller(token, recipient.uid)).isNotNull()
        assertThrows(ErrnoException::class.java) {
            SecureMediaContentProvider().externalReadCallback(token, recipient.uid + 1)
            .onRead(0, bytes.size, destination)
        }
        assertThat(child.observation.value.bytesCopied).isEqualTo(0)
        child.close()
        parent.close()
        runCurrent()
    }

    @Test fun `authorization revision and original process deadline reject later reads without renewal`() = runTest {
        val (manager, parent) = fixture()
        val source = manager.inspectExternalSource(parent.uri)
        val child = manager.issueExternalRead(manager.prepareExternalRead(
            parent.uri,
            owner.operationId,
            owner.copy(operationId = "external-new-operation-0003"),
            recipient
        ))
        val token = requireNotNull(child.uri.lastPathSegment)
        assertThat(SecureMediaLeaseRegistry.acquireForCaller(token, recipient.uid)?.authorizedState?.processTtlDeadlineElapsedRealtimeMillis)
            .isAtMost(source.processDeadlineElapsedRealtimeMillis)
        validator.revision++
        val destination = ByteArray(bytes.size) { 7 }
        assertThrows(ErrnoException::class.java) {
            SecureMediaContentProvider().externalReadCallback(token, recipient.uid)
            .onRead(0, bytes.size, destination)
        }
        assertThat(destination.all { it == 0.toByte() }).isTrue()
        runCurrent()
        withContext(Dispatchers.Default) { withTimeout(5_000) { child.observation.first { it.cleanupComplete || it.cleanupPending } } }
        assertThat(child.observation.value.cleanupComplete).isTrue()
        assertThat(child.observation.value.bytesCopied).isEqualTo(0)
        parent.close()
        runCurrent()
    }

    @Test fun `closing an undispatched plan removes only its journalled object and rejects issue`() = runTest {
        val (manager, parent) = fixture()
        val plan = manager.prepareExternalRead(parent.uri, owner.operationId, owner.copy(operationId = "external-new-operation-0004"), recipient)
        plan.close()
        runCurrent()
        awaitCleanup(plan)
        assertThat(plan.observation.value.cleanupComplete).isTrue()
        assertThat(store.transientMediaObjectDeletion(plan.objectRecord.identity)?.owner).isEqualTo(plan.objectRecord.owner)
        assertThat(runCatching { manager.issueExternalRead(plan) }.isFailure).isTrue()
        assertThat(manager.inspectExternalSource(parent.uri).owner).isEqualTo(owner)
        parent.close()
        runCurrent()
    }

    @Test fun `forged plan wrapper and expired source cannot issue a new capability`() = runTest {
        val (manager, parent) = fixture()
        val plan = manager.prepareExternalRead(parent.uri, owner.operationId, owner.copy(operationId = "external-new-operation-0005"), recipient)
        val forged = object : SecureMediaExternalReadPlan by plan {}
        assertThat(runCatching { manager.issueExternalRead(forged) }.isFailure).isTrue()
        validator.elapsed = 400_000
        assertThat(runCatching { manager.issueExternalRead(plan) }.isFailure).isTrue()
        runCurrent()
        assertThat(plan.observation.value.bytesCopied).isEqualTo(0)
        parent.close()
        runCurrent()
    }

    private fun ByteArray.hasBytes(candidate: ByteArray) = indices.any {
        it + candidate.size <= size &&
        copyOfRange(it, it + candidate.size).contentEquals(candidate)
    }
    private class Authority(val binding: SecureMediaExternalReadBinding? = null) : SecureMediaReleaseAuthority {
        var closed = false
        override fun close() {
            closed = true
        }
    }
    private inner class Validator : SecureMediaAuthorizationValidator {
        private val deadline = System.currentTimeMillis() + 60_000
        val parent = Authority()
        private val children = mutableSetOf<Authority>()
        var revision = 1L
        var elapsed = 1_000L
        override fun deriveExternalReadAuthority(
            authority: SecureMediaReleaseAuthority,
            binding: SecureMediaExternalReadBinding,
        ): SecureMediaReleaseAuthority? {
            if (authority !== parent || parent.closed) return null
            return Authority(binding).also { children.add(it) }
        }
        override fun validate(authority: SecureMediaReleaseAuthority, expectedContentId: String,
            expectedImmutableContextSha256Hex: String, expectedAuthorizationRevision: Long?): SecureMediaAuthorizationValidation {
            if (authority !is Authority || authority !== parent && authority !in children || authority.closed ||
                expectedAuthorizationRevision != null && expectedAuthorizationRevision != revision) {
                    return SecureMediaAuthorizationValidation.Rejected(SecureMediaAuthorizationRejection.AUTHORITY_UNAVAILABLE)
                }
            return SecureMediaAuthorizationValidation.Verified(SecureMediaVerifiedAuthorization(
                "root-authority",
                descriptor.contentId,
                descriptor.immutableContextSha256Hex,
                deadline,
                revision,
                "content:content",
                authority.binding,
                if (authority.binding == null) null else "local-child",
                if (authority.binding == null) null else 61_000
            ), SecureMediaValidationTime(1, elapsed))
        }
    }
}

private class ExternalTestEnvelopeService : ObjectKeyEnvelopeService {
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
