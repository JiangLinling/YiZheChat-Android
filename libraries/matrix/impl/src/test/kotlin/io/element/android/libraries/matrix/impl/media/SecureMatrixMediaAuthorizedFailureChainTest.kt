/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.media

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaCachePolicy
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.security.contentprotection.api.ProtectContentRequest
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.AuthorizedSecureMediaLeaseManager
import io.element.android.security.securestorage.api.AuthorizedSecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidator
import io.element.android.security.securestorage.api.SecureMediaAuthorizedLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaCleanupException
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectStagingCleanupItem
import io.element.android.security.securestorage.api.SecureObjectStagingCleanupReport
import io.element.android.security.securestorage.api.SecureObjectStagingStore
import io.element.android.security.securestorage.api.SecureObjectStagingTransaction
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class SecureMatrixMediaAuthorizedFailureChainTest {
    @Test
    fun `partial protected decrypt failure returns no staging transaction or lease`() = runTest {
        val frame = "protected-frame".encodeToByteArray()
        val manager = RecordingStageLeaseManager()
        val loader = loader(
            frame = frame,
            leaseManager = manager,
            store = InMemorySensitiveFileStore(),
            protectedService = PartialFailureProtectedService(InjectedFailure()),
        )

        val result = loader.downloadMediaFile(MediaSource("mxc://example.org/partial"), "image/jpeg", "partial.jpg")

        assertThat(result.exceptionOrNull()).isInstanceOf(InjectedFailure::class.java)
        assertThat(manager.stageReturned).isFalse()
        assertThat(manager.issueAuthorizedCalls).isEqualTo(0)
        assertThat(manager.partialBytes).isEqualTo("PARTIAL".encodeToByteArray())
        assertThat(frame.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `cancellation after partial protected decrypt returns no transaction and is rethrown`() = runTest {
        val frame = "protected-frame".encodeToByteArray()
        val manager = RecordingStageLeaseManager()
        val cancellation = CancellationException("cancelled after partial protected decrypt")
        val loader = loader(
            frame = frame,
            leaseManager = manager,
            store = InMemorySensitiveFileStore(),
            protectedService = PartialFailureProtectedService(cancellation),
        )

        val failure = captureFailure {
            loader.downloadMediaFile(MediaSource("mxc://example.org/cancel"), "image/jpeg", "cancel.jpg")
        }

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure.message).isEqualTo(cancellation.message)
        assertThat(manager.stageReturned).isFalse()
        assertThat(manager.issueAuthorizedCalls).isEqualTo(0)
        assertThat(manager.partialBytes).isEqualTo("PARTIAL".encodeToByteArray())
        assertThat(frame.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `incomplete Matrix abort returns itemized cleanup failure instead of lease success`() = runTest {
        val frame = "protected-frame".encodeToByteArray()
        val originalFailure = InjectedFailure()
        val manager = IncompleteAbortLeaseManager(originalFailure)
        val authority = TestAuthority()
        val loader = loader(
            frame = frame,
            leaseManager = manager,
            store = InMemorySensitiveFileStore(),
            protectedService = SuccessfulProtectedService(authority),
        )

        val result = loader.downloadMediaFile(MediaSource("mxc://example.org/cleanup"), "image/jpeg", "cleanup.jpg")

        val failure = result.exceptionOrNull()
        assertThat(failure).isInstanceOf(SecureMediaCleanupException::class.java)
        val cleanup = (failure as SecureMediaCleanupException).cleanupReport
        assertThat(cleanup.transactionId).isEqualTo(manager.transaction.transactionId)
        assertThat(cleanup.complete).isFalse()
        assertThat(cleanup.items).containsExactly(SecureObjectStagingCleanupItem("encrypted-staging", false))
        assertThat(failure.cause).isSameInstanceAs(originalFailure)
        assertThat(manager.transaction.abortCalls).isEqualTo(1)
        assertThat(authority.closed).isTrue()
        assertThat(frame.all { it == 0.toByte() }).isTrue()
    }

    private fun loader(
        frame: ByteArray,
        leaseManager: SecureMediaLeaseManager,
        store: SensitiveFileStore,
        protectedService: MatrixProtectedMediaService,
    ) = SecureMatrixMediaLoader(
        delegate = FrameMediaLoader(frame),
        vaultId = VAULT_ID,
        sensitiveFileStore = store,
        secureMediaLeaseManager = leaseManager,
        sessionCoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        clock = SystemClock { System.currentTimeMillis() },
        appOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        protectedMediaService = protectedService,
        accountIdHash = "a".repeat(64),
        matrixDeviceIdHash = "d".repeat(64),
        requireInnerProtection = { true },
    )

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        error("Expected failure")
    } catch (failure: Throwable) {
        failure
    }

    private class FrameMediaLoader(private val frame: ByteArray) : MatrixMediaLoader {
        override suspend fun loadMediaContent(source: MediaSource, cachePolicy: MediaCachePolicy): Result<ByteArray> =
            Result.success(frame)

        override suspend fun loadMediaThumbnail(
            source: MediaSource,
            width: Long,
            height: Long,
            cachePolicy: MediaCachePolicy,
        ): Result<ByteArray> = Result.success(frame)

        override suspend fun downloadMediaFile(
            source: MediaSource,
            mimeType: String?,
            filename: String?,
            useCache: Boolean,
        ): Result<MediaFile> = error("Secure loader must not request a plaintext SDK file.")
    }

    private abstract class TestProtectedService : MatrixProtectedMediaService, MatrixProtectedMediaAuthorityService {
        override suspend fun protect(source: File, request: ProtectContentRequest): ProtectedMatrixMedia = error("unused")
        override fun isProtected(frame: ByteArray): Boolean = true
        override suspend fun decrypt(
            frame: ByteArray,
            vaultId: AccountVaultId,
            accountIdHash: String,
            plaintext: OutputStream,
        ): ProtectedMatrixMediaDecryption = error("legacy decrypt is not used by the protected file path")

        override fun inspect(frame: ByteArray) = ProtectedMatrixMediaInspection(
            contentId = "content-123",
            kind = "IMAGE",
            policyId = "policy",
            policyVersion = 1,
            roomIdSha256Hex = "1".repeat(64),
            immutableContextSha256Hex = "2".repeat(64),
            expectedPlaintextLength = PLAINTEXT.size.toLong(),
        )
    }

    private class PartialFailureProtectedService(private val failure: Throwable) : TestProtectedService() {
        override suspend fun decryptToEncryptedStaging(
            frame: ByteArray,
            vaultId: AccountVaultId,
            accountIdHash: String,
            plaintext: OutputStream,
        ): SecureMediaReleaseAuthority {
            plaintext.write("PARTIAL".encodeToByteArray())
            throw failure
        }
    }

    private class SuccessfulProtectedService(private val authority: SecureMediaReleaseAuthority) : TestProtectedService() {
        override suspend fun decryptToEncryptedStaging(
            frame: ByteArray,
            vaultId: AccountVaultId,
            accountIdHash: String,
            plaintext: OutputStream,
        ): SecureMediaReleaseAuthority {
            plaintext.write(PLAINTEXT)
            return authority
        }
    }

    private class TestAuthority : SecureMediaReleaseAuthority {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class IncompleteAbortLeaseManager(
        private val issueFailure: Throwable,
    ) : SecureMediaLeaseManager, AuthorizedSecureMediaLeaseManager, SecureObjectStagingStore {
        lateinit var transaction: IncompleteAbortTransaction

        override suspend fun stage(
            request: SecureObjectWriteRequest,
            writePlaintext: suspend (OutputStream) -> Unit,
        ): SecureObjectStagingTransaction {
            val bytes = ByteArrayOutputStream().also { writePlaintext(it) }.toByteArray()
            transaction = IncompleteAbortTransaction(request, bytes.size.toLong())
            return transaction
        }

        override suspend fun issueAuthorized(request: SecureMediaAuthorizedLeaseRequest): AuthorizedSecureMediaLease =
            throw issueFailure

        override fun cleanupFailures(): List<SecureObjectStagingCleanupReport> = emptyList()
        override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease = error("unused")
        override fun revokeScope(scope: String) = Unit
        override fun revokeVault(vaultId: AccountVaultId) = Unit
    }

    private class RecordingStageLeaseManager : SecureMediaLeaseManager, AuthorizedSecureMediaLeaseManager, SecureObjectStagingStore {
        var stageReturned = false
        var issueAuthorizedCalls = 0
        var partialBytes = ByteArray(0)

        override suspend fun stage(
            request: SecureObjectWriteRequest,
            writePlaintext: suspend (OutputStream) -> Unit,
        ): SecureObjectStagingTransaction {
            val output = ByteArrayOutputStream()
            try {
                writePlaintext(output)
            } finally {
                partialBytes = output.toByteArray()
            }
            stageReturned = true
            return IncompleteAbortTransaction(request, partialBytes.size.toLong())
        }

        override suspend fun issueAuthorized(request: SecureMediaAuthorizedLeaseRequest): AuthorizedSecureMediaLease {
            issueAuthorizedCalls++
            error("A partial decrypt must not reach lease issuance.")
        }

        override fun cleanupFailures(): List<SecureObjectStagingCleanupReport> = emptyList()
        override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease = error("unused")
        override fun revokeScope(scope: String) = Unit
        override fun revokeVault(vaultId: AccountVaultId) = Unit
    }

    private class IncompleteAbortTransaction(
        override val request: SecureObjectWriteRequest,
        override val plaintextLength: Long,
    ) : SecureObjectStagingTransaction {
        override val transactionId: String = "incomplete-abort"
        var abortCalls = 0

        override suspend fun commit(): SecureObjectReference = error("unused")

        override suspend fun abort(): SecureObjectStagingCleanupReport {
            abortCalls++
            return SecureObjectStagingCleanupReport(
                transactionId,
                listOf(SecureObjectStagingCleanupItem("encrypted-staging", false)),
            )
        }
    }

    private class InjectedFailure : RuntimeException("injected protected-media failure")

    private companion object {
        val VAULT_ID = AccountVaultId("vault")
        val PLAINTEXT = "hello".encodeToByteArray()
    }
}
