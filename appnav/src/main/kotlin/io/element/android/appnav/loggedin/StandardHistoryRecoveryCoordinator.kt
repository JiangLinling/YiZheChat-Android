/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appnav.loggedin

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.networkmonitor.api.NetworkMonitor
import io.element.android.features.networkmonitor.api.NetworkStatus
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.encryption.EncryptionService
import io.element.android.libraries.matrix.api.encryption.HiddenMessageRecoveryInventory
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import io.element.android.libraries.matrix.api.encryption.RecoveryState
import io.element.android.libraries.matrix.api.verification.SessionVerificationService
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.DeviceRecoveryState
import io.element.android.security.contentprotection.api.DeviceRecoveryStatus
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.contentprotection.api.StartDeviceRecoveryRequest
import io.element.android.security.dataprotection.api.AccountHistoryAccessBoundaryProvider
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.accountIdHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges successful Matrix history recovery to KMS re-wrap authorization for the current installation.
 *
 * It never handles event ids or plaintext, never authorizes confidential history, and never changes an existing
 * terminal tombstone. Server recovery is idempotent; the bounded polling only observes a previously pending
 * old-device approval and remains fail-closed for every non-transient error.
 */
@SingleIn(SessionScope::class)
@Inject
class StandardHistoryRecoveryCoordinator(
    private val encryptionService: EncryptionService,
    private val sessionVerificationService: SessionVerificationService,
    private val hiddenMessageRecoveryInventory: HiddenMessageRecoveryInventory,
    private val networkMonitor: NetworkMonitor,
    private val kmsShareClient: KmsShareClient,
    private val deviceTrustService: DeviceTrustService,
    private val matrixContentProtectionRuntime: MatrixContentProtectionRuntime,
    private val accountReactivationScopeStore: AccountReactivationScopeStore,
    private val accountHistoryAccessBoundaryProvider: AccountHistoryAccessBoundaryProvider,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(StandardHistoryRecoveryState.WAITING_FOR_PREREQUISITES)

    internal val state: StateFlow<StandardHistoryRecoveryState> = mutableState.asStateFlow()

    fun start(accountId: String, matrixDeviceId: String) {
        require(accountId.isNotBlank())
        require(matrixDeviceId.isNotBlank())
        if (!started.compareAndSet(false, true)) return
        val accountHash = accountIdHash(accountId)
        val matrixDeviceIdHash = sha256Hex(matrixDeviceId)
        sessionCoroutineScope.launch {
            try {
                if (accountHistoryAccessBoundaryProvider.current() != null) {
                    // An explicit identity reset is an irreversible user choice. Never start an old-device/KMS
                    // recovery loop for history that the new identity can no longer authenticate.
                    updateState(
                        StandardHistoryRecoveryState.IDENTITY_RESET_UNRECOVERABLE,
                        HistoryRecoveryStatus.IDENTITY_RESET_UNRECOVERABLE,
                    )
                    return@launch
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                updateState(StandardHistoryRecoveryState.FAILED_CLOSED, HistoryRecoveryStatus.FAILED_CLOSED)
                Timber.tag(DIAGNOSTIC_TAG).w(failure, "Identity-reset history boundary could not be verified")
                return@launch
            }
            val prerequisites = combine(
                encryptionService.recoveryStateStateFlow,
                sessionVerificationService.sessionVerifiedStatus,
                hiddenMessageRecoveryInventory.candidateCountByRoom,
                networkMonitor.connectivity,
                networkMonitor.isNetworkBlocked,
            ) { recoveryState, verificationState, candidatesByRoom, connectivity, networkBlocked ->
                RecoveryPrerequisites(
                    recoveryState = recoveryState,
                    verificationState = verificationState,
                    candidateRoomIds = candidatesByRoom
                        .filterValues { count -> count > 0 }
                        .keys
                        .mapTo(linkedSetOf()) { roomId -> roomId.value },
                    networkReady = connectivity == NetworkStatus.Connected && !networkBlocked,
                )
            }
            combine(prerequisites, deviceTrustService.trust()) { current, trust ->
                RecoveryEligibility(
                    prerequisites = current,
                    trustedDevice = trust.canRequestStandardRecovery(),
                    installationId = trust.installationId,
                )
            }
                .distinctUntilChanged()
                .collectLatest { eligibility ->
                    val blockedStatus = eligibility.blockedStatus()
                    if (blockedStatus != null) {
                        updateState(StandardHistoryRecoveryState.WAITING_FOR_PREREQUISITES, blockedStatus)
                        return@collectLatest
                    }
                    if (mutableState.value == StandardHistoryRecoveryState.STANDARD_AUTHORIZED) {
                        hiddenMessageRecoveryInventory.updateRecoveryStatus(HistoryRecoveryStatus.STANDARD_AUTHORIZED)
                        return@collectLatest
                    }
                    // The KMS proof requires an admitted LC08 owner even when Matrix recovery
                    // completed earlier. One check owns all bounded retries and read-only polls;
                    // neither a transient failure nor a pending approval creates another owner.
                    val request = lifecycleOperationRequest(
                        lifecycleId = FullAppLifecycleId.LC_08,
                        trigger = "new_device",
                        accountIdHash = accountHash,
                        matrixDeviceIdHash = matrixDeviceIdHash,
                    )
                    try {
                        withTimeout((request.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1L)) {
                            appOperationalStateCoordinator.perform(request) {
                                recoverUntilResolved(
                                    accountHash = accountHash,
                                    installationId = eligibility.installationId,
                                    matrixDeviceIdHash = matrixDeviceIdHash,
                                    candidateRoomIds = eligibility.prerequisites.candidateRoomIds,
                                )
                                // A bounded check may end pending, expired, or failed. None of
                                // those outcomes may emit successful client-applied receipts.
                                if (mutableState.value != StandardHistoryRecoveryState.STANDARD_AUTHORIZED) {
                                    throw HistoryRecoveryNotApplied()
                                }
                            }
                        }
                    } catch (_: HistoryRecoveryNotApplied) {
                        // Keep the exact public state established by the recovery check.
                    } catch (_: TimeoutCancellationException) {
                        updateState(StandardHistoryRecoveryState.REQUEST_EXPIRED, HistoryRecoveryStatus.REQUEST_EXPIRED)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: java.io.IOException) {
                        updateState(StandardHistoryRecoveryState.SERVICE_UNAVAILABLE, HistoryRecoveryStatus.KMS_UNAVAILABLE)
                    } catch (_: ContentProtectionException.KmsUnavailable) {
                        updateState(StandardHistoryRecoveryState.SERVICE_UNAVAILABLE, HistoryRecoveryStatus.KMS_UNAVAILABLE)
                    } catch (failure: Exception) {
                        updateState(StandardHistoryRecoveryState.FAILED_CLOSED, HistoryRecoveryStatus.FAILED_CLOSED)
                        Timber.tag(DIAGNOSTIC_TAG).w(failure, "Standard history recovery admission rejected fail-closed")
                    }
                }
        }
    }

    private suspend fun recoverUntilResolved(
        accountHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
        candidateRoomIds: Set<String>,
    ) {
        var startAttempt = 0
        var status: DeviceRecoveryStatus
        while (true) {
            updateState(StandardHistoryRecoveryState.REQUESTING, HistoryRecoveryStatus.AUTHORIZING_DEVICE)
            try {
                status = kmsShareClient.startDeviceRecovery(StartDeviceRecoveryRequest(accountHash, installationId))
                validateStatus(status, accountHash, installationId)
                break
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: ContentProtectionException.KmsUnavailable) {
                if (startAttempt >= TRANSIENT_RETRY_DELAYS_MILLIS.size) {
                    updateState(StandardHistoryRecoveryState.SERVICE_UNAVAILABLE, HistoryRecoveryStatus.KMS_UNAVAILABLE)
                    return
                }
                updateState(StandardHistoryRecoveryState.RETRY_WAIT, HistoryRecoveryStatus.RETRYING)
                delay(TRANSIENT_RETRY_DELAYS_MILLIS[startAttempt])
                startAttempt++
            } catch (failure: Exception) {
                updateState(StandardHistoryRecoveryState.FAILED_CLOSED, HistoryRecoveryStatus.FAILED_CLOSED)
                Timber.tag(DIAGNOSTIC_TAG).w(failure, "Standard history recovery stopped fail-closed")
                return
            }
        }

        var pollFailureCount = 0
        for (pollDelayMillis in STATUS_POLL_DELAYS_MILLIS) {
            if (applyStatus(status, accountHash, installationId, matrixDeviceIdHash, candidateRoomIds)) return
            delay(pollDelayMillis)
            try {
                status = kmsShareClient.deviceRecoveryStatus(status.recoveryId)
                validateStatus(status, accountHash, installationId)
                pollFailureCount = 0
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: ContentProtectionException.KmsUnavailable) {
                pollFailureCount++
                if (pollFailureCount >= MAX_STATUS_POLL_FAILURES) {
                    updateState(StandardHistoryRecoveryState.SERVICE_UNAVAILABLE, HistoryRecoveryStatus.KMS_UNAVAILABLE)
                    return
                }
                updateState(StandardHistoryRecoveryState.RETRY_WAIT, HistoryRecoveryStatus.RETRYING)
            } catch (failure: Exception) {
                updateState(StandardHistoryRecoveryState.FAILED_CLOSED, HistoryRecoveryStatus.FAILED_CLOSED)
                Timber.tag(DIAGNOSTIC_TAG).w(failure, "Standard history recovery status rejected fail-closed")
                return
            }
        }
        applyStatus(status, accountHash, installationId, matrixDeviceIdHash, candidateRoomIds)
    }

    private fun validateStatus(status: DeviceRecoveryStatus, accountHash: String, installationId: String) {
        if (status.accountIdHash != accountHash || status.newInstallationId != installationId) {
            throw ContentProtectionException.AuthenticationFailed()
        }
    }

    /** Returns true when no automatic status polling should continue. */
    private suspend fun applyStatus(
        status: DeviceRecoveryStatus,
        accountHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
        candidateRoomIds: Set<String>,
    ): Boolean {
        if (status.standardAuthorized) {
            try {
                val recoveryAttempt = accountReactivationScopeStore.nextHistoryRecoveryAttempt(
                    accountIdHash = accountHash,
                    installationId = installationId,
                    matrixDeviceIdHash = matrixDeviceIdHash,
                    roomScopeIds = candidateRoomIds,
                )
                // Persist before signaling. A restarted process continues above this generation;
                // another account, Matrix device or room cannot consume the additive grant.
                matrixContentProtectionRuntime.requestHistoryRecoveryRefresh(
                    accountScopeId = accountHash,
                    roomScopeIds = candidateRoomIds,
                    recoveryGeneration = recoveryAttempt.generation,
                )
                updateState(StandardHistoryRecoveryState.STANDARD_AUTHORIZED, HistoryRecoveryStatus.STANDARD_AUTHORIZED)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                updateState(StandardHistoryRecoveryState.FAILED_CLOSED, HistoryRecoveryStatus.FAILED_CLOSED)
                Timber.tag(DIAGNOSTIC_TAG).w(failure, "History recovery delta checkpoint failed closed")
            }
            return true
        }
        if (status.state == DeviceRecoveryState.EXPIRED || status.requestExpiresAtEpochMillis <= System.currentTimeMillis()) {
            updateState(StandardHistoryRecoveryState.REQUEST_EXPIRED, HistoryRecoveryStatus.REQUEST_EXPIRED)
            return true
        }
        when (status.state) {
            DeviceRecoveryState.AWAITING_OLD_DEVICE -> updateState(
                StandardHistoryRecoveryState.AWAITING_EXTERNAL_APPROVAL,
                HistoryRecoveryStatus.WAITING_FOR_OLD_DEVICE,
            )
            DeviceRecoveryState.AWAITING_ADMIN_APPROVAL -> updateState(
                StandardHistoryRecoveryState.AWAITING_EXTERNAL_APPROVAL,
                HistoryRecoveryStatus.WAITING_FOR_ADMIN_APPROVAL,
            )
            DeviceRecoveryState.STANDARD_AUTHORIZED,
            DeviceRecoveryState.CONFIDENTIAL_AUTHORIZED -> throw ContentProtectionException.AuthenticationFailed()
            DeviceRecoveryState.EXPIRED -> error("Handled above")
        }
        return false
    }

    private fun updateState(state: StandardHistoryRecoveryState, publicStatus: HistoryRecoveryStatus) {
        mutableState.value = state
        hiddenMessageRecoveryInventory.updateRecoveryStatus(publicStatus)
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun DeviceTrustSnapshot.canRequestStandardRecovery(): Boolean {
        if (attestationVersion <= 0 || installationId.isBlank() || !allowsLocalVaultAccess) return false
        return remotelyTrusted ||
            (trustState == DeviceTrustState.MANAGED && managedLicense?.isActive(System.currentTimeMillis()) == true)
    }

    private data class RecoveryPrerequisites(
        val recoveryState: RecoveryState,
        val verificationState: SessionVerifiedStatus,
        val candidateRoomIds: Set<String>,
        val networkReady: Boolean,
    )

    private class HistoryRecoveryNotApplied : Exception()

    private data class RecoveryEligibility(
        val prerequisites: RecoveryPrerequisites,
        val trustedDevice: Boolean,
        val installationId: String,
    ) {
        fun blockedStatus(): HistoryRecoveryStatus? = when {
            prerequisites.candidateRoomIds.isEmpty() -> HistoryRecoveryStatus.IDLE
            prerequisites.recoveryState != RecoveryState.ENABLED -> HistoryRecoveryStatus.WAITING_FOR_MATRIX_RECOVERY
            prerequisites.verificationState != SessionVerifiedStatus.Verified -> HistoryRecoveryStatus.WAITING_FOR_MATRIX_VERIFICATION
            !prerequisites.networkReady -> HistoryRecoveryStatus.WAITING_FOR_NETWORK
            !trustedDevice -> HistoryRecoveryStatus.WAITING_FOR_DEVICE_TRUST
            else -> null
        }
    }

    private companion object {
        const val DIAGNOSTIC_TAG = "YiZheHistoryRecovery"
        val TRANSIENT_RETRY_DELAYS_MILLIS = longArrayOf(5_000L, 30_000L, 120_000L, 300_000L)
        val STATUS_POLL_DELAYS_MILLIS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
        const val MAX_STATUS_POLL_FAILURES = 3
    }
}

internal enum class StandardHistoryRecoveryState {
    WAITING_FOR_PREREQUISITES,
    REQUESTING,
    RETRY_WAIT,
    AWAITING_EXTERNAL_APPROVAL,
    REQUEST_EXPIRED,
    SERVICE_UNAVAILABLE,
    STANDARD_AUTHORIZED,
    IDENTITY_RESET_UNRECOVERABLE,
    FAILED_CLOSED,
}
