/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav.securitysetup

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import io.element.android.appnav.R
import io.element.android.appnav.StartupWaitingView
import io.element.android.appnav.revealStartupSplashWhenLaidOut
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.securebackup.api.RecoveryKeySubmissionService
import io.element.android.features.securebackup.api.RecoverySessionState
import io.element.android.features.securebackup.api.requiresGate
import androidx.compose.runtime.collectAsState
import io.element.android.libraries.matrix.api.encryption.RecoveryException
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.designsystem.atomic.molecules.ButtonColumnMolecule
import io.element.android.libraries.designsystem.atomic.molecules.IconTitleSubtitleMolecule
import io.element.android.libraries.designsystem.atomic.pages.HeaderFooterPage
import io.element.android.libraries.designsystem.background.OnboardingBackground
import io.element.android.libraries.designsystem.components.BigIcon
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.DeviceLocationEvidence
import io.element.android.security.dataprotection.api.DeviceLocationEvidenceState
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.ManagedDeviceSecurityState
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.TimeZone
import kotlin.coroutines.resume

enum class SecuritySetupPhase {
    NOT_SUBMITTED,
    CHECKING,
    LOCATION_REQUIRED,
    LOCATION_SUBMITTING,
    LOCATION_PERMISSION_DENIED,
    LOCATION_UNAVAILABLE,
    VALIDATION_PENDING,
    READY,
    ACTION_REQUIRED,
    SERVICE_UNAVAILABLE,
    MANAGED_APPROVAL_PENDING,
    RECOVERY_METHOD_REQUIRED,
    RECOVERY_EXPIRED,
    SECURITY_BLOCKED,
}

enum class IdentityResetConvergenceState {
    IDLE,
    RUNNING,
    RETRY_REQUIRED,
}

internal fun firstActivationOperationRequest(accountIdHash: String, matrixDeviceIdHash: String) =
    lifecycleOperationRequest(
        lifecycleId = FullAppLifecycleId.LC_05,
        trigger = "first_activation",
        accountIdHash = accountIdHash,
        matrixDeviceIdHash = matrixDeviceIdHash,
    )

data class SecuritySetupState(
    val phase: SecuritySetupPhase,
    val identityConfirmationRequired: Boolean = false,
    val recoveryKey: String = "",
    val displayRecoveryKey: Boolean = false,
    val recoveryKeyAction: AsyncAction<Unit> = AsyncAction.Uninitialized,
    val recoverySessionState: RecoverySessionState = RecoverySessionState.None,
    val errorCode: String? = null,
    val eventSink: (SecuritySetupEvent) -> Unit,
)

sealed interface SecuritySetupEvent {
    data object Submit : SecuritySetupEvent
    data object CollectLocation : SecuritySetupEvent
    data object Retry : SecuritySetupEvent
    data object UseAnotherDevice : SecuritySetupEvent
    data class RecoveryKeyChanged(val recoveryKey: String) : SecuritySetupEvent
    data object ToggleRecoveryKeyVisibility : SecuritySetupEvent
    data object SubmitRecoveryKey : SecuritySetupEvent
    data object RefreshRecovery : SecuritySetupEvent
    data object DismissRecoveryKeyError : SecuritySetupEvent
    data object ResetIdentity : SecuritySetupEvent
}

internal fun recoveryRejectionMessage(reasonCode: String): Int = when (reasonCode) {
    "RECOVERY_OPERATION_EXPIRED" -> R.string.screen_security_setup_recovery_expired
    "RECOVERY_SECRET_STORAGE_REJECTED" -> R.string.screen_security_identity_recovery_key_error
    "RECOVERY_RECHECK_REQUIRED" -> R.string.screen_security_setup_service_unavailable
    else -> R.string.screen_security_setup_security_blocked
}

internal fun recoveryFailureMessage(error: Throwable): Int = when (error) {
    is io.element.android.security.dataprotection.api.RecoveryIntentRejected -> recoveryRejectionMessage(error.reasonCode)
    is RecoveryException.SecretStorage -> R.string.screen_security_identity_recovery_key_error
    is ContentProtectionException.DeviceRecoveryWaiting -> R.string.screen_security_recovery_waiting
    is ContentProtectionException.Expired -> R.string.screen_security_setup_recovery_expired
    is ContentProtectionException.AccessDenied,
    is ContentProtectionException.AuthenticationFailed,
    is ContentProtectionException.Revoked -> R.string.screen_security_setup_security_blocked
    else -> R.string.screen_security_setup_service_unavailable
}

internal fun recoveryActionAfterRefresh(
    result: Result<Unit>,
    recoveryState: RecoverySessionState,
    verificationStatus: SessionVerifiedStatus,
): AsyncAction<Unit> = result.fold(
    onSuccess = {
        // A successful query that retires an expired intent is not identity verification.
        if (!recoveryState.requiresGate && verificationStatus is SessionVerifiedStatus.Verified) {
            AsyncAction.Success(Unit)
        } else {
            AsyncAction.Uninitialized
        }
    },
    onFailure = { AsyncAction.Failure(it) },
)

@Composable
fun SecuritySetupRoute(
    managementControlService: ManagementControlService,
    kmsShareClient: KmsShareClient,
    recoveryKeySubmissionService: RecoveryKeySubmissionService,
    accountIdHash: String,
    matrixDeviceIdHash: String,
    appOperationalStateCoordinator: AppOperationalStateCoordinator,
    lifecycleCoroutineScope: CoroutineScope,
    sessionVerificationStatus: SessionVerifiedStatus,
    lockScreenSetupRequired: Boolean,
    analyticsChoiceComplete: Boolean,
    localRequirementsLoaded: Boolean,
    recoveryCompletionToken: Long,
    identityResetConvergenceState: IdentityResetConvergenceState,
    onApprovalReady: () -> Unit,
    onUseAnotherDevice: () -> Unit,
    onOpenLockScreenSetup: () -> Unit,
    onAnalyticsChoice: (Boolean) -> Unit,
    onRequirementsChanged: () -> Unit,
    onResetIdentity: () -> Unit,
    onRetryIdentityReset: () -> Unit,
    onReady: () -> Unit,
    canRestoreOffline: suspend () -> Boolean,
    onOfflineReady: () -> Unit,
    verifiedStartupReadiness: () -> io.element.android.security.dataprotection.api.StartupSecurityReadiness? = { null },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(SecuritySetupPhase.CHECKING) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    var userReadyRequested by remember { mutableStateOf(false) }
    var activationFinalizing by remember { mutableStateOf(false) }
    var retryNotBeforeEpochMillis by remember { mutableStateOf(0L) }
    // Recovery material deliberately uses remember, not rememberSaveable, so it never enters the
    // Android saved-state bundle. Process recreation requires the user to enter it again.
    var recoveryKey by remember { mutableStateOf("") }
    var displayRecoveryKey by remember { mutableStateOf(false) }
    val recoveryKeyAction: MutableState<AsyncAction<Unit>> = remember { mutableStateOf(AsyncAction.Uninitialized) }
    // SDK Verified alone does not complete the admitted LC-08 action. Editing an error
    // must not release READY before a successful retry has submitted its real receipts.
    var recoveryAwaitingCompletion by remember { mutableStateOf(false) }
    val recoverySessionState by recoveryKeySubmissionService.recoveryState.collectAsState()

    fun pendingActivation() = selectPendingFirstActivation(
        operations = appOperationalStateCoordinator.operations.value.values,
        accountIdHash = accountIdHash,
        matrixDeviceIdHash = matrixDeviceIdHash,
    )

    suspend fun synchronize() {
        runSecuritySetupSynchronization(onFailure = { failure ->
            // Authority denial and unavailable transport retain the safety gate.
            // No failed synchronization may escape a Compose effect and kill the app.
            timber.log.Timber.tag(SECURITY_SETUP_DIAGNOSTIC_TAG).w(
                "YZSETUP_SYNC_FAILURE type=%s", failure.javaClass.simpleName
            )
            activationFinalizing = false
            phase = failure.toSecuritySetupPhase()
        }) {
        if (System.currentTimeMillis() < retryNotBeforeEpochMillis) {
            phase = SecuritySetupPhase.SERVICE_UNAVAILABLE
            return@runSecuritySetupSynchronization
        }
        // A committed, authenticated CORE_READY checkpoint already proves completed admission.
        // The process-local LC-05 operation map can be empty after a cold offline start.
        if (canRestoreOffline()) {
            phase = SecuritySetupPhase.READY
            return@runSecuritySetupSynchronization
        }
        val pendingActivation = pendingActivation()
        // The process startup coordinator already fetched and applied this signed decision.
        // Reuse only its still-valid READY device result; pending LC-05 keeps its own sync context.
        if (pendingActivation == null && canReuseStartupDeviceReady(verifiedStartupReadiness())) {
            phase = SecuritySetupPhase.READY
            return@runSecuritySetupSynchronization
        }
        val report = if (pendingActivation == null) {
            managementControlService.synchronize()
        } else {
            // Login starts LC-05 before the authenticated Matrix client exists. Once the logged-in
            // flow is available, resume that exact operation so the real device-sync path can bind
            // its Matrix authority receipt to the original login operation.
            appOperationalStateCoordinator.resume(pendingActivation.operationId)
            withContext(LifecycleOperationContext(pendingActivation.operationId, FullAppLifecycleId.LC_05)) {
                managementControlService.synchronize()
            }
        }
        Log.i(
            SECURITY_SETUP_DIAGNOSTIC_TAG,
            "YZSETUP_SYNC status=${report.status.name} device_state=${report.deviceSecurityState?.name ?: "NONE"}",
        )
        errorCode = report.errorCode
        retryNotBeforeEpochMillis = report.retryAfterMillis?.let {
            runCatching { Math.addExact(System.currentTimeMillis(), it) }.getOrDefault(Long.MAX_VALUE)
        } ?: 0L
        phase = when (report.status) {
            ManagementSyncStatus.SYNCHRONIZED -> when (report.deviceSecurityState) {
                ManagedDeviceSecurityState.LOCATION_REQUIRED -> SecuritySetupPhase.LOCATION_REQUIRED
                ManagedDeviceSecurityState.VALIDATION_PENDING -> SecuritySetupPhase.VALIDATION_PENDING
                ManagedDeviceSecurityState.MANAGED_APPROVAL_PENDING -> SecuritySetupPhase.MANAGED_APPROVAL_PENDING
                ManagedDeviceSecurityState.READY -> SecuritySetupPhase.READY
                ManagedDeviceSecurityState.ACTION_REQUIRED, null -> SecuritySetupPhase.ACTION_REQUIRED
            }
            ManagementSyncStatus.RETRY_REQUIRED -> SecuritySetupPhase.SERVICE_UNAVAILABLE
            ManagementSyncStatus.DISABLED,
            ManagementSyncStatus.SECURITY_REJECTED -> SecuritySetupPhase.ACTION_REQUIRED
        }
        }
    }

    suspend fun collectAndSubmitLocation() {
        phase = SecuritySetupPhase.LOCATION_SUBMITTING
        val location = currentLocation(context)
        if (location == null) {
            Log.w(SECURITY_SETUP_DIAGNOSTIC_TAG, "YZSETUP_LOCATION_FAILURE=LOCATION_UNAVAILABLE")
            phase = SecuritySetupPhase.LOCATION_UNAVAILABLE
            return
        }
        val capturedAt = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        val evidence = DeviceLocationEvidence(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.coerceAtLeast(1f),
            capturedAtEpochMillis = capturedAt,
            provider = location.provider?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,32}")) } ?: "unknown",
            isMock = LocationCompat.isMock(location),
            timezoneOffsetMinutes = TimeZone.getDefault().getOffset(capturedAt) / 60_000,
        )
        var diagnosticStage = "OPERATION_SELECT"
        phase = runCatching {
            val activation = pendingActivation()
            if (activation == null) {
                diagnosticStage = "OPERATION_START"
                appOperationalStateCoordinator.perform(
                    firstActivationOperationRequest(accountIdHash, matrixDeviceIdHash)
                ) {
                    diagnosticStage = "LOCATION_SUBMIT"
                    managementControlService.submitDeviceLocation(evidence)
                }
            } else {
                diagnosticStage = "OPERATION_RESUME"
                appOperationalStateCoordinator.resume(activation.operationId)
                withContext(LifecycleOperationContext(activation.operationId, FullAppLifecycleId.LC_05)) {
                    diagnosticStage = "LOCATION_SUBMIT"
                    managementControlService.submitDeviceLocation(evidence)
                }
            }
        }.fold(
            onSuccess = { state ->
                Log.i(SECURITY_SETUP_DIAGNOSTIC_TAG, "YZSETUP_LOCATION_RESULT=${state.name}")
                if (state == DeviceLocationEvidenceState.ACCEPTED) {
                    SecuritySetupPhase.VALIDATION_PENDING
                } else {
                    SecuritySetupPhase.LOCATION_REQUIRED
                }
            },
            onFailure = { failure ->
                val failureType = failure.javaClass.simpleName.ifBlank { "AnonymousFailure" }
                Log.w(SECURITY_SETUP_DIAGNOSTIC_TAG, "YZSETUP_LOCATION_FAILURE=$diagnosticStage type=$failureType")
                SecuritySetupPhase.SERVICE_UNAVAILABLE
            },
        )
        if (phase == SecuritySetupPhase.VALIDATION_PENDING) {
            synchronize()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            coroutineScope.launch { collectAndSubmitLocation() }
        } else {
            phase = SecuritySetupPhase.LOCATION_PERMISSION_DENIED
        }
    }

    fun requestLocation() {
        if (context.hasLocationPermission()) {
            coroutineScope.launch { collectAndSubmitLocation() }
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    fun submitApproval() {
        if (phase == SecuritySetupPhase.CHECKING || activationFinalizing || System.currentTimeMillis() < retryNotBeforeEpochMillis) return
        phase = SecuritySetupPhase.CHECKING
        coroutineScope.launch {
            val hasLiveOrReceiptedActivation = pendingActivation() != null || hasSuccessfulFirstActivationReceipt(
                operations = appOperationalStateCoordinator.operations.value.values,
                accountIdHash = accountIdHash,
                matrixDeviceIdHash = matrixDeviceIdHash,
            )
            if (hasLiveOrReceiptedActivation) {
                synchronize()
            } else {
                // Only the user's explicit retry creates a replacement for an expired/failed LC-05.
                // Concurrent taps are suppressed by the CHECKING phase above.
                runCatching {
                    appOperationalStateCoordinator.perform(
                        firstActivationOperationRequest(accountIdHash, matrixDeviceIdHash)
                    ) {
                        managementControlService.synchronize()
                    }
                }.fold(
                    onSuccess = { synchronize() },
                    onFailure = { failure ->
                        if (failure is CancellationException) throw failure
                        phase = failure.toSecuritySetupPhase()
                    },
                )
            }
            if (phase == SecuritySetupPhase.LOCATION_REQUIRED) {
                requestLocation()
            }
        }
    }

    suspend fun refreshRecoveryAction() {
        recoveryKeyAction.value = AsyncAction.Loading
        val result = recoveryKeySubmissionService.refreshPendingRecovery()
        recoveryKeyAction.value = recoveryActionAfterRefresh(
            result, recoveryKeySubmissionService.recoveryState.value, sessionVerificationStatus,
        )
    }

    LaunchedEffect(recoveryCompletionToken, identityResetConvergenceState) {
        if (identityResetConvergenceState != IdentityResetConvergenceState.IDLE) {
            return@LaunchedEffect
        }
        if (recoveryCompletionToken > 0L) {
            if (recoverySessionState.requiresGate && !recoveryKeyAction.value.isLoading()) {
                refreshRecoveryAction()
            }
            phase = SecuritySetupPhase.CHECKING
            userReadyRequested = true
        }
        synchronize()
    }
    val locallyCompleted = remember(context) { context.hasCompletedDeviceCapabilitySetup() }
    val canResumeCompletedSetup = canResumeCompletedSecuritySetup(
        phase = phase,
        locallyCompleted = locallyCompleted,
        localRequirementsLoaded = localRequirementsLoaded,
        sessionVerificationStatus = sessionVerificationStatus,
        lockScreenSetupRequired = lockScreenSetupRequired,
    )
    LaunchedEffect(
        phase,
        canResumeCompletedSetup,
        localRequirementsLoaded,
        lockScreenSetupRequired,
        userReadyRequested,
        sessionVerificationStatus,
        identityResetConvergenceState,
        recoveryKeyAction.value,
        recoveryAwaitingCompletion,
        recoverySessionState,
    ) {
        if (identityResetConvergenceState != IdentityResetConvergenceState.IDLE || recoveryAwaitingCompletion || recoverySessionState.requiresGate) {
            return@LaunchedEffect
        }
        if (phase == SecuritySetupPhase.READY) {
            if (localRequirementsLoaded && !lockScreenSetupRequired && canRestoreOffline()) {
                // Publish authenticated offline CORE before waking session initialization.
                // An immediate dispatcher must not allocate an online startup operation here.
                onOfflineReady()
                onApprovalReady()
                return@LaunchedEffect
            }
            // Approval only starts the restricted session initialization. The unified workbench
            // stays visible until the local safeguards and cryptographic session verification are done.
            onApprovalReady()
            if ((canResumeCompletedSetup || userReadyRequested) && sessionVerificationStatus is SessionVerifiedStatus.Verified) {
                var pendingActivation = pendingActivation()
                var activationAlreadyReceipted = hasSuccessfulFirstActivationReceipt(
                    operations = appOperationalStateCoordinator.operations.value.values,
                    accountIdHash = accountIdHash,
                    matrixDeviceIdHash = matrixDeviceIdHash,
                )
                var activationFailure: Throwable? = null
                if (pendingActivation == null && !activationAlreadyReceipted) {
                    activationFailure = runCatching {
                        appOperationalStateCoordinator.reconcileFirstActivation(accountIdHash, matrixDeviceIdHash)
                    }.exceptionOrNull()?.also { failure ->
                        if (failure is CancellationException) throw failure
                    }
                    if (activationFailure == null) {
                        pendingActivation = pendingActivation()
                        activationAlreadyReceipted = hasSuccessfulFirstActivationReceipt(
                            operations = appOperationalStateCoordinator.operations.value.values,
                            accountIdHash = accountIdHash,
                            matrixDeviceIdHash = matrixDeviceIdHash,
                        )
                    }
                }
                if (
                    activationFailure == null &&
                    pendingActivation == null &&
                    !activationAlreadyReceipted &&
                    (canResumeCompletedSetup || userReadyRequested)
                ) {
                    Log.i(SECURITY_SETUP_DIAGNOSTIC_TAG, "YZSETUP_ACTIVATION_RECONCILING=RECONCILED_NEW_INTENT")
                    activationFailure = runCatching {
                        appOperationalStateCoordinator.perform(
                            firstActivationOperationRequest(accountIdHash, matrixDeviceIdHash)
                        ) {
                            managementControlService.synchronize()
                        }
                    }.exceptionOrNull()?.also { failure ->
                        if (failure is CancellationException) throw failure
                    }
                    pendingActivation = pendingActivation()
                    activationAlreadyReceipted = hasSuccessfulFirstActivationReceipt(
                        operations = appOperationalStateCoordinator.operations.value.values,
                        accountIdHash = accountIdHash,
                        matrixDeviceIdHash = matrixDeviceIdHash,
                    )
                }
                activationFinalizing = true
                val activationDiagnostic = pendingActivation?.operationId
                    ?: if (activationAlreadyReceipted) "ALREADY_RECEIPTED" else "MISSING_RECEIPT"
                Log.i(
                    SECURITY_SETUP_DIAGNOSTIC_TAG,
                    "YZSETUP_ACTIVATION_FINALIZING operation=$activationDiagnostic",
                )
                val activationToFinalize = pendingActivation
                activationFailure = activationFailure ?: if (activationToFinalize == null) {
                    if (activationAlreadyReceipted) null else IllegalStateException("No valid LC-05 receipt is available.")
                } else {
                    try {
                    appOperationalStateCoordinator.resume(activationToFinalize.operationId)
                    withContext(LifecycleOperationContext(activationToFinalize.operationId, FullAppLifecycleId.LC_05)) {
                        // Continue the already-authorized LC-05 operation through the standard
                        // Android receipt path. Calling the product action directly leaves the
                        // ANDROID_SERVICE and ANDROID_FRONTEND steps permanently pending even
                        // after Matrix, control-plane, database, and KMS have all committed.
                        appOperationalStateCoordinator.perform(
                            firstActivationOperationRequest(accountIdHash, matrixDeviceIdHash)
                        ) {
                            val syncReport = managementControlService.synchronize()
                            check(syncReport.status == ManagementSyncStatus.SYNCHRONIZED) {
                                "Matrix authority verification did not synchronize."
                            }
                            check(syncReport.deviceSecurityState == ManagedDeviceSecurityState.READY) {
                                "Device security is not ready for KMS activation."
                            }
                            val proposedKey = ByteArray(PRIVATE_PROFILE_KEY_BYTES).also(SecureRandom()::nextBytes)
                            var acceptedKey: ByteArray? = null
                            try {
                                acceptedKey = kmsShareClient.ensurePrivateProfileKey(accountIdHash, proposedKey).keyMaterial
                            } finally {
                                proposedKey.fill(0)
                                acceptedKey?.fill(0)
                            }
                        }
                    }
                    val operation = appOperationalStateCoordinator.resume(activationToFinalize.operationId)
                    check(operation.status == LifecycleOperationStatus.SUCCEEDED && operation.terminalReceiptId != null) {
                        "LC-05 successful authority receipts are still pending."
                    }
                    null
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    failure
                }
                }
                if (activationFailure == null) {
                    Log.i(SECURITY_SETUP_DIAGNOSTIC_TAG, "YZSETUP_ACTIVATION_COMPLETE")
                    // Session verification can complete while the verification child is on top of
                    // this workbench. In that path the footer action is never clicked, so persist
                    // the same local completion marker here before refreshing FTUE/navigation.
                    // The authority operation has already reached its signed terminal receipt;
                    // this marker only releases the local UI gate.
                    context.markDeviceCapabilitySetupCompleted()
                    onReady()
                } else {
                    val nextPhase = activationFailure.toSecuritySetupPhase()
                    Log.w(
                        SECURITY_SETUP_DIAGNOSTIC_TAG,
                        "YZSETUP_ACTIVATION_FAILURE=${nextPhase.diagnosticReasonCode()}",
                    )
                    activationFinalizing = false
                    phase = nextPhase
                    userReadyRequested = false
                }
            }
            return@LaunchedEffect
        }
        if (phase == SecuritySetupPhase.SERVICE_UNAVAILABLE) {
            for ((retryIndex, retryDelayMillis) in SECURITY_SETUP_AUTO_RETRY_DELAYS_MILLIS.withIndex()) {
                delay(maxOf(retryDelayMillis, retryNotBeforeEpochMillis - System.currentTimeMillis()))
                if (!isActive || phase != SecuritySetupPhase.SERVICE_UNAVAILABLE) {
                    return@LaunchedEffect
                }
                Log.i(
                    SECURITY_SETUP_DIAGNOSTIC_TAG,
                    "YZSETUP_AUTO_RETRY attempt=${retryIndex + 1}/${SECURITY_SETUP_AUTO_RETRY_DELAYS_MILLIS.size}",
                )
                try {
                    synchronize()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    Log.w(
                        SECURITY_SETUP_DIAGNOSTIC_TAG,
                        "YZSETUP_AUTO_RETRY_FAILURE attempt=${retryIndex + 1} type=${failure.javaClass.simpleName}",
                    )
                }
                if (phase != SecuritySetupPhase.SERVICE_UNAVAILABLE) {
                    return@LaunchedEffect
                }
            }
            while (isActive && phase == SecuritySetupPhase.SERVICE_UNAVAILABLE) {
                delay(maxOf(30_000L, retryNotBeforeEpochMillis - System.currentTimeMillis()))
                synchronize()
            }
            return@LaunchedEffect
        }
        while (isActive && phase.isApprovalPending()) {
            delay(10_000)
            synchronize()
        }
    }

    val identityConfirmationRequired = recoveryAwaitingCompletion || recoveryNeedsUserAction(recoverySessionState) || phase in setOf(
        SecuritySetupPhase.RECOVERY_METHOD_REQUIRED,
        SecuritySetupPhase.RECOVERY_EXPIRED,
    ) || (phase == SecuritySetupPhase.READY && sessionVerificationStatus is SessionVerifiedStatus.NotVerified)

    LaunchedEffect(
        phase,
        sessionVerificationStatus,
        lockScreenSetupRequired,
        localRequirementsLoaded,
        identityResetConvergenceState,
    ) {
        if (identityResetConvergenceState == IdentityResetConvergenceState.IDLE &&
            phase == SecuritySetupPhase.READY &&
            sessionVerificationStatus is SessionVerifiedStatus.Verified &&
            !lockScreenSetupRequired &&
            localRequirementsLoaded
        ) {
            // Device capabilities and analytics are LC-03/optional preferences. They may limit only
            // their own features and must not become a second LC-05/LC-06 readiness gate.
            userReadyRequested = true
        }
    }

    LaunchedEffect(recoveryKeyAction.value) {
        if (recoveryKeyAction.value is AsyncAction.Success) {
            recoveryAwaitingCompletion = false
            recoveryKey = ""
            displayRecoveryKey = false
            phase = SecuritySetupPhase.CHECKING
            userReadyRequested = true
            synchronize()
        }
    }

    val eventSink: (SecuritySetupEvent) -> Unit = { event ->
        when (event) {
            SecuritySetupEvent.Submit -> submitApproval()
            SecuritySetupEvent.CollectLocation -> {
                if (phase == SecuritySetupPhase.LOCATION_PERMISSION_DENIED) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                    phase = SecuritySetupPhase.LOCATION_REQUIRED
                } else {
                    requestLocation()
                }
            }
            SecuritySetupEvent.Retry -> {
                if (identityResetConvergenceState == IdentityResetConvergenceState.RETRY_REQUIRED) {
                    onRetryIdentityReset()
                } else {
                    submitApproval()
                }
            }
            SecuritySetupEvent.UseAnotherDevice -> {
                if (!recoveryKeyAction.value.isLoading()) {
                    // Explicitly switch recovery methods; retain the original operation facts.
                    recoveryAwaitingCompletion = false
                    onUseAnotherDevice()
                }
            }
            is SecuritySetupEvent.RecoveryKeyChanged -> {
                recoveryKey = event.recoveryKey
                if (recoveryKeyAction.value is AsyncAction.Failure) {
                    recoveryKeyAction.value = AsyncAction.Uninitialized
                }
            }
            SecuritySetupEvent.ToggleRecoveryKeyVisibility -> displayRecoveryKey = !displayRecoveryKey
            SecuritySetupEvent.SubmitRecoveryKey -> {
                if (recoveryKey.isNotBlank() && recoveryKeyAction.value.isUninitialized()) {
                    recoveryAwaitingCompletion = true
                    coroutineScope.launch {
                        suspend {
                            recoveryKeySubmissionService.recover(recoveryKey).getOrThrow()
                        }.runCatchingUpdatingState(recoveryKeyAction)
                    }
                }
            }
            SecuritySetupEvent.RefreshRecovery -> {
                if (!recoveryKeyAction.value.isLoading()) coroutineScope.launch {
                    refreshRecoveryAction()
                }
            }
            SecuritySetupEvent.DismissRecoveryKeyError -> recoveryKeyAction.value = AsyncAction.Uninitialized
            SecuritySetupEvent.ResetIdentity -> onResetIdentity()
        }
    }

    if (identityResetConvergenceState == IdentityResetConvergenceState.RUNNING) {
        StartupWaitingView(modifier)
    } else if (recoverySessionState is RecoverySessionState.Discovering &&
        (phase == SecuritySetupPhase.CHECKING || phase == SecuritySetupPhase.READY) &&
        identityResetConvergenceState == IdentityResetConvergenceState.IDLE
    ) {
        // Discovery still blocks HOME, but is not evidence that identity recovery is required.
        StartupWaitingView(modifier)
    } else if (identityResetConvergenceState == IdentityResetConvergenceState.RETRY_REQUIRED) {
        SecuritySetupView(
            state = SecuritySetupState(
                phase = SecuritySetupPhase.SERVICE_UNAVAILABLE,
                eventSink = eventSink,
            ),
            modifier = modifier.revealStartupSplashWhenLaidOut(),
        )
    } else if (identityConfirmationRequired || phase == SecuritySetupPhase.SECURITY_BLOCKED) {
        SecuritySetupView(
            state = SecuritySetupState(
                phase = phase,
                identityConfirmationRequired = identityConfirmationRequired,
                recoveryKey = recoveryKey,
                displayRecoveryKey = displayRecoveryKey,
                recoveryKeyAction = recoveryKeyAction.value,
                recoverySessionState = recoverySessionState,
                eventSink = eventSink,
            ),
            modifier = modifier.revealStartupSplashWhenLaidOut(),
        )
    } else if (activationFinalizing || shouldShowStartupWaiting(
            phase = phase,
            locallyCompleted = locallyCompleted,
            localRequirementsLoaded = localRequirementsLoaded,
            canResumeCompletedSetup = canResumeCompletedSetup,
        )
    ) {
        // A returning, already-configured user must not briefly see the setup/onboarding page
        // while the remote management state is refreshed or the navigation callback is committed.
        StartupWaitingView(modifier)
    } else {
        if (phase == SecuritySetupPhase.READY && localRequirementsLoaded && lockScreenSetupRequired) {
            LaunchedEffect(Unit) { onOpenLockScreenSetup() }
            StartupWaitingView(modifier.revealStartupSplashWhenLaidOut())
        } else {
            SecuritySetupView(
                state = SecuritySetupState(
                    phase = phase,
                    errorCode = errorCode,
                    identityConfirmationRequired = identityConfirmationRequired,
                    recoveryKey = recoveryKey,
                    displayRecoveryKey = displayRecoveryKey,
                    recoveryKeyAction = recoveryKeyAction.value,
                recoverySessionState = recoverySessionState,
                    eventSink = eventSink,
                ),
                modifier = modifier.revealStartupSplashWhenLaidOut(),
            )
        }
    }
}

/** Unknown recovery state blocks entry without exposing a recovery-key form. */
internal fun recoveryNeedsUserAction(state: RecoverySessionState): Boolean = when (state) {
    RecoverySessionState.Discovering, RecoverySessionState.None -> false
    is RecoverySessionState.Pending, is RecoverySessionState.Waiting, is RecoverySessionState.Rejected -> true
}

private const val PRIVATE_PROFILE_KEY_BYTES = 32
private const val SECURITY_SETUP_DIAGNOSTIC_TAG = "YiZheSecuritySetup"
internal val SECURITY_SETUP_AUTO_RETRY_DELAYS_MILLIS = listOf(2_000L, 4_000L, 8_000L)

// v2 retries the one-time reconciliation for development builds that ran before LC-05 operation
// identifiers were actually injected into persistent storage. Authority receipts remain mandatory.
private const val SECURITY_SETUP_RECOVERY_PREFERENCES = "security_setup_recovery_v2"

private fun Context.claimLegacyFirstActivationReconciliation(
    accountIdHash: String,
    matrixDeviceIdHash: String,
): Boolean {
    val preferences = getSharedPreferences(SECURITY_SETUP_RECOVERY_PREFERENCES, Context.MODE_PRIVATE)
    val key = "lc05_${accountIdHash}_$matrixDeviceIdHash"
    if (preferences.getBoolean(key, false)) return false
    preferences.edit().putBoolean(key, true).apply()
    return true
}

/**
 * Select only the newest first-activation operation before checking whether it is pending.
 *
 * Older interrupted login attempts can remain recoverable on the server. Once a newer attempt has
 * reached its signed terminal receipt, falling back to an older non-terminal operation strands the
 * current verified session on the setup screen.
 */
internal fun selectPendingFirstActivation(
    operations: Collection<LifecycleOperation>,
    accountIdHash: String,
    matrixDeviceIdHash: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
): LifecycleOperation? {
    val latestActivation = selectLatestFirstActivation(operations, accountIdHash, matrixDeviceIdHash)
    return latestActivation?.takeUnless { it.status.isTerminal || it.deadlineEpochMillis <= nowEpochMillis }
}

private fun selectLatestFirstActivation(
    operations: Collection<LifecycleOperation>,
    accountIdHash: String,
    matrixDeviceIdHash: String,
): LifecycleOperation? {
    val firstActivations = operations.filter { it.lifecycleId == FullAppLifecycleId.LC_05 }
    val currentSessionActivations = firstActivations.filter {
        it.subjects.accountIdHash == accountIdHash && it.subjects.matrixDeviceIdHash == matrixDeviceIdHash
    }
    val candidates = currentSessionActivations.ifEmpty {
        // The login entry creates LC-05 before a Matrix session exists, so the newest current login
        // is temporarily unbound. Once synchronization binds an operation, unrelated/unbound stale
        // attempts must no longer participate in selection.
        firstActivations.filter {
            it.subjects.accountIdHash == null && it.subjects.matrixDeviceIdHash == null
        }
    }
    return candidates.maxWithOrNull(
        compareBy<LifecycleOperation> { it.createdAtEpochMillis }.thenBy { it.updatedAtEpochMillis }
    )
}

internal fun hasSuccessfulFirstActivationReceipt(
    operations: Collection<LifecycleOperation>,
    accountIdHash: String,
    matrixDeviceIdHash: String,
): Boolean = selectLatestFirstActivation(operations, accountIdHash, matrixDeviceIdHash)?.let { operation ->
    operation.status == LifecycleOperationStatus.SUCCEEDED && operation.terminalReceiptId != null
} == true

internal fun canResumeCompletedSecuritySetup(
    phase: SecuritySetupPhase,
    locallyCompleted: Boolean,
    localRequirementsLoaded: Boolean,
    sessionVerificationStatus: SessionVerifiedStatus,
    lockScreenSetupRequired: Boolean,
): Boolean = phase == SecuritySetupPhase.READY &&
    locallyCompleted &&
    localRequirementsLoaded &&
    sessionVerificationStatus is SessionVerifiedStatus.Verified &&
    !lockScreenSetupRequired

internal fun shouldShowStartupWaiting(
    phase: SecuritySetupPhase,
    locallyCompleted: Boolean,
    localRequirementsLoaded: Boolean,
    canResumeCompletedSetup: Boolean,
): Boolean = phase == SecuritySetupPhase.CHECKING ||
    canResumeCompletedSetup ||
    (phase == SecuritySetupPhase.READY && locallyCompleted && !localRequirementsLoaded)

@Composable
fun SecuritySetupView(
    state: SecuritySetupState,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = true) {}
    if (state.identityConfirmationRequired) {
        IdentityConfirmationView(state = state, modifier = modifier)
        return
    }
    val progress = state.phase.toSecurityProgress()
    val validationPending = state.phase.isApprovalPending()
    HeaderFooterPage(
        modifier = modifier.statusBarsPadding().fillMaxSize(),
        background = { OnboardingBackground() },
        header = {
            IconTitleSubtitleMolecule(
                modifier = Modifier.padding(top = 48.dp, bottom = 24.dp),
                iconStyle = BigIcon.Style.Default(CompoundIcons.Shield()),
                title = stringResource(
                    if (validationPending) {
                        R.string.screen_security_setup_submitted_title
                    } else {
                        R.string.screen_security_setup_title
                    }
                ),
                subTitle = stringResource(
                    if (validationPending) {
                        R.string.screen_security_setup_submitted_subtitle
                    } else {
                        R.string.screen_security_setup_subtitle
                    }
                ),
            )
        },
        footer = {
            ButtonColumnMolecule {
                when (state.phase) {
                    SecuritySetupPhase.LOCATION_REQUIRED,
                    SecuritySetupPhase.LOCATION_PERMISSION_DENIED,
                    SecuritySetupPhase.LOCATION_UNAVAILABLE -> Button(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(
                            if (state.phase == SecuritySetupPhase.LOCATION_PERMISSION_DENIED) {
                                R.string.screen_security_setup_open_settings
                            } else {
                                R.string.screen_security_setup_collect_location
                            }
                        ),
                        onClick = { state.eventSink(SecuritySetupEvent.CollectLocation) },
                    )
                    SecuritySetupPhase.ACTION_REQUIRED,
                    SecuritySetupPhase.SERVICE_UNAVAILABLE -> Button(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.screen_security_setup_retry),
                        onClick = { state.eventSink(SecuritySetupEvent.Retry) },
                    )
                    SecuritySetupPhase.RECOVERY_METHOD_REQUIRED,
                    SecuritySetupPhase.RECOVERY_EXPIRED -> Unit
                    else -> Unit
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SecurityProgress(
                states = progress,
                labels = listOf(
                    stringResource(R.string.screen_security_setup_step_identity),
                    stringResource(R.string.screen_security_setup_step_device_info),
                    stringResource(R.string.screen_security_setup_step_validation),
                ),
            )
            if (state.phase in setOf(
                    SecuritySetupPhase.LOCATION_REQUIRED,
                    SecuritySetupPhase.LOCATION_SUBMITTING,
                    SecuritySetupPhase.LOCATION_PERMISSION_DENIED,
                    SecuritySetupPhase.LOCATION_UNAVAILABLE,
                )
            ) {
                Text(text = stringResource(R.string.screen_security_setup_location_explanation))
            }
            when (state.phase) {
                SecuritySetupPhase.NOT_SUBMITTED -> Unit
                SecuritySetupPhase.CHECKING,
                SecuritySetupPhase.LOCATION_SUBMITTING -> LoadingStatus()
                SecuritySetupPhase.LOCATION_PERMISSION_DENIED -> Text(stringResource(R.string.screen_security_setup_location_permission_denied))
                SecuritySetupPhase.LOCATION_UNAVAILABLE -> Text(stringResource(R.string.screen_security_setup_location_unavailable))
                SecuritySetupPhase.VALIDATION_PENDING -> Text(stringResource(R.string.screen_security_setup_validation_pending))
                SecuritySetupPhase.MANAGED_APPROVAL_PENDING -> Text(stringResource(R.string.screen_entry_compatibility_preparing))
                SecuritySetupPhase.READY -> Text(stringResource(R.string.screen_security_setup_validation_complete))
                SecuritySetupPhase.ACTION_REQUIRED -> Text(stringResource(
                    when (state.errorCode) {
                    "COMPATIBILITY_POLICY_DISABLED" -> R.string.screen_entry_compatibility_policy_description
                    "COMPATIBILITY_EVIDENCE_REJECTED" -> R.string.screen_entry_compatibility_evidence_description
                    "COMPATIBILITY_BINDING_CONFLICT" -> R.string.screen_entry_compatibility_binding_description
                    else -> R.string.screen_security_setup_action_required
                }
                ))
                SecuritySetupPhase.SERVICE_UNAVAILABLE -> Text(stringResource(R.string.screen_security_setup_service_unavailable))
                SecuritySetupPhase.RECOVERY_METHOD_REQUIRED -> Text(stringResource(R.string.screen_security_setup_recovery_required))
                SecuritySetupPhase.RECOVERY_EXPIRED -> Text(stringResource(R.string.screen_security_setup_recovery_expired))
                SecuritySetupPhase.SECURITY_BLOCKED -> Text(stringResource(R.string.screen_security_setup_security_blocked))
                SecuritySetupPhase.LOCATION_REQUIRED -> Unit
            }
        }
    }
}

@Composable
private fun IdentityConfirmationView(
    state: SecuritySetupState,
    modifier: Modifier = Modifier,
) {
    val submitting = state.recoveryKeyAction.isLoading()
    HeaderFooterPage(
        modifier = modifier.statusBarsPadding().fillMaxSize(),
        background = { OnboardingBackground() },
        header = {
            IconTitleSubtitleMolecule(
                modifier = Modifier.padding(top = 48.dp, bottom = 20.dp),
                iconStyle = BigIcon.Style.Default(CompoundIcons.LockSolid()),
                title = stringResource(R.string.screen_security_identity_title),
                subTitle = stringResource(R.string.screen_security_identity_subtitle),
            )
        },
        footer = {
            ButtonColumnMolecule {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.screen_security_identity_submit_recovery_key),
                    enabled = state.recoveryKey.isNotBlank() && state.recoveryKeyAction.isUninitialized(),
                    showProgress = submitting,
                    onClick = { state.eventSink(SecuritySetupEvent.SubmitRecoveryKey) },
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.screen_security_identity_use_other_device),
                    enabled = !submitting,
                    onClick = { state.eventSink(SecuritySetupEvent.UseAnotherDevice) },
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.screen_security_identity_cannot_confirm),
                    enabled = !submitting,
                    onClick = { state.eventSink(SecuritySetupEvent.ResetIdentity) },
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.phase == SecuritySetupPhase.RECOVERY_EXPIRED) {
                Text(
                    text = stringResource(R.string.screen_security_setup_recovery_expired),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Password },
                minLines = 2,
                value = state.recoveryKey,
                onValueChange = { state.eventSink(SecuritySetupEvent.RecoveryKeyChanged(it)) },
                enabled = !submitting,
                visualTransformation = if (state.displayRecoveryKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { state.eventSink(SecuritySetupEvent.SubmitRecoveryKey) },
                ),
                placeholder = stringResource(R.string.screen_security_identity_recovery_key_placeholder),
                trailingIcon = {
                    val icon = if (state.displayRecoveryKey) {
                        CompoundIcons.VisibilityOn()
                    } else {
                        CompoundIcons.VisibilityOff()
                    }
                    val description = stringResource(
                        if (state.displayRecoveryKey) CommonStrings.a11y_hide_password else CommonStrings.a11y_show_password
                    )
                    Icon(
                        imageVector = icon,
                        contentDescription = description,
                        modifier = Modifier.clickable {
                            state.eventSink(SecuritySetupEvent.ToggleRecoveryKeyVisibility)
                        },
                    )
                },
            )
            if (state.recoverySessionState is RecoverySessionState.Waiting || state.recoverySessionState is RecoverySessionState.Pending ||
                state.recoverySessionState is RecoverySessionState.Rejected) {
                if (state.recoverySessionState is RecoverySessionState.Waiting) {
                    Text(stringResource(R.string.screen_security_recovery_waiting))
                }
                androidx.compose.material3.TextButton(onClick = { state.eventSink(SecuritySetupEvent.RefreshRecovery) },
                    enabled = !state.recoveryKeyAction.isLoading()) {
                    Text(stringResource(R.string.screen_security_recovery_refresh))
                }
            }
            if (state.recoverySessionState is RecoverySessionState.Rejected && state.recoveryKeyAction !is AsyncAction.Failure) {
                val reason = (state.recoverySessionState as RecoverySessionState.Rejected).reasonCode
                Text(stringResource(recoveryRejectionMessage(reason)), color = MaterialTheme.colorScheme.error)
            }
            if (state.recoveryKeyAction is AsyncAction.Failure &&
                (state.recoveryKeyAction as AsyncAction.Failure).error !is ContentProtectionException.DeviceRecoveryWaiting) {
                Text(
                    text = stringResource(recoveryFailureMessage((state.recoveryKeyAction as AsyncAction.Failure).error)),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(R.string.screen_security_identity_reset_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal enum class SecurityProgressState {
    COMPLETE,
    CURRENT,
    PENDING,
    ERROR,
}

internal fun SecuritySetupPhase.toSecurityProgress(): List<SecurityProgressState> {
    val locationComplete = this in setOf(
        SecuritySetupPhase.VALIDATION_PENDING,
        SecuritySetupPhase.MANAGED_APPROVAL_PENDING,
        SecuritySetupPhase.READY,
        SecuritySetupPhase.ACTION_REQUIRED,
        SecuritySetupPhase.SERVICE_UNAVAILABLE,
        SecuritySetupPhase.RECOVERY_METHOD_REQUIRED,
        SecuritySetupPhase.RECOVERY_EXPIRED,
        SecuritySetupPhase.SECURITY_BLOCKED,
    )
    return listOf(
        SecurityProgressState.COMPLETE,
        when {
            locationComplete -> SecurityProgressState.COMPLETE
            this in setOf(
                SecuritySetupPhase.LOCATION_PERMISSION_DENIED,
                SecuritySetupPhase.LOCATION_UNAVAILABLE,
            ) -> SecurityProgressState.ERROR
            else -> SecurityProgressState.CURRENT
        },
        when {
            this == SecuritySetupPhase.READY -> SecurityProgressState.COMPLETE
            this in setOf(
                SecuritySetupPhase.ACTION_REQUIRED,
                SecuritySetupPhase.RECOVERY_METHOD_REQUIRED,
                SecuritySetupPhase.RECOVERY_EXPIRED,
                SecuritySetupPhase.SECURITY_BLOCKED,
            ) -> SecurityProgressState.ERROR
            locationComplete -> SecurityProgressState.CURRENT
            else -> SecurityProgressState.PENDING
        },
    )
}

private fun SecuritySetupPhase.isApprovalPending(): Boolean = this in setOf(
    SecuritySetupPhase.VALIDATION_PENDING,
    SecuritySetupPhase.MANAGED_APPROVAL_PENDING,
)

internal suspend fun runSecuritySetupSynchronization(
    onFailure: (Exception) -> Unit,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        onFailure(failure)
    }
}

internal fun Throwable.toSecuritySetupPhase(): SecuritySetupPhase = when (this) {
    is ContentProtectionException.DeviceRecoveryRequired -> SecuritySetupPhase.RECOVERY_METHOD_REQUIRED
    is ContentProtectionException.Expired -> SecuritySetupPhase.RECOVERY_EXPIRED
    is ContentProtectionException.AccessDenied,
    is ContentProtectionException.AuthenticationFailed,
    is ContentProtectionException.Revoked -> SecuritySetupPhase.SECURITY_BLOCKED
    else -> SecuritySetupPhase.SERVICE_UNAVAILABLE
}

private fun SecuritySetupPhase.diagnosticReasonCode(): String = when (this) {
    SecuritySetupPhase.RECOVERY_METHOD_REQUIRED -> "DEVICE_RECOVERY_REQUIRED"
    SecuritySetupPhase.RECOVERY_EXPIRED -> "DEVICE_RECOVERY_EXPIRED"
    SecuritySetupPhase.SECURITY_BLOCKED -> "SECURITY_REJECTED"
    else -> "AUTHORITY_RECEIPTS"
}

@Composable
internal fun SecurityProgress(
    states: List<SecurityProgressState>,
    labels: List<String>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val state = states[index]
            val backgroundColor = when (state) {
                SecurityProgressState.COMPLETE -> MaterialTheme.colorScheme.primary
                SecurityProgressState.CURRENT -> MaterialTheme.colorScheme.primaryContainer
                SecurityProgressState.ERROR -> MaterialTheme.colorScheme.errorContainer
                SecurityProgressState.PENDING -> MaterialTheme.colorScheme.surfaceVariant
            }
            val contentColor = when (state) {
                SecurityProgressState.COMPLETE -> MaterialTheme.colorScheme.onPrimary
                SecurityProgressState.CURRENT -> MaterialTheme.colorScheme.onPrimaryContainer
                SecurityProgressState.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                SecurityProgressState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(backgroundColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state == SecurityProgressState.COMPLETE) "✓" else "${index + 1}",
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
                Text(
                    text = label,
                    color = if (state == SecurityProgressState.PENDING) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        Color.Unspecified
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (state == SecurityProgressState.CURRENT) FontWeight.SemiBold else FontWeight.Normal
                    ),
                )
            }
        }
    }
}

@Composable
private fun LoadingStatus() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(stringResource(R.string.screen_security_setup_checking))
    }
}

private fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? = withTimeoutOrNull(15_000) {
    val manager = context.getSystemService(LocationManager::class.java)
    val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { LocationManagerCompat.hasProvider(manager, it) && manager.isProviderEnabled(it) }
        ?: return@withTimeoutOrNull null
    suspendCancellableCoroutine { continuation ->
        val cancellationSignal = CancellationSignal()
        continuation.invokeOnCancellation { cancellationSignal.cancel() }
        LocationManagerCompat.getCurrentLocation(
            manager,
            provider,
            cancellationSignal,
            ContextCompat.getMainExecutor(context),
        ) { location ->
            if (continuation.isActive) continuation.resume(location ?: manager.getLastKnownLocation(provider))
        }
    }
}

private class SecuritySetupStateProvider : PreviewParameterProvider<SecuritySetupState> {
    override val values = sequenceOf(
        SecuritySetupState(SecuritySetupPhase.LOCATION_REQUIRED) {},
        SecuritySetupState(SecuritySetupPhase.VALIDATION_PENDING) {},
        SecuritySetupState(SecuritySetupPhase.SERVICE_UNAVAILABLE) {},
    )
}

@PreviewsDayNight
@Composable
private fun SecuritySetupViewPreview(
    @PreviewParameter(SecuritySetupStateProvider::class) state: SecuritySetupState,
) = ElementPreview {
    SecuritySetupView(state)
}
