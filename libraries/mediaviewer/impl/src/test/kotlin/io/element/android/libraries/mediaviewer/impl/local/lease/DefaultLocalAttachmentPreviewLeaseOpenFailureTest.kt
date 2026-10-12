/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local.lease

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.mediaviewer.api.anImageMediaInfo
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewOpenException
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerRole
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStep
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.test.FakeLifecycleOperationOwnerAuthority
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.VaultKeyService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class DefaultLocalAttachmentPreviewLeaseOpenFailureTest {
    private val ownedFiles = mutableListOf<File>()

    @After
    fun cleanupFixtures() {
        ownedFiles.forEach { it.delete() }
    }

    @Test
    fun `post admission missing source releases its acquired owner and queues only two observed Android facts`() = runTest {
        val fixture = fixture(Mode.MISSING)
        val failure = fixture.open()
        assertThat(failure.cleanup.isComplete).isTrue()
        assertThat(failure.cleanupFactsQueued).isTrue()
        assertThat(failure.cleanupPending).isFalse()
        assertThat(fixture.receipts.map { it.component }).containsExactly(LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE)
        assertThat(fixture.receipts.map { it.operationId }.toSet()).containsExactly(fixture.source.lifecycleOperationId)
        assertThat(fixture.owner.ownerState(fixture.source.lifecycleOperationId)?.role).isNull()
        // An enqueued client fact is not a signed terminal authority projection.
        assertThat(fixture.admission.status).isEqualTo(LifecycleOperationStatus.RUNNING)
        coVerify(exactly = 0) { fixture.store.write(any(), any()) }
    }

    @Test
    fun `returned owned staging is deleted when its length is rejected before lease issuance`() = runTest {
        val fixture = fixture(Mode.LENGTH_MISMATCH)
        val failure = fixture.open()
        assertThat(failure.cleanup.isComplete).isTrue()
        assertThat(failure.cleanupFactsQueued).isTrue()
        coVerify(exactly = 1) { fixture.store.delete(fixture.writtenReference) }
        coVerify(exactly = 0) { fixture.leases.issue(any()) }
    }

    @Test
    fun `failed staging deletion retains the acquired owner and never queues success facts`() = runTest {
        val fixture = fixture(Mode.DELETE_FAILED)
        val failure = fixture.open()
        assertThat(failure.cleanup.encryptedObjectDeleted).isFalse()
        assertThat(failure.cleanup.ownerReleased).isFalse()
        assertThat(failure.cleanupPending).isTrue()
        assertThat(fixture.owner.ownerState(fixture.source.lifecycleOperationId)?.role).isEqualTo(LifecycleOperationOwnerRole.PREVIEW)
        assertThat(fixture.receipts).isEmpty()
    }

    @Test
    fun `write failure without a returned reference remains unknown and is not erased through a synthetic reference`() = runTest {
        val fixture = fixture(Mode.WRITE_FAILED)
        val failure = fixture.open()
        assertThat(failure.cleanup.encryptedObjectDeleted).isNull()
        assertThat(failure.cleanupPending).isTrue()
        assertThat(fixture.receipts).isEmpty()
        coVerify(exactly = 0) { fixture.store.delete(any()) }
    }

    @Test
    fun `failed issuance without a returned capability is not treated as observed read revocation`() = runTest {
        val fixture = fixture(Mode.ISSUE_FAILED)
        val failure = fixture.open()
        assertThat(failure.cleanup.readAccessRevoked).isNull()
        assertThat(failure.cleanup.encryptedObjectDeleted).isTrue()
        assertThat(failure.cleanupPending).isTrue()
        assertThat(fixture.receipts).isEmpty()
    }

    @Test
    fun `host close failure still attempts deletion and retains unresolved owner`() = runTest {
        val fixture = fixture(Mode.CLOSE_FAILED)
        val failure = fixture.open()
        assertThat(failure.cleanup.readAccessRevoked).isFalse()
        assertThat(failure.cleanup.encryptedObjectDeleted).isTrue()
        assertThat(failure.cleanup.ownerReleased).isFalse()
        assertThat(fixture.receipts).isEmpty()
        coVerify(exactly = 1) { fixture.store.delete(fixture.writtenReference) }
    }

    @Test
    fun `owner rejection cannot complete a previously held preview operation`() = runTest {
        val fixture = fixture(Mode.OWNER_HELD)
        fixture.owner.acquireOwner(fixture.source.lifecycleOperationId, LifecycleOperationOwnerRole.PREVIEW, "existing-owner-command")
        val failure = fixture.open()
        assertThat(failure.cleanup.ownerReleased).isNull()
        assertThat(failure.cleanupPending).isTrue()
        assertThat(fixture.receipts).isEmpty()
        assertThat(fixture.owner.ownerState(fixture.source.lifecycleOperationId)?.role).isEqualTo(LifecycleOperationOwnerRole.PREVIEW)
    }

    @Test
    fun `failed receipt persistence is explicit even after complete local rollback`() = runTest {
        val fixture = fixture(Mode.MISSING)
        coEvery { fixture.coordinator.enqueueClientReceipts(any()) } throws IOException("fixture receipt store unavailable")
        val failure = fixture.open()
        assertThat(failure.cleanup.isComplete).isTrue()
        assertThat(failure.cleanupFactsQueued).isFalse()
        assertThat(failure.cleanupPending).isTrue()
    }

    private fun TestScope.fixture(mode: Mode): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceFile = File.createTempFile("preview-failure-", ".pdf", context.cacheDir)
        ownedFiles += sourceFile
        sourceFile.writeBytes(byteArrayOf(37, 80, 68, 70))
        if (mode == Mode.MISSING) check(sourceFile.delete())
        val source = LocalMedia(
            uri = Uri.fromFile(sourceFile),
            info = anImageMediaInfo().copy(mimeType = "application/pdf", filename = sourceFile.name, fileSize = 4L),
            lifecycleOperationId = UUID.randomUUID().toString(),
            lifecycleRoomIdHash = "a".repeat(64),
        )
        val coordinator = mockk<AppOperationalStateCoordinator>()
        val receipts = mutableListOf<LifecycleClientReceipt>()
        val store = mockk<SensitiveFileStore>()
        val leases = mockk<SecureMediaLeaseManager>()
        val vault = mockk<VaultKeyService>()
        val owner = FakeLifecycleOperationOwnerAuthority()
        val fixture = Fixture(source, coordinator, store, leases, owner, receipts)
        coEvery { coordinator.start(any()) } coAnswers {
            val request = firstArg<LifecycleOperationRequest>()
            val now = System.currentTimeMillis()
            LifecycleOperation(
                operationId = request.operationId,
                lifecycleId = request.lifecycleId,
                idempotencyKey = request.idempotencyKey,
                subjects = LifecycleSubjectHashes(request.accountIdHash, matrixDeviceIdHash = request.matrixDeviceIdHash, roomIdHash = request.roomIdHash),
                status = LifecycleOperationStatus.RUNNING,
                currentPhase = LifecyclePhase.INTENT_PERSISTED,
                steps = listOf(LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE).map { component ->
                    LifecycleStep(
                        "local-preview-${component.name}",
                        component,
                        status = LifecycleStepStatus.PENDING,
                        attemptCount = 0,
                        updatedAtEpochMillis = now
                    )
                },
                deadlineEpochMillis = request.deadlineEpochMillis,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ).also { fixture.admission = it }
        }
        coEvery { coordinator.enqueueClientReceipts(any()) } coAnswers { receipts += firstArg<List<LifecycleClientReceipt>>() }
        coEvery { vault.isUnlocked(any()) } returns true
        coEvery { store.write(any(), any()) } coAnswers {
            val request = firstArg<SecureObjectWriteRequest>()
            val write = secondArg<suspend (OutputStream) -> Unit>()
            ByteArrayOutputStream().use { output ->
                write(output)
                assertThat(output.size()).isEqualTo(4)
            }
            if (mode == Mode.WRITE_FAILED) throw IOException("fixture write failed before returning ownership")
            SecureObjectReference(
                objectId = request.objectId,
                vaultId = request.vaultId,
                purpose = request.purpose,
                keyEnvelopeId = KeyEnvelopeId("fixture-key-envelope"),
                formatVersion = 1,
                plaintextLength = if (mode in setOf(Mode.LENGTH_MISMATCH, Mode.DELETE_FAILED)) 3L else 4L,
                ciphertextLength = 32,
                associatedDataSha256 = MessageDigest.getInstance("SHA-256").digest(request.associatedData)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) },
            ).also { fixture.writtenReference = it }
        }
        coEvery { store.delete(any()) } returns (mode != Mode.DELETE_FAILED)
        coEvery { leases.issue(any()) } coAnswers {
            if (mode == Mode.ISSUE_FAILED) throw IOException("fixture issuance failed without returning a capability")
            mockk<SecureMediaLease>().also { lease ->
                every { lease.uri } returns Uri.parse("content://invalid-fixture-carrier/source")
                every { lease.close() } throws IOException("fixture capability close failed")
            }
        }
        fixture.service = DefaultLocalAttachmentPreviewLeaseService(
            context = context,
        matrixClient = FakeMatrixClient(),
        coordinator = coordinator,
        ownerAuthority = owner,
            vaultKeyService = vault,
        sensitiveFileStore = store,
        secureMediaLeaseManager = leases,
        sessionCoroutineScope = backgroundScope,
        )
        return fixture
    }

    private class Fixture(
        val source: LocalMedia,
        val coordinator: AppOperationalStateCoordinator,
        val store: SensitiveFileStore,
        val leases: SecureMediaLeaseManager,
        val owner: FakeLifecycleOperationOwnerAuthority,
        val receipts: List<LifecycleClientReceipt>,
    ) {
        lateinit var service: DefaultLocalAttachmentPreviewLeaseService
        lateinit var admission: LifecycleOperation
        lateinit var writtenReference: SecureObjectReference
        suspend fun open(): LocalAttachmentPreviewOpenException = service.openForBrowse(source).exceptionOrNull() as LocalAttachmentPreviewOpenException
    }

    private enum class Mode { MISSING, LENGTH_MISMATCH, DELETE_FAILED, WRITE_FAILED, ISSUE_FAILED, CLOSE_FAILED, OWNER_HELD }
}
