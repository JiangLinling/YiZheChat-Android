/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.security

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.element.android.libraries.androidutils.clipboard.InAppClipboard
import io.element.android.libraries.androidutils.startup.StartupTrace
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.push.api.notifications.NotificationCleaner
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.dataprotection.api.ActiveDataProtectionPolicy
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicyRevisionProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.DeletionOperationId
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.ManagedDeviceSecurityState
import io.element.android.security.dataprotection.api.ManagementCommandExecutionReport
import io.element.android.security.dataprotection.api.ManagementCommandState
import io.element.android.security.dataprotection.api.ManagementCommandType
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.ManagementHeartbeatReport
import io.element.android.security.dataprotection.api.ManagementHeartbeatStatus
import io.element.android.security.dataprotection.api.ManagementPolicyApplication
import io.element.android.security.dataprotection.api.ManagementPolicyApplicationState
import io.element.android.security.dataprotection.api.ManagementSecurityRefreshService
import io.element.android.security.dataprotection.api.ManagementSyncReport
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.PolicyApplicationEvidence
import io.element.android.security.dataprotection.api.PolicyCleanupOperation
import io.element.android.security.dataprotection.api.PolicyCleanupOperationState
import io.element.android.security.dataprotection.api.StartupSecurityReadiness
import io.element.android.security.dataprotection.api.StartupSecurityReadinessState
import io.element.android.security.dataprotection.api.VerifiedManagementCommand
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.dataprotection.api.toStartupSecurityReadiness
import io.element.android.security.dataprotection.api.withOperation
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureVaultDestroyedException
import io.element.android.security.securestorage.api.VaultDestroyReason
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.services.appnavstate.api.AppForegroundStateService
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

private const val STARTUP_DIAGNOSTIC_TAG = "YiZheManagement"
private const val MAX_STARTUP_FAILURE_CAUSES = 8

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<ManagementSecurityRefreshService>())
@ContributesBinding(AppScope::class, binding = binding<DataProtectionPolicyRevisionProvider>())
class ManagementControlCoordinator(
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
    private val managementControlService: ManagementControlService,
    private val sessionStore: SessionStore,
    private val policyProvider: DataProtectionPolicyProvider,
    private val dataDeletionCoordinator: DataDeletionCoordinator,
    private val deviceTrustService: DeviceTrustService,
    private val vaultKeyService: VaultKeyService,
    private val deviceShareKeyService: DeviceShareKeyService,
    private val contentProtectionService: ContentProtectionService,
    private val secureMediaLeaseManager: SecureMediaLeaseManager,
    private val matrixContentProtectionRuntime: MatrixContentProtectionRuntime,
    private val notificationCleaner: NotificationCleaner,
    private val appForegroundStateService: AppForegroundStateService,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val coroutineDispatchers: CoroutineDispatchers,
) : ManagementSecurityRefreshService, DataProtectionPolicyRevisionProvider, DefaultLifecycleObserver {
    private val synchronizationMutex = Mutex()
    private val startupReadinessMutex = Mutex()
    private val mutablePolicyRevision = MutableStateFlow(0L)
    private val mutableStartupReadiness = MutableStateFlow(
        StartupSecurityReadiness(
            state = StartupSecurityReadinessState.UNKNOWN,
            observedAtEpochMillis = System.currentTimeMillis(),
        )
    )
    override val policyRevision = mutablePolicyRevision.asStateFlow()
    val startupReadiness = mutableStartupReadiness.asStateFlow()
    private val mutableEntryRestriction = MutableStateFlow<String?>(null)
    private val mutableEntryIsOffline = MutableStateFlow(false)
    override val localEntryRestriction = mutableEntryRestriction.asStateFlow()
    override val entryIsOffline = mutableEntryIsOffline.asStateFlow()
    private var heartbeatJob: Job? = null
    private var startupReadinessJob: Deferred<StartupSecurityReadiness>? = null
    private var isProcessStarted = false

    fun startAsync() {
        appCoroutineScope.launch {
            withContext(Dispatchers.Main.immediate) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(this@ManagementControlCoordinator)
            }
        }
        appCoroutineScope.launch {
            combine(
                appForegroundStateService.hasRingingCall,
                appForegroundStateService.isInCall,
            ) { hasRingingCall, isInCall -> hasRingingCall || isInCall }
                .distinctUntilChanged()
                .collect { callActive ->
                    if (callActive) {
                        startHeartbeatIfNeeded()
                    } else {
                        stopHeartbeatIfIdle()
                    }
                }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        isProcessStarted = true
        startHeartbeatIfNeeded()
    }

    override fun onStop(owner: LifecycleOwner) {
        isProcessStarted = false
        stopHeartbeatIfIdle()
    }

    private fun startHeartbeatIfNeeded() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = appCoroutineScope.launch {
            val readiness = awaitStartupReadiness(
                System.currentTimeMillis() + STARTUP_READINESS_TIMEOUT_MILLIS,
            )
            val trust = deviceTrustService.current()
            // A verified administrative lock blocks content, not the control channel needed to receive its release.
            // Keep the original foreground LC-07 owner; other rejected or replaced identities remain fail-closed.
            val awaitingAdministrativeUnlock = readiness.state == StartupSecurityReadinessState.SECURITY_REJECTED &&
                readiness.reasonCode == "DEVICE_ADMINISTRATIVELY_LOCKED" &&
                mutableEntryRestriction.value == "DEVICE_ADMINISTRATIVELY_LOCKED" &&
                readiness.installationId == trust.installationId && trust.trustState == DeviceTrustState.STALE
            if (readiness.state != StartupSecurityReadinessState.READY_FOR_LC01 && !readiness.offlineEligible &&
                !awaitingAdministrativeUnlock
            ) return@launch
            var nextDelayMillis = DEFAULT_HEARTBEAT_INTERVAL_MILLIS
            while (true) {
                delay(withJitter(nextDelayMillis))
                val heartbeat = runCatching {
                    appOperationalStateCoordinator.perform(
                        lifecycleOperationRequest(FullAppLifecycleId.LC_07, "heartbeat")
                    ) {
                        val pulse = managementControlService.heartbeat()
                        if (pulse.status in setOf(ManagementHeartbeatStatus.POLICY_REFRESH_REQUIRED, ManagementHeartbeatStatus.REAUTH_REQUIRED)) {
                            // Keep full refresh/automatic renewal inside this original, still-pending LC-07.
                            // The lightweight receipt remains the actual heartbeat result.
                            val refresh = synchronizeAndExecuteWithReport()
                            val stillAdministrativelyLocked = refresh.errorCode == "DEVICE_ADMINISTRATIVELY_LOCKED" &&
                                deviceTrustService.current().trustState == DeviceTrustState.STALE
                            pulse.copy(
                                status = if (refresh.status == ManagementSyncStatus.SECURITY_REJECTED && !stillAdministrativelyLocked) {
                                    ManagementHeartbeatStatus.SECURITY_REJECTED
                                } else {
                                    pulse.status
                                },
                                nextHeartbeatDelayMillis = refresh.heartbeatIntervalMillis ?: DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
                            )
                        } else {
                            pulse
                        }
                    }
                }.getOrElse { failure ->
                    Timber.w(failure, "Management heartbeat lifecycle remains retryable")
                    ManagementHeartbeatReport(
                        status = ManagementHeartbeatStatus.RETRY_REQUIRED,
                        nextHeartbeatDelayMillis = DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
                    )
                }
                when (heartbeat.status) {
                    ManagementHeartbeatStatus.ACTIVE -> {
                        mutableEntryIsOffline.value = false
                        // A pulse confirms connectivity, not release of an administrative lock or local Vault gate.
                        if (deviceTrustService.current().allowsLocalVaultAccess) mutableEntryRestriction.value = null
                        nextDelayMillis = heartbeat.nextHeartbeatDelayMillis
                    }
                    ManagementHeartbeatStatus.RETRY_REQUIRED -> {
                        if (managementControlService.offlineEntryStatus() == null) {
                            mutableEntryRestriction.update { current ->
                                current ?: retryEntryRestriction("CONTROL_PLANE_UNAVAILABLE")
                            }
                        }
                        nextDelayMillis = heartbeat.nextHeartbeatDelayMillis
                    }
                    ManagementHeartbeatStatus.POLICY_REFRESH_REQUIRED,
                    ManagementHeartbeatStatus.REAUTH_REQUIRED -> {
                        nextDelayMillis = heartbeat.nextHeartbeatDelayMillis
                    }
                    ManagementHeartbeatStatus.REVOKED,
                    ManagementHeartbeatStatus.SECURITY_REJECTED -> {
                        mutableEntryRestriction.value = "SECURITY_POLICY_REJECTED"
                        restrictLocalAccess()
                        matrixContentProtectionRuntime.clearSensitiveCaches()
                        matrixContentProtectionRuntime.requestAuthorizationRefresh()
                        return@launch
                    }
                    ManagementHeartbeatStatus.DISABLED -> return@launch
                }
            }
        }
    }

    private fun stopHeartbeatIfIdle() {
        if (shouldKeepManagementHeartbeatRunning(
                isProcessStarted = isProcessStarted,
                hasRingingCall = appForegroundStateService.hasRingingCall.value,
                isInCall = appForegroundStateService.isInCall.value,
            )
        ) {
            return
        }
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun synchronizeAsync() {
        appCoroutineScope.launch { joinStartupOrSynchronize() }
    }

    suspend fun joinStartupOrSynchronize(): Boolean {
        val current = mutableStartupReadiness.value.effectiveFor()
        if (current.state != StartupSecurityReadinessState.READY_FOR_LC01) {
            return awaitStartupReadiness(
                System.currentTimeMillis() + STARTUP_READINESS_TIMEOUT_MILLIS,
            ).state == StartupSecurityReadinessState.READY_FOR_LC01
        }
        return synchronizeAndExecuteWithReport().status == ManagementSyncStatus.SYNCHRONIZED
    }

    override suspend fun refreshSecurityAccess(): ManagementSyncReport = synchronizeAndExecuteWithReport()

    override fun onNetworkUnavailable() {
        mutableEntryIsOffline.value = true
        mutableStartupReadiness.value = mutableStartupReadiness.value.copy(
            state = StartupSecurityReadinessState.UNKNOWN,
            reasonCode = "CONTROL_CONNECTION_FAILED",
        )
    }

    override fun verifiedOnlineReadiness(): StartupSecurityReadiness? = mutableStartupReadiness.value.takeIf {
        mutableEntryRestriction.value == null && !mutableEntryIsOffline.value &&
            it.state == StartupSecurityReadinessState.READY_FOR_LC01 && it.isFresh()
    }

    override suspend fun awaitStartupReadiness(deadlineEpochMillis: Long): StartupSecurityReadiness {
        val now = System.currentTimeMillis()
        if (deadlineEpochMillis <= now) {
            return publishStartupReadiness(startupReadinessTimeout())
        }
        val existing = mutableStartupReadiness.value.takeIf {
            mutableEntryRestriction.value == null && !mutableEntryIsOffline.value &&
                it.state == StartupSecurityReadinessState.READY_FOR_LC01 && it.isFresh(now)
        }
        if (existing != null) return existing

        val sharedJob = startupReadinessMutex.withLock {
            val active = startupReadinessJob?.takeIf { it.isActive }
            if (active != null) {
                active
            } else {
                mutableStartupReadiness.value = StartupSecurityReadiness(
                    state = StartupSecurityReadinessState.BOOTSTRAPPING,
                    observedAtEpochMillis = System.currentTimeMillis(),
                )
                val effectiveDeadline = minOf(
                    deadlineEpochMillis,
                    now + STARTUP_READINESS_TIMEOUT_MILLIS,
                )
                // Policy, key and receipt work must not occupy the UI dispatcher.
                appCoroutineScope.async(context = coroutineDispatchers.computation, start = CoroutineStart.LAZY) {
                    runStartupReadiness(effectiveDeadline)
                }.also { startupReadinessJob = it }
            }
        }
        sharedJob.start()
        managementControlService.offlineEntryStatus()?.takeIf { mutableEntryRestriction.value == null }?.let { entry ->
            mutableEntryIsOffline.value = true
            return StartupSecurityReadiness(
                state = StartupSecurityReadinessState.RETRYABLE_FAILURE,
                reasonCode = "CONTROL_NETWORK_IO",
                policyVersion = entry.policyVersion,
                installationId = deviceTrustService.current().installationId,
                observedAtEpochMillis = now,
                offlineEligible = true,
            )
        }

        val remainingMillis = deadlineEpochMillis - System.currentTimeMillis()
        if (remainingMillis <= 0) {
            return publishStartupReadiness(startupReadinessTimeout())
        }
        return try {
            withTimeout(remainingMillis) { sharedJob.await() }
        } catch (_: TimeoutCancellationException) {
            publishStartupReadiness(startupReadinessTimeout())
        }
    }

    private suspend fun runStartupReadiness(deadlineEpochMillis: Long): StartupSecurityReadiness {
        val trustBeforeStartup = deviceTrustService.current()
        val cachedCommands = managementControlService.pendingCommands()
        if (commandsAllowedWhileRevoked(trustBeforeStartup.trustState, cachedCommands).isNotEmpty()) {
            // Enrollment is intentionally unavailable after revocation. Finish only the cached signed
            // REVOKE command before entering the ordinary LC-02 dependency chain so the server receives
            // its terminal report while every user-facing access path remains rejected.
            return synchronizeAndExecuteWithReport().toStartupSecurityReadiness(
                installationId = trustBeforeStartup.installationId,
                observedAtEpochMillis = System.currentTimeMillis(),
            ).also(::publishStartupReadiness)
        }
        val request = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_02,
            trigger = "process_create",
            deadlineEpochMillis = deadlineEpochMillis,
        )
        return try {
            runOrderedStartupDependencyChain(
                ensureEnrollment = {
                    StartupTrace.measureSuspend("Startup.device.enrollment") { managementControlService.ensureDeviceEnrollment() }
                },
                runLc02 = { afterLc02 ->
                    StartupTrace.measureSuspend("Startup.device.lc02") {
                        appOperationalStateCoordinator.performResuming(request, block = afterLc02)
                    }
                },
                afterLc02 = { operationId ->
                    val remainingMillis = deadlineEpochMillis - System.currentTimeMillis()
                    if (remainingMillis <= 0) {
                        StartupSecurityReadiness(
                            state = StartupSecurityReadinessState.RETRYABLE_FAILURE,
                            reasonCode = "STARTUP_READINESS_TIMEOUT",
                            operationId = operationId,
                            observedAtEpochMillis = System.currentTimeMillis(),
                            retryAtEpochMillis = System.currentTimeMillis(),
                        )
                    } else {
                        withTimeout(remainingMillis) {
                            StartupTrace.measureSuspend("Startup.device.sync") {
                                synchronizeAndExecuteWithReport()
                            }.let { report ->
                                report.toStartupSecurityReadiness(
                                    operationId = operationId,
                                    installationId = deviceTrustService.current().installationId,
                                    offlineEligible = report.status == ManagementSyncStatus.RETRY_REQUIRED &&
                                        report.acceptedPolicyVersion != null &&
                                        !managementControlService.isOfflineDeadlineExceeded(),
                                )
                            }
                        }
                    }
                },
            ).also { readiness ->
                publishStartupReadiness(readiness)
            }
        } catch (_: TimeoutCancellationException) {
            publishStartupReadiness(startupReadinessTimeout())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Timber.w(failure, "Management startup readiness remains retryable")
            val reasonCode = startupFailureReasonCode(failure)
            Log.w(
                STARTUP_DIAGNOSTIC_TAG,
                "YZMGMT_STARTUP_FAILURE=$reasonCode type_chain=${startupFailureTypeChain(failure)}",
            )
            publishStartupReadiness(
                StartupSecurityReadiness(
                    state = StartupSecurityReadinessState.RETRYABLE_FAILURE,
                    reasonCode = reasonCode,
                    observedAtEpochMillis = System.currentTimeMillis(),
                    retryAtEpochMillis = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun publishStartupReadiness(readiness: StartupSecurityReadiness): StartupSecurityReadiness {
        mutableStartupReadiness.value = readiness
        return readiness
    }

    private fun startupReadinessTimeout(): StartupSecurityReadiness {
        val now = System.currentTimeMillis()
        return StartupSecurityReadiness(
            state = StartupSecurityReadinessState.RETRYABLE_FAILURE,
            reasonCode = "STARTUP_READINESS_TIMEOUT",
            observedAtEpochMillis = now,
            retryAtEpochMillis = now,
        )
    }

    private suspend fun synchronizeAndExecuteWithReport(): ManagementSyncReport = synchronizationMutex.withLock {
        val policyBefore = policyProvider.current()
        val trustBefore = deviceTrustService.current()
        val cachedCommands = managementControlService.pendingCommands()
        val revokedClosureCommands = commandsAllowedWhileRevoked(trustBefore.trustState, cachedCommands)
        val completingRevokedCommand = revokedClosureCommands.isNotEmpty()
        val syncReport = if (completingRevokedCommand) {
            // A REVOKE command deliberately destroys the local session before its final report can be
            // submitted. On the next process start the installation is already REVOKED, so enrollment and
            // ordinary synchronization must stay blocked. The exact, previously verified command is the one
            // exception: replay it from the signed local cache so the idempotent COMPLETE report can close.
            ManagementSyncReport(
                status = ManagementSyncStatus.SECURITY_REJECTED,
                acceptedPolicyVersion = policyBefore.policy.version,
                pendingCommandCount = revokedClosureCommands.size,
                errorCode = "LOCAL_ACCESS_RESTRICTED",
                deviceSecurityState = ManagedDeviceSecurityState.ACTION_REQUIRED,
            )
        } else {
            managementControlService.synchronize()
        }
        Log.i(
            STARTUP_DIAGNOSTIC_TAG,
            "YZMGMT_SYNC_RESULT status=${syncReport.status.name} error_code=${syncReport.errorCode ?: "NONE"}",
        )
        // A successful online synchronization itself satisfies ONLINE_REQUIRED. Comparing the local clock with the
        // signed server timestamp immediately after a network round trip would otherwise mark every device stale.
        val offlineDeadlineExceeded = !completingRevokedCommand &&
            syncReport.status != ManagementSyncStatus.SYNCHRONIZED &&
            managementControlService.isOfflineDeadlineExceeded()
        if (syncReport.status == ManagementSyncStatus.SYNCHRONIZED) mutableEntryIsOffline.value = false
        if (syncReport.status == ManagementSyncStatus.SECURITY_REJECTED) {
            mutableEntryRestriction.value = syncReport.toStartupSecurityReadiness().reasonCode ?: "SECURITY_POLICY_REJECTED"
            restrictLocalAccess()
        } else if (syncReport.status == ManagementSyncStatus.SYNCHRONIZED && deviceTrustService.current().allowsLocalVaultAccess) {
            mutableEntryRestriction.value = null
        } else if (sessionStore.getAllSessions().isNotEmpty() && managementControlService.offlineEntryStatus() == null) {
            // A transport retry cannot replace a known administrative/security restriction.
            mutableEntryRestriction.update { current ->
                current ?: if (offlineDeadlineExceeded) "OFFLINE_ENTRY_EXPIRED" else retryEntryRestriction(syncReport.errorCode)
            }
            // Offline expiry preserves the account, Vault and drafts. The UI gate hides protected projections.
        }
        val commands = if (completingRevokedCommand) {
            revokedClosureCommands
        } else {
            managementControlService.pendingCommands()
        }
        Log.i(
            STARTUP_DIAGNOSTIC_TAG,
            "YZMGMT_PENDING_COMMANDS count=${commands.size} types=${commands.joinToString(",") { it.type.name }}",
        )
        var commandExecutionFailed = false
        var commandReportPending = false
        commands.forEach { command ->
            runCatchingExceptions { execute(command) }.onFailure { failure ->
                if (failure is ManagementCommandReportException) {
                    commandReportPending = true
                } else {
                    commandExecutionFailed = true
                }
                Log.w(
                    STARTUP_DIAGNOSTIC_TAG,
                    "YZMGMT_COMMAND_FAILURE type=${command.type.name} " +
                        "failure_chain=${startupFailureTypeChain(failure)}",
                )
            }
        }
        val policyAfter = policyProvider.current()
        val deviceTrust = deviceTrustService.current()
        var policyApplyHandshakeFailed = false
        var policyApplicationEvidencePending = false
        var stagedPolicyApplication: ManagementPolicyApplication? = null
        val pendingPolicyApplication = managementControlService.policyApplication()
        if (pendingPolicyApplication != null && pendingPolicyApplication.state in POLICY_APPLICATION_WORK_STATES) {
            if (hasExactPolicyApplicationAuthority(syncReport, policyAfter, pendingPolicyApplication)) {
                try {
                    stagedPolicyApplication = if (shouldStagePolicyCleanup(pendingPolicyApplication)) {
                        managementControlService.stagePolicyCleanup(
                            version = pendingPolicyApplication.version,
                            operations = cleanupPlanForExactVersionHandshake(pendingPolicyApplication),
                        )
                    } else {
                        pendingPolicyApplication
                    }
                } catch (failure: Exception) {
                    policyApplyHandshakeFailed = true
                    Log.w(
                        STARTUP_DIAGNOSTIC_TAG,
                        "YZMGMT_POLICY_APPLY_HANDSHAKE_PENDING version=${pendingPolicyApplication.version} " +
                            "failure_chain=${startupFailureTypeChain(failure)}",
                    )
                }
            } else {
                policyApplyHandshakeFailed = true
            }
        }
        val policyChanged = policyBefore != policyAfter
        val policyProjectionImpact = classifyPolicyProjectionImpact(policyBefore, policyAfter)
        if (policyChanged) {
            mutablePolicyRevision.update { it + 1 }
        }
        val comparedAtEpochMillis = System.currentTimeMillis()
        val authorizationChanged = trustBefore.authorizationFingerprint(comparedAtEpochMillis) !=
            deviceTrust.authorizationFingerprint(comparedAtEpochMillis)
        val mustInvalidateProtectedProjections = shouldInvalidateProtectedProjections(
            policyProjectionImpact = policyProjectionImpact,
            authorizationChanged = authorizationChanged,
            hasGlobalInvalidationCommand = commands.any { command -> command.type.requiresGlobalProjectionInvalidation() },
            securityRejected = syncReport.status == ManagementSyncStatus.SECURITY_REJECTED,
            offlineDeadlineExceeded = offlineDeadlineExceeded,
        )
        val pendingAuthorizationCleanup = pendingAuthorizationCacheCleanup(stagedPolicyApplication)
        if (mustInvalidateProtectedProjections || pendingAuthorizationCleanup != null) {
            // A real policy/trust/revocation boundary still fails closed immediately. A successful
            // no-op foreground synchronization must not erase every authorized timeline projection
            // and make the visible room cycle through encrypted shells again. The one retry exception
            // is an already staged authorization-cache cleanup whose terminal persistence did not finish:
            // repeat the cleanup before marking that exact durable operation complete.
            matrixContentProtectionRuntime.clearSensitiveCaches()
            matrixContentProtectionRuntime.requestAuthorizationRefresh()
            pendingAuthorizationCleanup?.let { operation ->
                stagedPolicyApplication = managementControlService.completePolicyCleanup(
                    version = checkNotNull(stagedPolicyApplication).version,
                    operationId = operation.operationId,
                )
            }
        } else if (policyProjectionImpact == PolicyProjectionImpact.ACCESS_RECOVERY) {
            // Moving from the built-in fail-closed baseline to a verified remote policy may make
            // previously hidden content eligible again. Retry only projections without an exact
            // current-authority cache hit; never clear unrelated authorized history.
            matrixContentProtectionRuntime.requestAuthorizationRefresh(
                MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY,
            )
        }
        stagedPolicyApplication?.let { application ->
            try {
                stagedPolicyApplication = completeLocallyProvablePolicyCleanup(application)
                check(
                    prepareSuccessfulPolicyApplicationReceipt(
                        managementControlService = managementControlService,
                        application = checkNotNull(stagedPolicyApplication),
                        appliedAtEpochMillis = System.currentTimeMillis(),
                    )
                ) {
                    "Earlier policy application evidence must be delivered before final evidence can be prepared."
                }
            } catch (failure: Exception) {
                policyApplicationEvidencePending = true
                Log.w(
                    STARTUP_DIAGNOSTIC_TAG,
                    "YZMGMT_POLICY_APPLICATION_EVIDENCE_PENDING version=${application.version} " +
                        "failure_chain=${startupFailureTypeChain(failure)}",
                )
            }
        }
        val pendingAfterExecution = managementControlService.pendingCommands()
        val remainingSessions = sessionStore.getAllSessions()
        val blockingCommands = pendingAfterExecution.filterNot { command ->
            command.isScopedLocalAccountClear() && remainingSessions.none { session ->
                session.isTokenValid && command.targetsAccountSession(accountIdHash(session.userId), stableCommandSubjectHash(session.deviceId))
            }
        }
        Log.i(
            STARTUP_DIAGNOSTIC_TAG,
            "YZMGMT_PENDING_AFTER_EXECUTION count=${pendingAfterExecution.size} " +
                "types=${pendingAfterExecution.joinToString(",") { it.type.name }}",
        )
        when {
            shouldRejectLocalAccess(
                allowsLocalVaultAccess = deviceTrust.allowsLocalVaultAccess,
                managedApprovalPending = deviceTrust.managedApprovalPending,
            ) -> {
                val reason = mutableEntryRestriction.value ?: "DEVICE_TRUST_NOT_READY"
                mutableEntryRestriction.value = reason
                syncReport.copy(status = ManagementSyncStatus.SECURITY_REJECTED, errorCode = reason)
            }
            commandExecutionFailed ->
                syncReport.copy(status = ManagementSyncStatus.RETRY_REQUIRED, errorCode = "COMMAND_EXECUTION_FAILED")
            policyApplyHandshakeFailed ->
                syncReport.copy(status = ManagementSyncStatus.RETRY_REQUIRED, errorCode = "POLICY_APPLICATION_HANDSHAKE_PENDING")
            policyApplicationEvidencePending ->
                syncReport.copy(status = ManagementSyncStatus.RETRY_REQUIRED, errorCode = "POLICY_APPLICATION_EVIDENCE_PENDING")
            commandReportPending ->
                syncReport.copy(status = ManagementSyncStatus.RETRY_REQUIRED, errorCode = "COMMAND_REPORT_PENDING")
            blockingCommands.isNotEmpty() ->
                syncReport.copy(status = ManagementSyncStatus.RETRY_REQUIRED, errorCode = "COMMAND_EXECUTION_PENDING")
            else -> syncReport
        }
    }

    private suspend fun restrictLocalAccess() {
        val restrictedAt = System.currentTimeMillis()
        deviceTrustService.applyRemoteRestriction(DeviceTrustState.STALE, restrictedAt)
        sessionStore.getAllSessions().forEach { session ->
            vaultKeyService.lock(deriveAccountVaultId(session.userId, session.deviceId))
        }
    }

    private suspend fun completeLocallyProvablePolicyCleanup(
        application: ManagementPolicyApplication,
    ): ManagementPolicyApplication {
        var current = application
        current.pendingCleanup(CLEANUP_NOTIFICATION_REDACTION)?.let { operation ->
            sessionStore.getAllSessions().forEach { session ->
                notificationCleaner.clearAllMessagesEvents(SessionId(session.userId))
            }
            current = managementControlService.completePolicyCleanup(current.version, operation.operationId)
        }
        current.pendingCleanup(CLEANUP_CLIPBOARD)?.let { operation ->
            InAppClipboard.clear()
            current = managementControlService.completePolicyCleanup(current.version, operation.operationId)
        }
        return current
    }

    private fun withJitter(delayMillis: Long): Long {
        val bounded = delayMillis.coerceIn(MIN_HEARTBEAT_INTERVAL_MILLIS, MAX_HEARTBEAT_INTERVAL_MILLIS)
        val spread = maxOf(1L, bounded / 10)
        return bounded + kotlin.random.Random.nextLong(-spread, spread + 1)
    }

    private suspend fun execute(command: VerifiedManagementCommand) {
        val trust = deviceTrustService.current()
        check(command.installationId == trust.installationId)
        if (command.type == ManagementCommandType.CLEAR_ACCOUNT && command.globalDestroyOperationId != null) {
            check(command.reasonCode == "GLOBAL_ACCOUNT_DESTROY")
            // The signed command is the original global saga's CLIENT_CLEAR task. Its authenticated
            // final report is receipted on that root; a device must not create or take ownership of it.
            val result = try {
                executeAccountClear(command)
            } catch (failure: Exception) {
                runCatchingExceptions { reportFailure(command) }.onFailure(failure::addSuppressed)
                throw failure
            }
            report(command, result.state, result.progress)
            return
        }
        check(command.type != ManagementCommandType.CLEAR_ACCOUNT || command.isScopedLocalAccountClear()) {
            "Account clear has no verified local or global intent."
        }
        val lifecycle = command.lifecycleBinding()
        val scopedHandover = command.isScopedLocalAccountClear()
        val commandHash = stableCommandSubjectHash(command.commandId)
        val request = lifecycleOperationRequest(
            lifecycleId = lifecycle.lifecycleId,
            trigger = lifecycle.trigger,
            accountIdHash = command.accountIdHash.takeIf { scopedHandover },
            matrixDeviceIdHash = command.targetMatrixDeviceIdHash.takeIf { scopedHandover },
            eventIdHash = command.contentId?.let(::stableCommandSubjectHash),
            operationId = if (scopedHandover) "managed-session-exit-$commandHash" else "managed-command-$commandHash",
            deadlineEpochMillis = command.expiresAtEpochMillis,
        )
        appOperationalStateCoordinator.withOperation(request) { operationId ->
            val result = try {
                when (command.type) {
                    ManagementCommandType.LOCK -> executeLock(command)
                    ManagementCommandType.REVOKE -> executeRevoke(command)
                    ManagementCommandType.CLEAR_ACCOUNT -> executeAccountClear(command)
                    ManagementCommandType.CLEAR_CONTENT -> executeContentClear(command)
                }
            } catch (failure: Exception) {
                runCatchingExceptions { reportFailure(command) }.onFailure(failure::addSuppressed)
                throw failure
            }
            report(command, result.state, result.progress)
            // WAITING_RESTART only proves the old login was fenced. Its original deletion journal
            // must finish (including stopped UI and closed files) before either cleanup receipt is sent.
            if (scopedHandover && result.state != ManagementCommandState.COMPLETE) return@withOperation
            runCatchingExceptions {
                appOperationalStateCoordinator.recordClientReceipt(
                    LifecycleClientReceipt(
                        operationId = operationId,
                        component = LifecycleComponent.ANDROID_SERVICE,
                        phase = LifecyclePhase.CLIENT_APPLIED,
                        resultCode = "MANAGEMENT_COMMAND_APPLIED",
                        receiptReference = "android-service:managed-command:$operationId",
                        occurredAtEpochMillis = System.currentTimeMillis(),
                    )
                )
                if (scopedHandover) {
                    appOperationalStateCoordinator.recordClientReceipt(
                        LifecycleClientReceipt(
                            operationId = operationId,
                            component = LifecycleComponent.ANDROID_FRONTEND,
                            phase = LifecyclePhase.CLIENT_APPLIED,
                            resultCode = "RETIRED_SESSION_UI_CLOSED",
                            receiptReference = "android-ui:retired-session-cleanup:$operationId",
                            occurredAtEpochMillis = System.currentTimeMillis(),
                        )
                    )
                }
            }.onFailure { failure ->
                Timber.w(
                    "Management command receipt pending type=%s failure_chain=%s",
                    command.type.name,
                    startupFailureTypeChain(failure),
                )
            }
        }
    }

    private suspend fun executeLock(command: VerifiedManagementCommand): ManagementCommandResult {
        mutableEntryRestriction.value = "DEVICE_ADMINISTRATIVELY_LOCKED"
        deviceTrustService.applyRemoteRestriction(DeviceTrustState.STALE, command.issuedAtEpochMillis)
        sessionStore.getAllSessions().forEach { session ->
            val vaultId = deriveAccountVaultId(session.userId, session.deviceId)
            secureMediaLeaseManager.revokeVault(vaultId)
            vaultKeyService.lock(vaultId)
        }
        io.element.android.libraries.androidutils.clipboard.InAppClipboard.clear()
        return ManagementCommandResult(ManagementCommandState.COMPLETE, 100)
    }

    private suspend fun executeRevoke(command: VerifiedManagementCommand): ManagementCommandResult {
        deviceTrustService.applyRemoteRestriction(DeviceTrustState.REVOKED, command.issuedAtEpochMillis)
        deviceShareKeyService.destroy()
        val sessions = sessionStore.getAllSessions()
        if (sessions.isEmpty()) {
            return ManagementCommandResult(ManagementCommandState.COMPLETE, 100)
        }
        sessions.forEach { session ->
            vaultKeyService.destroy(deriveAccountVaultId(session.userId, session.deviceId), VaultDestroyReason.DEVICE_REVOKED)
            prepareDeletion(command, session, DeletionReason.DEVICE_REVOKED)
        }
        return ManagementCommandResult(ManagementCommandState.WAITING_RESTART, 70)
    }

    private suspend fun executeAccountClear(command: VerifiedManagementCommand): ManagementCommandResult {
        checkNotNull(command.accountIdHash)
        val sessions = sessionStore.getAllSessions().filter {
            command.targetsAccountSession(accountIdHash(it.userId), stableCommandSubjectHash(it.deviceId))
        }
        if (sessions.isEmpty()) {
            // Removing the old SessionStore row is not evidence that its files were cleaned.
            val recovery = dataDeletionCoordinator.resumePending()
            check(recovery.unreadableJournalEntries == 0) { "Account cleanup journal is unreadable." }
            val operationId = "managed-${command.commandId}-${checkNotNull(command.accountIdHash).take(12)}"
            val signedOutOperationId = command.targetMatrixDeviceIdHash?.let { matrixHash ->
                io.element.android.security.dataprotection.api.signedOutCleanupOperationId(checkNotNull(command.accountIdHash), matrixHash).value
            }
            if (recovery.operationReports.any {
                !it.isComplete && (it.operationId.value in setOf(operationId, signedOutOperationId) ||
                    (command.globalDestroyOperationId != null && (it.accountIdHash == null || it.accountIdHash == command.accountIdHash)))
            }) {
                return ManagementCommandResult(ManagementCommandState.WAITING_RESTART, 70)
            }
            return ManagementCommandResult(ManagementCommandState.COMPLETE, 100)
        }
        // LC-22 revokes one account identity, not the installation itself. Changing the shared
        // device-trust state here would lock every unrelated account stored on the same phone.
        // Device-wide trust transitions remain owned by LOCK/REVOKE (LC-09/LC-07).
        sessions.forEach { session ->
            prepareDeletion(command, session, DeletionReason.ADMINISTRATIVE_REVOCATION)
            secureMediaLeaseManager.revokeVault(deriveAccountVaultId(session.userId, session.deviceId))
            check(vaultKeyService.destroy(
                deriveAccountVaultId(session.userId, session.deviceId),
                VaultDestroyReason.ADMINISTRATIVE_REVOCATION,
            )) { "Account key retirement failed." }
            // Stop displaying this exact login immediately. WAITING_RESTART concerns durable SDK/file
            // cleanup and never permits decrypted UI to remain visible after its key was destroyed.
            sessionStore.invalidateSessionIfDeviceMatches(session.userId, session.deviceId)
        }
        return ManagementCommandResult(ManagementCommandState.WAITING_RESTART, 70)
    }

    private suspend fun executeContentClear(command: VerifiedManagementCommand): ManagementCommandResult {
        var targetTerminalCommitted = false
        try {
            val contentId = ContentId(checkNotNull(command.contentId))
            val sessions = sessionStore.getAllSessions()
            val locallyRevoked = sessions.map { session ->
                try {
                    contentProtectionService.revokeLocalContent(
                        vaultId = deriveAccountVaultId(session.userId, session.deviceId),
                        contentId = contentId,
                    )
                } catch (_: SecureVaultDestroyedException) {
                    // Account-wide cryptographic erasure is a stronger terminal condition than a
                    // per-content tombstone. Treat the target as already inaccessible while still
                    // continuing with other sessions and process-level cache invalidation.
                    true
                }
            }
            check(locallyRevoked.all { it }) { "Local content revocation failed." }
            secureMediaLeaseManager.revokeScope("content:${contentId.value}")
            sessions.forEach { session -> notificationCleaner.clearAllMessagesEvents(SessionId(session.userId)) }
            // CLEAR_CONTENT is a permanent terminal transition for one content id, not an account,
            // trust or authorization boundary. Its local tombstone is already durable above. Clear and
            // re-project only that target so unrelated history never falls back to security shells.
            matrixContentProtectionRuntime.requestContentInvalidation(contentId)
            targetTerminalCommitted = true
            return ManagementCommandResult(ManagementCommandState.COMPLETE, 100)
        } catch (failure: Exception) {
            if (!targetTerminalCommitted) {
                // If the content tombstone could not be durably committed, retaining any process-memory
                // plaintext would violate the remote destruction request. Fall back to the existing hard
                // fail-closed boundary; reporting failures after a successful target commit do not need it.
                matrixContentProtectionRuntime.clearSensitiveCaches()
                matrixContentProtectionRuntime.requestAuthorizationRefresh()
            }
            throw failure
        }
    }

    private suspend fun prepareDeletion(
        command: VerifiedManagementCommand,
        session: SessionData,
        reason: DeletionReason,
    ) {
        val vaultId = deriveAccountVaultId(session.userId, session.deviceId)
        val report = dataDeletionCoordinator.prepare(
            DeletionRequest(
                operationId = DeletionOperationId("managed-${command.commandId}-${accountIdHash(session.userId).take(12)}"),
                scope = DeletionScope.Account(
                    accountIdHash = accountIdHash(session.userId),
                    vaultId = vaultId.value,
                    localSessionId = session.userId,
                    sessionFilePath = session.sessionPath,
                    sessionCachePath = session.cachePath,
                ),
                reason = reason,
                policyVersion = policyProvider.current().policy.version,
                requestedAtEpochMillis = command.issuedAtEpochMillis,
            )
        )
        check(report.errorCode == null) { "Account cleanup preparation failed." }
    }

    private suspend fun report(
        command: VerifiedManagementCommand,
        state: ManagementCommandState,
        progress: Int,
        errorCode: String? = null,
    ) {
        check(
            managementControlService.reportCommand(
                ManagementCommandExecutionReport(command.commandId, command.type, state, progress, errorCode)
            )
        ) { throw ManagementCommandReportException() }
    }

    private suspend fun reportFailure(command: VerifiedManagementCommand) {
        check(
            managementControlService.reportCommand(
                ManagementCommandExecutionReport(
                    commandId = command.commandId,
                    commandType = command.type,
                    state = ManagementCommandState.FAILED,
                    progress = 0,
                    errorCode = ERROR_LOCAL_EXECUTION_FAILED,
                )
            )
        ) { throw ManagementCommandReportException() }
    }

    private data class ManagementCommandResult(val state: ManagementCommandState, val progress: Int)

    private companion object {
        const val ERROR_LOCAL_EXECUTION_FAILED = "LOCAL_COMMAND_EXECUTION_FAILED"
        const val DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 60_000L
        const val STARTUP_READINESS_TIMEOUT_MILLIS = 10_000L
        const val MIN_HEARTBEAT_INTERVAL_MILLIS = 30_000L
        const val MAX_HEARTBEAT_INTERVAL_MILLIS = 5 * 60_000L
    }
}

/**
 * Converts implementation exceptions into stable, user-safe startup reasons. Exception messages,
 * URLs, identifiers and response bodies must never cross this projection boundary.
 */
internal fun retryEntryRestriction(failureReason: String?): String = when {
    failureReason in setOf("CONTROL_DNS_UNAVAILABLE", "CONTROL_CONNECTION_FAILED", "CONTROL_REQUEST_TIMEOUT", "CONTROL_NETWORK_IO",
        "CONTROL_SERVER_UNAVAILABLE", "CONTROL_RATE_LIMITED", "CONTROL_PLANE_UNAVAILABLE") -> checkNotNull(failureReason)
    else -> "CONTROL_PLANE_UNAVAILABLE"
}

internal fun startupFailureReasonCode(failure: Throwable): String {
    val chain = generateSequence(failure) { it.cause }.take(MAX_STARTUP_FAILURE_CAUSES).toList()
    fun hasType(vararg typeNames: String): Boolean = chain.any { throwable ->
        generateSequence<Class<*>>(throwable.javaClass) { it.superclass }.any { it.name in typeNames || it.simpleName in typeNames }
    }
    return when {
        hasType(
            "android.security.keystore.KeyPermanentlyInvalidatedException",
            "android.security.keystore.UserNotAuthenticatedException",
            "android.security.KeyStoreException",
            "java.security.InvalidKeyException",
            "java.security.KeyStoreException",
            "java.security.ProviderException",
            "java.security.SignatureException",
            "java.security.UnrecoverableKeyException",
            "KeyPermanentlyInvalidatedException",
            "UserNotAuthenticatedException",
            "InvalidKeyException",
            "KeyStoreException",
            "ProviderException",
            "SignatureException",
            "UnrecoverableKeyException",
        ) -> "DEVICE_SIGNING_KEY_UNAVAILABLE"
        hasType(
            "javax.net.ssl.SSLHandshakeException",
            "javax.net.ssl.SSLPeerUnverifiedException",
            "java.security.cert.CertificateException",
            "SSLHandshakeException",
            "SSLPeerUnverifiedException",
            "CertificateException",
        ) -> "CONTROL_TLS_REJECTED"
        hasType("java.net.UnknownHostException", "UnknownHostException") -> "CONTROL_DNS_UNAVAILABLE"
        hasType("java.net.SocketTimeoutException", "SocketTimeoutException") -> "CONTROL_REQUEST_TIMEOUT"
        hasType("java.net.ConnectException", "ConnectException") -> "CONTROL_CONNECTION_FAILED"
        hasType("HttpStatusException") -> "CONTROL_SERVER_REJECTED"
        hasType("java.io.IOException", "IOException") -> "CONTROL_NETWORK_IO"
        chain.any { it is SecurityException } -> "SECURITY_POLICY_REJECTED"
        chain.any { it is IllegalStateException } -> "STARTUP_AUTHORITY_CONTRADICTORY"
        else -> "CONTROL_PLANE_UNAVAILABLE"
    }
}

internal fun startupFailureTypeChain(failure: Throwable): String = generateSequence(failure) { it.cause }
    .take(MAX_STARTUP_FAILURE_CAUSES)
    .joinToString(separator = ">") { throwable ->
        throwable.javaClass.simpleName.ifBlank { "AnonymousFailure" }
    }

internal suspend fun <T> runOrderedStartupDependencyChain(
    ensureEnrollment: suspend () -> Unit,
    runLc02: suspend (suspend (operationId: String) -> T) -> T,
    afterLc02: suspend (operationId: String) -> T,
): T {
    ensureEnrollment()
    return runLc02(afterLc02)
}

internal fun VerifiedManagementCommand.isScopedMobileHandover(): Boolean =
    type == ManagementCommandType.CLEAR_ACCOUNT && reasonCode == "ACCOUNT_MOBILE_SUPERSEDED" &&
        accountIdHash != null && targetMatrixDeviceIdHash != null && (mobileGeneration ?: 0) > 0

internal fun VerifiedManagementCommand.isScopedLocalAccountClear(): Boolean =
    type == ManagementCommandType.CLEAR_ACCOUNT && reasonCode in setOf("ACCOUNT_MOBILE_SUPERSEDED", "ADMIN_LOCAL_ACCOUNT_CLEAR") &&
        accountIdHash != null && targetMatrixDeviceIdHash != null && (mobileGeneration ?: 0) > 0 && globalDestroyOperationId == null

internal fun shouldRejectLocalAccess(
    allowsLocalVaultAccess: Boolean,
    managedApprovalPending: Boolean,
): Boolean = !allowsLocalVaultAccess && !managedApprovalPending

internal fun shouldKeepManagementHeartbeatRunning(
    isProcessStarted: Boolean,
    hasRingingCall: Boolean,
    isInCall: Boolean,
): Boolean = isProcessStarted || hasRingingCall || isInCall

/**
 * A revoked installation may only finish the exact signed revoke command already stored locally.
 * It may not execute lock, content-clear or account-clear work and it never re-enters enrollment.
 */
internal fun commandsAllowedWhileRevoked(
    trustState: DeviceTrustState,
    cachedCommands: List<VerifiedManagementCommand>,
): List<VerifiedManagementCommand> = if (trustState == DeviceTrustState.REVOKED) {
    cachedCommands.filter { command -> command.type == ManagementCommandType.REVOKE }
} else {
    emptyList()
}

internal fun shouldInvalidateProtectedProjections(
    policyProjectionImpact: PolicyProjectionImpact,
    authorizationChanged: Boolean,
    hasGlobalInvalidationCommand: Boolean,
    securityRejected: Boolean,
    offlineDeadlineExceeded: Boolean,
): Boolean = policyProjectionImpact == PolicyProjectionImpact.HARD_INVALIDATION ||
    authorizationChanged ||
    hasGlobalInvalidationCommand ||
    securityRejected ||
    offlineDeadlineExceeded

/**
 * Describes how a synchronized policy transition may affect already-authorized timeline content.
 *
 * Existing content is bound to its immutable retained signed policy. Publishing another verified
 * policy (including a retention-only correction) therefore affects new sends and presentation
 * controls, but is not an authorization revocation for historical projections. Losing the verified
 * source remains an immediate hard fail-closed boundary. Gaining it from the built-in baseline may
 * retry hidden content without clearing exact current-authority cache entries.
 */
internal enum class PolicyProjectionImpact {
    NONE,
    PRESENTATION_ONLY,
    ACCESS_RECOVERY,
    HARD_INVALIDATION,
}

internal fun classifyPolicyProjectionImpact(
    before: ActiveDataProtectionPolicy,
    after: ActiveDataProtectionPolicy,
): PolicyProjectionImpact = when {
    before == after -> PolicyProjectionImpact.NONE
    after.source != DataProtectionPolicySource.VERIFIED_REMOTE -> PolicyProjectionImpact.HARD_INVALIDATION
    before.source != DataProtectionPolicySource.VERIFIED_REMOTE -> PolicyProjectionImpact.ACCESS_RECOVERY
    before.policy.hasProjectionAuthorizationChange(after.policy) -> PolicyProjectionImpact.HARD_INVALIDATION
    else -> PolicyProjectionImpact.PRESENTATION_ONLY
}

/**
 * Fields that can change whether plaintext is allowed to remain projected in this process.
 *
 * Retention and UI capability fields deliberately stay out of this fingerprint: existing content
 * resolves the immutable signed policy stored in its envelope, while a newly published policy is
 * used for new sends and live presentation controls. Access, hardware and envelope requirements
 * are security boundaries and therefore retain the original immediate fail-closed invalidation.
 */
private fun DataProtectionPolicyV1.hasProjectionAuthorizationChange(after: DataProtectionPolicyV1): Boolean =
    policyId != after.policyId ||
        maximumOfflineMillis != after.maximumOfflineMillis ||
        offlineAccessMode != after.offlineAccessMode ||
        hardwareTrustRequirement != after.hardwareTrustRequirement ||
        requireContentEnvelope != after.requireContentEnvelope

internal fun ManagementCommandType.requiresGlobalProjectionInvalidation(): Boolean =
    this == ManagementCommandType.LOCK || this == ManagementCommandType.REVOKE

internal fun hasExactPolicyApplicationAuthority(
    syncReport: ManagementSyncReport,
    activePolicy: ActiveDataProtectionPolicy,
    application: ManagementPolicyApplication,
): Boolean = syncReport.status == ManagementSyncStatus.SYNCHRONIZED &&
    syncReport.acceptedPolicyVersion == application.version &&
    activePolicy.source == DataProtectionPolicySource.VERIFIED_REMOTE &&
    activePolicy.policy.version == application.version &&
    application.state in POLICY_APPLICATION_WORK_STATES

internal fun shouldStagePolicyCleanup(application: ManagementPolicyApplication): Boolean =
    application.state == ManagementPolicyApplicationState.PENDING_APPLY &&
        application.applyHandshakeAtEpochMillis == null

internal fun cleanupPlanForExactVersionHandshake(
    application: ManagementPolicyApplication,
): List<PolicyCleanupOperation> {
    check(shouldStagePolicyCleanup(application))
    check(application.cleanupOperations.all { operation ->
        operation.state == PolicyCleanupOperationState.PENDING
    })
    return application.cleanupOperations
}

internal fun pendingAuthorizationCacheCleanup(
    application: ManagementPolicyApplication?,
): PolicyCleanupOperation? = application
    ?.cleanupOperations
    ?.firstOrNull { operation ->
        operation.kind == CLEANUP_AUTHORIZATION_CACHE &&
            operation.state == PolicyCleanupOperationState.PENDING
    }

internal fun ManagementPolicyApplication.pendingCleanup(kind: String): PolicyCleanupOperation? =
    cleanupOperations.firstOrNull { operation ->
        operation.kind == kind && operation.state == PolicyCleanupOperationState.PENDING
    }

internal fun successfulPolicyApplicationEvidence(
    application: ManagementPolicyApplication,
    appliedAtEpochMillis: Long,
): PolicyApplicationEvidence {
    check(application.state in POLICY_APPLICATION_WORK_STATES)
    check(application.applyHandshakeAtEpochMillis != null)
    check(appliedAtEpochMillis > 0)
    val result = if (application.cleanupOperations.any { it.state == PolicyCleanupOperationState.PENDING }) {
        ManagementPolicyApplicationState.APPLIED_CLEANUP_PENDING
    } else {
        ManagementPolicyApplicationState.APPLIED
    }
    return PolicyApplicationEvidence(
        version = application.version,
        appliedAtEpochMillis = appliedAtEpochMillis,
        enforcedCapabilities = application.requiredCapabilities,
        refusedCapabilities = emptyList(),
        result = result,
    )
}

internal suspend fun prepareSuccessfulPolicyApplicationReceipt(
    managementControlService: ManagementControlService,
    application: ManagementPolicyApplication,
    appliedAtEpochMillis: Long,
): Boolean = managementControlService.preparePolicyApplicationReceipt(
    successfulPolicyApplicationEvidence(application, appliedAtEpochMillis),
)

private const val CLEANUP_AUTHORIZATION_CACHE = "AUTHORIZATION_CACHE"
private const val CLEANUP_NOTIFICATION_REDACTION = "NOTIFICATION_REDACTION"
private const val CLEANUP_CLIPBOARD = "CLIPBOARD_CLEAR"
private val POLICY_APPLICATION_WORK_STATES = setOf(
    ManagementPolicyApplicationState.PENDING_APPLY,
    ManagementPolicyApplicationState.APPLIED_CLEANUP_PENDING,
)

internal data class ManagementCommandLifecycleBinding(
    val lifecycleId: FullAppLifecycleId,
    val trigger: String,
)

internal fun VerifiedManagementCommand.lifecycleBinding(): ManagementCommandLifecycleBinding = when (type) {
    ManagementCommandType.LOCK -> ManagementCommandLifecycleBinding(FullAppLifecycleId.LC_09, "policy_changed")
    ManagementCommandType.REVOKE -> ManagementCommandLifecycleBinding(FullAppLifecycleId.LC_07, "device_revoke")
    ManagementCommandType.CLEAR_ACCOUNT -> ManagementCommandLifecycleBinding(
        FullAppLifecycleId.LC_22,
        if (isScopedLocalAccountClear()) "remove_local_account" else "admin_destroy",
    )
    ManagementCommandType.CLEAR_CONTENT -> ManagementCommandLifecycleBinding(FullAppLifecycleId.LC_21, "admin_destroy")
}

private fun stableCommandSubjectHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private class ManagementCommandReportException : IllegalStateException("Management command report delivery failed.")

private fun DeviceTrustSnapshot.authorizationFingerprint(nowEpochMillis: Long): List<Any?> = listOf(
    installationId,
    hardwareSecurityLevel,
    trustState,
    attestationVersion,
    attestationPublicKeySha256,
    riskSignals,
    managedApprovalPending,
    allowsLocalVaultAccess,
    managedLicense?.licenseId,
    managedLicense?.devicePublicKeySha256,
    managedLicense?.contentShareKeyId,
    managedLicense?.appPackageName,
    managedLicense?.appVersionCode,
    managedLicense?.revision,
    managedLicense?.allowedScopes,
    managedLicense?.accountIdHashes,
    managedLicense?.isActive(nowEpochMillis),
)
