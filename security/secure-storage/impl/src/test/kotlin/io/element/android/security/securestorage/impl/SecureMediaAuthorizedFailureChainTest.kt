/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.securestorage.impl

import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureMediaAuthorizationRejection
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidation
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidator
import io.element.android.security.securestorage.api.SecureMediaAuthorizedLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaCleanupException
import io.element.android.security.securestorage.api.SecureMediaLogicalResourceDescriptor
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.securestorage.api.SecureMediaValidationTime
import io.element.android.security.securestorage.api.SecureMediaVerifiedAuthorization
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectNotFoundException
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes

class SecureMediaAuthorizedFailureChainTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val roots by lazy {
        object : SecureStorageRoots {
            override fun persistentRoot(): File = File(temporaryFolder.root, "persistent")
            override fun runtimeRoot(): File = File(temporaryFolder.root, "runtime")
        }
    }

    @Test
    fun `authority close failure never skips committed object cleanup`() = runTest {
        val descriptor = descriptor()
        val store = RecordingSensitiveFileStore()
        val fixture = manager(store, ScriptedValidator(descriptor, rejectOnCall = 3))
        val staging = DefaultSecureObjectStagingStore(roots, store).stage(writeRequest(descriptor)) { it.write(PLAINTEXT) }
        val authority = object : SecureMediaReleaseAuthority {
            override fun close() { throw IOException("Injected authority close failure") }
        }
        val failure = captureFailure { fixture.manager.issueAuthorized(authorizedRequest(staging, descriptor, authority)) }
        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(failure.suppressed).hasLength(1)
        assertThat(store.deleteCount).isEqualTo(1)
        assertThat(fixture.issuedTokens).isEmpty()
        assertStoredObjectMissing(store)
    }

    @Test
    fun `partial plaintext followed by failure or cancellation leaves no staging or object`() = runTest {
        listOf<Throwable>(InjectedFailure(), CancellationException("cancelled after partial decrypt")).forEach { injected ->
            val store = RecordingSensitiveFileStore()
            val request = writeRequest(descriptor(), "object-${injected.javaClass.simpleName}")

            val failure = captureFailure {
                DefaultSecureObjectStagingStore(roots, store).stage(request) { output ->
                    output.write("PARTIAL-PLAINTEXT-CANARY".encodeToByteArray())
                    throw injected
                }
            }

            assertThat(failure).isInstanceOf(injected.javaClass)
            assertThat(failure.message).isEqualTo(injected.message)
            assertThat(store.writeCount).isEqualTo(0)
            assertThat(stagingFiles()).isEmpty()
        }
    }

    @Test
    fun `authority revision rejection after real commit removes object and publishes no lease`() = runTest {
        val descriptor = descriptor()
        val store = RecordingSensitiveFileStore()
        val validator = ScriptedValidator(descriptor, rejectOnCall = 3)
        val fixture = manager(store, validator)
        val staging = DefaultSecureObjectStagingStore(roots, store).stage(writeRequest(descriptor)) {
            it.write(PLAINTEXT)
        }

        val failure = captureFailure { fixture.manager.issueAuthorized(authorizedRequest(staging, descriptor, TestAuthority())) }

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(store.writeCount).isEqualTo(1)
        assertThat(store.deleteCount).isEqualTo(1)
        assertThat(fixture.issuedTokens).isEmpty()
        assertThat(stagingFiles()).isEmpty()
        assertStoredObjectMissing(store)
    }

    @Test
    fun `authority deadline crossing during real commit removes object and publishes no lease`() = runTest {
        val descriptor = descriptor()
        val issuerExpired = AtomicBoolean(false)
        val store = RecordingSensitiveFileStore(afterWrite = { issuerExpired.set(true) })
        val staging = DefaultSecureObjectStagingStore(roots, store).stage(writeRequest(descriptor)) {
            it.write(PLAINTEXT)
        }
        val validator = ScriptedValidator(
            descriptor = descriptor,
            rejectionProvider = {
                SecureMediaAuthorizationRejection.EXPIRED.takeIf { issuerExpired.get() }
            },
        )
        val fixture = manager(store, validator)

        val failure = captureFailure { fixture.manager.issueAuthorized(authorizedRequest(staging, descriptor, TestAuthority())) }

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(store.writeCount).isEqualTo(1)
        assertThat(store.deleteCount).isEqualTo(1)
        assertThat(fixture.issuedTokens).isEmpty()
        assertStoredObjectMissing(store)
    }

    @Test
    fun `descriptor object vault and purpose mismatches abort before commit or lease publication`() = runTest {
        val descriptor = descriptor()
        val mismatches = listOf(
            writeRequest(descriptor, objectId = "wrong-object") to descriptor,
            writeRequest(descriptor, vaultId = AccountVaultId("wrong-vault")) to descriptor,
            writeRequest(descriptor, purpose = SecureObjectPurpose.ATTACHMENT) to descriptor,
            writeRequest(descriptor, associatedData = "wrong-aad".encodeToByteArray()) to descriptor,
            writeRequest(descriptor) to descriptor.copy(resourceId = "wrong-resource"),
        )

        mismatches.forEach { (mismatchedRequest, logicalDescriptor) ->
            val store = RecordingSensitiveFileStore()
            val fixture = manager(store, ScriptedValidator(logicalDescriptor))
            val authority = TestAuthority()
            val staging = DefaultSecureObjectStagingStore(roots, store).stage(mismatchedRequest) {
                it.write(PLAINTEXT)
            }

            val failure = captureFailure {
                fixture.manager.issueAuthorized(authorizedRequest(staging, logicalDescriptor, authority))
            }

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(authority.closed).isTrue()
            assertThat(store.writeCount).isEqualTo(0)
            assertThat(fixture.issuedTokens).isEmpty()
            assertThat(stagingFiles()).isEmpty()
        }
    }

    @Test
    fun `failed committed object deletion returns receipt and never reports lease success`() = runTest {
        val descriptor = descriptor()
        val store = RecordingSensitiveFileStore(failDelete = true)
        val fixture = manager(store, ScriptedValidator(descriptor, rejectOnCall = 3))
        val staging = DefaultSecureObjectStagingStore(roots, store).stage(writeRequest(descriptor)) {
            it.write(PLAINTEXT)
        }

        val failure = captureFailure { fixture.manager.issueAuthorized(authorizedRequest(staging, descriptor, TestAuthority())) }

        assertThat(failure).isInstanceOf(SecureMediaCleanupException::class.java)
        val cleanup = (failure as SecureMediaCleanupException).cleanupReport
        assertThat(cleanup.complete).isFalse()
        assertThat(cleanup.items.single().target).isEqualTo("committed-object")
        assertThat(cleanup.items.single().removedOrAbsent).isFalse()
        assertThat(fixture.manager.cleanupFailures()).containsExactly(cleanup)
        assertThat(fixture.issuedTokens).isEmpty()
        assertThat(store.deleteCount).isEqualTo(1)
        assertThat(store.readRecorded()).isEqualTo(PLAINTEXT)
    }

    @Test
    fun `thrown committed object deletion returns receipt with original and cleanup failures`() = runTest {
        val descriptor = descriptor()
        val deletionFailure = IOException("injected delete failure")
        val store = RecordingSensitiveFileStore(deleteFailure = deletionFailure)
        val fixture = manager(store, ScriptedValidator(descriptor, rejectOnCall = 3))
        val staging = DefaultSecureObjectStagingStore(roots, store).stage(writeRequest(descriptor)) {
            it.write(PLAINTEXT)
        }

        val failure = captureFailure {
            fixture.manager.issueAuthorized(authorizedRequest(staging, descriptor, TestAuthority()))
        }

        assertThat(failure).isInstanceOf(SecureMediaCleanupException::class.java)
        val cleanupFailure = failure as SecureMediaCleanupException
        assertThat(cleanupFailure.cleanupReport.items)
            .containsExactly(io.element.android.security.securestorage.api.SecureObjectStagingCleanupItem("committed-object", false))
        assertThat(cleanupFailure.cause).isInstanceOf(SecurityException::class.java)
        assertThat(cleanupFailure.suppressed.toList()).containsExactly(deletionFailure)
        assertThat(fixture.manager.cleanupFailures()).containsExactly(cleanupFailure.cleanupReport)
        assertThat(fixture.issuedTokens).isEmpty()
        assertThat(store.readRecorded()).isEqualTo(PLAINTEXT)
    }

    @Test
    fun `uri creation failure leaves no registry entry or committed object`() = runTest {
        val descriptor = descriptor()
        val store = RecordingSensitiveFileStore()
        val fixture = manager(store, ScriptedValidator(descriptor))
        val capturedTokens = mutableListOf<String>()
        fixture.manager.leaseUriFactory = { token ->
            capturedTokens += token
            throw IllegalStateException("injected URI failure")
        }
        val staging = DefaultSecureObjectStagingStore(roots, store).stage(writeRequest(descriptor)) {
            it.write(PLAINTEXT)
        }

        val failure = captureFailure {
            fixture.manager.issueAuthorized(authorizedRequest(staging, descriptor, TestAuthority()))
        }

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(capturedTokens).hasSize(1)
        assertThat(SecureMediaLeaseRegistry.acquire(capturedTokens.single())).isNull()
        assertStoredObjectMissing(store)
    }

    @Test
    fun `authorized scope and vault revocation close authorities delete objects and remove registry entries`() = runTest {
        val store = RecordingSensitiveFileStore()
        val fixture = manager(store, EchoValidator())
        val firstDescriptor = descriptor(contentId = "content-one", objectId = "object-one", vaultId = AccountVaultId("vault-one"))
        val secondDescriptor = descriptor(contentId = "content-two", objectId = "object-two", vaultId = AccountVaultId("vault-two"))
        val firstAuthority = TestAuthority()
        val secondAuthority = TestAuthority()
        val stagingStore = DefaultSecureObjectStagingStore(roots, store)
        val firstStaging = stagingStore.stage(writeRequest(firstDescriptor)) { it.write(PLAINTEXT) }
        val secondStaging = stagingStore.stage(writeRequest(secondDescriptor)) { it.write(PLAINTEXT) }

        fixture.manager.issueAuthorized(authorizedRequest(firstStaging, firstDescriptor, firstAuthority))
        fixture.manager.issueAuthorized(authorizedRequest(secondStaging, secondDescriptor, secondAuthority))
        val firstToken = fixture.issuedTokens[0]
        val secondToken = fixture.issuedTokens[1]

        fixture.manager.revokeScope("content:${firstDescriptor.contentId}")
        assertThat(SecureMediaLeaseRegistry.acquire(firstToken)).isNull()
        assertThat(SecureMediaLeaseRegistry.acquire(secondToken)).isNotNull()
        assertThat(firstAuthority.closed).isTrue()
        assertThat(secondAuthority.closed).isFalse()

        fixture.manager.revokeVault(secondDescriptor.vaultId)
        assertThat(SecureMediaLeaseRegistry.acquire(secondToken)).isNull()
        assertThat(secondAuthority.closed).isTrue()
        assertThat(store.deleteCount).isEqualTo(2)
    }

    @Test
    fun `throwing registry authorization guard revokes capability fail closed`() {
        val store = InMemorySensitiveFileStore()
        val reference = SecureObjectReference(
            objectId = SecureObjectId("guard-object"),
            vaultId = AccountVaultId("guard-vault"),
            purpose = SecureObjectPurpose.IMAGE,
            keyEnvelopeId = io.element.android.security.securestorage.api.KeyEnvelopeId("guard-key"),
            formatVersion = 1,
            plaintextLength = 1,
            ciphertextLength = 1,
            associatedDataSha256 = "0".repeat(64),
        )
        var revokeCalls = 0
        val token = "guard-throws-token"
        SecureMediaLeaseRegistry.register(
            token,
            SecureMediaLeaseRegistry.Entry(
                reference = reference,
                mimeType = "image/jpeg",
                displayName = null,
                expiresAtMillis = System.currentTimeMillis() + 60_000,
                requireSeekableDescriptor = false,
                sensitiveFileStore = store,
                isReadAuthorized = { throw IllegalStateException("guard failure") },
                onRevoked = { revokeCalls++ },
            )
        )

        assertThat(SecureMediaLeaseRegistry.acquire(token)).isNull()
        assertThat(SecureMediaLeaseRegistry.acquire(token)).isNull()
        assertThat(revokeCalls).isEqualTo(1)
    }

    private fun manager(
        store: RecordingSensitiveFileStore,
        validator: SecureMediaAuthorizationValidator,
    ): ManagerFixture {
        val issuedTokens = mutableListOf<String>()
        val manager = DefaultSecureMediaLeaseManager(
            context = mockk<Context>().also { every { it.packageName } returns "io.element.android.test" },
            appCoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            sensitiveFileStore = store,
            authorizationValidator = validator,
            stagingStore = DefaultSecureObjectStagingStore(roots, store),
        ).also {
            it.leaseUriFactory = { token ->
                issuedTokens += token
                mockk<Uri>().also { uri -> every { uri.lastPathSegment } returns token }
            }
        }
        return ManagerFixture(manager, issuedTokens)
    }

    private fun descriptor(
        contentId: String = "content-123",
        objectId: String = "object",
        vaultId: AccountVaultId = AccountVaultId("vault"),
    ) = SecureMediaLogicalResourceDescriptor(
        assetId = "asset",
        mediaVersion = 7,
        roomIdSha256Hex = "1".repeat(64),
        contentContractId = "contract",
        resourceId = "resource",
        contentId = contentId,
        kind = "IMAGE",
        index = 0,
        expectedPlaintextLength = PLAINTEXT.size.toLong(),
        immutableContextSha256Hex = "2".repeat(64),
        matrixLocatorSha256Hex = "3".repeat(64),
        outerFrameSha256Hex = "4".repeat(64),
        outerFrameLength = 100,
        vaultId = vaultId,
        objectId = SecureObjectId(objectId),
        purpose = SecureObjectPurpose.IMAGE,
    )

    private fun writeRequest(
        descriptor: SecureMediaLogicalResourceDescriptor,
        objectId: String = descriptor.objectId.value,
        vaultId: AccountVaultId = descriptor.vaultId,
        purpose: SecureObjectPurpose = descriptor.purpose,
        associatedData: ByteArray = descriptor.canonicalBytes(),
    ) = SecureObjectWriteRequest(
        objectId = SecureObjectId(objectId),
        vaultId = vaultId,
        purpose = purpose,
        associatedData = associatedData,
        expectedPlaintextLength = descriptor.expectedPlaintextLength,
    )

    private fun authorizedRequest(
        staging: io.element.android.security.securestorage.api.SecureObjectStagingTransaction,
        descriptor: SecureMediaLogicalResourceDescriptor,
        authority: SecureMediaReleaseAuthority,
    ) = SecureMediaAuthorizedLeaseRequest(
        staging = staging,
        logicalResource = descriptor,
        releaseAuthority = authority,
        mimeType = "image/jpeg",
        displayName = "protected.jpg",
        timeToLive = 5.minutes,
    )

    private fun stagingFiles(): List<File> = roots.runtimeRoot().walkTopDown().filter(File::isFile).toList()

    private suspend fun assertStoredObjectMissing(store: RecordingSensitiveFileStore) {
        val reference = checkNotNull(store.lastReference)
        assertThat(captureFailure { store.read(reference) { it.readBytes() } })
            .isInstanceOf(SecureObjectNotFoundException::class.java)
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        error("Expected failure")
    } catch (failure: Throwable) {
        failure
    }

    private class TestAuthority : SecureMediaReleaseAuthority {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class ScriptedValidator(
        private val descriptor: SecureMediaLogicalResourceDescriptor,
        private val deadlineEpochMillis: Long = System.currentTimeMillis() + 60_000,
        private val rejectOnCall: Int? = null,
        private val rejectionProvider: () -> SecureMediaAuthorizationRejection? = { null },
    ) : SecureMediaAuthorizationValidator {
        private val calls = AtomicLong()

        override fun validate(
            authority: SecureMediaReleaseAuthority,
            expectedContentId: String,
            expectedImmutableContextSha256Hex: String,
            expectedAuthorizationRevision: Long?,
        ): SecureMediaAuthorizationValidation {
            if (calls.incrementAndGet().toInt() == rejectOnCall) {
                return SecureMediaAuthorizationValidation.Rejected(SecureMediaAuthorizationRejection.REVISION_MISMATCH)
            }
            rejectionProvider()?.let { rejection ->
                return SecureMediaAuthorizationValidation.Rejected(rejection)
            }
            return SecureMediaAuthorizationValidation.Verified(
                authorization = SecureMediaVerifiedAuthorization(
                    authorityId = "authority",
                    contentId = descriptor.contentId,
                    immutableContextSha256Hex = descriptor.immutableContextSha256Hex,
                    authorizationDeadlineEpochMillis = deadlineEpochMillis,
                    authorizationRevision = 1,
                    revocationScope = "content:${descriptor.contentId}",
                ),
                time = SecureMediaValidationTime(bootCount = 1, elapsedRealtimeMillis = 1_000),
            )
        }
    }

    private class EchoValidator : SecureMediaAuthorizationValidator {
        private val deadlines = mutableMapOf<String, Long>()

        override fun validate(
            authority: SecureMediaReleaseAuthority,
            expectedContentId: String,
            expectedImmutableContextSha256Hex: String,
            expectedAuthorizationRevision: Long?,
        ): SecureMediaAuthorizationValidation = SecureMediaAuthorizationValidation.Verified(
            authorization = SecureMediaVerifiedAuthorization(
                authorityId = "authority",
                contentId = expectedContentId,
                immutableContextSha256Hex = expectedImmutableContextSha256Hex,
                authorizationDeadlineEpochMillis = deadlines.getOrPut(expectedContentId) {
                    System.currentTimeMillis() + 60_000
                },
                authorizationRevision = 1,
                revocationScope = "content:$expectedContentId",
            ),
            time = SecureMediaValidationTime(bootCount = 1, elapsedRealtimeMillis = 1_000),
        )
    }

    private class RecordingSensitiveFileStore(
        private val delegate: InMemorySensitiveFileStore = InMemorySensitiveFileStore(),
        private val failDelete: Boolean = false,
        private val afterWrite: () -> Unit = {},
        private val deleteFailure: Throwable? = null,
    ) : SensitiveFileStore by delegate {
        var writeCount = 0
        var deleteCount = 0
        var lastReference: SecureObjectReference? = null

        override suspend fun write(
            request: SecureObjectWriteRequest,
            writePlaintext: suspend (OutputStream) -> Unit,
        ): SecureObjectReference {
            val reference = delegate.write(request, writePlaintext)
            writeCount++
            lastReference = reference
            afterWrite()
            return reference
        }

        override suspend fun delete(reference: SecureObjectReference): Boolean {
            deleteCount++
            deleteFailure?.let { throw it }
            return if (failDelete) false else delegate.delete(reference)
        }

        suspend fun readRecorded(): ByteArray = delegate.read(checkNotNull(lastReference)) { it.readBytes() }
    }

    private data class ManagerFixture(
        val manager: DefaultSecureMediaLeaseManager,
        val issuedTokens: MutableList<String>,
    )

    private class InjectedFailure : RuntimeException("injected after partial plaintext")

    private companion object {
        val PLAINTEXT = "hello".encodeToByteArray()
    }
}
