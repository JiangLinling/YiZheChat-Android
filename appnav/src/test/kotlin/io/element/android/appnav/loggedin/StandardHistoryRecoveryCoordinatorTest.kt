/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appnav.loggedin

import com.google.common.truth.Truth.assertThat
import io.element.android.features.networkmonitor.test.FakeNetworkMonitor
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.encryption.HiddenMessageRecoveryInventory
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import io.element.android.libraries.matrix.api.encryption.RecoveryState
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.libraries.matrix.test.encryption.FakeEncryptionService
import io.element.android.libraries.matrix.test.verification.FakeSessionVerificationService
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.DeviceRecoveryState
import io.element.android.security.contentprotection.api.DeviceRecoveryStatus
import io.element.android.security.contentprotection.api.FetchRevocationsRequest
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.KmsShareReference
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefresh
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.contentprotection.api.OfflineLease
import io.element.android.security.contentprotection.api.ReleaseKmsShareRequest
import io.element.android.security.contentprotection.api.ReleasedKmsShare
import io.element.android.security.contentprotection.api.RemoteRevocationSnapshot
import io.element.android.security.contentprotection.api.StartDeviceRecoveryRequest
import io.element.android.security.contentprotection.api.StoreKmsShareRequest
import io.element.android.security.dataprotection.api.AccountActivationReceipt
import io.element.android.security.dataprotection.api.AccountHistoryAccessBoundary
import io.element.android.security.dataprotection.api.AccountHistoryAccessBoundaryProvider
import io.element.android.security.dataprotection.api.AccountReactivationAttempt
import io.element.android.security.dataprotection.api.AccountReactivationCheckpoint
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.HistoryRecoveryAttempt
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.PreparedAccountReactivation
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.ManagedDeviceLicense
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.test.FakeDeviceTrustService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StandardHistoryRecoveryCoordinatorTest {
    @Test
    fun `additional room still requests its own original recovery path`() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()
        val additionalRoom = RoomId("!additional:example.org")
        fixture.inventory.candidateCountByRoom.value = mapOf(ROOM_ID to 3, additionalRoom to 1)
        runCurrent()

        assertThat(fixture.kms.requests).hasSize(2)
        assertThat(fixture.operations.startedRequests).hasSize(2)
        assertThat(fixture.runtime.historyScopes.last()).isEqualTo(
            HistoryScope(accountIdHash(ACCOUNT_ID), setOf(ROOM_ID.value, additionalRoom.value), 2L)
        )
    }

    @Test
    fun `revocation while candidates are empty retires the completed scope`() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()
        fixture.inventory.candidateCountByRoom.value = emptyMap()
        runCurrent()
        fixture.trust.set(trustedSnapshot().copy(trustState = DeviceTrustState.REVOKED))
        runCurrent()
        assertThat(fixture.inventory.recoveryStatus.value).isEqualTo(HistoryRecoveryStatus.WAITING_FOR_DEVICE_TRUST)
        fixture.inventory.candidateCountByRoom.value = mapOf(ROOM_ID to 3)
        runCurrent()
        assertThat(fixture.kms.requests).hasSize(1)
        fixture.trust.set(trustedSnapshot())
        runCurrent()
        assertThat(fixture.kms.requests).hasSize(2)
    }

    @Test
    fun `new installation cannot reuse completed recovery scope`() = runTest {
        val installation = "installation-replacement"
        val fixture = fixture(backgroundScope, outcomes = ArrayDeque(listOf(
            Result.success(authorizedStatus()),
            Result.success(authorizedStatus().copy(newInstallationId = installation)),
        )))
        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()
        fixture.trust.set(trustedSnapshot().copy(installationId = installation))
        runCurrent()
        assertThat(fixture.kms.requests).hasSize(2)
        assertThat(fixture.kms.requests.last().newInstallationId).isEqualTo(installation)
        assertThat(fixture.runtime.historyScopes.last().recoveryGeneration).isEqualTo(2L)
    }

    @Test
    fun `changed device key cannot reuse completed recovery scope`() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()
        fixture.trust.set(trustedSnapshot().copy(attestationPublicKeySha256 = "a".repeat(64)))
        runCurrent()
        assertThat(fixture.kms.requests).hasSize(2)
    }

    @Test
    fun `heartbeat timestamp alone does not restart completed recovery`() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()
        fixture.trust.set(trustedSnapshot().copy(lastVerifiedAtEpochMillis = 2L, lastEvidenceAtEpochMillis = 2L))
        runCurrent()
        assertThat(fixture.kms.requests).hasSize(1)
    }

    @Test
    fun `expired managed license cannot reuse completed recovery`() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()
        fixture.trust.set(trustedSnapshot().copy(
            trustState = DeviceTrustState.MANAGED,
            managedLicense = ManagedDeviceLicense(
                licenseId = "expired-license",
                installationId = INSTALLATION_ID,
                devicePublicKeySha256 = "a".repeat(64),
                contentShareKeyId = "share",
                appPackageName = "com.yizhe.chat.internal",
                appVersionCode = 1L,
                revision = 1L,
                allowedScopes = setOf("content"),
                offlineAllowed = false,
                issuedAtEpochMillis = 1L,
                expiresAtEpochMillis = 2L,
            ),
        ))
        runCurrent()
        assertThat(fixture.kms.requests).hasSize(1)
        assertThat(fixture.inventory.recoveryStatus.value).isEqualTo(HistoryRecoveryStatus.WAITING_FOR_DEVICE_TRUST)
        fixture.trust.set(trustedSnapshot())
        runCurrent()
        assertThat(fixture.kms.requests).hasSize(2)
    }

    @Test
    fun `failed applied receipt never records completed recovery scope`() = runTest {
        val fixture = fixture(backgroundScope, failAppliedReceipt = true)
        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()
        assertThat(fixture.operations.clientReceipts).isEmpty()
        fixture.inventory.candidateCountByRoom.value = emptyMap()
        runCurrent()
        fixture.inventory.candidateCountByRoom.value = mapOf(ROOM_ID to 3)
        runCurrent()
        assertThat(fixture.kms.requests).hasSize(2)
        assertThat(fixture.coordinator.state.value).isEqualTo(StandardHistoryRecoveryState.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `temporary empty candidate projection does not restart completed recovery for the same room`() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()
        assertThat(fixture.operations.clientReceipts).isNotEmpty()

        fixture.inventory.candidateCountByRoom.value = emptyMap()
        runCurrent()
        fixture.inventory.candidateCountByRoom.value = mapOf(ROOM_ID to 3)
        runCurrent()

        assertThat(fixture.kms.requests).hasSize(1)
        assertThat(fixture.operations.startedRequests).hasSize(1)
        assertThat(fixture.runtime.historyScopes).containsExactly(
            HistoryScope(accountIdHash(ACCOUNT_ID), setOf(ROOM_ID.value), 1L)
        )
    }

    @Test
    fun `verified recovered session requests standard authorization and additive refresh`() = runTest {
        val fixture = fixture(backgroundScope)

        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()

        assertThat(fixture.kms.requests).hasSize(1)
        assertThat(fixture.kms.contexts.single()?.lifecycleId).isEqualTo(FullAppLifecycleId.LC_08)
        assertThat(fixture.kms.requests.single().newInstallationId).isEqualTo(INSTALLATION_ID)
        assertThat(fixture.runtime.refreshModes).containsExactly(MatrixAuthorizationRefreshMode.HISTORY_RECOVERY_DELTA)
        assertThat(fixture.runtime.historyScopes).containsExactly(
            HistoryScope(accountIdHash(ACCOUNT_ID), setOf(ROOM_ID.value), 1L)
        )
        assertThat(fixture.coordinator.state.value).isEqualTo(StandardHistoryRecoveryState.STANDARD_AUTHORIZED)
        assertThat(fixture.inventory.recoveryStatus.value).isEqualTo(HistoryRecoveryStatus.STANDARD_AUTHORIZED)
    }

    @Test
    fun `untrusted device cannot start recovery even when history keys are restored`() = runTest {
        val fixture = fixture(
            sessionCoroutineScope = backgroundScope,
            trustSnapshot = trustedSnapshot().copy(
                trustState = DeviceTrustState.PENDING,
                lastVerifiedAtEpochMillis = null,
            )
        )

        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()

        assertThat(fixture.kms.requests).isEmpty()
        assertThat(fixture.runtime.refreshModes).isEmpty()
        assertThat(fixture.coordinator.state.value).isEqualTo(StandardHistoryRecoveryState.WAITING_FOR_PREREQUISITES)
        assertThat(fixture.inventory.recoveryStatus.value).isEqualTo(HistoryRecoveryStatus.WAITING_FOR_DEVICE_TRUST)
    }

    @Test
    fun `transient KMS failure retries after delay and never broadens the refresh`() = runTest {
        val fixture = fixture(
            sessionCoroutineScope = backgroundScope,
            outcomes = ArrayDeque(
                listOf(
                    Result.failure(ContentProtectionException.KmsUnavailable()),
                    Result.success(authorizedStatus()),
                )
            )
        )

        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()
        assertThat(fixture.kms.requests).hasSize(1)
        assertThat(fixture.coordinator.state.value).isEqualTo(StandardHistoryRecoveryState.RETRY_WAIT)

        advanceTimeBy(5_000)
        runCurrent()

        assertThat(fixture.kms.requests).hasSize(2)
        assertThat(fixture.kms.contexts.map { it?.operationId }.distinct()).hasSize(1)
        assertThat(fixture.operations.startedRequests).hasSize(1)
        assertThat(fixture.runtime.refreshModes).containsExactly(MatrixAuthorizationRefreshMode.HISTORY_RECOVERY_DELTA)
        assertThat(fixture.coordinator.state.value).isEqualTo(StandardHistoryRecoveryState.STANDARD_AUTHORIZED)
    }

    @Test
    fun `pending external approval keeps protected history closed`() = runTest {
        val fixture = fixture(
            sessionCoroutineScope = backgroundScope,
            outcomes = ArrayDeque(listOf(Result.success(authorizedStatus(standardAuthorized = false)))),
        )

        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()

        assertThat(fixture.kms.requests).hasSize(1)
        assertThat(fixture.runtime.refreshModes).isEmpty()
        assertThat(fixture.coordinator.state.value).isEqualTo(StandardHistoryRecoveryState.AWAITING_EXTERNAL_APPROVAL)
        assertThat(fixture.inventory.recoveryStatus.value).isEqualTo(HistoryRecoveryStatus.WAITING_FOR_OLD_DEVICE)
    }

    @Test
    fun `pending recovery is polled read-only without starting another request`() = runTest {
        val fixture = fixture(
            sessionCoroutineScope = backgroundScope,
            outcomes = ArrayDeque(listOf(Result.success(authorizedStatus(standardAuthorized = false)))),
            statusOutcomes = ArrayDeque(listOf(Result.success(authorizedStatus()))),
        )

        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()

        assertThat(fixture.kms.requests).hasSize(1)
        assertThat(fixture.kms.statusRequests).containsExactly("recovery-0123456789")
        assertThat(fixture.kms.contexts.map { it?.operationId }.distinct()).hasSize(1)
        assertThat(fixture.operations.startedRequests).hasSize(1)
        assertThat(fixture.runtime.refreshModes).containsExactly(MatrixAuthorizationRefreshMode.HISTORY_RECOVERY_DELTA)
        assertThat(fixture.coordinator.state.value).isEqualTo(StandardHistoryRecoveryState.STANDARD_AUTHORIZED)
    }

    @Test
    fun `expired recovery request stops without authorizing protected history`() = runTest {
        val fixture = fixture(
            sessionCoroutineScope = backgroundScope,
            outcomes = ArrayDeque(listOf(Result.success(expiredStatus()))),
        )

        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()

        assertThat(fixture.kms.requests).hasSize(1)
        assertThat(fixture.kms.statusRequests).isEmpty()
        assertThat(fixture.runtime.refreshModes).isEmpty()
        assertThat(fixture.coordinator.state.value).isEqualTo(StandardHistoryRecoveryState.REQUEST_EXPIRED)
        assertThat(fixture.inventory.recoveryStatus.value).isEqualTo(HistoryRecoveryStatus.REQUEST_EXPIRED)
        assertThat(fixture.operations.clientReceipts).isEmpty()
    }

    @Test
    fun `completed bounded approval polling never receipts an unapplied recovery as success`() = runTest {
        val fixture = fixture(
            backgroundScope,
            outcomes = ArrayDeque(listOf(Result.success(authorizedStatus(standardAuthorized = false)))),
            statusOutcomes = ArrayDeque(List(5) { Result.success(authorizedStatus(standardAuthorized = false)) })
        )
        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()
        advanceTimeBy(525_001)
        runCurrent()
        assertThat(fixture.coordinator.state.value).isEqualTo(StandardHistoryRecoveryState.AWAITING_EXTERNAL_APPROVAL)
        assertThat(fixture.operations.clientReceipts).isEmpty()
        assertThat(fixture.operations.startedRequests).hasSize(1)
        assertThat(fixture.kms.contexts.map { it?.operationId }.distinct()).hasSize(1)
    }

    @Test
    fun `signed explicit identity reset permanently closes old history without KMS retry`() = runTest {
        val fixture = fixture(
            sessionCoroutineScope = backgroundScope,
            historyBoundary = AccountHistoryAccessBoundary(100L),
        )

        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()

        assertThat(fixture.kms.requests).isEmpty()
        assertThat(fixture.runtime.refreshModes).isEmpty()
        assertThat(fixture.coordinator.state.value).isEqualTo(StandardHistoryRecoveryState.IDENTITY_RESET_UNRECOVERABLE)
        assertThat(fixture.inventory.recoveryStatus.value).isEqualTo(HistoryRecoveryStatus.IDENTITY_RESET_UNRECOVERABLE)
    }

    @Test
    fun `failed lifecycle admission never calls KMS or refreshes history`() = runTest {
        val operations = FakeAppOperationalStateCoordinator(startFailure = { java.io.IOException("TEST_ADMISSION_UNAVAILABLE") })
        val fixture = fixture(backgroundScope, operations = operations)

        fixture.coordinator.start(ACCOUNT_ID, MATRIX_DEVICE_ID)
        runCurrent()

        assertThat(fixture.operations.startedRequests).hasSize(1)
        assertThat(fixture.kms.requests).isEmpty()
        assertThat(fixture.runtime.refreshModes).isEmpty()
        assertThat(fixture.coordinator.state.value).isEqualTo(StandardHistoryRecoveryState.SERVICE_UNAVAILABLE)
    }

    private fun fixture(
        sessionCoroutineScope: CoroutineScope,
        trustSnapshot: DeviceTrustSnapshot = trustedSnapshot(),
        outcomes: ArrayDeque<Result<DeviceRecoveryStatus>> = ArrayDeque(listOf(Result.success(authorizedStatus()))),
        statusOutcomes: ArrayDeque<Result<DeviceRecoveryStatus>> = ArrayDeque(),
        historyBoundary: AccountHistoryAccessBoundary? = null,
        operations: FakeAppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        failAppliedReceipt: Boolean = false,
    ): Fixture {
        val encryptionService = FakeEncryptionService().apply {
            recoveryStateStateFlow.value = RecoveryState.ENABLED
        }
        val verificationService = FakeSessionVerificationService(SessionVerifiedStatus.Verified)
        val inventory = TestHiddenMessageRecoveryInventory(initialCount = 3)
        val kms = TestKmsShareClient(outcomes, statusOutcomes)
        val runtime = TestMatrixContentProtectionRuntime()
        val trust = FakeDeviceTrustService(trustSnapshot)
        val operationCoordinator = if (failAppliedReceipt) {
            object : AppOperationalStateCoordinator by operations {
                override suspend fun recordClientReceipt(receipt: LifecycleClientReceipt): LifecycleOperation =
                    throw java.io.IOException("TEST_RECEIPT_UNAVAILABLE")
            }
        } else {
            operations
        }
        val coordinator = StandardHistoryRecoveryCoordinator(
            encryptionService = encryptionService,
            sessionVerificationService = verificationService,
            hiddenMessageRecoveryInventory = inventory,
            networkMonitor = FakeNetworkMonitor(),
            kmsShareClient = kms,
            deviceTrustService = trust,
            matrixContentProtectionRuntime = runtime,
            accountReactivationScopeStore = TestAccountReactivationScopeStore(),
            accountHistoryAccessBoundaryProvider = object : AccountHistoryAccessBoundaryProvider {
                override suspend fun current(): AccountHistoryAccessBoundary? = historyBoundary
            },
            appOperationalStateCoordinator = operationCoordinator,
            sessionCoroutineScope = sessionCoroutineScope,
        )
        return Fixture(coordinator, kms, runtime, inventory, operations, trust)
    }

    private data class Fixture(
        val coordinator: StandardHistoryRecoveryCoordinator,
        val kms: TestKmsShareClient,
        val runtime: TestMatrixContentProtectionRuntime,
        val inventory: TestHiddenMessageRecoveryInventory,
        val operations: FakeAppOperationalStateCoordinator,
        val trust: FakeDeviceTrustService,
    )

    private class TestHiddenMessageRecoveryInventory(initialCount: Int) : HiddenMessageRecoveryInventory {
        override val candidateCount: StateFlow<Int> = MutableStateFlow(initialCount)
        override val candidateCountByRoom: MutableStateFlow<Map<RoomId, Int>> = MutableStateFlow(
            if (initialCount > 0) mapOf(ROOM_ID to initialCount) else emptyMap()
        )
        override val recoveryStatus: MutableStateFlow<HistoryRecoveryStatus> = MutableStateFlow(HistoryRecoveryStatus.IDLE)
        override fun replaceRoomCandidates(roomId: RoomId, eventIds: Set<String>) = Unit
        override fun clearRoom(roomId: RoomId) = Unit
        override fun updateRecoveryStatus(status: HistoryRecoveryStatus) {
            recoveryStatus.value = status
        }
    }

    private class TestKmsShareClient(
        private val outcomes: ArrayDeque<Result<DeviceRecoveryStatus>>,
        private val statusOutcomes: ArrayDeque<Result<DeviceRecoveryStatus>>,
    ) : KmsShareClient {
        val requests = mutableListOf<StartDeviceRecoveryRequest>()
        val statusRequests = mutableListOf<String>()
        val contexts = mutableListOf<LifecycleOperationContext?>()

        override suspend fun startDeviceRecovery(request: StartDeviceRecoveryRequest): DeviceRecoveryStatus {
            contexts += currentLifecycleOperationContext()
            requests += request
            return (outcomes.removeFirstOrNull() ?: Result.success(authorizedStatus())).getOrThrow()
        }

        override suspend fun deviceRecoveryStatus(recoveryId: String): DeviceRecoveryStatus {
            contexts += currentLifecycleOperationContext()
            statusRequests += recoveryId
            return (statusOutcomes.removeFirstOrNull() ?: Result.success(authorizedStatus())).getOrThrow()
        }

        override suspend fun storeShare(request: StoreKmsShareRequest): KmsShareReference = error("Not used")
        override suspend fun releaseShare(request: ReleaseKmsShareRequest): ReleasedKmsShare = error("Not used")
        override suspend fun issueOfflineLease(request: ReleaseKmsShareRequest): OfflineLease = error("Not used")
        override suspend fun fetchRevocations(request: FetchRevocationsRequest): RemoteRevocationSnapshot = error("Not used")
        override suspend fun destroyShare(contentId: ContentId, reason: ContentAccessDestroyReason): Boolean = error("Not used")
    }

    private class TestMatrixContentProtectionRuntime : MatrixContentProtectionRuntime {
        val refreshModes = mutableListOf<MatrixAuthorizationRefreshMode>()
        val historyScopes = mutableListOf<HistoryScope>()
        override fun attach() = Unit
        override fun detach() = Unit
        override fun isConnected(): Boolean = true
        override fun requestAuthorizationRefresh(mode: MatrixAuthorizationRefreshMode) {
            refreshModes += mode
        }

        override fun requestHistoryRecoveryRefresh(
            accountScopeId: String,
            roomScopeIds: Set<String>,
            recoveryGeneration: Long,
        ) {
            refreshModes += MatrixAuthorizationRefreshMode.HISTORY_RECOVERY_DELTA
            historyScopes += HistoryScope(accountScopeId, roomScopeIds, recoveryGeneration)
        }

        override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable =
            AutoCloseable {}
    }

    private class TestAccountReactivationScopeStore : AccountReactivationScopeStore {
        private var historyGeneration = 0L

        override suspend fun current(
            accountIdHash: String,
            installationId: String,
            matrixDeviceIdHash: String,
        ): AccountReactivationCheckpoint? = null

        override suspend fun requiresCoreReadyEnforcement(
            accountIdHash: String,
            installationId: String,
            matrixDeviceIdHash: String,
        ): Boolean = false

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
        ): HistoryRecoveryAttempt = HistoryRecoveryAttempt(
            accountIdHash = accountIdHash,
            installationId = installationId,
            matrixDeviceIdHash = matrixDeviceIdHash,
            generation = ++historyGeneration,
            roomScopeHashes = roomScopeIds,
        )

        override suspend fun clearPermanentlyDeletedAccount(
            accountIdHash: String,
            installationId: String,
        ): Boolean = error("Not used")
    }

    private data class HistoryScope(
        val accountScopeId: String,
        val roomScopeIds: Set<String>,
        val recoveryGeneration: Long,
    )

    private companion object {
        const val ACCOUNT_ID = "@yizhe01:yizhe.test"
        const val INSTALLATION_ID = "installation-0123456789"
        const val MATRIX_DEVICE_ID = "MATRIXDEVICE01"
        val ROOM_ID = RoomId("!history:example.org")

        fun trustedSnapshot() = DeviceTrustSnapshot(
            installationId = INSTALLATION_ID,
            hardwareSecurityLevel = HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
            trustState = DeviceTrustState.TRUSTED,
            attestationVersion = 2,
            lastVerifiedAtEpochMillis = 1,
        )

        fun authorizedStatus(standardAuthorized: Boolean = true) = DeviceRecoveryStatus(
            recoveryId = "recovery-0123456789",
            accountIdHash = accountIdHash(ACCOUNT_ID),
            newInstallationId = INSTALLATION_ID,
            state = if (standardAuthorized) DeviceRecoveryState.STANDARD_AUTHORIZED else DeviceRecoveryState.AWAITING_OLD_DEVICE,
            standardAuthorized = standardAuthorized,
            oldDeviceConfirmed = false,
            administratorApproved = false,
            confidentialAuthorized = false,
            requestExpiresAtEpochMillis = Long.MAX_VALUE,
        )

        fun expiredStatus() = DeviceRecoveryStatus(
            recoveryId = "recovery-0123456789",
            accountIdHash = accountIdHash(ACCOUNT_ID),
            newInstallationId = INSTALLATION_ID,
            state = DeviceRecoveryState.EXPIRED,
            standardAuthorized = false,
            oldDeviceConfirmed = false,
            administratorApproved = false,
            confidentialAuthorized = false,
            requestExpiresAtEpochMillis = 1,
        )
    }
}
