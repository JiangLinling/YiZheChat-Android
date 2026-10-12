package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.MessageDigest

class DefaultRecoveryIntentAuthorityTest {
    private val scope = RecoveryIntentScope("a".repeat(64), "installation-owned-original", "b".repeat(64))
    private val original = RecoveryIntentRecord(scope, "owned-original-operation-001", "owned-original-idempotency-001", 100_000, 1, "c".repeat(64), 1)
    private class MemoryJournal : RecoveryIntentJournal {
        val entries = mutableMapOf<RecoveryIntentScope, RecoveryIntentRecord>()
        override fun read(scope: RecoveryIntentScope) = entries[scope]
        override fun clearAccount(accountIdHash: String): Boolean { entries.keys.removeAll { it.accountIdHash == accountIdHash }; return true }
        override fun compareAndSet(scope: RecoveryIntentScope, expectedRevision: Long?, next: RecoveryIntentRecord?): Boolean {
            if (entries[scope]?.revision != expectedRevision) return false
            if (next == null) entries.remove(scope) else entries[scope] = next
            return true
        }
    }
    private fun context(record: RecoveryIntentRecord = original, status: LifecycleOperationStatus = LifecycleOperationStatus.RUNNING,
        now: Long = 10_000, code: String? = null) = RecoveryOperationContext(
        operationId = record.operationId, scope = record.scope, trigger = "recovery_key_entered",
        identityGeneration = record.identityGeneration ?: "c".repeat(64), mobileGeneration = record.mobileGeneration ?: 1,
        currentIdentityGeneration = record.identityGeneration ?: "c".repeat(64), currentMobileGeneration = record.mobileGeneration ?: 1,
        scopeValid = true, rootValid = true, currentRootValid = true, status = status,
        terminalReceiptId = if (status.isTerminal) "signed-original-terminal-receipt" else null,
        deadlineEpochMillis = record.deadlineEpochMillis, serverTimeEpochMillis = now, matrixComplete = true,
        recoveryId = record.recoveryId, recoveryExpiresAtEpochMillis = record.recoveryExpiresAtEpochMillis,
        terminalResultCode = code,
        idempotencyKeySha256 = MessageDigest.getInstance("SHA-256").digest(record.idempotencyKey.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun resolution(record: RecoveryIntentRecord, admitted: Boolean = false, now: Long = 10_000) =
        RecoveryIntentResolution(record.operationId, record.scope, context(record).idempotencyKeySha256,
            record.deadlineEpochMillis, now, admitted)
    private fun services(journal: MemoryJournal): Triple<DefaultRecoveryIntentAuthority, AppOperationalStateCoordinator, ManagementControlService> {
        val coordinator = mockk<AppOperationalStateCoordinator>()
        val management = mockk<ManagementControlService>()
        coEvery { coordinator.start(any()) } returns mockk()
        coEvery { coordinator.operation(any()) } returns mockk()
        coEvery { management.recoveryOperationContext(any()) } answers { context(firstArg()) }
        coEvery { management.resolveRecoveryIntent(any()) } answers { resolution(firstArg()) }
        return Triple(DefaultRecoveryIntentAuthority(journal, coordinator, management), coordinator, management)
    }
    @Test fun `cold start restores exact owned operation without starting another`() = runTest {
        val journal = MemoryJournal().apply { entries[scope] = original }
        val (authority, coordinator, _) = services(journal)
        assertThat(authority.restore(scope)).isEqualTo(original)
        coVerify(exactly = 1) { coordinator.operation(original.operationId) }
        coVerify(exactly = 0) { coordinator.start(any()) }
        assertThat(authority.admit(scope)?.operationId).isEqualTo(original.operationId)
        coVerify(exactly = 0) { coordinator.start(any()) }
    }
    @Test fun `another account installation or Matrix device never adopts original journal`() = runTest {
        val journal = MemoryJournal().apply { entries[scope] = original }
        val (authority, coordinator, _) = services(journal)
        for (other in listOf(scope.copy(accountIdHash = "d".repeat(64)), scope.copy(installationId = "other-installation-001"), scope.copy(matrixDeviceIdHash = "e".repeat(64)))) {
            assertThat(authority.restore(other)).isNull()
        }
        assertThat(journal.read(scope)).isEqualTo(original)
        coVerify(exactly = 0) { coordinator.operation(any()) }
    }
    @Test fun `unadmitted live draft submits same persisted request and deadline`() = runTest {
        val draft = original.copy(identityGeneration = null, mobileGeneration = null)
        val journal = MemoryJournal().apply { entries[scope] = draft }
        val (authority, coordinator, _) = services(journal)
        val recovered = authority.restore(scope)
        assertThat(recovered?.operationId).isEqualTo(draft.operationId)
        assertThat(recovered?.deadlineEpochMillis).isEqualTo(draft.deadlineEpochMillis)
        coVerify(exactly = 1) { coordinator.start(draft.request()) }
    }
    @Test fun `waiting identity survives recreation and never starts replacement`() = runTest {
        val journal = MemoryJournal().apply { entries[scope] = original }
        val (authority, coordinator, management) = services(journal)
        val waiting = authority.waiting(original, "original-waiting-recovery", 90_000)
        val recreated = DefaultRecoveryIntentAuthority(journal, coordinator, management)
        assertThat(recreated.restore(scope)?.recoveryId).isEqualTo("original-waiting-recovery")
        assertThat(recreated.verify(waiting).recoveryId).isEqualTo("original-waiting-recovery")
        coVerify(exactly = 0) { coordinator.start(any()) }
    }
    @Test fun `expired cold restore stays gated and does not create fresh intent`() = runTest {
        val journal = MemoryJournal().apply { entries[scope] = original }
        val (authority, coordinator, management) = services(journal)
        coEvery { management.recoveryOperationContext(any()) } returns context(now = 100_001)
        val failure = runCatching { authority.restore(scope) }.exceptionOrNull()
        assertThat((failure as RecoveryIntentRejected).reasonCode).isEqualTo("RECOVERY_OPERATION_EXPIRED")
        assertThat(journal.read(scope)).isEqualTo(original)
        coVerify(exactly = 0) { coordinator.start(any()) }
        coVerify(exactly = 0) { management.expireRecoveryOperation(any()) }
    }
    @Test fun `explicit retry retires expired operation only after signed failure receipt`() = runTest {
        val journal = MemoryJournal().apply { entries[scope] = original }
        val (authority, coordinator, management) = services(journal)
        var expired = false
        coEvery { management.expireRecoveryOperation(original) } answers { expired = true }
        coEvery { management.recoveryOperationContext(any()) } answers {
            val record = firstArg<RecoveryIntentRecord>()
            if (record.operationId == original.operationId) context(record,
                if (expired) LifecycleOperationStatus.FAILED_FINAL else LifecycleOperationStatus.RUNNING,
                now = 100_001, code = if (expired) "RECOVERY_OPERATION_EXPIRED" else null)
            else context(record)
        }
        val fresh = authority.admit(scope)
        assertThat(fresh.operationId).isNotEqualTo(original.operationId)
        assertThat(fresh.idempotencyKey).isNotEqualTo(original.idempotencyKey)
        coVerify(exactly = 1) { management.expireRecoveryOperation(original) }
        coVerify(exactly = 1) { coordinator.start(fresh.request()) }
    }
    @Test fun `expiry transport failure cannot drop original or create new intent`() = runTest {
        val journal = MemoryJournal().apply { entries[scope] = original }
        val (authority, coordinator, management) = services(journal)
        coEvery { management.recoveryOperationContext(any()) } returns context(now = 100_001)
        coEvery { management.expireRecoveryOperation(any()) } throws java.io.IOException("transport unavailable")
        assertThat(runCatching { authority.admit(scope) }.isFailure).isTrue()
        assertThat(journal.read(scope)).isEqualTo(original)
        coVerify(exactly = 0) { coordinator.start(any()) }
    }
    @Test fun `wrong key terminal receipt permits only an explicit new attempt`() = runTest {
        val journal = MemoryJournal().apply { entries[scope] = original }
        val (authority, coordinator, management) = services(journal)
        coEvery { management.recoveryOperationContext(any()) } answers {
            val record = firstArg<RecoveryIntentRecord>()
            if (record.operationId == original.operationId) context(record, LifecycleOperationStatus.FAILED_FINAL, code = "RECOVERY_SECRET_STORAGE_REJECTED") else context(record)
        }
        assertThat(runCatching { authority.restore(scope) }.isFailure).isTrue()
        coVerify(exactly = 0) { coordinator.start(any()) }
        assertThat(authority.admit(scope).operationId).isNotEqualTo(original.operationId)
    }
    @Test fun `signed generation invalidation clears only matching local owner`() = runTest {
        val other = scope.copy(accountIdHash = "d".repeat(64))
        val journal = MemoryJournal().apply { entries[scope] = original; entries[other] = original.copy(scope = other) }
        val (authority, _, management) = services(journal)
        coEvery { management.recoveryOperationContext(original) } returns context().copy(scopeValid = false, rootValid = false,
            currentRootValid = true, currentIdentityGeneration = "e".repeat(64))
        assertThat(authority.restore(scope)).isNull()
        assertThat(journal.read(scope)).isNull()
        assertThat(journal.read(other)).isNotNull()
    }
    @Test fun `mismatched signed identity never clears owner on generation change`() = runTest {
        val journal = MemoryJournal().apply { entries[scope] = original }
        val (authority, _, management) = services(journal)
        coEvery { management.recoveryOperationContext(original) } returns context().copy(operationId = "other-operation-0001",
            scopeValid = false, currentIdentityGeneration = "d".repeat(64))
        assertThat(runCatching { authority.restore(scope) }.isFailure).isTrue()
        assertThat(journal.read(scope)).isEqualTo(original)
    }
    @Test fun `completion requires same operation signed success receipt`() = runTest {
        val journal = MemoryJournal().apply { entries[scope] = original }
        val (authority, _, management) = services(journal)
        assertThat(runCatching { authority.completed(original) }.isFailure).isTrue()
        assertThat(journal.read(scope)).isEqualTo(original)
        coEvery { management.recoveryOperationContext(original) } returns context(status = LifecycleOperationStatus.SUCCEEDED)
        authority.completed(original)
        assertThat(journal.read(scope)).isNull()
    }

    @Test fun `admission response loss resolves existing operation without replaying start`() = runTest {
        val draft = original.copy(identityGeneration = null, mobileGeneration = null)
        val journal = MemoryJournal().apply { entries[scope] = draft }
        val (authority, coordinator, management) = services(journal)
        coEvery { management.resolveRecoveryIntent(draft) } returns resolution(draft, admitted = true)
        assertThat(authority.restore(scope)?.operationId).isEqualTo(draft.operationId)
        coVerify(exactly = 1) { coordinator.operation(draft.operationId) }
        coVerify(exactly = 0) { coordinator.start(any()) }
    }
    @Test fun `signed expired nonadmission retires only local draft on cold restore`() = runTest {
        val draft = original.copy(identityGeneration = null, mobileGeneration = null)
        val other = scope.copy(accountIdHash = "d".repeat(64))
        val journal = MemoryJournal().apply { entries[scope] = draft; entries[other] = original.copy(scope = other) }
        val (authority, coordinator, management) = services(journal)
        coEvery { management.resolveRecoveryIntent(draft) } returns resolution(draft, now = 100_001)
        assertThat(authority.restore(scope)).isNull()
        assertThat(journal.read(scope)).isNull()
        assertThat(journal.read(other)).isNotNull()
        coVerify(exactly = 0) { coordinator.start(any()) }
        coVerify(exactly = 0) { management.expireRecoveryOperation(any()) }
        coVerify(exactly = 0) { management.recoveryOperationContext(any()) }
    }
    @Test fun `explicit retry after signed expired nonadmission creates real new identity`() = runTest {
        val draft = original.copy(identityGeneration = null, mobileGeneration = null)
        val journal = MemoryJournal().apply { entries[scope] = draft }
        val (authority, coordinator, management) = services(journal)
        coEvery { management.resolveRecoveryIntent(draft) } returns resolution(draft, now = 100_001)
        val fresh = authority.admit(scope)
        assertThat(fresh.operationId).isNotEqualTo(draft.operationId)
        assertThat(fresh.idempotencyKey).isNotEqualTo(draft.idempotencyKey)
        coVerify(exactly = 0) { coordinator.start(draft.request()) }
        coVerify(exactly = 1) { coordinator.start(fresh.request()) }
        coVerify(exactly = 0) { management.expireRecoveryOperation(any()) }
    }
    @Test fun `unknown nonadmission remains owned on transport or signature scope failure`() = runTest {
        val draft = original.copy(identityGeneration = null, mobileGeneration = null)
        val journal = MemoryJournal().apply { entries[scope] = draft }
        val (authority, coordinator, management) = services(journal)
        coEvery { management.resolveRecoveryIntent(draft) } throws java.io.IOException("unavailable")
        assertThat(runCatching { authority.restore(scope) }.isFailure).isTrue()
        assertThat(journal.read(scope)).isEqualTo(draft)
        val invalid = listOf(resolution(draft, now = 100_001).copy(operationId = "another-operation-001"),
            resolution(draft, now = 100_001).copy(scope = scope.copy(matrixDeviceIdHash = "d".repeat(64))),
            resolution(draft, now = 100_001).copy(idempotencyKeySha256 = "f".repeat(64)),
            resolution(draft, now = 100_001).copy(deadlineEpochMillis = 99_999))
        for (proof in invalid) {
            coEvery { management.resolveRecoveryIntent(draft) } returns proof
            assertThat(runCatching { authority.restore(scope) }.isFailure).isTrue()
            assertThat(journal.read(scope)).isEqualTo(draft)
        }
        coVerify(exactly = 0) { coordinator.start(any()) }
    }
}
