/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.libraries.androidutils.clipboard.InAppClipboard
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.push.api.notifications.NotificationCleaner
import io.element.android.libraries.sessionstorage.api.LoggedInState
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultProvisioningRequest
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Keeps account Vault keys aligned with account selection, app visibility, and the application lock screen. */
@Inject
@SingleIn(AppScope::class)
class AccountVaultLifecycleCoordinator(
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
    private val sessionStore: SessionStore,
    private val lockScreenService: LockScreenService,
    private val featureFlagService: FeatureFlagService,
    private val deviceTrustService: DeviceTrustService,
    private val vaultKeyService: VaultKeyService,
    private val matrixContentProtectionRuntime: MatrixContentProtectionRuntime,
    private val protectedPreviewAuthorizationGate: ProtectedPreviewAuthorizationGate,
    private val notificationCleaner: NotificationCleaner,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
) : DefaultLifecycleObserver {
    private val lifecycleMutex = Mutex()
    private val isAppVisible = MutableStateFlow(false)
    private var observationJob: Job? = null
    private var lockTimeoutObservationJob: Job? = null
    private var managedVaultIds = emptySet<AccountVaultId>()
    private var managedSessionIds = emptySet<String>()
    private var authorizationRecoveryPending = true

    fun startAsync() {
        appCoroutineScope.launch(Dispatchers.Main.immediate) {
            start()
        }
    }

    suspend fun start() {
        lifecycleMutex.withLock {
            if (observationJob != null) return
            withContext(Dispatchers.Main.immediate) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(this@AccountVaultLifecycleCoordinator)
            }
            val accountLifecycle = combine(
                sessionStore.sessionsFlow(),
                sessionStore.loggedInStateFlow(),
                lockScreenService.lockState,
                lockScreenService.isPinSetup(),
                isAppVisible,
            ) { sessions, loggedInState, lockState, isPinSetup, appVisible ->
                LifecycleSnapshot(sessions, loggedInState, lockState, isPinSetup, appVisible)
            }
            observationJob = accountLifecycle
                .combine(deviceTrustService.trust()) { lifecycle, deviceTrust ->
                    lifecycle.copy(deviceTrustAllowsAccess = deviceTrust.allowsLocalVaultAccess)
                }
                .combine(protectedPreviewAuthorizationGate.accessState) { lifecycle, previewAccess ->
                    lifecycle.copy(previewAccessAllows = previewAccess.allowsPreviewAttempt)
                }
                .distinctUntilChanged()
                .onEach(::reconcile)
                .launchIn(appCoroutineScope)
            // The app scope survives activity recreation/background. Subscribe once to the actual
            // timer event; observing an initial Locked state must not invent a lock_timeout intent.
            lockTimeoutObservationJob = lockScreenService.backgroundLockTimeouts
                .onEach {
                    runCatchingExceptions {
                        appOperationalStateCoordinator.perform(
                            lifecycleOperationRequest(FullAppLifecycleId.LC_09, "lock_timeout")
                        ) {
                            lifecycleMutex.withLock {
                                check(lockScreenService.lockState.value == LockScreenLockState.Locked) {
                                    "Timeout protection was superseded before enforcement."
                                }
                                managedVaultIds.forEach { vaultKeyService.lock(it) }
                                InAppClipboard.clear()
                                matrixContentProtectionRuntime.clearSensitiveCaches()
                            }
                        }
                    }.onFailure {
                        Timber.tag(LOG_TAG).w(it, "Background lock lifecycle enforcement receipt is pending.")
                    }
                }
                .launchIn(appCoroutineScope)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        isAppVisible.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppVisible.value = false
    }

    fun onMemoryPressure() {
        appCoroutineScope.launch { lockAllManagedVaults() }
    }

    private suspend fun reconcile(snapshot: LifecycleSnapshot) {
        lifecycleMutex.withLock {
            val sessionVaults = snapshot.sessions.associateWith { session ->
                deriveAccountVaultId(session.userId, session.deviceId)
            }
            val currentSessionIds = sessionVaults.keys.mapTo(mutableSetOf()) { session -> session.userId }
            val currentAuthorizationRevision = matrixContentProtectionRuntime.authorizationRevision()
            (managedSessionIds - currentSessionIds).forEach { removedSessionId ->
                InAppClipboard.clearForSession(removedSessionId, currentAuthorizationRevision)
            }
            managedSessionIds = currentSessionIds
            managedVaultIds = sessionVaults.values.toSet()
            val requireTrustedHardware = featureFlagService.isFeatureEnabled(FeatureFlags.HardwareBackedVault)
            sessionVaults.forEach { (session, vaultId) ->
                runCatchingExceptions {
                    vaultKeyService.provision(
                        VaultProvisioningRequest(
                            vaultId = vaultId,
                            accountIdHash = accountIdHash(session.userId),
                            requireUserAuthentication = snapshot.isPinSetup,
                            requireTrustedHardware = requireTrustedHardware,
                        )
                    )
                }.onFailure {
                    Timber.tag(LOG_TAG).e(it, "Unable to provision an account Vault.")
                }
            }

            val activeSessionId = (snapshot.loggedInState as? LoggedInState.LoggedIn)?.sessionId
            var activeVaultUnlocked = false
            sessionVaults.forEach { (session, vaultId) ->
                val canUnlock = snapshot.previewAccessAllows &&
                    snapshot.deviceTrustAllowsAccess &&
                    snapshot.lockState == LockScreenLockState.Unlocked &&
                    session.userId == activeSessionId
                val reconciled = runCatchingExceptions {
                    if (canUnlock) {
                        check(vaultKeyService.unlock(vaultId)) { "Account Vault unlock was rejected." }
                    } else {
                        vaultKeyService.lock(vaultId)
                    }
                    canUnlock
                }.onFailure {
                    Timber.tag(LOG_TAG).e(it, "Unable to reconcile an account Vault state.")
                }.getOrDefault(false)
                if (session.userId == activeSessionId) activeVaultUnlocked = reconciled
            }
            val authorizationReady = snapshot.previewAccessAllows &&
                snapshot.lockState == LockScreenLockState.Unlocked &&
                snapshot.deviceTrustAllowsAccess &&
                activeSessionId != null &&
                activeVaultUnlocked
            val shouldRecoverAuthorization = shouldRequestAuthorizationRecovery(
                authorizationReady = authorizationReady,
                appVisible = snapshot.appVisible,
                recoveryPending = authorizationRecoveryPending,
            )
            if (!authorizationReady) {
                authorizationRecoveryPending = true
                sessionVaults.keys.forEach { session ->
                    notificationCleaner.clearAllMessagesEvents(SessionId(session.userId))
                }
                matrixContentProtectionRuntime.clearSensitiveCaches()
                matrixContentProtectionRuntime.requestAuthorizationRefresh()
            } else if (shouldRecoverAuthorization) {
                Timber.tag(LOG_TAG).d("YZCP_VAULT_READY_RECOVERY")
                // Startup first publishes a conservative NOT_ENROLLED/locked snapshot, then loads
                // the durable trust record and unlocks the active Vault. The hard invalidation from
                // that conservative phase must be followed by an explicit recovery revision, or a
                // historical attachment that failed once can remain on its safe shell indefinitely.
                // This remains a hard refresh because Vault/trust recovery is an authorization
                // boundary. SELECTIVE_CACHE_EXPIRY is reserved for authenticated cache expiry.
                matrixContentProtectionRuntime.requestAuthorizationRefresh()
                authorizationRecoveryPending = false
            }
        }
    }

    private suspend fun lockAllManagedVaults() {
        lifecycleMutex.withLock {
            deviceTrustService.markLocked()
            managedVaultIds.forEach { vaultId ->
                runCatchingExceptions { vaultKeyService.lock(vaultId) }
                    .onFailure { Timber.tag(LOG_TAG).e(it, "Unable to release an account Vault key.") }
            }
            InAppClipboard.clear()
            matrixContentProtectionRuntime.clearSensitiveCaches()
        }
    }

    private data class LifecycleSnapshot(
        val sessions: List<SessionData>,
        val loggedInState: LoggedInState,
        val lockState: LockScreenLockState,
        val isPinSetup: Boolean,
        val appVisible: Boolean,
        val deviceTrustAllowsAccess: Boolean = false,
        val previewAccessAllows: Boolean = false,
    )

    private companion object {
        const val LOG_TAG = "AccountVault"
    }
}

internal fun shouldRequestAuthorizationRecovery(
    authorizationReady: Boolean,
    appVisible: Boolean,
    recoveryPending: Boolean,
): Boolean = authorizationReady && appVisible && recoveryPending
