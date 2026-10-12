/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.preferences.impl.chatsecurity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.features.messages.api.MessageReference
import io.element.android.features.messages.api.MessageReferenceStore
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.encryption.EncryptionService
import io.element.android.libraries.matrix.api.encryption.HiddenMessageRecoveryInventory
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import io.element.android.libraries.matrix.api.encryption.RecoveryState
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.ConfirmDeviceRecoveryRequest
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.DeviceRecoveryState
import io.element.android.security.contentprotection.api.DeviceRecoveryStatus
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.contentprotection.api.PendingDeviceRecovery
import io.element.android.security.contentprotection.api.StartDeviceRecoveryRequest
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.ManagedDeviceSecurityState
import io.element.android.security.dataprotection.api.ManagementSecurityRefreshService
import io.element.android.security.dataprotection.api.ManagementSyncReport
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.accountIdHash
import kotlinx.coroutines.launch

@Inject
class ChatSecurityPresenter(
    private val managementSecurityRefreshService: ManagementSecurityRefreshService,
    private val encryptionService: EncryptionService,
    private val hiddenMessageRecoveryInventory: HiddenMessageRecoveryInventory,
    private val kmsShareClient: KmsShareClient,
    private val matrixContentProtectionRuntime: MatrixContentProtectionRuntime,
    private val sessionStore: SessionStore,
    private val deviceTrustService: DeviceTrustService,
    private val messageReferenceStore: MessageReferenceStore,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
) : Presenter<ChatSecurityState> {
    @Composable
    override fun present(): ChatSecurityState {
        val scope = rememberCoroutineScope()
        var publicState by remember { mutableStateOf(PublicDeviceSecurityState.NOT_STARTED) }
        var hasServerBackup by remember { mutableStateOf<Boolean?>(null) }
        var isRefreshing by remember { mutableStateOf(false) }
        var currentDeviceCode by remember { mutableStateOf<String?>(null) }
        var deviceRecoveryStatus by remember { mutableStateOf<DeviceRecoveryStatus?>(null) }
        var pendingRecoveries by remember { mutableStateOf<List<PendingDeviceRecovery>>(emptyList()) }
        var isRecoveryActionRunning by remember { mutableStateOf(false) }
        var recoveryActionFailure by remember { mutableStateOf<PublicRecoveryActionFailure?>(null) }
        var isLocalHistoryCleanupRunning by remember { mutableStateOf(false) }
        var localHistoryCleanupFailed by remember { mutableStateOf(false) }
        val candidateCount by hiddenMessageRecoveryInventory.candidateCount.collectAsState()
        val recoveryState by encryptionService.recoveryStateStateFlow.collectAsState()
        val recoveryStatus by hiddenMessageRecoveryInventory.recoveryStatus.collectAsState()

        fun refresh() {
            if (isRefreshing) return
            scope.launch {
                isRefreshing = true
                val latestSession = sessionStore.getLatestSession()
                var latestManagementReport: ManagementSyncReport? = null
                var latestPendingRecoveries = emptyList<PendingDeviceRecovery>()
                val managementResult = runCatching {
                    appOperationalStateCoordinator.perform(
                        lifecycleOperationRequest(
                            lifecycleId = FullAppLifecycleId.LC_23,
                            trigger = "diagnostics_refresh",
                            accountIdHash = latestSession?.userId?.let(::accountIdHash),
                            matrixDeviceIdHash = latestSession?.deviceId?.let(::accountIdHash),
                        )
                    ) {
                        managementSecurityRefreshService.refreshSecurityAccess().also { report ->
                            latestManagementReport = report
                            check(report.status in SUCCESSFUL_MANAGEMENT_SYNC_STATUSES) {
                                "Management security refresh did not reach a successful authority state: ${report.status}."
                            }
                            // Pending requests are an observed diagnostic projection, not a recovery action.
                            // Reuse LC-23 so the signed KMS query has an operation identity without creating
                            // an LC-08 operation that can never obtain the action's required receipts.
                            latestPendingRecoveries = latestSession?.let { session ->
                                runCatching {
                                    kmsShareClient.pendingDeviceRecoveries(accountIdHash(session.userId))
                                }.getOrDefault(emptyList())
                            }.orEmpty()
                        }
                    }
                }
                publicState = latestManagementReport?.toPublicState()
                    ?: PublicDeviceSecurityState.SERVICE_UNAVAILABLE
                hasServerBackup = if (managementResult.isSuccess) {
                    encryptionService.doesBackupExistOnServer().getOrNull()
                } else {
                    null
                }
                currentDeviceCode = runCatching { deviceTrustService.current().installationId.deviceCode() }.getOrNull()
                pendingRecoveries = if (managementResult.isSuccess) latestPendingRecoveries else emptyList()
                isRefreshing = false
            }
        }

        fun requestDeviceRecovery() {
            if (isRecoveryActionRunning) return
            scope.launch {
                isRecoveryActionRunning = true
                recoveryActionFailure = null
                val result = runCatching {
                    val session = checkNotNull(sessionStore.getLatestSession())
                    val installationId = deviceTrustService.current().installationId
                    appOperationalStateCoordinator.perform(
                        lifecycleOperationRequest(
                            lifecycleId = FullAppLifecycleId.LC_08,
                            trigger = "new_device",
                            accountIdHash = accountIdHash(session.userId),
                            matrixDeviceIdHash = accountIdHash(session.deviceId),
                        )
                    ) {
                        val recovery = kmsShareClient.startDeviceRecovery(
                            StartDeviceRecoveryRequest(accountIdHash(session.userId), installationId)
                        )
                        if (recovery.standardAuthorized) {
                            matrixContentProtectionRuntime.requestAuthorizationRefresh(
                                MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY,
                            )
                        }
                        val managementReport = managementSecurityRefreshService.refreshSecurityAccess()
                        check(managementReport.status in SUCCESSFUL_MANAGEMENT_SYNC_STATUSES) {
                            "Recovery Matrix verification did not reach an authority state: ${managementReport.status}."
                        }
                        recovery
                    }
                }
                deviceRecoveryStatus = result.getOrNull()?.also { status ->
                    when {
                        status.standardAuthorized -> {
                            hiddenMessageRecoveryInventory.updateRecoveryStatus(HistoryRecoveryStatus.STANDARD_AUTHORIZED)
                        }
                        status.state == DeviceRecoveryState.AWAITING_OLD_DEVICE ->
                            hiddenMessageRecoveryInventory.updateRecoveryStatus(HistoryRecoveryStatus.WAITING_FOR_OLD_DEVICE)
                        status.state == DeviceRecoveryState.AWAITING_ADMIN_APPROVAL ->
                            hiddenMessageRecoveryInventory.updateRecoveryStatus(HistoryRecoveryStatus.WAITING_FOR_ADMIN_APPROVAL)
                        status.state == DeviceRecoveryState.EXPIRED ->
                            hiddenMessageRecoveryInventory.updateRecoveryStatus(HistoryRecoveryStatus.REQUEST_EXPIRED)
                        else -> hiddenMessageRecoveryInventory.updateRecoveryStatus(HistoryRecoveryStatus.FAILED_CLOSED)
                    }
                }
                recoveryActionFailure = result.exceptionOrNull()?.toPublicRecoveryActionFailure()
                isRecoveryActionRunning = false
            }
        }

        fun confirmDeviceRecovery(recoveryId: String) {
            if (isRecoveryActionRunning) return
            scope.launch {
                isRecoveryActionRunning = true
                recoveryActionFailure = null
                val result = runCatching {
                    val session = checkNotNull(sessionStore.getLatestSession())
                    appOperationalStateCoordinator.perform(
                        lifecycleOperationRequest(
                            lifecycleId = FullAppLifecycleId.LC_08,
                            trigger = "old_device_approval",
                            accountIdHash = accountIdHash(session.userId),
                            matrixDeviceIdHash = accountIdHash(session.deviceId),
                        )
                    ) {
                        val confirmed = kmsShareClient.confirmDeviceRecovery(
                            ConfirmDeviceRecoveryRequest(recoveryId, accountIdHash(session.userId))
                        )
                        val managementReport = managementSecurityRefreshService.refreshSecurityAccess()
                        check(managementReport.status in SUCCESSFUL_MANAGEMENT_SYNC_STATUSES) {
                            "Recovery confirmation Matrix verification did not reach an authority state: ${managementReport.status}."
                        }
                        confirmed
                    }
                }
                if (result.isSuccess) {
                    pendingRecoveries = pendingRecoveries.filterNot { it.recoveryId == recoveryId }
                }
                recoveryActionFailure = result.exceptionOrNull()?.toPublicRecoveryActionFailure()
                isRecoveryActionRunning = false
            }
        }

        fun clearUnavailableHistoryLocally() {
            if (isLocalHistoryCleanupRunning) return
            scope.launch {
                isLocalHistoryCleanupRunning = true
                localHistoryCleanupFailed = false
                clearUnavailableHistoryLocally(
                    inventory = hiddenMessageRecoveryInventory,
                    messageReferenceStore = messageReferenceStore,
                ).onSuccess {
                    hiddenMessageRecoveryInventory.updateRecoveryStatus(HistoryRecoveryStatus.IDLE)
                }.onFailure {
                    localHistoryCleanupFailed = true
                }
                isLocalHistoryCleanupRunning = false
            }
        }

        LaunchedEffect(Unit) { refresh() }
        return ChatSecurityState(
            deviceSecurityState = publicState,
            historyRecoveryState = historyRecoveryState(
                deviceSecurityState = publicState,
                hasServerBackup = hasServerBackup,
                recoveryState = recoveryState,
                candidateCount = candidateCount,
                recoveryStatus = recoveryStatus,
            ),
            currentDeviceCode = currentDeviceCode,
            deviceRecoveryStatus = deviceRecoveryStatus,
            pendingDeviceRecoveries = pendingRecoveries,
            isRefreshing = isRefreshing,
            isRecoveryActionRunning = isRecoveryActionRunning,
            recoveryActionFailure = recoveryActionFailure,
            unavailableHistoryCount = candidateCount,
            isLocalHistoryCleanupRunning = isLocalHistoryCleanupRunning,
            localHistoryCleanupFailed = localHistoryCleanupFailed,
            eventSink = { event ->
                when (event) {
                    ChatSecurityEvent.Refresh -> refresh()
                    ChatSecurityEvent.RequestDeviceRecovery -> requestDeviceRecovery()
                    is ChatSecurityEvent.ConfirmDeviceRecovery -> confirmDeviceRecovery(event.recoveryId)
                    ChatSecurityEvent.ClearUnavailableHistoryLocally -> clearUnavailableHistoryLocally()
                    ChatSecurityEvent.DismissLocalHistoryCleanupError -> localHistoryCleanupFailed = false
                    ChatSecurityEvent.DismissRecoveryError -> recoveryActionFailure = null
                }
            },
        )
    }
}

private fun String.deviceCode(): String = takeLast(8).uppercase()

internal fun historyRecoveryState(
    deviceSecurityState: PublicDeviceSecurityState,
    hasServerBackup: Boolean?,
    recoveryState: RecoveryState,
    candidateCount: Int,
    recoveryStatus: HistoryRecoveryStatus = HistoryRecoveryStatus.IDLE,
): PublicHistoryRecoveryState = when {
    candidateCount == 0 -> PublicHistoryRecoveryState.NONE
    recoveryStatus == HistoryRecoveryStatus.IDENTITY_RESET_UNRECOVERABLE ->
        PublicHistoryRecoveryState.IDENTITY_RESET_UNRECOVERABLE(candidateCount)
    hasServerBackup == null && deviceSecurityState == PublicDeviceSecurityState.SERVICE_UNAVAILABLE ->
        PublicHistoryRecoveryState.SERVICE_UNAVAILABLE
    hasServerBackup == null -> PublicHistoryRecoveryState.CHECKING
    deviceSecurityState != PublicDeviceSecurityState.PASSED -> PublicHistoryRecoveryState.DEVICE_VERIFICATION_REQUIRED
    recoveryStatus == HistoryRecoveryStatus.WAITING_FOR_OLD_DEVICE ->
        PublicHistoryRecoveryState.WAITING_FOR_OLD_DEVICE(candidateCount)
    recoveryStatus == HistoryRecoveryStatus.WAITING_FOR_ADMIN_APPROVAL ->
        PublicHistoryRecoveryState.WAITING_FOR_ADMIN_APPROVAL(candidateCount)
    recoveryStatus == HistoryRecoveryStatus.REQUEST_EXPIRED ||
        recoveryStatus == HistoryRecoveryStatus.FAILED_CLOSED ->
        PublicHistoryRecoveryState.FAILED_CLOSED(candidateCount)
    recoveryStatus == HistoryRecoveryStatus.KMS_UNAVAILABLE -> PublicHistoryRecoveryState.SERVICE_UNAVAILABLE
    recoveryStatus == HistoryRecoveryStatus.WAITING_FOR_MATRIX_VERIFICATION ||
        recoveryStatus == HistoryRecoveryStatus.WAITING_FOR_DEVICE_TRUST ->
        PublicHistoryRecoveryState.DEVICE_VERIFICATION_REQUIRED
    !hasServerBackup -> PublicHistoryRecoveryState.NO_MATRIX_BACKUP
    recoveryState == RecoveryState.DISABLED -> PublicHistoryRecoveryState.AVAILABLE(candidateCount)
    else -> PublicHistoryRecoveryState.RECOVERING(candidateCount)
}

internal suspend fun clearUnavailableHistoryLocally(
    inventory: HiddenMessageRecoveryInventory,
    messageReferenceStore: MessageReferenceStore,
): Result<Int> = runCatching {
    val snapshot = inventory.snapshotCandidatesForLocalCleanup()
    val references = snapshot.flatMap { (roomId, eventIds) ->
        eventIds.map { eventId -> MessageReference(roomId, EventId(eventId)) }
    }
    if (references.isNotEmpty()) {
        // The encrypted local index is the commit point. Only after it is durable may
        // the process-memory candidate inventory be closed.
        messageReferenceStore.deleteUnavailableHistoryLocally(
            references = references,
            cutoffTimestamp = System.currentTimeMillis(),
        )
        snapshot.keys.forEach(inventory::clearRoom)
    }
    references.size
}

internal fun Throwable.toPublicRecoveryActionFailure(): PublicRecoveryActionFailure = when (this) {
    is ContentProtectionException.KmsUnavailable -> PublicRecoveryActionFailure.SERVICE_UNAVAILABLE
    is ContentProtectionException.Expired -> PublicRecoveryActionFailure.REQUEST_EXPIRED
    is ContentProtectionException.RecoveryApprovalRequired -> PublicRecoveryActionFailure.APPROVAL_REQUIRED
    is ContentProtectionException.AccessDenied,
    is ContentProtectionException.DeviceKeyUnavailable -> PublicRecoveryActionFailure.DEVICE_NOT_ELIGIBLE
    is ContentProtectionException.AuthenticationFailed,
    is ContentProtectionException.InvalidEnvelope,
    is ContentProtectionException.ReplayRejected,
    is ContentProtectionException.ClockRollbackDetected,
    is ContentProtectionException.Revoked -> PublicRecoveryActionFailure.SECURITY_REJECTED
    else -> PublicRecoveryActionFailure.UNKNOWN
}

private fun ManagementSyncReport.toPublicState(): PublicDeviceSecurityState = when (status) {
    ManagementSyncStatus.DISABLED -> PublicDeviceSecurityState.NOT_STARTED
    ManagementSyncStatus.RETRY_REQUIRED -> PublicDeviceSecurityState.SERVICE_UNAVAILABLE
    ManagementSyncStatus.SECURITY_REJECTED -> PublicDeviceSecurityState.NOT_PASSED
    ManagementSyncStatus.SYNCHRONIZED -> when (deviceSecurityState) {
        ManagedDeviceSecurityState.READY -> PublicDeviceSecurityState.PASSED
        ManagedDeviceSecurityState.VALIDATION_PENDING,
        ManagedDeviceSecurityState.MANAGED_APPROVAL_PENDING -> PublicDeviceSecurityState.PROCESSING
        ManagedDeviceSecurityState.LOCATION_REQUIRED,
        ManagedDeviceSecurityState.ACTION_REQUIRED,
        null -> PublicDeviceSecurityState.ACTION_REQUIRED
    }
}

private val SUCCESSFUL_MANAGEMENT_SYNC_STATUSES = setOf(
    ManagementSyncStatus.SYNCHRONIZED,
    ManagementSyncStatus.DISABLED,
)
