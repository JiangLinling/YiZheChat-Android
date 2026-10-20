/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.AccountActivationReceipt
import io.element.android.security.dataprotection.api.AccountIdentityContinuity
import io.element.android.security.dataprotection.api.AccountReactivationCheckpointState
import io.element.android.security.dataprotection.api.PreparedAccountReactivation
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.test.FakeDeviceRootKeyService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EncryptedAccountReactivationScopeStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `confirmed reset cancellation permits fresh activation without asserting reset completion`() = runTest {
        val store = EncryptedAccountReactivationScopeStore(roots(), FakeDeviceRootKeyService(INSTALLATION_ID))
        store.beginIdentityReset(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH, "a".repeat(64))
        assertThat(runCatching {
            store.cancelIdentityReset(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH, "b".repeat(64))
        }.isFailure).isTrue()
        store.cancelIdentityReset(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH, "a".repeat(64))
        store.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        val checkpoint = checkNotNull(store.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH))
        assertThat(checkpoint.reactivationRequired).isTrue()
        assertThat(checkpoint.pendingExplicitIdentityReset).isFalse()
        assertThat(checkpoint.identityResetPreviousKeyHash).isNull()
    }

    @Test
    fun `reset intent survives restart and cannot become activation before Matrix completion`() = runTest {
        val roots = roots()
        val key = FakeDeviceRootKeyService(INSTALLATION_ID)
        val first = EncryptedAccountReactivationScopeStore(roots, key)
        first.beginIdentityReset(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH, "a".repeat(64))
        val resumed = EncryptedAccountReactivationScopeStore(roots, key)
        val before = checkNotNull(resumed.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH))
        assertThat(before.identityResetPreviousKeyHash).isEqualTo("a".repeat(64))
        assertThat(before.pendingExplicitIdentityReset).isFalse()
        assertThat(runCatching { resumed.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH) }.isFailure).isTrue()
        resumed.markExplicitIdentityReset(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        val completed = checkNotNull(resumed.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH))
        assertThat(completed.attempt).isEqualTo(before.attempt)
        assertThat(completed.identityResetPreviousKeyHash).isNull()
        assertThat(completed.pendingExplicitIdentityReset).isTrue()
    }

    @Test
    fun `generation is durably incremented across process recreation`() = runTest {
        val roots = roots()
        val rootKey = FakeDeviceRootKeyService(INSTALLATION_ID)
        val first = EncryptedAccountReactivationScopeStore(roots, rootKey)
            .nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)

        val second = EncryptedAccountReactivationScopeStore(roots, rootKey)
            .nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)

        assertThat(first.clientGeneration).isEqualTo(1)
        assertThat(second.clientGeneration).isEqualTo(2)
        assertThat(second.clientScopeId).isEqualTo(first.clientScopeId)
        assertThat(second.scopeStartedAtEpochMillis).isEqualTo(first.scopeStartedAtEpochMillis)
        assertThat(second.idempotencyKey).isNotEqualTo(first.idempotencyKey)
    }

    @Test
    fun `new Matrix device creates an ordered isolated scope`() = runTest {
        val roots = roots()
        val store = EncryptedAccountReactivationScopeStore(roots, FakeDeviceRootKeyService(INSTALLATION_ID))
        val first = store.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)

        val second = store.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, OTHER_MATRIX_DEVICE_HASH)

        assertThat(second.clientGeneration).isEqualTo(1)
        assertThat(second.clientScopeId).isNotEqualTo(first.clientScopeId)
        assertThat(second.scopeStartedAtEpochMillis).isGreaterThan(first.scopeStartedAtEpochMillis)
    }

    @Test
    fun `prepared and committed transaction survive process recreation`() = runTest {
        val roots = roots()
        val rootKey = FakeDeviceRootKeyService(INSTALLATION_ID)
        val firstStore = EncryptedAccountReactivationScopeStore(roots, rootKey)
        val attempt = firstStore.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        val prepared = PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE)
        firstStore.markPrepared(attempt, prepared)
        firstStore.markCommitted(attempt, receipt(attempt))

        val recovered = EncryptedAccountReactivationScopeStore(roots, rootKey)
            .current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)

        assertThat(recovered?.state).isEqualTo(AccountReactivationCheckpointState.COMMITTED)
        assertThat(recovered?.prepared).isEqualTo(prepared)
        assertThat(recovered?.receipt).isEqualTo(receipt(attempt))
    }

    @Test
    fun `history recovery generation and hashed room scope survive process recreation`() = runTest {
        val roots = roots()
        val rootKey = FakeDeviceRootKeyService(INSTALLATION_ID)
        val store = EncryptedAccountReactivationScopeStore(roots, rootKey)
        val activation = store.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        store.markPrepared(activation, PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE))
        store.markCommitted(activation, receipt(activation))

        val first = store.nextHistoryRecoveryAttempt(
            ACCOUNT_HASH,
            INSTALLATION_ID,
            MATRIX_DEVICE_HASH,
            setOf(ROOM_ID),
        )
        val second = EncryptedAccountReactivationScopeStore(roots, rootKey).nextHistoryRecoveryAttempt(
            ACCOUNT_HASH,
            INSTALLATION_ID,
            MATRIX_DEVICE_HASH,
            setOf(ROOM_ID),
        )
        val checkpoint = store.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)

        assertThat(first.generation).isEqualTo(1L)
        assertThat(second.generation).isEqualTo(2L)
        assertThat(second.roomScopeHashes).containsExactly(sha256Hex(ROOM_ID))
        assertThat(checkpoint?.historyRecoveryGeneration).isEqualTo(2L)
        assertThat(checkpoint?.pendingHistoryRoomScopeHashes).doesNotContain(ROOM_ID)
    }

    @Test
    fun `wrong Matrix device cannot resume another device checkpoint`() = runTest {
        val roots = roots()
        val store = EncryptedAccountReactivationScopeStore(roots, FakeDeviceRootKeyService(INSTALLATION_ID))
        store.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)

        assertThat(store.current(ACCOUNT_HASH, INSTALLATION_ID, OTHER_MATRIX_DEVICE_HASH)).isNull()
    }

    @Test
    fun `fresh account and current authenticated session do not enable reactivation gate`() = runTest {
        val roots = roots()
        val store = EncryptedAccountReactivationScopeStore(roots, FakeDeviceRootKeyService(INSTALLATION_ID))

        assertThat(
            store.requiresCoreReadyEnforcement(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        ).isFalse()

        store.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)

        assertThat(
            store.requiresCoreReadyEnforcement(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        ).isFalse()
    }

    @Test
    fun `normal account removal survives restart and requires signed activation`() = runTest {
        val roots = roots()
        val rootKey = FakeDeviceRootKeyService(INSTALLATION_ID)
        val store = EncryptedAccountReactivationScopeStore(roots, rootKey)
        val first = store.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        store.markPrepared(first, PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE))
        store.markCommitted(first, receipt(first))

        assertThat(store.markAccountRemoved(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)).isTrue()

        val restarted = EncryptedAccountReactivationScopeStore(roots, rootKey)
        assertThat(
            restarted.requiresCoreReadyEnforcement(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        ).isTrue()
        assertThat(
            restarted.requiresCoreReadyEnforcement(ACCOUNT_HASH, INSTALLATION_ID, OTHER_MATRIX_DEVICE_HASH)
        ).isTrue()

        val second = restarted.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, OTHER_MATRIX_DEVICE_HASH)
        restarted.markPrepared(second, PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE))
        restarted.markCommitted(second, receipt(second))

        assertThat(
            restarted.requiresCoreReadyEnforcement(ACCOUNT_HASH, INSTALLATION_ID, OTHER_MATRIX_DEVICE_HASH)
        ).isFalse()
    }

    @Test
    fun `explicit identity reset invalidates old transaction and survives until signed reset receipt`() = runTest {
        val roots = roots()
        val rootKey = FakeDeviceRootKeyService(INSTALLATION_ID)
        val store = EncryptedAccountReactivationScopeStore(roots, rootKey)
        val oldAttempt = store.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        store.markPrepared(oldAttempt, PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE))

        assertThat(store.markExplicitIdentityReset(ACCOUNT_HASH, INSTALLATION_ID, OTHER_MATRIX_DEVICE_HASH)).isTrue()
        val resetCheckpoint = EncryptedAccountReactivationScopeStore(roots, rootKey)
            .current(ACCOUNT_HASH, INSTALLATION_ID, OTHER_MATRIX_DEVICE_HASH)

        assertThat(resetCheckpoint?.state).isEqualTo(AccountReactivationCheckpointState.ALLOCATED)
        assertThat(resetCheckpoint?.pendingExplicitIdentityReset).isTrue()
        assertThat(resetCheckpoint?.reactivationRequired).isTrue()
        val resetAttempt = checkNotNull(resetCheckpoint).attempt
        store.markPrepared(resetAttempt, PreparedAccountReactivation(ACTIVATION_ID, Long.MAX_VALUE))
        store.markCommitted(
            resetAttempt,
            receipt(resetAttempt, AccountIdentityContinuity.RESET_CONFIRMED),
        )

        val committed = store.current(ACCOUNT_HASH, INSTALLATION_ID, OTHER_MATRIX_DEVICE_HASH)
        assertThat(committed?.pendingExplicitIdentityReset).isFalse()
        assertThat(committed?.reactivationRequired).isFalse()
        assertThat(committed?.receipt?.identityContinuity).isEqualTo(AccountIdentityContinuity.RESET_CONFIRMED)
    }

    @Test
    fun `failed remote logout can restore only the same Matrix session`() = runTest {
        val roots = roots()
        val store = EncryptedAccountReactivationScopeStore(roots, FakeDeviceRootKeyService(INSTALLATION_ID))
        store.markAccountRemoved(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)

        assertThat(store.restoreAccountActive(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)).isTrue()
        assertThat(
            store.requiresCoreReadyEnforcement(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        ).isFalse()

        assertThrows(IllegalStateException::class.java) {
            runTest {
                store.restoreAccountActive(ACCOUNT_HASH, INSTALLATION_ID, OTHER_MATRIX_DEVICE_HASH)
            }
        }
    }

    @Test
    fun `corrupt retained lifecycle state never falls back to fresh login`() = runTest {
        val roots = roots()
        val rootKey = FakeDeviceRootKeyService(INSTALLATION_ID)
        EncryptedAccountReactivationScopeStore(roots, rootKey)
            .markAccountRemoved(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        revisionFiles(roots).forEach { it.writeBytes(byteArrayOf(1, 2, 3)) }

        assertThrows(IllegalStateException::class.java) {
            runTest {
                EncryptedAccountReactivationScopeStore(roots, rootKey)
                    .requiresCoreReadyEnforcement(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
            }
        }
    }

    @Test
    fun `permanent account deletion erases every retained activation revision`() = runTest {
        val roots = roots()
        val rootKey = FakeDeviceRootKeyService(INSTALLATION_ID)
        val store = EncryptedAccountReactivationScopeStore(roots, rootKey)
        store.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        store.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        assertThat(revisionFiles(roots)).isNotEmpty()

        assertThat(store.clearPermanentlyDeletedAccount(ACCOUNT_HASH, INSTALLATION_ID)).isTrue()

        assertThat(revisionFiles(roots)).isEmpty()
        assertThat(store.current(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)).isNull()
        assertThat(store.clearPermanentlyDeletedAccount(ACCOUNT_HASH, INSTALLATION_ID)).isTrue()
    }

    @Test
    fun `corrupt newest revision falls back before allocating the next generation`() = runTest {
        val roots = roots()
        val rootKey = FakeDeviceRootKeyService(INSTALLATION_ID)
        val store = EncryptedAccountReactivationScopeStore(roots, rootKey)
        val first = store.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        store.nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        revisionFiles(roots).maxBy { it.name }.writeBytes(byteArrayOf(1, 2, 3))

        val recovered = EncryptedAccountReactivationScopeStore(roots, rootKey)
            .nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)

        assertThat(recovered.clientScopeId).isEqualTo(first.clientScopeId)
        assertThat(recovered.clientGeneration).isEqualTo(2)
    }

    @Test
    fun `fully unauthenticated checkpoint fails closed`() = runTest {
        val roots = roots()
        EncryptedAccountReactivationScopeStore(
            roots,
            FakeDeviceRootKeyService(INSTALLATION_ID),
        ).nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
        revisionFiles(roots).forEach { it.writeBytes(byteArrayOf(1, 2, 3)) }

        assertThrows(IllegalStateException::class.java) {
            runTest {
                EncryptedAccountReactivationScopeStore(
                    roots,
                    FakeDeviceRootKeyService(INSTALLATION_ID),
                ).nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
            }
        }
    }

    @Test
    fun `another installation root cannot authenticate checkpoint`() = runTest {
        val roots = roots()
        EncryptedAccountReactivationScopeStore(
            roots,
            FakeDeviceRootKeyService(INSTALLATION_ID),
        ).nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)

        assertThrows(IllegalStateException::class.java) {
            runTest {
                EncryptedAccountReactivationScopeStore(
                    roots,
                    FakeDeviceRootKeyService("different-installation-root"),
                ).nextAttempt(ACCOUNT_HASH, INSTALLATION_ID, MATRIX_DEVICE_HASH)
            }
        }
    }

    private fun roots(): SecureStorageRoots {
        val persistent = temporaryFolder.root.resolve("persistent")
        val runtime = temporaryFolder.root.resolve("runtime")
        return object : SecureStorageRoots {
            override fun persistentRoot(): File = persistent
            override fun runtimeRoot(): File = runtime
        }
    }

    private fun revisionFiles(roots: SecureStorageRoots): List<File> = roots.persistentRoot()
        .resolve("account-reactivation")
        .listFiles()
        .orEmpty()
        .filter { it.name.startsWith("scope-") }

    private fun receipt(
        attempt: io.element.android.security.dataprotection.api.AccountReactivationAttempt,
        identityContinuity: AccountIdentityContinuity = AccountIdentityContinuity.FIRST_ACTIVATION,
    ) =
        AccountActivationReceipt(
            activationId = ACTIVATION_ID,
            accountIdHash = attempt.accountIdHash,
            installationId = attempt.installationId,
            matrixDeviceIdHash = attempt.matrixDeviceIdHash,
            clientScopeId = attempt.clientScopeId,
            clientGeneration = attempt.clientGeneration,
            attestationVersion = 7,
            policyVersion = 9,
            matrixCrossSigningKeyHash = OTHER_MATRIX_DEVICE_HASH,
            recipientKeyId = RECIPIENT_KEY_ID,
            identityContinuity = identityContinuity,
            accountMobileGeneration = 7,
            committedAtEpochMillis = 1234,
        )

    private fun sha256Hex(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val MATRIX_DEVICE_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val OTHER_MATRIX_DEVICE_HASH = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val INSTALLATION_ID = "installation-reactivation-test-0001"
        const val ACTIVATION_ID = "activation-reactivation-test-0001"
        const val RECIPIENT_KEY_ID = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val ROOM_ID = "!history-room:example.org"
    }
}
