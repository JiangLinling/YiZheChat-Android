/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.securebackup.impl.enter

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.securebackup.api.requiresGate
import io.element.android.features.securebackup.impl.setup.views.RecoveryKeyUserStory
import io.element.android.features.securebackup.impl.setup.views.RecoveryKeyViewState
import io.element.android.features.securebackup.impl.tools.RecoveryKeyTools
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.encryption.EncryptionService
import io.element.android.libraries.matrix.api.encryption.RecoveryException
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.encryption.FakeEncryptionService
import io.element.android.security.contentprotection.api.DeviceRecoveryState
import io.element.android.security.contentprotection.api.DeviceRecoveryStatus
import io.element.android.security.contentprotection.api.AccountPrivateProfileKey
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.dataprotection.api.*
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.ManagedDeviceSecurityState
import io.element.android.security.dataprotection.api.ManagementSecurityRefreshService
import io.element.android.security.dataprotection.api.ManagementSyncReport
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.tests.testutils.WarmUpRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test

class SecureBackupEnterRecoveryKeyPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createPresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.isSubmitEnabled).isFalse()
            assertThat(initialState.submitAction).isEqualTo(AsyncAction.Uninitialized)
            assertThat(initialState.recoveryKeyViewState).isEqualTo(
                RecoveryKeyViewState(
                    recoveryKeyUserStory = RecoveryKeyUserStory.Enter,
                    formattedRecoveryKey = "",
                    displayTextFieldContents = false,
                    inProgress = false,
                )
            )
        }
    }

    @Test
    fun `present - enter recovery key`() = runTest {
        val encryptionService = FakeEncryptionService()
        val coordinator = FakeAppOperationalStateCoordinator()
        val kmsShareClient = authorizedKmsShareClient()
        val runtime = RecordingMatrixContentProtectionRuntime()
        val presenter = createPresenter(encryptionService, coordinator, kmsShareClient, runtime)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            initialState.eventSink(SecureBackupEnterRecoveryKeyEvents.OnRecoveryKeyChange("1234"))
            val withRecoveryKeyState = awaitItem()
            assertThat(withRecoveryKeyState.isSubmitEnabled).isTrue()
            assertThat(withRecoveryKeyState.recoveryKeyViewState).isEqualTo(
                RecoveryKeyViewState(
                    recoveryKeyUserStory = RecoveryKeyUserStory.Enter,
                    formattedRecoveryKey = "1234",
                    displayTextFieldContents = false,
                    inProgress = false,
                )
            )
            encryptionService.givenRecoverFailure(AN_EXCEPTION)
            withRecoveryKeyState.eventSink(SecureBackupEnterRecoveryKeyEvents.Submit)
            val loadingState = awaitItem()
            assertThat(loadingState.submitAction).isEqualTo(AsyncAction.Loading)
            assertThat(loadingState.isSubmitEnabled).isFalse()
            val errorState = awaitItem()
            val recoveryFailure = errorState.submitAction as AsyncAction.Failure
            assertThat(recoveryFailure.error).isInstanceOf(AN_EXCEPTION.javaClass)
            assertThat(recoveryFailure.error).hasMessageThat().isEqualTo(AN_EXCEPTION.message)
            val originalOperationId = coordinator.startedRequests.single().operationId
            assertThat(errorState.isSubmitEnabled).isFalse()
            errorState.eventSink(SecureBackupEnterRecoveryKeyEvents.ClearDialog)
            val clearedState = awaitItem()
            assertThat(clearedState.submitAction).isEqualTo(AsyncAction.Uninitialized)
            assertThat(clearedState.isSubmitEnabled).isTrue()
            encryptionService.givenRecoverFailure(null)
            clearedState.eventSink(SecureBackupEnterRecoveryKeyEvents.Submit)
            val loadingState2 = awaitItem()
            assertThat(loadingState2.submitAction).isEqualTo(AsyncAction.Loading)
            assertThat(loadingState2.isSubmitEnabled).isFalse()
            val finalState = awaitItem()
            assertThat(finalState.submitAction).isEqualTo(AsyncAction.Success(Unit))
            assertThat(finalState.isSubmitEnabled).isFalse()
            assertThat(coordinator.startedRequests).hasSize(1)
            assertThat(coordinator.startedRequests.single().operationId).isEqualTo(originalOperationId)
            assertThat(coordinator.startedRequests.last().lifecycleId).isEqualTo(FullAppLifecycleId.LC_08)
            assertThat(coordinator.startedRequests.last().trigger).isEqualTo("recovery_key_entered")
            assertThat(coordinator.clientReceipts.map { it.operationId }.distinct()).hasSize(1)
            assertThat(coordinator.clientReceipts).hasSize(2)
            coVerify(exactly = 1) { kmsShareClient.startDeviceRecovery(any()) }
            assertThat(runtime.refreshModes).containsExactly(MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY)
        }
    }

    @Test
    fun `present - server-unverified Matrix recovery remains fail closed`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val kmsShareClient = authorizedKmsShareClient()
        val presenter = createPresenter(
            coordinator = coordinator,
            kmsShareClient = kmsShareClient,
            managementSecurityRefreshService = managementService(
                ManagementSyncReport(
                    status = ManagementSyncStatus.SYNCHRONIZED,
                    deviceSecurityState = ManagedDeviceSecurityState.VALIDATION_PENDING,
                )
            ),
        )
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(SecureBackupEnterRecoveryKeyEvents.OnRecoveryKeyChange("1234"))
            awaitItem().eventSink(SecureBackupEnterRecoveryKeyEvents.Submit)
            assertThat(awaitItem().submitAction).isEqualTo(AsyncAction.Loading)
            assertThat(awaitItem().submitAction).isInstanceOf(AsyncAction.Failure::class.java)
            assertThat(coordinator.startedRequests).hasSize(1)
            assertThat(coordinator.clientReceipts).isEmpty()
            coVerify(exactly = 0) { kmsShareClient.startDeviceRecovery(any()) }
        }
    }

    @Test
    fun `present - pending KMS authorization does not emit Android success receipts`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val kmsShareClient = authorizedKmsShareClient(standardAuthorized = false)
        val runtime = RecordingMatrixContentProtectionRuntime()
        val presenter = createPresenter(
            coordinator = coordinator,
            kmsShareClient = kmsShareClient,
            matrixContentProtectionRuntime = runtime,
        )
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(SecureBackupEnterRecoveryKeyEvents.OnRecoveryKeyChange("1234"))
            awaitItem().eventSink(SecureBackupEnterRecoveryKeyEvents.Submit)
            assertThat(awaitItem().submitAction).isEqualTo(AsyncAction.Loading)
            assertThat(awaitItem().submitAction).isInstanceOf(AsyncAction.Failure::class.java)
            assertThat(coordinator.startedRequests).hasSize(1)
            assertThat(coordinator.clientReceipts).isEmpty()
            coVerify(exactly = 1) { kmsShareClient.startDeviceRecovery(any()) }
            assertThat(runtime.refreshModes).isEmpty()
        }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `page disposal after Matrix readiness cannot abandon original recovery receipts`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val kms = authorizedKmsShareClient()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { kms.startDeviceRecovery(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            releaseAfterStart.add(kms)
            DeviceRecoveryStatus("recovery-request-1234567890", "a".repeat(64), TEST_INSTALLATION_ID,
                DeviceRecoveryState.STANDARD_AUTHORIZED, true, false, false, false, Long.MAX_VALUE)
        }
        val runtime = RecordingMatrixContentProtectionRuntime()
        val presenter = createPresenter(coordinator = coordinator, kmsShareClient = kms, matrixContentProtectionRuntime = runtime)
        val pageWaiter = launch { presenter.recover("1234").getOrThrow() }
        entered.await()
        val original = coordinator.startedRequests.single().operationId
        pageWaiter.cancelAndJoin()
        assertThat(coordinator.clientReceipts).isEmpty()
        release.complete(Unit)
        runCurrent()
        assertThat(coordinator.startedRequests).hasSize(1)
        assertThat(coordinator.clientReceipts).hasSize(2)
        assertThat(coordinator.clientReceipts.map { it.operationId }.distinct()).containsExactly(original)
        assertThat(runtime.refreshModes).containsExactly(MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY)
    }

    @Test
    fun `secret-storage rejection persists original failure and never emits success receipts`() = runTest {
        val base = FakeAppOperationalStateCoordinator()
        val persisted = mutableListOf<LifecycleClientReceipt>()
        val coordinator = object : AppOperationalStateCoordinator by base {
            override suspend fun enqueueClientReceipts(receipts: List<LifecycleClientReceipt>) { persisted += receipts }
        }
        val rejected = RecoveryException.SecretStorage("test rejection")
        val encryption = FakeEncryptionService().apply { givenRecoverFailure(rejected) }
        val kms = authorizedKmsShareClient()
        val result = createPresenter(encryptionService = encryption, coordinator = coordinator, kmsShareClient = kms).recover("1234")
        assertThat(result.exceptionOrNull()).isInstanceOf(RecoveryException.SecretStorage::class.java)
        assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("test rejection")
        val original = base.startedRequests.single().operationId
        assertThat(persisted.single().operationId).isEqualTo(original)
        assertThat(persisted.single().resultCode).isEqualTo("RECOVERY_SECRET_STORAGE_REJECTED")
        assertThat(base.clientReceipts).containsExactly(persisted.single())
        coVerify(exactly = 0) { kms.startDeviceRecovery(any()) }
    }

    private fun TestScope.createPresenter(
        encryptionService: EncryptionService = FakeEncryptionService(),
        coordinator: AppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        kmsShareClient: KmsShareClient = authorizedKmsShareClient(),
        matrixContentProtectionRuntime: MatrixContentProtectionRuntime = RecordingMatrixContentProtectionRuntime(),
        intentAuthority: RecoveryIntentAuthority = RecordingRecoveryIntentAuthority(coordinator),
        managementSecurityRefreshService: ManagementSecurityRefreshService = managementService(
            ManagementSyncReport(
                status = ManagementSyncStatus.SYNCHRONIZED,
                deviceSecurityState = ManagedDeviceSecurityState.READY,
            )
        ),
    ) = SecureBackupEnterRecoveryKeyPresenter(
        encryptionService = encryptionService,
        recoveryKeyTools = RecoveryKeyTools(),
        matrixClient = FakeMatrixClient(encryptionService = encryptionService),
        appOperationalStateCoordinator = coordinator,
        recoveryIntentAuthority = intentAuthority,
        managementSecurityRefreshService = managementSecurityRefreshService,
        sessionCoroutineScope = backgroundScope,
        kmsShareClient = kmsShareClient,
        deviceTrustService = mockk<DeviceTrustService>().also { service ->
            coEvery { service.current() } returns DeviceTrustSnapshot(
                installationId = TEST_INSTALLATION_ID,
                hardwareSecurityLevel = HardwareSecurityLevel.SOFTWARE,
                trustState = DeviceTrustState.MANAGED,
                attestationVersion = 1,
                lastVerifiedAtEpochMillis = 1,
            )
        },
        matrixContentProtectionRuntime = matrixContentProtectionRuntime,
    )


    private class RecordingRecoveryIntentAuthority(private val coordinator: AppOperationalStateCoordinator) : RecoveryIntentAuthority {
        private var current: RecoveryIntentRecord? = null
        override suspend fun restore(scope: RecoveryIntentScope) = current?.takeIf { it.scope == scope }
        override suspend fun admit(scope: RecoveryIntentScope): RecoveryIntentRecord {
            current?.let { return it }
            val request = lifecycleOperationRequest(FullAppLifecycleId.LC_08, "recovery_key_entered",
                accountIdHash = scope.accountIdHash, matrixDeviceIdHash = scope.matrixDeviceIdHash)
            coordinator.start(request)
            return RecoveryIntentRecord(scope, request.operationId, request.idempotencyKey, request.deadlineEpochMillis, 1,
                identityGeneration = "d".repeat(64), mobileGeneration = 1).also { current = it }
        }
        override suspend fun verify(record: RecoveryIntentRecord) = RecoveryOperationContext(
            operationId = record.operationId, scope = record.scope, trigger = "recovery_key_entered",
            identityGeneration = checkNotNull(record.identityGeneration), mobileGeneration = 1,
            currentIdentityGeneration = checkNotNull(record.identityGeneration), currentMobileGeneration = 1,
            scopeValid = true, rootValid = true, currentRootValid = true, status = LifecycleOperationStatus.RUNNING,
            terminalReceiptId = null, deadlineEpochMillis = record.deadlineEpochMillis, serverTimeEpochMillis = System.currentTimeMillis(),
            matrixComplete = true, recoveryId = record.recoveryId, recoveryExpiresAtEpochMillis = record.recoveryExpiresAtEpochMillis,
            idempotencyKeySha256 = "a".repeat(64))
        override suspend fun waiting(record: RecoveryIntentRecord, recoveryId: String, expiresAtEpochMillis: Long) =
            record.copy(revision = record.revision + 1, recoveryId = recoveryId, recoveryExpiresAtEpochMillis = expiresAtEpochMillis).also { current = it }
        override suspend fun completed(record: RecoveryIntentRecord) { current = null }
    }

    @Test
    fun `rejected original intent never reaches SDK or KMS and keeps startup gate`() = runTest {
        val encryption = mockk<EncryptionService>()
        val kms = authorizedKmsShareClient()
        val authority = mockk<RecoveryIntentAuthority>()
        coEvery { authority.admit(any()) } throws RecoveryIntentRejected("RECOVERY_OPERATION_EXPIRED")
        val presenter = createPresenter(encryptionService = encryption, kmsShareClient = kms, intentAuthority = authority)
        assertThat(presenter.recover("1234").isFailure).isTrue()
        assertThat(presenter.recoveryState.value.requiresGate).isTrue()
        coVerify(exactly = 0) { encryption.recover(any()) }
        coVerify(exactly = 0) { kms.releasePrivateProfileKey(any()) }
    }

    private val releaseAfterStart = mutableSetOf<KmsShareClient>()

    @Test
    fun `valid waiting request retains its identity and emits no success receipts`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val kms = authorizedKmsShareClient(standardAuthorized = false)
        val result = createPresenter(coordinator = coordinator, kmsShareClient = kms).recover("1234")
        val failure = result.exceptionOrNull() as ContentProtectionException.DeviceRecoveryWaiting
        assertThat(failure.recoveryId).isEqualTo("recovery-request-1234567890")
        assertThat(failure.requestExpiresAtEpochMillis).isEqualTo(Long.MAX_VALUE)
        assertThat(coordinator.clientReceipts).isEmpty()
        coVerify(exactly = 1) { kms.releasePrivateProfileKey(any()) }
        coVerify(exactly = 0) { kms.ensurePrivateProfileKey(any(), any()) }
    }

    @Test
    fun `existing authorized installation releases original key and never starts recovery`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val kms = authorizedKmsShareClient()
        val material = ByteArray(32) { 9 }
        coEvery { kms.releasePrivateProfileKey(any()) } returns AccountPrivateProfileKey(4, material)
        val result = createPresenter(coordinator = coordinator, kmsShareClient = kms).recover("1234")
        assertThat(result.isSuccess).isTrue()
        assertThat(material.toList()).containsExactlyElementsIn(ByteArray(32).toList())
        assertThat(coordinator.clientReceipts).hasSize(2)
        assertThat(coordinator.clientReceipts.map { it.operationId }.distinct())
            .containsExactly(coordinator.startedRequests.single().operationId)
        coVerify(exactly = 0) { kms.startDeviceRecovery(any()) }
        coVerify(exactly = 0) { kms.ensurePrivateProfileKey(any(), any()) }
    }

    @Test
    fun `release denial integrity and transport failures never downgrade to recovery`() = runTest {
        listOf(ContentProtectionException.AccessDenied(), ContentProtectionException.AuthenticationFailed(),
            ContentProtectionException.KmsUnavailable(), ContentProtectionException.Revoked()).forEach { failure ->
            val coordinator = FakeAppOperationalStateCoordinator()
            val kms = authorizedKmsShareClient()
            coEvery { kms.releasePrivateProfileKey(any()) } throws failure
            val result = createPresenter(coordinator = coordinator, kmsShareClient = kms).recover("1234")
            assertThat(result.exceptionOrNull()).isInstanceOf(failure.javaClass)
            assertThat(coordinator.clientReceipts).isEmpty()
            coVerify(exactly = 0) { kms.startDeviceRecovery(any()) }
            coVerify(exactly = 0) { kms.ensurePrivateProfileKey(any(), any()) }
        }
    }

    @Test
    fun `expired recovery never releases key or emits success receipts`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val kms = authorizedKmsShareClient()
        coEvery { kms.startDeviceRecovery(any()) } returns DeviceRecoveryStatus(
            "recovery-request-1234567890", "a".repeat(64), TEST_INSTALLATION_ID,
            DeviceRecoveryState.EXPIRED, true, false, false, false, 1,
        )
        val result = createPresenter(coordinator = coordinator, kmsShareClient = kms).recover("1234")
        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.Expired::class.java)
        assertThat(coordinator.clientReceipts).isEmpty()
        coVerify(exactly = 1) { kms.releasePrivateProfileKey(any()) }
    }

    private fun authorizedKmsShareClient(standardAuthorized: Boolean = true): KmsShareClient = mockk<KmsShareClient>().also { client ->
        // Existing tests exercise the new-installation path; once it is authorized, release succeeds.
        coEvery { client.releasePrivateProfileKey(any()) } coAnswers {
            if (!releaseAfterStart.remove(client)) throw ContentProtectionException.DeviceRecoveryRequired()
            AccountPrivateProfileKey(1, ByteArray(32) { 7 })
        }
        coEvery { client.startDeviceRecovery(any()) } answers {
            if (standardAuthorized) releaseAfterStart.add(client)
            val request = firstArg<io.element.android.security.contentprotection.api.StartDeviceRecoveryRequest>()
            DeviceRecoveryStatus(
                recoveryId = "recovery-request-1234567890",
                accountIdHash = request.accountIdHash,
                newInstallationId = request.newInstallationId,
                state = if (standardAuthorized) DeviceRecoveryState.STANDARD_AUTHORIZED else DeviceRecoveryState.AWAITING_OLD_DEVICE,
                standardAuthorized = standardAuthorized,
                oldDeviceConfirmed = false,
                administratorApproved = false,
                confidentialAuthorized = false,
                requestExpiresAtEpochMillis = Long.MAX_VALUE,
            )
        }
    }

    private fun managementService(report: ManagementSyncReport): ManagementSecurityRefreshService =
        object : ManagementSecurityRefreshService {
            override suspend fun refreshSecurityAccess() = report
        }

    private class RecordingMatrixContentProtectionRuntime : MatrixContentProtectionRuntime {
        val refreshModes = mutableListOf<MatrixAuthorizationRefreshMode>()

        override fun attach() = Unit
        override fun detach() = Unit
        override fun isConnected() = true
        override fun requestAuthorizationRefresh(mode: MatrixAuthorizationRefreshMode) {
            refreshModes += mode
        }
    }

    private companion object {
        const val TEST_INSTALLATION_ID = "test-installation-1234567890"
    }
}
