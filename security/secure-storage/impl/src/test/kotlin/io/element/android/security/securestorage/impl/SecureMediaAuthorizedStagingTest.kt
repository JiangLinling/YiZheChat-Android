/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.securestorage.impl

import com.google.common.truth.Truth.assertThat
import android.content.Context
import android.net.Uri
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidation
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidator
import io.element.android.security.securestorage.api.SecureMediaAuthorizedLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaLogicalResourceDescriptor
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.securestorage.api.SecureMediaValidationTime
import io.element.android.security.securestorage.api.SecureMediaVerifiedAuthorization
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectNotFoundException
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.minutes

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SecureMediaAuthorizedStagingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val roots by lazy {
        object : SecureStorageRoots {
            override fun persistentRoot(): File = File(temporaryFolder.root, "persistent")
            override fun runtimeRoot(): File = File(temporaryFolder.root, "runtime")
        }
    }

    @Test
    fun `staging is encrypted and not published until commit`() = runTest {
        val store = InMemorySensitiveFileStore()
        val stagingStore = DefaultSecureObjectStagingStore(roots, store)
        val plaintext = "STAGING-PLAINTEXT-CANARY".encodeToByteArray()
        val request = request(plaintext.size.toLong(), "aad".encodeToByteArray())

        val staging = stagingStore.stage(request) { it.write(plaintext) }

        assertThat(captureFailure { store.read(proposedReference(request)) { it.readBytes() } })
            .isInstanceOf(SecureObjectNotFoundException::class.java)
        val stagingFiles = roots.runtimeRoot().walkTopDown().filter(File::isFile).toList()
        assertThat(stagingFiles).hasSize(1)
        assertThat(stagingFiles.single().readBytes().containsSubsequence(plaintext)).isFalse()

        val reference = staging.commit()

        assertThat(store.read(reference) { it.readBytes() }).isEqualTo(plaintext)
        assertThat(stagingFiles.single().exists()).isFalse()
    }

    @Test
    fun `abort removes encrypted staging and is idempotent`() = runTest {
        val store = InMemorySensitiveFileStore()
        val staging = DefaultSecureObjectStagingStore(roots, store).stage(
            request(4, "aad".encodeToByteArray())
        ) { it.write("data".encodeToByteArray()) }

        assertThat(staging.abort().complete).isTrue()
        assertThat(staging.abort().complete).isTrue()
        assertThat(roots.runtimeRoot().walkTopDown().none(File::isFile)).isTrue()
    }

    @Test
    fun `failed staging deletion is attempted again after abort state is recorded`() = runTest {
        val staging = DefaultSecureObjectStagingStore(roots, InMemorySensitiveFileStore()).stage(
            request(4, "aad".encodeToByteArray())
        ) { it.write("data".encodeToByteArray()) }
        val path = roots.runtimeRoot().walkTopDown().single { it.isFile }
        check(path.delete())
        check(path.mkdir())
        val blocker = File(path, "blocker").apply { writeText("test-only") }
        assertThat(staging.abort().complete).isFalse()
        check(blocker.delete())
        assertThat(staging.abort().complete).isTrue()
        assertThat(path.exists()).isFalse()
    }

    @Test
    fun `authorized lease binds descriptor and revalidates revision on acquire`() = runTest {
        val store = InMemorySensitiveFileStore()
        val stagingStore = DefaultSecureObjectStagingStore(roots, store)
        val descriptor = descriptor(expectedLength = 5)
        val staging = stagingStore.stage(
            request(5, descriptor.canonicalBytes())
        ) { it.write("hello".encodeToByteArray()) }
        val authority = FakeAuthority()
        val validator = FakeValidator(descriptor)
        val manager = DefaultSecureMediaLeaseManager(
            context = testContext(),
            appCoroutineScope = this,
            sensitiveFileStore = store,
            authorizationValidator = validator,
            stagingStore = stagingStore,
        ).also { it.leaseUriFactory = ::testUri }

        val lease = manager.issueAuthorized(
            SecureMediaAuthorizedLeaseRequest(
                staging = staging,
                logicalResource = descriptor,
                releaseAuthority = authority,
                mimeType = "image/jpeg",
                displayName = "protected.jpg",
                timeToLive = 5.minutes,
            )
        )
        val token = checkNotNull(lease.uri.lastPathSegment)
        assertThat(SecureMediaLeaseRegistry.acquire(token)).isNotNull()

        validator.revision = 2
        assertThat(SecureMediaLeaseRegistry.acquire(token)).isNull()
        runCurrent()

        assertThat(authority.closed).isTrue()
        assertThat(manager.cleanupFailures()).isEmpty()
    }

    @Test
    fun `legacy lease issue and close semantics remain unchanged`() = runTest {
        val store = InMemorySensitiveFileStore()
        val reference = store.write(request(4, "legacy".encodeToByteArray())) { it.write("data".encodeToByteArray()) }
        val manager = DefaultSecureMediaLeaseManager(
            context = testContext(),
            appCoroutineScope = this,
            sensitiveFileStore = store,
        ).also { it.leaseUriFactory = ::testUri }

        val lease = manager.issue(
            SecureMediaLeaseRequest(
                reference = reference,
                mimeType = "image/jpeg",
                displayName = "legacy.jpg",
                timeToLive = 5.minutes,
                deleteObjectOnRevoke = false,
            )
        )
        val token = checkNotNull(lease.uri.lastPathSegment)
        assertThat(SecureMediaLeaseRegistry.acquire(token)).isNotNull()

        lease.close()

        assertThat(SecureMediaLeaseRegistry.acquire(token)).isNull()
        assertThat(store.read(reference) { it.readBytes().decodeToString() }).isEqualTo("data")
    }

    private fun descriptor(expectedLength: Long) = SecureMediaLogicalResourceDescriptor(
        assetId = "asset",
        mediaVersion = 7,
        roomIdSha256Hex = "1".repeat(64),
        contentContractId = "contract",
        resourceId = "resource",
        contentId = "content-123",
        kind = "IMAGE",
        index = 0,
        expectedPlaintextLength = expectedLength,
        immutableContextSha256Hex = "2".repeat(64),
        matrixLocatorSha256Hex = "3".repeat(64),
        outerFrameSha256Hex = "4".repeat(64),
        outerFrameLength = 100,
        vaultId = AccountVaultId("vault"),
        objectId = SecureObjectId("object"),
        purpose = SecureObjectPurpose.IMAGE,
    )

    private fun request(expectedLength: Long, aad: ByteArray) = SecureObjectWriteRequest(
        objectId = SecureObjectId("object"),
        vaultId = AccountVaultId("vault"),
        purpose = SecureObjectPurpose.IMAGE,
        associatedData = aad,
        expectedPlaintextLength = expectedLength,
    )

    private fun testContext(): Context = mockk<Context>().also {
        every { it.packageName } returns "io.element.android.test"
    }

    private fun testUri(token: String): Uri = mockk<Uri>().also {
        every { it.lastPathSegment } returns token
    }

    private fun proposedReference(request: SecureObjectWriteRequest) = io.element.android.security.securestorage.api.SecureObjectReference(
        objectId = request.objectId,
        vaultId = request.vaultId,
        purpose = request.purpose,
        keyEnvelopeId = io.element.android.security.securestorage.api.KeyEnvelopeId("not-published"),
        formatVersion = 1,
        plaintextLength = request.expectedPlaintextLength ?: 0,
        ciphertextLength = 1,
        associatedDataSha256 = "0".repeat(64),
    )

    private class FakeAuthority : SecureMediaReleaseAuthority {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class FakeValidator(private val descriptor: SecureMediaLogicalResourceDescriptor) : SecureMediaAuthorizationValidator {
        var revision = 1L
        private val deadline = System.currentTimeMillis() + 60_000
        override fun validate(
            authority: SecureMediaReleaseAuthority,
            expectedContentId: String,
            expectedImmutableContextSha256Hex: String,
            expectedAuthorizationRevision: Long?,
        ): SecureMediaAuthorizationValidation {
            if (expectedAuthorizationRevision != null && expectedAuthorizationRevision != revision) {
                return SecureMediaAuthorizationValidation.Rejected(
                    io.element.android.security.securestorage.api.SecureMediaAuthorizationRejection.REVISION_MISMATCH
                )
            }
            return SecureMediaAuthorizationValidation.Verified(
                authorization = SecureMediaVerifiedAuthorization(
                    authorityId = "authority",
                    contentId = descriptor.contentId,
                    immutableContextSha256Hex = descriptor.immutableContextSha256Hex,
                    authorizationDeadlineEpochMillis = deadline,
                    authorizationRevision = revision,
                    revocationScope = "content:${descriptor.contentId}",
                ),
                time = SecureMediaValidationTime(bootCount = 1, elapsedRealtimeMillis = 1_000),
            )
        }
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        error("Expected failure")
    } catch (failure: Throwable) {
        failure
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean =
        indices.any { start -> start + candidate.size <= size && copyOfRange(start, start + candidate.size).contentEquals(candidate) }
}
