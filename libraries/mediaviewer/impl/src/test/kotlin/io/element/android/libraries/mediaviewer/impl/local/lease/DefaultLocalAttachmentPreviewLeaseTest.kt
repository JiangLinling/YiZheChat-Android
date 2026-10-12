/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.lease

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewCancelReason
import io.element.android.libraries.mediaviewer.impl.local.office.FilePreviewLeaseBinding
import io.element.android.libraries.mediaviewer.impl.local.office.FilePreviewLeaseRegistry
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerAcquireResult
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerAuthority
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerCancelReason
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerCapability
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerMutationResult
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerPhase
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerRole
import io.element.android.security.dataprotection.test.FakeLifecycleOperationOwnerAuthority
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStoreMaintenanceReport
import io.element.android.security.securestorage.api.SecureVaultDeletionReport
import io.element.android.security.securestorage.api.SensitiveFileStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultLocalAttachmentPreviewLeaseTest {
    @Test
    fun `transfer revokes preview and deletes staging before upload ownership`() = runTest {
        val fixture = fixture()

        assertThat(fixture.lease.transferToUpload().isSuccess).isTrue()

        assertThat(FilePreviewLeaseRegistry.acquire(fixture.token, BINDING, AUTHORITY)).isNull()
        assertThat(fixture.store.deleteCount).isEqualTo(1)
        assertThat(fixture.authority.ownerState(OPERATION_ID)?.role).isEqualTo(LifecycleOperationOwnerRole.UPLOAD)
        assertThat(fixture.events.take(2)).containsExactly("delete", "transfer").inOrder()
        assertThat(fixture.lease.transferToUpload().isFailure).isTrue()
        assertThat(fixture.store.deleteCount).isEqualTo(1)
    }

    @Test
    fun `duplicate close revokes synchronously and cleanup is awaitable`() = runTest {
        val fixture = fixture()

        fixture.lease.close()
        fixture.lease.close()

        assertThat(FilePreviewLeaseRegistry.acquire(fixture.token, BINDING, AUTHORITY)).isNull()
        advanceUntilIdle()
        val cleanup = fixture.lease.closeAndAwait().getOrThrow()
        assertThat(cleanup.isComplete).isTrue()
        assertThat(fixture.store.deleteCount).isEqualTo(1)
        assertThat(fixture.authority.ownerState(OPERATION_ID)?.phase)
            .isEqualTo(LifecycleOperationOwnerPhase.AVAILABLE)
    }

    @Test
    fun `explicit cancellation is idempotent after complete cleanup`() = runTest {
        val fixture = fixture()

        val first = fixture.lease.cancel(LocalAttachmentPreviewCancelReason.PREVIEW_FAILED).getOrThrow()
        val second = fixture.lease.cancel(LocalAttachmentPreviewCancelReason.PREVIEW_FAILED).getOrThrow()

        assertThat(first.isComplete).isTrue()
        assertThat(second).isEqualTo(first)
        assertThat(fixture.store.deleteCount).isEqualTo(1)
    }

    @Test
    fun `close completes cleanup after the external session scope is cancelled`() = runTest {
        val sessionJob = Job()
        val fixture = fixture(
            sessionCoroutineScope = CoroutineScope(coroutineContext.minusKey(Job) + sessionJob),
        )
        sessionJob.cancel()

        fixture.lease.close()
        val cleanup = fixture.lease.closeAndAwait().getOrThrow()

        assertThat(cleanup.isComplete).isTrue()
        assertThat(fixture.store.deleteCount).isEqualTo(1)
        assertThat(fixture.authority.ownerState(OPERATION_ID)?.phase)
            .isEqualTo(LifecycleOperationOwnerPhase.AVAILABLE)
    }

    @Test
    fun `closeAndAwait waits for encrypted deletion and owner release`() = runTest {
        val deleteStarted = CompletableDeferred<Unit>()
        val allowDelete = CompletableDeferred<Unit>()
        val fixture = fixture(deleteStarted = deleteStarted, allowDelete = allowDelete)

        val awaitingCleanup = async { fixture.lease.closeAndAwait() }
        deleteStarted.await()

        assertThat(awaitingCleanup.isCompleted).isFalse()
        assertThat(FilePreviewLeaseRegistry.acquire(fixture.token, BINDING, AUTHORITY)).isNull()
        assertThat(fixture.authority.ownerState(OPERATION_ID)?.role)
            .isEqualTo(LifecycleOperationOwnerRole.PREVIEW)

        allowDelete.complete(Unit)
        val cleanup = awaitingCleanup.await().getOrThrow()

        assertThat(cleanup.isComplete).isTrue()
        assertThat(fixture.events).containsExactly("delete", "cancel").inOrder()
        assertThat(fixture.authority.ownerState(OPERATION_ID)?.phase)
            .isEqualTo(LifecycleOperationOwnerPhase.AVAILABLE)
    }

    @Test
    fun `close reports deletion failure and retains owner fail closed`() = runTest {
        val fixture = fixture(deleteResult = false)

        val cleanup = fixture.lease.closeAndAwait()

        assertThat(cleanup.isFailure).isTrue()
        assertThat(FilePreviewLeaseRegistry.acquire(fixture.token, BINDING, AUTHORITY)).isNull()
        assertThat(fixture.store.deleteCount).isEqualTo(1)
        assertThat(fixture.events).containsExactly("delete")
        assertThat(fixture.authority.ownerState(OPERATION_ID)?.role)
            .isEqualTo(LifecycleOperationOwnerRole.PREVIEW)
    }

    @Test
    fun `close racing transfer never cancels a transferred upload owner`() = runTest {
        val deleteStarted = CompletableDeferred<Unit>()
        val allowDelete = CompletableDeferred<Unit>()
        val fixture = fixture(deleteStarted = deleteStarted, allowDelete = allowDelete)
        val transfer = async { fixture.lease.transferToUpload() }
        deleteStarted.await()

        fixture.lease.close()
        allowDelete.complete(Unit)

        assertThat(transfer.await().isSuccess).isTrue()
        advanceUntilIdle()
        assertThat(fixture.authority.ownerState(OPERATION_ID)?.role)
            .isEqualTo(LifecycleOperationOwnerRole.UPLOAD)
        assertThat(fixture.events).doesNotContain("cancel")

        assertThat(fixture.lease.completeUpload().isSuccess).isTrue()
        assertThat(fixture.lease.closeAndAwait().getOrThrow().isComplete).isTrue()
    }

    private suspend fun TestScope.fixture(
        sessionCoroutineScope: CoroutineScope = this,
        deleteStarted: CompletableDeferred<Unit>? = null,
        allowDelete: CompletableDeferred<Unit>? = null,
        deleteResult: Boolean = true,
    ): Fixture {
        val events = mutableListOf<String>()
        val authority = FakeLifecycleOperationOwnerAuthority()
        val recordingAuthority = RecordingOwnerAuthority(authority, events)
        val owner = (recordingAuthority.acquireOwner(
            operationId = OPERATION_ID,
            role = LifecycleOperationOwnerRole.PREVIEW,
            commandId = "acquire-preview-owner-command",
        ) as LifecycleOperationOwnerAcquireResult.Granted).capability
        val token = FilePreviewLeaseRegistry.register(BINDING, TestMediaFile())
        val store = RecordingSensitiveFileStore(events, deleteStarted, allowDelete, deleteResult)
        val previewMedia = aLocalMedia(Uri.parse("content://$AUTHORITY/v1/${"a".repeat(64)}")).copy(
            lifecycleOperationId = OPERATION_ID,
            filePreviewLeaseToken = token,
        )
        return Fixture(
            lease = DefaultLocalAttachmentPreviewLease(
                previewMedia = previewMedia,
                token = token,
                reference = REFERENCE,
                initialOwner = owner,
                ownerAuthority = recordingAuthority,
                sensitiveFileStore = store,
                sessionCoroutineScope = sessionCoroutineScope,
                ttl = 1.hours,
            ),
            token = token,
            authority = authority,
            store = store,
            events = events,
        )
    }

    private data class Fixture(
        val lease: DefaultLocalAttachmentPreviewLease,
        val token: String,
        val authority: FakeLifecycleOperationOwnerAuthority,
        val store: RecordingSensitiveFileStore,
        val events: List<String>,
    )

    private class RecordingOwnerAuthority(
        private val delegate: LifecycleOperationOwnerAuthority,
        private val events: MutableList<String>,
    ) : LifecycleOperationOwnerAuthority by delegate {
        override suspend fun transferOwner(
            capability: LifecycleOperationOwnerCapability,
            toRole: LifecycleOperationOwnerRole,
            commandId: String,
        ): LifecycleOperationOwnerMutationResult {
            events += "transfer"
            return delegate.transferOwner(capability, toRole, commandId)
        }

        override suspend fun completeOwner(
            capability: LifecycleOperationOwnerCapability,
            commandId: String,
        ): LifecycleOperationOwnerMutationResult {
            events += "complete"
            return delegate.completeOwner(capability, commandId)
        }

        override suspend fun cancelOwner(
            capability: LifecycleOperationOwnerCapability,
            commandId: String,
            reason: LifecycleOperationOwnerCancelReason,
        ): LifecycleOperationOwnerMutationResult {
            events += "cancel"
            return delegate.cancelOwner(capability, commandId, reason)
        }
    }

    private class RecordingSensitiveFileStore(
        private val events: MutableList<String>,
        private val deleteStarted: CompletableDeferred<Unit>?,
        private val allowDelete: CompletableDeferred<Unit>?,
        private val deleteResult: Boolean,
    ) : SensitiveFileStore {
        var deleteCount = 0
            private set

        override suspend fun write(
            request: SecureObjectWriteRequest,
            writePlaintext: suspend (OutputStream) -> Unit,
        ): SecureObjectReference = error("Not used")

        override suspend fun <T> read(
            reference: SecureObjectReference,
            readPlaintext: suspend (InputStream) -> T,
        ): T = error("Not used")

        override suspend fun readRange(
            reference: SecureObjectReference,
            offset: Long,
            byteCount: Int,
        ): ByteArray = error("Not used")

        override suspend fun delete(reference: SecureObjectReference): Boolean {
            deleteCount++
            events += "delete"
            deleteStarted?.complete(Unit)
            allowDelete?.await()
            return deleteResult
        }

        override suspend fun deleteObject(
            identity: io.element.android.security.securestorage.api.SecureObjectIdentity,
        ): Boolean = error("Not used")

        override suspend fun deleteVault(vaultId: AccountVaultId): SecureVaultDeletionReport = error("Not used")
        override suspend fun runMaintenance(): SecureStoreMaintenanceReport = error("Not used")
    }

    private class TestMediaFile : MediaFile {
        override fun path(): String = error("No plaintext path")
        override fun uriString(): String = "content://$AUTHORITY/v1/${"a".repeat(64)}"
        override fun openInputStream(): InputStream = ByteArrayInputStream(byteArrayOf(1))
        override fun persist(path: String): Boolean = false
        override fun close() = Unit
    }

    private companion object {
        const val OPERATION_ID = "file-preview-operation-id"
        const val AUTHORITY = "com.yizhe.chat.debug.securemedia"
        val BINDING = FilePreviewLeaseBinding(OPERATION_ID, "room-hash", "attachment-hash")
        val REFERENCE = SecureObjectReference(
            objectId = SecureObjectId("preview-object"),
            vaultId = AccountVaultId("preview-vault"),
            purpose = SecureObjectPurpose.DOCUMENT_PREVIEW,
            keyEnvelopeId = KeyEnvelopeId("preview-envelope"),
            formatVersion = 1,
            plaintextLength = 1,
            ciphertextLength = 2,
            associatedDataSha256 = "a".repeat(64),
        )
    }
}
