/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.AccountActivationReceipt
import io.element.android.security.dataprotection.api.AccountDeletionPreparationRequest
import io.element.android.security.dataprotection.api.AccountDeletionReceipt
import io.element.android.security.dataprotection.api.AccountDeletionRecoveryRequest
import io.element.android.security.dataprotection.api.AccountReactivationAttempt
import io.element.android.security.dataprotection.api.AccountReactivationCheckpoint
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.dataprotection.api.AppUpdateDownloadProgress
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DeletionOperationId
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.DeletionRecoveryReport
import io.element.android.security.dataprotection.api.DeletionReport
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.DeletionScopedClientCapability
import io.element.android.security.dataprotection.api.DeletionStep
import io.element.android.security.dataprotection.api.DeviceLocationEvidence
import io.element.android.security.dataprotection.api.DeviceLocationEvidenceState
import io.element.android.security.dataprotection.api.HistoryRecoveryAttempt
import io.element.android.security.dataprotection.api.ManagedAppRelease
import io.element.android.security.dataprotection.api.ManagementCommandExecutionReport
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.ManagementHeartbeatReport
import io.element.android.security.dataprotection.api.ManagementHeartbeatStatus
import io.element.android.security.dataprotection.api.ManagementSyncReport
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.PermanentAccountDeletionAwaitingGlobalDestroyException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionAwaitingMatrixException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionClientReceipt
import io.element.android.security.dataprotection.api.PermanentAccountDeletionClientReceiptResult
import io.element.android.security.dataprotection.api.PermanentAccountDeletionHandle
import io.element.android.security.dataprotection.api.PermanentAccountDeletionPreparationExpiredException
import io.element.android.security.dataprotection.api.PermanentAccountDeletionState
import io.element.android.security.dataprotection.api.PermanentAccountDeletionStatus
import io.element.android.security.dataprotection.api.PreparedAccountDeletion
import io.element.android.security.dataprotection.api.PreparedAccountReactivation
import io.element.android.security.dataprotection.api.PreparedAppUpdate
import io.element.android.security.dataprotection.api.RecoverableAccountDeletion
import io.element.android.security.dataprotection.api.SecurityErasureMilestone
import io.element.android.security.dataprotection.api.VerifiedManagementCommand
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultPermanentAccountDeletionCoordinatorTest {
    @Test
    fun `upgrade rejection preserves identity but never auto submits after restart`() = runTest {
        val store = InMemoryPermanentDeletionStore()
        val management = FakeDeletionManagementService().apply {
            onPrepare = { throw io.element.android.security.dataprotection.api.PermanentAccountDeletionSubmissionBlockedException() }
        }
        val first = coordinator(store, management)
        assertThat(first.prepare(request(), eraseData = true).exceptionOrNull())
            .isInstanceOf(io.element.android.security.dataprotection.api.PermanentAccountDeletionSubmissionBlockedException::class.java)
        assertThat(store.record?.phase).isEqualTo(PermanentDeletionPhase.SUBMISSION_BLOCKED)
        val saved = requireNotNull(store.record)
        val restarted = coordinator(store, management)
        assertThat(restarted.pending(saved.accountIdHash)?.value).isEqualTo(OPERATION_ID)
        assertThat(restarted.pendingRecovery(saved.accountIdHash)).isNull()
        management.onPrepare = { prepared(it) }
        restarted.resumePending(coldStart = true)
        assertThat(management.prepareRequests).hasSize(1)
        // Only a new explicit user submission retries the exact existing operation and secret.
        assertThat(restarted.prepare(request(), eraseData = true).getOrThrow()).isEqualTo(saved.handle)
        assertThat(management.prepareRequests[1]).isEqualTo(management.prepareRequests[0])
        assertThat(restarted.pendingRecovery(saved.accountIdHash)).isEqualTo(saved.handle)
    }

    @Test
    fun `draft is durable before prepare request and lost response retries exact credentials`() = runTest {
        val store = InMemoryPermanentDeletionStore()
        val management = FakeDeletionManagementService()
        var first = true
        management.onPrepare = { request ->
            assertThat(store.record?.phase).isEqualTo(PermanentDeletionPhase.DRAFT)
            if (first) {
                first = false
                error("response lost")
            }
            prepared(request)
        }
        val coordinator = coordinator(store, management)

        assertThat(coordinator.prepare(request(), eraseData = true).isFailure).isTrue()
        val recovered = coordinator.prepare(request(), eraseData = true).getOrThrow()

        assertThat(recovered.value).isEqualTo(OPERATION_ID)
        assertThat(management.prepareRequests).hasSize(2)
        assertThat(management.prepareRequests[1]).isEqualTo(management.prepareRequests[0])
        assertThat(store.record?.phase).isEqualTo(PermanentDeletionPhase.PREPARED)
    }

    @Test
    fun `prepared frozen intent cannot be abandoned`() = runTest {
        val store = InMemoryPermanentDeletionStore()
        val coordinator = coordinator(store, FakeDeletionManagementService())
        val handle = coordinator.prepare(request(), eraseData = true).getOrThrow()

        val result = runCatching { coordinator.abandon(handle) }

        assertThat(result.isFailure).isTrue()
        assertThat(store.record?.handle).isEqualTo(handle)
        assertThat(store.record?.phase).isEqualTo(PermanentDeletionPhase.PREPARED)
    }

    @Test
    fun `lost local secret discovers and recovers the same frozen operation`() = runTest {
        val store = InMemoryPermanentDeletionStore()
        val management = FakeDeletionManagementService().apply {
            recoverable = RecoverableAccountDeletion(
                operationId = OPERATION_ID,
                deletionIntentId = "deletion-intent-0000000000000001",
                accountIdHash = ACCOUNT_HASH,
                installationId = INSTALLATION_ID,
                eraseData = true,
                freezeAtEpochMillis = 1,
            )
        }
        val coordinator = coordinator(store, management)

        assertThat(coordinator.pendingRecovery(ACCOUNT_HASH)?.value).isEqualTo(OPERATION_ID)
        val recovered = coordinator.prepare(request(), eraseData = true).getOrThrow()

        assertThat(recovered.value).isEqualTo(OPERATION_ID)
        assertThat(management.prepareRequests).isEmpty()
        assertThat(management.recoveryRequests).hasSize(1)
        assertThat(management.recoveryOperationIds).containsExactly(OPERATION_ID)
        assertThat(store.record?.phase).isEqualTo(PermanentDeletionPhase.PREPARED)
        assertThat(store.record?.deletionIntentId).isEqualTo("deletion-intent-0000000000000001")
    }

    @Test
    fun `expired draft rotates credentials before Matrix deactivation can start`() = runTest {
        val store = InMemoryPermanentDeletionStore()
        val management = FakeDeletionManagementService()
        management.onPrepare = { request ->
            if (management.prepareRequests.size == 1) throw PermanentAccountDeletionPreparationExpiredException()
            prepared(request)
        }

        coordinator(store, management).prepare(request(), eraseData = false).getOrThrow()

        assertThat(management.prepareRequests).hasSize(2)
        assertThat(management.prepareRequests[1].idempotencyKey)
            .isNotEqualTo(management.prepareRequests[0].idempotencyKey)
        assertThat(management.prepareRequests[1].confirmationTokenSha256)
            .isNotEqualTo(management.prepareRequests[0].confirmationTokenSha256)
        assertThat(store.record?.eraseData).isFalse()
    }

    @Test
    fun `local cleanup cannot run before signed remote commit`() = runTest {
        val store = InMemoryPermanentDeletionStore()
        val deletion = FakeLocalDeletionCoordinator()
        val management = FakeDeletionManagementService()
        val coordinator = coordinator(store, management, deletion)
        val handle = coordinator.prepare(request(), eraseData = true).getOrThrow()

        val premature = runCatching { coordinator.completeLocal(handle, PRECONDITIONS) }
        assertThat(premature.isFailure).isTrue()
        assertThat(deletion.deleteCalls).isEqualTo(0)

        val receipt = coordinator.commitRemote(handle).getOrThrow()
        val report = coordinator.completeLocal(handle, PRECONDITIONS)

        assertThat(receipt.eraseData).isTrue()
        assertThat(report.isComplete).isTrue()
        assertThat(deletion.deleteCalls).isEqualTo(1)
        assertThat(store.record).isNull()
    }

    @Test
    fun `cold start waits for Matrix terminal state without deleting local account`() = runTest {
        val store = InMemoryPermanentDeletionStore()
        val deletion = FakeLocalDeletionCoordinator()
        val management = FakeDeletionManagementService().apply {
            onCommit = { _, _ -> throw PermanentAccountDeletionAwaitingMatrixException() }
        }
        val coordinator = coordinator(store, management, deletion)
        coordinator.prepare(request(), eraseData = true).getOrThrow()

        val report = coordinator.resumePending(coldStart = true)

        assertThat(report.waitingForMatrixDeactivationCount).isEqualTo(1)
        assertThat(report.completedCount).isEqualTo(0)
        assertThat(deletion.deleteCalls).isEqualTo(0)
        assertThat(store.record?.phase).isEqualTo(PermanentDeletionPhase.PREPARED)
        assertThat(management.prepareOperationIds).containsExactly(OPERATION_ID)
        assertThat(management.commitOperationIds).containsExactly(OPERATION_ID)
    }

    @Test
    fun `remote commit survives failed local cleanup and cold start finishes it`() = runTest {
        val store = InMemoryPermanentDeletionStore()
        val deletion = FakeLocalDeletionCoordinator(complete = false)
        val coordinator = coordinator(store, FakeDeletionManagementService(), deletion)
        val handle = coordinator.prepare(request(), eraseData = false).getOrThrow()
        coordinator.commitRemote(handle).getOrThrow()

        val first = coordinator.completeLocal(handle, PRECONDITIONS)
        deletion.complete = true
        val recovered = coordinator.resumePending(coldStart = true)

        assertThat(first.isComplete).isFalse()
        assertThat(recovered.completedCount).isEqualTo(1)
        assertThat(deletion.deleteCalls).isEqualTo(2)
        assertThat(deletion.lastRequest?.confirmedPreconditions).containsAtLeastElementsIn(PRECONDITIONS)
        assertThat(store.record).isNull()
    }

    @Test
    fun `signed remote deletion erases activation checkpoint before local account data`() = runTest {
        val store = InMemoryPermanentDeletionStore()
        val deletion = FakeLocalDeletionCoordinator()
        val activation = FakeAccountReactivationScopeStore()
        val coordinator = coordinator(store, FakeDeletionManagementService(), deletion, activation)
        val handle = coordinator.prepare(request(), eraseData = true).getOrThrow()
        coordinator.commitRemote(handle).getOrThrow()

        val report = coordinator.completeLocal(handle, PRECONDITIONS)

        assertThat(report.isComplete).isTrue()
        assertThat(activation.clearedScopes).containsExactly(ACCOUNT_HASH to INSTALLATION_ID)
        assertThat(deletion.deleteCalls).isEqualTo(1)
    }

    @Test
    fun `activation checkpoint erasure failure keeps remote committed intent for retry`() = runTest {
        val store = InMemoryPermanentDeletionStore()
        val deletion = FakeLocalDeletionCoordinator()
        val activation = FakeAccountReactivationScopeStore(clearSucceeds = false)
        val coordinator = coordinator(store, FakeDeletionManagementService(), deletion, activation)
        val handle = coordinator.prepare(request(), eraseData = true).getOrThrow()
        coordinator.commitRemote(handle).getOrThrow()

        val failure = runCatching { coordinator.completeLocal(handle, PRECONDITIONS) }

        assertThat(failure.isFailure).isTrue()
        assertThat(deletion.deleteCalls).isEqualTo(0)
        assertThat(store.record?.phase).isEqualTo(PermanentDeletionPhase.REMOTE_COMMITTED)
    }

    @Test
    fun `local clear waits for signed security erasure milestone`() = runTest {
        val store = InMemoryPermanentDeletionStore()
        val deletion = FakeLocalDeletionCoordinator()
        val management = FakeDeletionManagementService().apply { securityErasureComplete = false }
        val coordinator = coordinator(store, management, deletion)
        val handle = coordinator.prepare(request(), eraseData = true).getOrThrow()
        coordinator.commitRemote(handle).getOrThrow()

        val failure = runCatching { coordinator.completeLocal(handle, PRECONDITIONS) }

        assertThat(failure.exceptionOrNull()).isInstanceOf(PermanentAccountDeletionAwaitingGlobalDestroyException::class.java)
        assertThat(deletion.deleteCalls).isEqualTo(0)
        assertThat(store.record?.phase).isEqualTo(PermanentDeletionPhase.REMOTE_COMMITTED)
    }

    @Test
    fun `lost client receipt response keeps only retry journal and does not clear twice`() = runTest {
        val store = InMemoryPermanentDeletionStore()
        val deletion = FakeLocalDeletionCoordinator()
        val management = FakeDeletionManagementService().apply { clientReceiptAccepted = false }
        val coordinator = coordinator(store, management, deletion)
        val handle = coordinator.prepare(request(), eraseData = true).getOrThrow()
        coordinator.commitRemote(handle).getOrThrow()

        val pending = coordinator.completeLocal(handle, PRECONDITIONS)
        assertThat(pending.retryRequired).isTrue()
        assertThat(store.record?.phase).isEqualTo(PermanentDeletionPhase.LOCAL_CLEARED)
        assertThat(store.record?.confirmationToken).isEqualTo("lc22-local-cleared-confirmation-token")
        assertThat(store.record?.localManifestDigestSha256).hasLength(64)

        management.clientReceiptAccepted = true
        val recovered = coordinator.resumePending(coldStart = true)

        assertThat(recovered.completedCount).isEqualTo(1)
        assertThat(deletion.deleteCalls).isEqualTo(1)
        assertThat(management.clientReceipts).hasSize(2)
        assertThat(management.clientReceipts[1]).isEqualTo(management.clientReceipts[0])
        assertThat(store.record).isNull()
    }

    private fun coordinator(
        store: InMemoryPermanentDeletionStore,
        management: FakeDeletionManagementService,
        deletion: FakeLocalDeletionCoordinator = FakeLocalDeletionCoordinator(),
        activation: FakeAccountReactivationScopeStore = FakeAccountReactivationScopeStore(),
    ) = DefaultPermanentAccountDeletionCoordinator(
        managementControlService = management,
        dataDeletionCoordinator = deletion,
        intentStore = store,
        accountReactivationScopeStore = activation,
    )

    private fun request() = DeletionRequest(
        operationId = DeletionOperationId(OPERATION_ID),
        scope = DeletionScope.Account(
            accountIdHash = ACCOUNT_HASH,
            vaultId = "vault",
            localSessionId = "@delete:example.org",
            sessionFilePath = "C:/sessions/delete",
            sessionCachePath = "C:/cache/delete",
        ),
        reason = DeletionReason.USER_REQUEST,
        policyVersion = 7,
        requestedAtEpochMillis = 1,
    )

    private fun prepared(request: AccountDeletionPreparationRequest) = PreparedAccountDeletion(
        deletionIntentId = "deletion-intent-0000000000000001",
        accountIdHash = request.accountIdHash,
        installationId = INSTALLATION_ID,
        eraseData = request.eraseData,
        preparedAtEpochMillis = System.currentTimeMillis(),
        expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
    )

    private companion object {
        const val ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val INSTALLATION_ID = "installation-delete-test-0001"
        const val OPERATION_ID = "operation-delete-test-0001"
        val PRECONDITIONS = setOf(
            DeletionStep.STOPPED_SYNC_AND_UI,
            DeletionStep.PURGED_MEMORY,
            DeletionStep.CLOSED_DATABASES,
        )
    }
}

private class FakeAccountReactivationScopeStore(
    private val clearSucceeds: Boolean = true,
) : AccountReactivationScopeStore {
    val clearedScopes = mutableListOf<Pair<String, String>>()

    override suspend fun current(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): AccountReactivationCheckpoint? = error("Not used")

    override suspend fun requiresCoreReadyEnforcement(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean = error("Not used")

    override suspend fun markAccountRemoved(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean = error("Not used")

    override suspend fun restoreAccountActive(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean = error("Not used")

    override suspend fun markExplicitIdentityReset(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean = error("Not used")

    override suspend fun nextAttempt(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): AccountReactivationAttempt = error("Not used")

    override suspend fun markPrepared(
        attempt: AccountReactivationAttempt,
        prepared: PreparedAccountReactivation,
    ): AccountReactivationCheckpoint = error("Not used")

    override suspend fun markCommitted(
        attempt: AccountReactivationAttempt,
        receipt: AccountActivationReceipt,
    ): AccountReactivationCheckpoint = error("Not used")

    override suspend fun nextHistoryRecoveryAttempt(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
        roomScopeIds: Set<String>,
    ): HistoryRecoveryAttempt = error("Not used")

    override suspend fun clearPermanentlyDeletedAccount(
        accountIdHash: String,
        installationId: String,
    ): Boolean {
        clearedScopes += accountIdHash to installationId
        return clearSucceeds
    }
}

private class InMemoryPermanentDeletionStore : PermanentAccountDeletionIntentStore {
    var record: PendingPermanentAccountDeletion? = null

    override suspend fun save(record: PendingPermanentAccountDeletion) {
        this.record = record
    }

    override suspend fun load(handle: PermanentAccountDeletionHandle): PendingPermanentAccountDeletion? =
        record?.takeIf { it.handle == handle }

    override suspend fun loadAll(): PendingPermanentDeletionRecords = PendingPermanentDeletionRecords(
        records = listOfNotNull(record),
        unreadableRecordCount = 0,
    )

    override suspend fun delete(handle: PermanentAccountDeletionHandle): Boolean {
        if (record?.handle == handle) record = null
        return record == null
    }
}

private class FakeLocalDeletionCoordinator(
    var complete: Boolean = true,
) : DataDeletionCoordinator {
    var deleteCalls = 0
    var lastRequest: DeletionRequest? = null

    override suspend fun prepare(request: DeletionRequest): DeletionReport = report(request)

    override suspend fun delete(request: DeletionRequest): DeletionReport {
        deleteCalls += 1
        lastRequest = request
        return report(request)
    }

    override suspend fun resumePending(coldStart: Boolean): DeletionRecoveryReport =
        DeletionRecoveryReport(emptyList(), 0)

    private fun report(request: DeletionRequest) = DeletionReport(
        operationId = request.operationId,
        completedSteps = if (complete) request.confirmedPreconditions + DeletionStep.COMPLETE else request.confirmedPreconditions,
        retryRequired = !complete,
        errorCode = if (complete) null else "RETRY_LOCAL_CLEANUP",
    )
}

private class FakeDeletionManagementService : ManagementControlService {
    val prepareRequests = mutableListOf<AccountDeletionPreparationRequest>()
    val recoveryRequests = mutableListOf<AccountDeletionRecoveryRequest>()
    val prepareOperationIds = mutableListOf<String?>()
    val recoveryOperationIds = mutableListOf<String?>()
    val commitOperationIds = mutableListOf<String?>()
    val clientReceipts = mutableListOf<PermanentAccountDeletionClientReceipt>()
    var securityErasureComplete = true
    var clientReceiptAccepted = true
    var recoverable: RecoverableAccountDeletion? = null
    var onPrepare: (AccountDeletionPreparationRequest) -> PreparedAccountDeletion = { request ->
        PreparedAccountDeletion(
            deletionIntentId = "deletion-intent-0000000000000001",
            accountIdHash = request.accountIdHash,
            installationId = "installation-delete-test-0001",
            eraseData = request.eraseData,
            preparedAtEpochMillis = System.currentTimeMillis(),
            expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
        )
    }
    var onCommit: (PreparedAccountDeletion, String) -> AccountDeletionReceipt = { prepared, _ ->
        AccountDeletionReceipt(
            deletionIntentId = prepared.deletionIntentId,
            accountIdHash = prepared.accountIdHash,
            installationId = prepared.installationId,
            globalDestroyJobId = "global-destroy-job-000000000001",
            eraseData = prepared.eraseData,
            committedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    override suspend fun preparePermanentAccountDeletion(request: AccountDeletionPreparationRequest): PreparedAccountDeletion {
        prepareOperationIds += currentLifecycleOperationContext()?.operationId
        prepareRequests += request
        return onPrepare(request)
    }

    override suspend fun pendingPermanentAccountDeletion(accountIdHash: String): RecoverableAccountDeletion? =
        recoverable?.takeIf { it.accountIdHash == accountIdHash }

    override suspend fun recoverPermanentAccountDeletion(
        request: AccountDeletionRecoveryRequest,
    ): PreparedAccountDeletion {
        recoveryOperationIds += currentLifecycleOperationContext()?.operationId
        recoveryRequests += request
        return PreparedAccountDeletion(
            deletionIntentId = requireNotNull(recoverable).deletionIntentId,
            accountIdHash = request.accountIdHash,
            installationId = requireNotNull(recoverable).installationId,
            eraseData = request.eraseData,
            preparedAtEpochMillis = System.currentTimeMillis(),
            expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
        )
    }

    override suspend fun commitPermanentAccountDeletion(
        prepared: PreparedAccountDeletion,
        confirmationToken: String,
    ): AccountDeletionReceipt {
        commitOperationIds += currentLifecycleOperationContext()?.operationId
        return onCommit(prepared, confirmationToken)
    }

    override suspend fun permanentAccountDeletionStatus(operationId: String): PermanentAccountDeletionStatus =
        PermanentAccountDeletionStatus(
            operationId = operationId,
            accountState = "FROZEN",
            deletionState = if (securityErasureComplete) {
                PermanentAccountDeletionState.CLIENT_CLEAR_PENDING
            } else {
                PermanentAccountDeletionState.FROZEN
            },
            generation = 1,
            policyVersion = "lc22-v2",
            securityErasureMilestone = if (securityErasureComplete) {
                SecurityErasureMilestone(
                    operationId = operationId,
                    accountIdHash = "a".repeat(64),
                    generation = 1,
                    policyVersion = "lc22-v2",
                    completedAtEpochMillis = System.currentTimeMillis(),
                    postconditionHash = "b".repeat(64),
                )
            } else {
                null
            },
            clientReceiptCapability = if (securityErasureComplete) {
                DeletionScopedClientCapability(
                    token = "deletion-scoped-token-for-client-clear-000001",
                    resourceScopeHash = "c".repeat(64),
                    expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
                )
            } else {
                null
            },
            retryable = true,
            finalReceiptPresent = false,
        )

    override suspend fun recordPermanentAccountDeletionClientReceipt(
        receipt: PermanentAccountDeletionClientReceipt,
    ): PermanentAccountDeletionClientReceiptResult {
        clientReceipts += receipt
        return PermanentAccountDeletionClientReceiptResult(
            accepted = clientReceiptAccepted,
            idempotentReplay = clientReceipts.size > 1,
            status = permanentAccountDeletionStatus(receipt.operationId),
        )
    }

    override suspend fun synchronize() = ManagementSyncReport(ManagementSyncStatus.SYNCHRONIZED)
    override suspend fun heartbeat() = ManagementHeartbeatReport(ManagementHeartbeatStatus.ACTIVE)
    override suspend fun submitDeviceLocation(evidence: DeviceLocationEvidence) = DeviceLocationEvidenceState.ACCEPTED
    override suspend fun isOfflineDeadlineExceeded() = false
    override suspend fun pendingCommands(): List<VerifiedManagementCommand> = emptyList()
    override suspend fun reportCommand(report: ManagementCommandExecutionReport) = true
    override suspend fun latestAppRelease(): ManagedAppRelease? = null
    override suspend fun downloadAndVerifyAppRelease(
        release: ManagedAppRelease,
        onProgress: suspend (AppUpdateDownloadProgress) -> Unit,
    ): PreparedAppUpdate = error("not used")
}
