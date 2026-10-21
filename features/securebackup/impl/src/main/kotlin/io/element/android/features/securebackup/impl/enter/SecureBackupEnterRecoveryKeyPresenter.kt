/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.securebackup.impl.enter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.element.android.features.securebackup.api.RecoveryKeySubmissionService
import io.element.android.features.securebackup.impl.setup.views.RecoveryKeyUserStory
import io.element.android.features.securebackup.impl.setup.views.RecoveryKeyViewState
import io.element.android.features.securebackup.impl.tools.RecoveryKeyTools
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.encryption.EncryptionService
import io.element.android.libraries.matrix.api.encryption.RecoveryException
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.DeviceRecoveryState
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.contentprotection.api.StartDeviceRecoveryRequest
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.ManagedDeviceSecurityState
import io.element.android.security.dataprotection.api.ManagementSecurityRefreshService
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.securestorage.api.DeviceTrustService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<RecoveryKeySubmissionService>())
class SecureBackupEnterRecoveryKeyPresenter(
    private val encryptionService: EncryptionService,
    private val recoveryKeyTools: RecoveryKeyTools,
    private val matrixClient: MatrixClient,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val managementSecurityRefreshService: ManagementSecurityRefreshService,
    private val kmsShareClient: KmsShareClient,
    private val recoveryIntentAuthority: io.element.android.security.dataprotection.api.RecoveryIntentAuthority,
    private val deviceTrustService: DeviceTrustService,
    private val matrixContentProtectionRuntime: MatrixContentProtectionRuntime,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
) : Presenter<SecureBackupEnterRecoveryKeyState>, RecoveryKeySubmissionService {
    override val recoveryState = kotlinx.coroutines.flow.MutableStateFlow<io.element.android.features.securebackup.api.RecoverySessionState>(
        io.element.android.features.securebackup.api.RecoverySessionState.Discovering)
    private val recoveryMutex = Mutex()
    @Composable
    override fun present(): SecureBackupEnterRecoveryKeyState {
        val coroutineScope = rememberCoroutineScope()
        var displayRecoveryKeyFieldContents by rememberSaveable {
            mutableStateOf(false)
        }
        // Recovery material must never be written into Compose saved-instance-state bundles.
        var recoveryKey by remember {
            mutableStateOf("")
        }
        val submitAction: MutableState<AsyncAction<Unit>> = remember {
            mutableStateOf(AsyncAction.Uninitialized)
        }

        fun handleEvent(event: SecureBackupEnterRecoveryKeyEvents) {
            when (event) {
                SecureBackupEnterRecoveryKeyEvents.ClearDialog -> {
                    submitAction.value = AsyncAction.Uninitialized
                }
                is SecureBackupEnterRecoveryKeyEvents.OnRecoveryKeyChange -> {
                    val previousRecoveryKey = recoveryKey
                    recoveryKey = if (previousRecoveryKey.isEmpty() && recoveryKeyTools.isRecoveryKeyFormatValid(event.recoveryKey)) {
                        // A Recovery key has been entered, remove the spaces for a better rendering
                        event.recoveryKey.replace("\\s+".toRegex(), "")
                    } else {
                        // Keep the recovery key as entered by the user. May contains spaces.
                        event.recoveryKey
                    }
                }
                SecureBackupEnterRecoveryKeyEvents.Submit -> {
                    // No need to remove the spaces, the SDK will do it.
                    coroutineScope.submitRecoveryKey(recoveryKey, submitAction)
                }
                is SecureBackupEnterRecoveryKeyEvents.ChangeRecoveryKeyFieldContentsVisibility -> {
                    displayRecoveryKeyFieldContents = event.visible
                }
            }
        }

        return SecureBackupEnterRecoveryKeyState(
            recoveryKeyViewState = RecoveryKeyViewState(
                recoveryKeyUserStory = RecoveryKeyUserStory.Enter,
                formattedRecoveryKey = recoveryKey,
                displayTextFieldContents = displayRecoveryKeyFieldContents,
                inProgress = submitAction.value.isLoading(),
            ),
            isSubmitEnabled = recoveryKey.isNotEmpty() && submitAction.value.isUninitialized(),
            submitAction = submitAction.value,
            eventSink = ::handleEvent,
        )
    }

    private fun CoroutineScope.submitRecoveryKey(
        recoveryKey: String,
        action: MutableState<AsyncAction<Unit>>
    ) = launch {
        suspend {
            recover(recoveryKey).getOrThrow()
        }.runCatchingUpdatingState(action)
    }

    private suspend fun currentRecoveryScope() = io.element.android.security.dataprotection.api.RecoveryIntentScope(
        sha256Hex(matrixClient.sessionId.value), deviceTrustService.current().installationId, sha256Hex(matrixClient.deviceId.value))

    override suspend fun restorePendingRecovery(): Result<Unit> = sessionCoroutineScope.async {
        recoveryMutex.withLock {
            runRecoveryAction {
                val owned = recoveryIntentAuthority.restore(currentRecoveryScope())
                recoveryState.value = if (owned == null) io.element.android.features.securebackup.api.RecoverySessionState.None
                else owned.toRecoveryState()
            }
        }
    }.await()

    override suspend fun refreshPendingRecovery(): Result<Unit> = sessionCoroutineScope.async {
        recoveryMutex.withLock {
            runRecoveryAction {
                val owned = recoveryIntentAuthority.restore(currentRecoveryScope())
                    ?: run { recoveryState.value = io.element.android.features.securebackup.api.RecoverySessionState.None; return@runRecoveryAction }
                recoveryState.value = owned.toRecoveryState()
                val context = recoveryIntentAuthority.verify(owned)
                check(context.matrixComplete && matrixClient.sessionVerificationService.sessionVerifiedStatus.value is
                    io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus.Verified) { "RECOVERY_KEY_REQUIRED" }
                continueOwnedRecovery(owned, null)
            }
        }
    }.await()

    override suspend fun recover(recoveryKey: String): Result<Unit> = sessionCoroutineScope.async {
        recoveryMutex.withLock {
            runRecoveryAction {
                val owned = recoveryIntentAuthority.admit(currentRecoveryScope())
                recoveryState.value = owned.toRecoveryState()
                continueOwnedRecovery(owned, recoveryKey)
            }
        }
    }.await()

    private suspend fun runRecoveryAction(block: suspend () -> Unit): Result<Unit> {
        return try { block(); Result.success(Unit) }
        catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
        catch (waiting: ContentProtectionException.DeviceRecoveryWaiting) { Result.failure(waiting) }
        catch (failure: Exception) {
            val reason = (failure as? io.element.android.security.dataprotection.api.RecoveryIntentRejected)?.reasonCode
                ?: if (failure is ContentProtectionException.Expired) "RECOVERY_OPERATION_EXPIRED" else "RECOVERY_RECHECK_REQUIRED"
            recoveryState.value = io.element.android.features.securebackup.api.RecoverySessionState.Rejected(reason)
            Result.failure(failure)
        }
    }

    private fun io.element.android.security.dataprotection.api.RecoveryIntentRecord.toRecoveryState():
        io.element.android.features.securebackup.api.RecoverySessionState = if (recoveryId != null && recoveryExpiresAtEpochMillis != null)
            io.element.android.features.securebackup.api.RecoverySessionState.Waiting(operationId, checkNotNull(recoveryId), checkNotNull(recoveryExpiresAtEpochMillis))
        else io.element.android.features.securebackup.api.RecoverySessionState.Pending(operationId)

    private suspend fun continueOwnedRecovery(initial: io.element.android.security.dataprotection.api.RecoveryIntentRecord, key: String?) {
        var owned = initial
        // Re-query signed scope/generation/deadline before *any* recovery product block.
        recoveryIntentAuthority.verify(owned)
        withContext(io.element.android.security.dataprotection.api.LifecycleOperationContext(owned.operationId, FullAppLifecycleId.LC_08)) {
            appOperationalStateCoordinator.perform(owned.request()) { operationId ->
                if (key != null) {
                    val matrixRecovery = encryptionService.recover(key)
                    val failure = matrixRecovery.exceptionOrNull()
                    if (failure is RecoveryException.SecretStorage) {
                        val receipt = LifecycleClientReceipt(operationId, LifecycleComponent.ANDROID_SERVICE,
                            LifecyclePhase.CLIENT_APPLIED, "RECOVERY_SECRET_STORAGE_REJECTED",
                            "android-service-recovery-rejected:$operationId", System.currentTimeMillis())
                        withContext(NonCancellable) {
                            appOperationalStateCoordinator.enqueueClientReceipts(listOf(receipt))
                            runCatching { appOperationalStateCoordinator.recordClientReceipt(receipt) }
                                .exceptionOrNull()?.let(failure::addSuppressed)
                        }
                    }
                    matrixRecovery.getOrThrow()
                }
                val management = managementSecurityRefreshService.refreshSecurityAccess()
                check(management.status == ManagementSyncStatus.SYNCHRONIZED &&
                    management.deviceSecurityState == ManagedDeviceSecurityState.READY) { "RECOVERY_MATRIX_AUTHORITY_PENDING" }
                // SDK/management callbacks cannot release a different generation while this action runs.
                recoveryIntentAuthority.verify(owned)
                if (owned.recoveryId != null) {
                    val status = kmsShareClient.deviceRecoveryStatus(checkNotNull(owned.recoveryId))
                    check(status.accountIdHash == owned.scope.accountIdHash && status.newInstallationId == owned.scope.installationId)
                    if (status.state == DeviceRecoveryState.EXPIRED) throw ContentProtectionException.Expired()
                    if (!status.standardAuthorized) throw ContentProtectionException.DeviceRecoveryWaiting(status.recoveryId, status.requestExpiresAtEpochMillis)
                }
                var authorityRetryUsed = false
                suspend fun releaseWithFreshAuthority(): io.element.android.security.contentprotection.api.AccountPrivateProfileKey {
                    return try {
                        kmsShareClient.releasePrivateProfileKey(owned.scope.accountIdHash)
                    } catch (changed: io.element.android.security.contentprotection.api.RecoveryReleaseAuthorityChanged) {
                        if (authorityRetryUsed) throw changed
                        authorityRetryUsed = true
                        // One retry within the SAME durable intent, after fresh signed ownership and
                        // management checks. Never repeat SDK recovery, admit another intent, or relax KMS checks.
                        recoveryIntentAuthority.verify(owned)
                        val refreshed = managementSecurityRefreshService.refreshSecurityAccess()
                        check(refreshed.status == ManagementSyncStatus.SYNCHRONIZED &&
                            refreshed.deviceSecurityState == ManagedDeviceSecurityState.READY) { "RECOVERY_MATRIX_AUTHORITY_PENDING" }
                        recoveryIntentAuthority.verify(owned)
                        kmsShareClient.releasePrivateProfileKey(owned.scope.accountIdHash)
                    }
                }
                val profileKey = try {
                    releaseWithFreshAuthority()
                } catch (required: ContentProtectionException.DeviceRecoveryRequired) {
                    // Never create another request while the original waiting request is known.
                    if (owned.recoveryId != null) throw required
                    val status = kmsShareClient.startDeviceRecovery(StartDeviceRecoveryRequest(owned.scope.accountIdHash, owned.scope.installationId))
                    owned = recoveryIntentAuthority.waiting(owned, status.recoveryId, status.requestExpiresAtEpochMillis)
                    recoveryState.value = owned.toRecoveryState()
                    if (status.state == DeviceRecoveryState.EXPIRED) throw ContentProtectionException.Expired()
                    if (!status.standardAuthorized) throw ContentProtectionException.DeviceRecoveryWaiting(status.recoveryId, status.requestExpiresAtEpochMillis)
                    releaseWithFreshAuthority()
                }
                profileKey.keyMaterial.fill(0)
                recoveryIntentAuthority.verify(owned)
                matrixContentProtectionRuntime.requestAuthorizationRefresh(MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY)
            }
        }
        // Durable owner survives response loss until the SAME operation has a verified terminal receipt.
        recoveryIntentAuthority.completed(owned)
        recoveryState.value = io.element.android.features.securebackup.api.RecoverySessionState.None
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
