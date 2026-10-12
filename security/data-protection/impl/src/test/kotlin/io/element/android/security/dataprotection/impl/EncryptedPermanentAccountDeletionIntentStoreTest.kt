/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.DeletionOperationId
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.PermanentAccountDeletionHandle
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.test.FakeDeviceRootKeyService
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EncryptedPermanentAccountDeletionIntentStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `pending intent survives process recreation`() = runTest {
        val roots = roots()
        val rootKey = FakeDeviceRootKeyService(INSTALLATION_ID)
        EncryptedPermanentAccountDeletionIntentStore(roots, rootKey).save(record())

        val recovered = EncryptedPermanentAccountDeletionIntentStore(roots, rootKey).load(HANDLE)

        assertThat(recovered).isEqualTo(record())
    }

    @Test
    fun `corrupt newest revision falls back to previous authenticated revision`() = runTest {
        val roots = roots()
        val rootKey = FakeDeviceRootKeyService(INSTALLATION_ID)
        val store = EncryptedPermanentAccountDeletionIntentStore(roots, rootKey)
        store.save(record())
        val firstRevision = recordFiles(roots).single().readBytes()
        store.save(record().copy(phase = PermanentDeletionPhase.DRAFT, eraseData = false))
        val newest = recordFiles(roots).single()
        val operationHash = newest.name.substringAfter("pending-").substringBeforeLast("-")
        File(newest.parentFile, "pending-$operationHash-00000001.yzpd").writeBytes(firstRevision)
        newest.writeBytes(byteArrayOf(1, 2, 3))

        val recovered = EncryptedPermanentAccountDeletionIntentStore(roots, rootKey).loadAll()

        assertThat(recovered.records).containsExactly(record())
        assertThat(recovered.unreadableRecordCount).isEqualTo(1)
    }

    @Test
    fun `record cannot be opened with another installation root key`() = runTest {
        val roots = roots()
        EncryptedPermanentAccountDeletionIntentStore(
            roots,
            FakeDeviceRootKeyService(INSTALLATION_ID),
        ).save(record())

        val recovered = EncryptedPermanentAccountDeletionIntentStore(
            roots,
            FakeDeviceRootKeyService("different-installation-root"),
        ).loadAll()

        assertThat(recovered.records).isEmpty()
        assertThat(recovered.unreadableRecordCount).isEqualTo(1)
    }

    @Test
    fun `delete removes every revision for the operation`() = runTest {
        val roots = roots()
        val rootKey = FakeDeviceRootKeyService(INSTALLATION_ID)
        val store = EncryptedPermanentAccountDeletionIntentStore(roots, rootKey)
        store.save(record())
        val onlyRevision = recordFiles(roots).single()
        File(onlyRevision.parentFile, onlyRevision.name.replace("00000001", "00000002"))
            .writeBytes(onlyRevision.readBytes())

        assertThat(store.delete(HANDLE)).isTrue()
        assertThat(recordFiles(roots)).isEmpty()
        assertThat(store.load(HANDLE)).isNull()
    }

    private fun roots(): SecureStorageRoots {
        val persistent = temporaryFolder.root.resolve("persistent")
        val runtime = temporaryFolder.root.resolve("runtime")
        return object : SecureStorageRoots {
            override fun persistentRoot(): File = persistent
            override fun runtimeRoot(): File = runtime
        }
    }

    private fun record() = PendingPermanentAccountDeletion(
        handle = HANDLE,
        accountIdHash = ACCOUNT_HASH,
        eraseData = true,
        idempotencyKey = "idempotency-key-000000000001",
        confirmationToken = "confirmation-token-0000000000000001",
        localDeletionRequest = DeletionRequest(
            operationId = DeletionOperationId(HANDLE.value),
            scope = DeletionScope.Account(
                accountIdHash = ACCOUNT_HASH,
                vaultId = "matrix-device-v1:opaque-vault",
                localSessionId = "@delete:example.org",
                sessionFilePath = "C:/sessions/delete",
                sessionCachePath = "C:/cache/delete",
            ),
            reason = DeletionReason.USER_REQUEST,
            policyVersion = 7,
            requestedAtEpochMillis = 1234,
        ),
        phase = PermanentDeletionPhase.PREPARED,
        deletionIntentId = "deletion-intent-0000000000000001",
        installationId = INSTALLATION_ID,
        preparedAtEpochMillis = 1000,
        expiresAtEpochMillis = 60_000,
    )

    private fun recordFiles(roots: SecureStorageRoots): List<File> = roots.persistentRoot()
        .resolve("permanent-account-deletion")
        .listFiles()
        .orEmpty()
        .filter { it.name.startsWith("pending-") }

    private companion object {
        const val ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val INSTALLATION_ID = "installation-delete-test-0001"
        val HANDLE = PermanentAccountDeletionHandle("operation-delete-test-0001")
    }
}
