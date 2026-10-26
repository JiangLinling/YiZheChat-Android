/*

 * Copyright (c) 2026 YiZheChat contributors.

 *

 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.

 * Please see LICENSE files in the repository root for full details.

 */

package io.element.android.x.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.repeatOnLifecycle
import io.element.android.appnav.StartupWaitingView
import io.element.android.appnav.revealStartupSplashWhenLaidOut
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.androidutils.startup.StartupTrace
import io.element.android.libraries.designsystem.atomic.pages.FlowStepPage
import io.element.android.libraries.designsystem.components.BigIcon
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.LinearProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.AppUpdateDownloadProgress
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.ManagedAppRelease
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.ManagementSecurityRefreshService
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.PreparedAppUpdate
import io.element.android.security.dataprotection.api.StartupSecurityReadiness
import io.element.android.security.dataprotection.api.StartupSecurityReadinessState
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.x.BuildConfig
import io.element.android.x.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun AppUpdateGate(
    managementControlService: ManagementControlService,
    securityRefreshService: ManagementSecurityRefreshService,
    appOperationalStateCoordinator: AppOperationalStateCoordinator,
    networkMonitor: io.element.android.features.networkmonitor.api.NetworkMonitor? = null,
    modifier: Modifier = Modifier,
    onBlockingState: (Boolean) -> Unit = {},
    allowRetainedStartup: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Keep the existing navigation saver while the protected composition is absent.
    // Re-entry still rebuilds the root node and checks the current authoritative session.
    val authorizedContentState = rememberSaveableStateHolder()
    val activity = requireNotNull(LocalActivity.current)

    val scope = rememberCoroutineScope()

    var checkAttempt by remember { mutableIntStateOf(0) }

    var state by remember { mutableStateOf<AppUpdateGateState>(AppUpdateGateState.Checking) }

    val restriction by securityRefreshService.localEntryRestriction.collectAsState(initial = null)

    val isOffline by securityRefreshService.entryIsOffline.collectAsState(initial = false)

    var retryNotBefore by remember { mutableStateOf<Long?>(null) }

    var checkRunning by remember { mutableStateOf(false) }

    var handoverRunning by remember { mutableStateOf(false) }

    var networkDisconnected by remember { mutableStateOf(false) }

    var offlineGrantValidated by remember { mutableStateOf(false) }

    var retainedEntryPermitted by remember { mutableStateOf(false) }

    var automaticCandidateAttempted by remember { mutableStateOf(false) }

    fun admitCandidateInstallation() {
                if (!handoverRunning && !checkRunning) {
                    handoverRunning = true
                    scope.launch {
                        try {
                            val subjects = checkNotNull(managementControlService.managedAuthorizationHandoverSubjects()) {
                                "RECOVERY_MATRIX_SESSION_UNAVAILABLE"
                            }
                            val accountHash = checkNotNull(subjects.accountIdHash)
                            val matrixHash = checkNotNull(subjects.matrixDeviceIdHash)
                            appOperationalStateCoordinator.performResuming(
                                lifecycleOperationRequest(
                                    lifecycleId = FullAppLifecycleId.LC_06,
                                    trigger = "reactivation",
                                    accountIdHash = accountHash,
                                    matrixDeviceIdHash = matrixHash,
                                )
                            ) {
                                managementControlService.transferManagedDeviceAuthorization(accountHash, matrixHash)
                            }
                            // Validate the signed license and refresh the real restriction. Never open content from HTTP success.
                            securityRefreshService.refreshSecurityAccess()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            state = AppUpdateGateState.CheckFailed(
                                startupGateAccessibilityContract(io.element.android.x.security.startupFailureReasonCode(failure))
                            )
                        } finally {
                            handoverRunning = false
                            checkAttempt++
                        }
                    }
                }
    }

    LaunchedEffect(state, checkRunning) {
        val failed = state as? AppUpdateGateState.CheckFailed ?: return@LaunchedEffect
        if (!automaticCandidateAttempted && !checkRunning &&
            failed.accessibility.primaryAction == StartupGatePrimaryAction.TRANSFER_MANAGED_AUTHORIZATION
        ) {
            // The server only admits this candidate. It does not retire the old
            // mobile or release account content; account cutover stays separate.
            automaticCandidateAttempted = true
            admitCandidateInstallation()
        }
    }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(state, restriction, allowRetainedStartup, networkDisconnected, isOffline) {
        retainedEntryPermitted = false
        if (!allowRetainedStartup || state != AppUpdateGateState.Checking || restriction != null || networkDisconnected || isOffline) return@LaunchedEffect
        try {
            val release = managementControlService.cachedAppRelease()
            val manifest = managementControlService.hasValidCachedAppReleaseManifest()
            if (!canPrepareRetainedAppContent(BuildConfig.DEBUG, manifest, release)) return@LaunchedEffect
            while (isActive) {
                val permitted = managementControlService.localListEntryStatus() != null
                if (permitted && !retainedEntryPermitted) StartupTrace.mark("Startup.retainedAppContent.ready")
                retainedEntryPermitted = permitted
                if (!retainedEntryPermitted) break
                delay(500L)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            retainedEntryPermitted = false
        }
    }

    LaunchedEffect(restriction) {
        restriction?.takeIf { state !is AppUpdateGateState.Required }?.let {
            state = AppUpdateGateState.CheckFailed(startupGateAccessibilityContract(it))
        }

        if (restriction == null && state is AppUpdateGateState.CheckFailed) checkAttempt++
    }

    LaunchedEffect(isOffline, networkDisconnected, state) {
        if ((isOffline || networkDisconnected) && state == AppUpdateGateState.Allowed) {
            while (isActive) {
                val entry = managementControlService.offlineEntryStatus()

                offlineGrantValidated = entry != null

                if (entry == null) {
                    state = AppUpdateGateState.CheckFailed(startupGateAccessibilityContract("OFFLINE_ENTRY_EXPIRED"))

                    break
                }

                delay(minOf(entry.remainingMillis, 1_000L).coerceAtLeast(1L))
            }
        }
    }

    LaunchedEffect(state, lifecycle) {
        val failed = state as? AppUpdateGateState.CheckFailed ?: return@LaunchedEffect

        if (!shouldAutomaticallyRetryStartup(failed.accessibility.reasonCode)) return@LaunchedEffect

        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            var attempt = 0

            while (isActive) {
                val retryDelay = startupRetryDelayMillis(attempt)

                delay(maxOf(retryDelay, (retryNotBefore ?: 0L) - System.currentTimeMillis()))

                if (!checkRunning) {
                    attempt++

                    checkAttempt++
                }
            }
        }
    }

    LaunchedEffect(networkMonitor) {
        networkMonitor?.connectivity?.collect { connectivity ->

            if (connectivity == io.element.android.features.networkmonitor.api.NetworkStatus.Disconnected) {
                offlineGrantValidated = false

                securityRefreshService.onNetworkUnavailable()
            }

            networkDisconnected = connectivity != io.element.android.features.networkmonitor.api.NetworkStatus.Connected

            if (connectivity == io.element.android.features.networkmonitor.api.NetworkStatus.Connected &&

                !checkRunning && state is AppUpdateGateState.CheckFailed && System.currentTimeMillis() >= (retryNotBefore ?: 0L)

            ) {
                checkAttempt++
            }
        }
    }

    LaunchedEffect(checkAttempt) {
        checkRunning = true

        try {
        val startupDeadlineEpochMillis = System.currentTimeMillis() + STARTUP_TOTAL_TIMEOUT_MILLIS

        StartupTrace.mark("Startup.update.check.begin")

        val cachedRelease = runCatching { managementControlService.cachedAppRelease() }.getOrNull()

        val hasValidCachedManifest = runCatching {
            managementControlService.hasValidCachedAppReleaseManifest()
        }.getOrDefault(false)

        val readiness = securityRefreshService.awaitStartupReadiness(startupDeadlineEpochMillis)

        StartupTrace.mark("Startup.update.deviceReadiness.returned")

        retryNotBefore = readiness.retryAtEpochMillis

        val release: Result<ManagedAppRelease?>? = if (readiness.canStartLc01()) {
            if (shouldBypassReleaseVersionCheck(BuildConfig.DEBUG, readiness)) {
                Log.i(UPDATE_DIAGNOSTIC_TAG, "YZUPDATE_RELEASE_CHECK_BYPASS=DEBUG_BUILD")

                Result.success(null)
            } else {
            val remainingMillis = startupDeadlineEpochMillis - System.currentTimeMillis()

            if (remainingMillis > 0) {
                withTimeoutOrNull(remainingMillis) {
                    try {
                        Result.success(
                            appOperationalStateCoordinator.performResuming(
                                lifecycleOperationRequest(
                                    lifecycleId = FullAppLifecycleId.LC_01,
                                    trigger = "cold_start",
                                    deadlineEpochMillis = startupDeadlineEpochMillis,
                                )
                            ) {
                                managementControlService.latestAppRelease()
                            }
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        Log.w(UPDATE_DIAGNOSTIC_TAG, "YZUPDATE_RELEASE_FAILURE=RELEASE_FETCH_OR_ACCEPT", failure)

                        Result.failure(failure)
                    }
                }
            } else {
                Log.w(UPDATE_DIAGNOSTIC_TAG, "YZUPDATE_RELEASE_FAILURE=TIMEOUT")

                null
            }
            }
        } else {
            Log.w(
                UPDATE_DIAGNOSTIC_TAG,
                "YZUPDATE_READINESS_FAILURE=${readiness.reasonCode ?: readiness.state.name}",
            )

            // The pinned public manifest is not account-content authorization. A device rejected
            // by LC-06 must still be able to install the signed repair without claiming READY.
            withTimeoutOrNull(5_000L) {
                runCatching { managementControlService.latestAppRelease() }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }

        val releaseAdmission = releaseAdmissionDecision(readiness.canStartLc01(), release)
        val requiredPublicUpdate = release?.getOrNull()?.takeIf { releaseAdmission == ReleaseAdmissionDecision.UPDATE_ONLY }
        val offlineDeadlineExceeded = runCatching { managementControlService.isOfflineDeadlineExceeded() }
            .getOrDefault(true)

        val failureReasonCode = when {
            readiness.reasonCode != null -> readiness.reasonCode

            readiness.state == StartupSecurityReadinessState.READY_FOR_LC01 && !readiness.isFresh() ->

                "STARTUP_READINESS_EXPIRED"

            else -> "RELEASE_MANIFEST_UNAVAILABLE"
        }

        StartupTrace.mark("Startup.update.check.end")

        state = when {
            requiredPublicUpdate != null -> AppUpdateGateState.Required(requiredPublicUpdate, AppUpdatePhase.ReadyToDownload)

            restriction != null -> AppUpdateGateState.CheckFailed(startupGateAccessibilityContract(checkNotNull(restriction)))

            (isOffline || networkDisconnected) && managementControlService.offlineEntryStatus() == null ->

                AppUpdateGateState.CheckFailed(startupGateAccessibilityContract("OFFLINE_ENTRY_EXPIRED"))

            releaseAdmission == ReleaseAdmissionDecision.CONTENT_ALLOWED -> AppUpdateGateState.Allowed

            (isOffline || networkDisconnected) && canUseBoundedOfflineStartup(readiness, offlineDeadlineExceeded) &&

                managementControlService.offlineEntryStatus() != null &&

                (BuildConfig.DEBUG && cachedRelease?.mandatory != true ||

                    canOpenAppShellFromCachedRelease(hasValidCachedManifest, cachedRelease)) ->

                AppUpdateGateState.Allowed

            else -> AppUpdateGateState.CheckFailed(startupGateAccessibilityContract(failureReasonCode))
        }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            state = AppUpdateGateState.CheckFailed(startupGateAccessibilityContract(io.element.android.x.security.startupFailureReasonCode(failure)))
        } finally {
            checkRunning = false
        }
    }

    // The authenticated retained grant only changes presentation during Checking.

    val displayedState = when {
        state is AppUpdateGateState.Required -> state

        restriction != null -> AppUpdateGateState.CheckFailed(startupGateAccessibilityContract(restriction))

        state == AppUpdateGateState.Allowed && restrictEntryWithoutOfflineGrant(isOffline, networkDisconnected, offlineGrantValidated) ->

            AppUpdateGateState.CheckFailed(startupGateAccessibilityContract("OFFLINE_ENTRY_EXPIRED"))

        state == AppUpdateGateState.Checking && retainedEntryPermitted && !networkDisconnected && !isOffline -> AppUpdateGateState.Allowed

        else -> state
    }

    BackHandler(enabled = displayedState !is AppUpdateGateState.Allowed) {}

    LaunchedEffect(displayedState, state, retainedEntryPermitted, restriction, networkDisconnected, isOffline) {
        StartupTrace.mark(
            "Startup.appGate display=${displayedState.javaClass.simpleName} state=${state.javaClass.simpleName}" +
                " retained=$retainedEntryPermitted restricted=${restriction != null}" +
                " disconnected=$networkDisconnected offline=$isOffline",
        )
    }

    androidx.compose.runtime.SideEffect {
        onBlockingState(displayedState != AppUpdateGateState.Checking && displayedState != AppUpdateGateState.Allowed)
    }
    when (val current = displayedState) {
        AppUpdateGateState.Allowed -> authorizedContentState.SaveableStateProvider("authorized-app-content") {
            androidx.compose.runtime.DisposableEffect(Unit) {
                StartupTrace.mark("Startup.appContent.enter")
                onDispose { StartupTrace.mark("Startup.appContent.dispose") }
            }
            content()
        }

        AppUpdateGateState.Checking -> SilentStartupSurface(modifier)

        is AppUpdateGateState.CheckFailed -> AppUpdateCheckFailedView(
            accessibility = current.accessibility,
            modifier = modifier,
            onRetry = {
                if (!handoverRunning && !checkRunning && System.currentTimeMillis() >= (retryNotBefore ?: 0L)) {
                    if (!shouldRefreshPendingEvidence(current.accessibility.reasonCode)) {
                        checkAttempt++
                        return@AppUpdateCheckFailedView
                    }
                    checkRunning = true
                    scope.launch {
                        try {
                            // A user retry may refresh pending evidence through the original challenge/PoP
                            // path. The following startup pass still owns signed readiness and LC-06 recovery.
                            managementControlService.recheckPendingDeviceEnrollment()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            state = AppUpdateGateState.CheckFailed(
                                startupGateAccessibilityContract(io.element.android.x.security.startupFailureReasonCode(failure))
                            )
                        } finally {
                            checkRunning = false
                            // Even an evidence transport failure must not block the public update probe.
                            checkAttempt++
                        }
                    }
                }
            },
            handoverRunning = handoverRunning,
            onTransferAuthorization = ::admitCandidateInstallation,
            retryAtEpochMillis = retryNotBefore,
        )

        is AppUpdateGateState.Required -> AppUpdateRequiredView(
            state = current,
            modifier = modifier,
            onDownload = {
                state = current.copy(phase = AppUpdatePhase.Downloading(AppUpdateDownloadProgress(0L, -1L)))

                scope.launch {
                    state = runCatching {
                        val prepared = managementControlService.downloadAndVerifyAppRelease(current.release) { progress ->

                            withContext(Dispatchers.Main.immediate) {
                                state = current.copy(
                                    phase = if (progress.totalBytes > 0L && progress.downloadedBytes >= progress.totalBytes) {
                                        AppUpdatePhase.Verifying
                                    } else {
                                        AppUpdatePhase.Downloading(progress)
                                    }
                                )
                            }
                        }

                        current.copy(phase = AppUpdatePhase.ReadyToInstall(prepared))
                    }.getOrElse { current.copy(phase = AppUpdatePhase.DownloadFailed) }
                }
            },
            onInstall = { prepared -> installUpdate(activity, prepared) },
        )
    }
}

internal fun restrictEntryWithoutOfflineGrant(isOffline: Boolean, networkDisconnected: Boolean, grantValidated: Boolean): Boolean =

    (isOffline || networkDisconnected) && !grantValidated

internal fun shouldRefreshPendingEvidence(reasonCode: String): Boolean = reasonCode in setOf(
    "COMPATIBILITY_EVIDENCE_REJECTED",
    "DEVICE_TRUST_NOT_READY",
)

internal enum class ReleaseAdmissionDecision { UPDATE_ONLY, CONTENT_ALLOWED, KEEP_RESTRICTED }

/** A verified public update is installable without granting account or content access. */
internal fun releaseAdmissionDecision(readyForLc01: Boolean, verifiedRelease: Result<ManagedAppRelease?>?): ReleaseAdmissionDecision = when {
    verifiedRelease?.getOrNull()?.mandatory == true -> ReleaseAdmissionDecision.UPDATE_ONLY
    readyForLc01 && verifiedRelease?.isSuccess == true -> ReleaseAdmissionDecision.CONTENT_ALLOWED
    else -> ReleaseAdmissionDecision.KEEP_RESTRICTED
}

internal fun shouldBypassReleaseVersionCheck(
    isDebugBuild: Boolean,
    readiness: StartupSecurityReadiness,
    nowEpochMillis: Long = System.currentTimeMillis(),
): Boolean = isDebugBuild && readiness.canStartLc01(nowEpochMillis)

private fun ManagedAppRelease?.toGateState(): AppUpdateGateState =

    this?.takeIf { it.mandatory }

        ?.let { AppUpdateGateState.Required(it, AppUpdatePhase.ReadyToDownload) }

        ?: AppUpdateGateState.Allowed

internal fun canPrepareRetainedAppContent(
    debugBuild: Boolean,
    hasValidCachedManifest: Boolean,
    cachedRelease: ManagedAppRelease?,
): Boolean = cachedRelease?.mandatory != true && (debugBuild || hasValidCachedManifest)

internal fun canOpenAppShellFromCachedRelease(
    hasValidCachedManifest: Boolean,
    cachedRelease: ManagedAppRelease?,
): Boolean = hasValidCachedManifest && cachedRelease?.mandatory != true

@Composable
private fun SilentStartupSurface(modifier: Modifier) {
    // Keep mandatory update checks fail-closed without flashing a user-facing

    // "checking" page when the installed version is already allowed.

    StartupWaitingView(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "正在检查设备状态"

            stateDescription = "正在验证，请稍候"

            liveRegion = LiveRegionMode.Polite
        },
    )
}

internal fun canUseBoundedOfflineStartup(
    sync: io.element.android.security.dataprotection.api.ManagementSyncReport,
    offlineDeadlineExceeded: Boolean,
): Boolean = sync.status in setOf(ManagementSyncStatus.SYNCHRONIZED, ManagementSyncStatus.RETRY_REQUIRED) &&

    sync.acceptedPolicyVersion != null &&

    !offlineDeadlineExceeded

internal fun canUseBoundedOfflineStartup(
    readiness: StartupSecurityReadiness,
    offlineDeadlineExceeded: Boolean,
): Boolean = readiness.policyVersion != null &&

    !offlineDeadlineExceeded &&

    readiness.isFresh() &&

    (readiness.state == StartupSecurityReadinessState.READY_FOR_LC01 ||

        readiness.state == StartupSecurityReadinessState.RETRYABLE_FAILURE && readiness.offlineEligible)

@Composable
private fun AppUpdateCheckFailedView(
    accessibility: StartupGateAccessibilityContract,
    onRetry: () -> Unit,
    modifier: Modifier,
    retryAtEpochMillis: Long? = null,
    handoverRunning: Boolean = false,
    onTransferAuthorization: () -> Unit = {},
) {
    val copy = startupGateCopy(accessibility.reasonCode)

    var diagnosticVisible by remember(accessibility.reasonCode) { mutableStateOf(false) }

    var remainingSeconds by remember(retryAtEpochMillis) { mutableStateOf(0L) }

    LaunchedEffect(retryAtEpochMillis) {
        do {
            val remainingMillis = ((retryAtEpochMillis ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

            remainingSeconds = remainingMillis / 1_000L + if (remainingMillis % 1_000L > 0) 1L else 0L

            if (remainingSeconds > 0) delay(1_000L)
        } while (remainingSeconds > 0)
    }

    FlowStepPage(
        modifier = modifier
            .revealStartupSplashWhenLaidOut()
            .semantics(mergeDescendants = true) {
                contentDescription = "设备验证状态"

                stateDescription = accessibility.userState.accessibilityLabel()

                liveRegion = accessibility.announcement.liveRegionMode()
            },
        iconStyle = if (shouldAutomaticallyRetryStartup(accessibility.reasonCode) && accessibility.reasonCode != "OFFLINE_ENTRY_EXPIRED") {
            BigIcon.Style.Default(CompoundIcons.Shield())
        } else {
            BigIcon.Style.AlertSolid
        },
        title = stringResource(copy.titleRes),
        subTitle = if (remainingSeconds > 0) stringResource(R.string.screen_entry_retry_countdown, remainingSeconds) else stringResource(copy.descriptionRes),
        buttons = {
            Button(
                text = stringResource(
                    when {
                        handoverRunning -> R.string.screen_entry_transfer_authorization_progress
                        accessibility.primaryAction == StartupGatePrimaryAction.TRANSFER_MANAGED_AUTHORIZATION ->
                            R.string.screen_entry_transfer_authorization
                        else -> R.string.screen_force_update_retry
                    }
                ),
                onClick = if (accessibility.primaryAction == StartupGatePrimaryAction.TRANSFER_MANAGED_AUTHORIZATION) {
                    onTransferAuthorization
                } else {
                    onRetry
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = remainingSeconds == 0L && !handoverRunning,
            )
        },
    ) {
        Text(
            text = if (diagnosticVisible) {
                stringResource(R.string.screen_startup_error_code, accessibility.reasonCode)
            } else {
                stringResource(R.string.screen_entry_view_diagnostic)
            },
            modifier = Modifier.clickable { diagnosticVisible = !diagnosticVisible },
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textSecondary,
        )
    }
}

internal data class StartupGateCopy(val titleRes: Int, val descriptionRes: Int)

internal fun startupGateCopy(reasonCode: String): StartupGateCopy = when (reasonCode) {
    "COMPATIBILITY_BINDING_CONFLICT" -> StartupGateCopy(
        R.string.screen_entry_compatibility_binding_title,
        R.string.screen_entry_compatibility_binding_description
    )

    "COMPATIBILITY_EVIDENCE_REJECTED" -> StartupGateCopy(
        R.string.screen_entry_compatibility_evidence_title,
        R.string.screen_entry_compatibility_evidence_description
    )

    "COMPATIBILITY_POLICY_DISABLED" -> StartupGateCopy(R.string.screen_entry_compatibility_policy_title, R.string.screen_entry_compatibility_policy_description)

    "OFFLINE_ENTRY_EXPIRED" -> StartupGateCopy(R.string.screen_entry_offline_expired_title, R.string.screen_entry_offline_expired_description)

    "CONTROL_SERVER_UNAVAILABLE" -> StartupGateCopy(R.string.screen_entry_service_retry_title, R.string.screen_entry_service_retry_description)

    "CONTROL_RATE_LIMITED" -> StartupGateCopy(R.string.screen_entry_rate_limit_title, R.string.screen_entry_rate_limit_description)

    "REMOTE_POLICY_APPLICATION_PENDING" -> StartupGateCopy(R.string.screen_entry_policy_pending_title, R.string.screen_entry_policy_pending_description)

    "STARTUP_READINESS_TIMEOUT" -> StartupGateCopy(R.string.screen_startup_error_timeout_title, R.string.screen_startup_error_timeout_description)

    "CONTROL_DNS_UNAVAILABLE" -> StartupGateCopy(R.string.screen_startup_error_dns_title, R.string.screen_startup_error_dns_description)

    "CONTROL_CONNECTION_FAILED" -> StartupGateCopy(R.string.screen_startup_error_connection_title, R.string.screen_startup_error_connection_description)

    "CONTROL_REQUEST_TIMEOUT" -> StartupGateCopy(R.string.screen_startup_error_request_timeout_title, R.string.screen_startup_error_request_timeout_description)

    "CONTROL_NETWORK_IO" -> StartupGateCopy(R.string.screen_startup_error_network_io_title, R.string.screen_startup_error_network_io_description)

    "CONTROL_TLS_REJECTED" -> StartupGateCopy(R.string.screen_startup_error_tls_title, R.string.screen_startup_error_tls_description)

    "CONTROL_SERVER_REJECTED" -> StartupGateCopy(R.string.screen_startup_error_server_rejected_title, R.string.screen_startup_error_server_rejected_description)

    "REMOTE_COMMAND_EXECUTION_PENDING" -> StartupGateCopy(
        R.string.screen_startup_error_command_pending_title,
        R.string.screen_startup_error_command_pending_description
    )

    "REMOTE_COMMAND_REPORT_PENDING" -> StartupGateCopy(
        R.string.screen_startup_error_command_report_title,
        R.string.screen_startup_error_command_report_description
    )

    "REMOTE_COMMAND_EXECUTION_FAILED" -> StartupGateCopy(
        R.string.screen_startup_error_command_failed_title,
        R.string.screen_startup_error_command_failed_description
    )

    "DEVICE_SIGNING_KEY_UNAVAILABLE" -> StartupGateCopy(R.string.screen_startup_error_device_key_title, R.string.screen_startup_error_device_key_description)

    "SIGNED_POLICY_UNAVAILABLE" -> StartupGateCopy(R.string.screen_startup_error_policy_missing_title, R.string.screen_startup_error_policy_missing_description)

    "DEVICE_TRUST_NOT_READY" -> StartupGateCopy(R.string.screen_startup_error_device_trust_title, R.string.screen_startup_error_device_trust_description)
    "DEVICE_ADMINISTRATIVELY_LOCKED" -> StartupGateCopy(R.string.screen_entry_remote_locked_title, R.string.screen_entry_remote_locked_description)

    "SIGNED_POLICY_EXPIRED" -> StartupGateCopy(R.string.screen_startup_error_policy_expired_title, R.string.screen_startup_error_policy_expired_description)

    "SECURITY_POLICY_REJECTED" -> StartupGateCopy(
        R.string.screen_startup_error_policy_rejected_title,
        R.string.screen_startup_error_policy_rejected_description
    )

    "ACCESS_TICKET_EXPIRED" -> StartupGateCopy(R.string.screen_startup_error_ticket_expired_title, R.string.screen_startup_error_ticket_expired_description)

    "INSTALLATION_IDENTITY_MISMATCH" -> StartupGateCopy(
        R.string.screen_startup_error_identity_mismatch_title,
        R.string.screen_startup_error_identity_mismatch_description
    )

    "STARTUP_CONFIGURATION_DISABLED" -> StartupGateCopy(
        R.string.screen_startup_error_configuration_title,
        R.string.screen_startup_error_configuration_description
    )

    "STARTUP_SNAPSHOT_STALE" -> StartupGateCopy(R.string.screen_startup_error_snapshot_stale_title, R.string.screen_startup_error_snapshot_stale_description)

    "STARTUP_AUTHORITY_CONTRADICTORY" -> StartupGateCopy(
        R.string.screen_startup_error_authority_conflict_title,
        R.string.screen_startup_error_authority_conflict_description
    )

    "STARTUP_READINESS_EXPIRED" -> StartupGateCopy(
        R.string.screen_startup_error_readiness_expired_title,
        R.string.screen_startup_error_readiness_expired_description
    )

    "RELEASE_MANIFEST_UNAVAILABLE" -> StartupGateCopy(
        R.string.screen_startup_error_release_manifest_title,
        R.string.screen_startup_error_release_manifest_description
    )

    "RELEASE_ARTIFACT_MISMATCH" -> StartupGateCopy(
        R.string.screen_startup_error_release_identity_title,
        R.string.screen_startup_error_release_identity_description
    )

    "CONTROL_PLANE_UNAVAILABLE" -> StartupGateCopy(
        R.string.screen_startup_error_control_unavailable_title,
        R.string.screen_startup_error_control_unavailable_description
    )

    else -> StartupGateCopy(R.string.screen_startup_error_unknown_title, R.string.screen_startup_error_unknown_description)
}

@Composable
private fun AppUpdateRequiredView(
    state: AppUpdateGateState.Required,
    onDownload: () -> Unit,
    onInstall: (PreparedAppUpdate) -> Unit,
    modifier: Modifier,
) {
    val phase = state.phase

    FlowStepPage(
        modifier = modifier
            .revealStartupSplashWhenLaidOut()
            .semantics(mergeDescendants = true) {
                contentDescription = "Required update status"

                stateDescription = phase.accessibilityLabel()

                liveRegion = LiveRegionMode.Polite
            },
        isScrollable = true,
        iconStyle = BigIcon.Style.Default(CompoundIcons.Download(), usePrimaryTint = true),
        title = stringResource(R.string.screen_force_update_required_title),
        subTitle = stringResource(R.string.screen_force_update_required_description, state.release.versionName),
        buttons = {
            when (phase) {
                AppUpdatePhase.ReadyToDownload,

                AppUpdatePhase.DownloadFailed -> Button(
                    text = stringResource(
                        if (phase == AppUpdatePhase.DownloadFailed) {
                            R.string.screen_force_update_retry_download
                        } else {
                            R.string.screen_force_update_download
                        }
                    ),
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                )

                is AppUpdatePhase.ReadyToInstall -> Button(
                    text = stringResource(R.string.screen_force_update_install),
                    onClick = { onInstall(phase.prepared) },
                    modifier = Modifier.fillMaxWidth(),
                )

                is AppUpdatePhase.Downloading,

                AppUpdatePhase.Verifying -> Unit
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.release.releaseNotes.isNotBlank()) {
                Text(
                    text = state.release.releaseNotes,
                    style = ElementTheme.typography.fontBodyMdRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }

            when (phase) {
                is AppUpdatePhase.Downloading -> DownloadProgress(phase.progress)

                AppUpdatePhase.Verifying -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                    Text(
                        text = stringResource(R.string.screen_force_update_verifying),
                        style = ElementTheme.typography.fontBodyMdRegular,
                    )
                }

                is AppUpdatePhase.ReadyToInstall -> Text(
                    text = stringResource(R.string.screen_force_update_ready_to_install),
                    style = ElementTheme.typography.fontBodyMdRegular,
                )

                AppUpdatePhase.DownloadFailed -> Text(
                    text = stringResource(R.string.screen_force_update_download_failed),
                    style = ElementTheme.typography.fontBodyMdRegular,
                    color = ElementTheme.colors.textCriticalPrimary,
                )

                AppUpdatePhase.ReadyToDownload -> Unit
            }
        }
    }
}

@Composable
private fun DownloadProgress(progress: AppUpdateDownloadProgress) {
    val fraction = progress.fraction

    if (fraction == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        Text(
            text = stringResource(R.string.screen_force_update_downloading_unknown, formatBytes(progress.downloadedBytes)),
            style = ElementTheme.typography.fontBodyMdRegular,
        )
    } else {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = stringResource(
                R.string.screen_force_update_downloading,
                (fraction * 100).toInt(),
                formatBytes(progress.downloadedBytes),
                formatBytes(progress.totalBytes),
            ),
            style = ElementTheme.typography.fontBodyMdRegular,
        )
    }
}

private fun installUpdate(activity: Activity, prepared: PreparedAppUpdate) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
        activity.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        )

        return
    }

    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", prepared.apkFile)

    activity.startActivity(
        Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)

            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"

    bytes < 1_024L * 1_024L -> "%.1f KB".format(bytes / 1_024.0)

    else -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
}

private sealed interface AppUpdateGateState {
    data object Checking : AppUpdateGateState

    data class CheckFailed(val accessibility: StartupGateAccessibilityContract) : AppUpdateGateState

    data object Allowed : AppUpdateGateState

    data class Required(
        val release: ManagedAppRelease,
        val phase: AppUpdatePhase,
    ) : AppUpdateGateState
}

private fun StartupGateUserState.accessibilityLabel(): String = when (this) {
    StartupGateUserState.CHECKING -> "Checking"

    StartupGateUserState.RETRYABLE -> "Action required: retry available"

    StartupGateUserState.BLOCKED -> "Startup blocked for security"

    StartupGateUserState.UPDATE -> "Update required"

    StartupGateUserState.DIAGNOSTIC -> "Startup diagnostic action required"
}

private fun StartupGateAnnouncement.liveRegionMode(): LiveRegionMode = when (this) {
    StartupGateAnnouncement.NONE -> LiveRegionMode.Polite

    StartupGateAnnouncement.POLITE_STATUS,

    StartupGateAnnouncement.POLITE_UPDATE -> LiveRegionMode.Polite

    StartupGateAnnouncement.ASSERTIVE_BLOCKED,

    StartupGateAnnouncement.ASSERTIVE_DIAGNOSTIC -> LiveRegionMode.Assertive
}

private fun AppUpdatePhase.accessibilityLabel(): String = when (this) {
    AppUpdatePhase.ReadyToDownload -> "Ready to download update"

    is AppUpdatePhase.Downloading -> "Downloading update"

    AppUpdatePhase.Verifying -> "Verifying update"

    is AppUpdatePhase.ReadyToInstall -> "Update ready to install"

    AppUpdatePhase.DownloadFailed -> "Update download failed; retry available"
}

private sealed interface AppUpdatePhase {
    data object ReadyToDownload : AppUpdatePhase

    data class Downloading(val progress: AppUpdateDownloadProgress) : AppUpdatePhase

    data object Verifying : AppUpdatePhase

    data class ReadyToInstall(val prepared: PreparedAppUpdate) : AppUpdatePhase

    data object DownloadFailed : AppUpdatePhase
}

private const val STARTUP_TOTAL_TIMEOUT_MILLIS = 15_000L

private const val UPDATE_DIAGNOSTIC_TAG = "YiZheUpdateGate"

internal fun shouldAutomaticallyRetryStartup(reason: String): Boolean = reason in setOf(
    "STARTUP_READINESS_TIMEOUT",
    "CONTROL_DNS_UNAVAILABLE",
    "CONTROL_CONNECTION_FAILED",
    "CONTROL_REQUEST_TIMEOUT",
    "CONTROL_NETWORK_IO",
    "CONTROL_SERVER_UNAVAILABLE",
    "CONTROL_RATE_LIMITED",
    "CONTROL_PLANE_UNAVAILABLE",
    "RELEASE_MANIFEST_UNAVAILABLE",
    "STARTUP_READINESS_EXPIRED",
    "OFFLINE_ENTRY_EXPIRED",
    "REMOTE_COMMAND_EXECUTION_PENDING",
    "REMOTE_COMMAND_REPORT_PENDING",
    "REMOTE_POLICY_APPLICATION_PENDING",
)

internal fun startupRetryDelayMillis(attempt: Int): Long = when (attempt) {
    0 -> 1_000L

    1 -> 2_000L

    2 -> 5_000L

    3 -> 10_000L

    else -> 30_000L
}
