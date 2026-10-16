/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav

import androidx.compose.runtime.setValue

import android.app.Activity
import android.os.Parcelable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumble.appyx.core.composable.PermanentChild
import com.bumble.appyx.core.lifecycle.subscribe
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.navigation.NavElements
import com.bumble.appyx.core.navigation.NavKey
import com.bumble.appyx.core.navigation.model.permanent.PermanentNavModel
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.node
import com.bumble.appyx.core.plugin.Plugin
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.BackStack.State.ACTIVE
import com.bumble.appyx.navmodel.backstack.BackStack.State.CREATED
import com.bumble.appyx.navmodel.backstack.BackStack.State.STASHED
import com.bumble.appyx.navmodel.backstack.BackStackElement
import com.bumble.appyx.navmodel.backstack.BackStackElements
import com.bumble.appyx.navmodel.backstack.operation.BackStackOperation
import com.bumble.appyx.navmodel.backstack.operation.Push
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import com.bumble.appyx.navmodel.backstack.operation.replace
import com.bumble.appyx.navmodel.backstack.operation.singleTop
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.appnav.di.MatrixSessionCache
import io.element.android.appnav.loggedin.AccountReactivationCoordinator
import io.element.android.appnav.loggedin.LoggedInNode
import io.element.android.appnav.loggedin.MediaPreviewConfigMigration
import io.element.android.appnav.loggedin.SendQueues
import io.element.android.appnav.loggedin.StandardHistoryRecoveryCoordinator
import io.element.android.appnav.room.RoomFlowNode
import io.element.android.appnav.room.RoomNavigationTarget
import io.element.android.appnav.room.joined.JoinedRoomLoadedFlowNode
import io.element.android.appnav.securitysetup.IdentityResetConvergenceState
import io.element.android.appnav.securitysetup.SecuritySetupRoute
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.createroom.api.CreateRoomEntryPoint
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.enterprise.api.SessionEnterpriseService
import io.element.android.features.ftue.api.FtueEntryPoint
import io.element.android.features.ftue.api.state.FtueService
import io.element.android.features.ftue.api.state.FtueState
import io.element.android.features.home.api.HomeEntryPoint
import io.element.android.features.linknewdevice.api.LinkNewDeviceEntryPoint
import io.element.android.features.location.api.live.ActiveLiveLocationShareManager
import io.element.android.features.lockscreen.api.LockScreenEntryPoint
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.features.networkmonitor.api.NetworkMonitor
import io.element.android.features.networkmonitor.api.NetworkStatus
import io.element.android.features.networkmonitor.api.ui.ConnectivityIndicatorContainer
import io.element.android.features.preferences.api.PreferencesEntryPoint
import io.element.android.features.preferences.api.PreferencesRootDestination
import io.element.android.features.roomdirectory.api.RoomDescription
import io.element.android.features.roomdirectory.api.RoomDirectoryEntryPoint
import io.element.android.features.securebackup.api.RecoveryKeySubmissionService
import io.element.android.features.securebackup.api.requiresGate
import io.element.android.features.securebackup.api.SecureBackupEntryPoint
import io.element.android.features.share.api.ShareEntryPoint
import io.element.android.features.share.api.ShareIntentData
import io.element.android.features.share.api.ShareResult
import io.element.android.features.startchat.api.StartChatEntryPoint
import io.element.android.features.userprofile.api.UserProfileEntryPoint
import io.element.android.features.verifysession.api.IncomingVerificationEntryPoint
import io.element.android.features.verifysession.api.OutgoingVerificationEntryPoint
import io.element.android.libraries.androidutils.startup.StartupTrace
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.architecture.BaseFlowNode
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.architecture.waitForChildAttached
import io.element.android.libraries.architecture.waitForNavTargetAttached
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.designsystem.theme.ElementThemeApp
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.RoomIdOrAlias
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.sync.SyncService
import io.element.android.libraries.matrix.api.verification.SessionVerificationServiceListener
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.libraries.matrix.api.verification.VerificationRequest
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.push.api.notifications.conversations.NotificationConversationService
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.PermanentAccountDeletionCoordinator
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.services.analytics.api.AnalyticsLongRunningTransaction
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.analytics.api.watchers.AnalyticsRoomListStateWatcher
import io.element.android.services.appnavstate.api.AppNavigationStateService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import im.vector.app.features.analytics.plan.JoinedRoom as JoinedRoomAnalyticsEvent

// The maximum number of room nodes that should be kept in the backstack at the same time.
// Having 5 rooms in the backstack seems reasonable and shouldn't grow the saved state size too much.
private const val MAX_ROOM_NODE_COUNT = 5
private const val YIZHE_INTERNAL_APPLICATION_ID = "com.yizhe.chat.internal"
private const val LOGGED_IN_STARTUP_TIMEOUT_MILLIS = 15_000L
private const val POST_LOGIN_STARTUP_FAILURE = "POST_LOGIN_STARTUP_FAILED"
private const val CORE_READY_STARTUP_TIMEOUT = "CORE_READY_STARTUP_TIMEOUT"

@ContributesNode(SessionScope::class)
@AssistedInject
class LoggedInFlowNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val homeEntryPoint: HomeEntryPoint,
    private val preferencesEntryPoint: PreferencesEntryPoint,
    private val startChatEntryPoint: StartChatEntryPoint,
    private val appNavigationStateService: AppNavigationStateService,
    private val secureBackupEntryPoint: SecureBackupEntryPoint,
    private val userProfileEntryPoint: UserProfileEntryPoint,
    private val ftueEntryPoint: FtueEntryPoint,
    private val linkNewDeviceEntryPoint: LinkNewDeviceEntryPoint,
    private val lockScreenEntryPoint: LockScreenEntryPoint,
    private val lockScreenService: LockScreenService,
    @SessionCoroutineScope
    private val sessionCoroutineScope: CoroutineScope,
    private val ftueService: FtueService,
    private val roomDirectoryEntryPoint: RoomDirectoryEntryPoint,
    private val shareEntryPoint: ShareEntryPoint,
    private val matrixClient: MatrixClient,
    private val matrixSessionCache: MatrixSessionCache,
    private val sendingQueue: SendQueues,
    private val incomingVerificationEntryPoint: IncomingVerificationEntryPoint,
    private val outgoingVerificationEntryPoint: OutgoingVerificationEntryPoint,
    private val mediaPreviewConfigMigration: MediaPreviewConfigMigration,
    private val sessionEnterpriseService: SessionEnterpriseService,
    private val networkMonitor: NetworkMonitor,
    private val notificationConversationService: NotificationConversationService,
    private val syncService: SyncService,
    private val enterpriseService: EnterpriseService,
    private val appPreferencesStore: AppPreferencesStore,
    private val featureFlagService: FeatureFlagService,
    private val buildMeta: BuildMeta,
    snackbarDispatcher: SnackbarDispatcher,
    private val analyticsService: AnalyticsService,
    private val analyticsRoomListStateWatcher: AnalyticsRoomListStateWatcher,
    private val createRoomEntryPoint: CreateRoomEntryPoint,
    private val activeLiveLocationShareManager: ActiveLiveLocationShareManager,
    private val managementControlService: ManagementControlService,
    private val policyRevisionProvider: io.element.android.security.dataprotection.api.DataProtectionPolicyRevisionProvider,
    private val managementSecurityRefreshService: io.element.android.security.dataprotection.api.ManagementSecurityRefreshService,
    private val standardHistoryRecoveryCoordinator: StandardHistoryRecoveryCoordinator,
    private val accountReactivationCoordinator: AccountReactivationCoordinator,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val permanentAccountDeletionCoordinator: PermanentAccountDeletionCoordinator,
    private val kmsShareClient: KmsShareClient,
    private val recoveryKeySubmissionService: RecoveryKeySubmissionService,
) : BaseFlowNode<LoggedInFlowNode.NavTarget>(
    backstack = BackStack(
        initialElement = NavTarget.Placeholder,
        // Restore protected destinations only after the current security/CORE gates are ready.
        savedStateMap = null,
    ),
    permanentNavModel = PermanentNavModel(
        navTargets = setOf(NavTarget.LoggedInPermanent),
        savedStateMap = buildContext.savedStateMap,
    ),
    buildContext = buildContext,
    plugins = plugins
) {
    init {
        StartupTrace.mark("LoggedInFlowNode.creation")
    }

    interface Callback : Plugin {
        fun navigateToBugReport()
        fun navigateToAddAccount()
    }

    @Suppress("UNCHECKED_CAST")
    private val navigationRestorer = LoggedInNavigationRestorer(
        buildContext.savedStateMap?.get("NavModel") as? NavElements<NavTarget, BackStack.State>,
    )

    private val callback: Callback = callback()
    private val securitySetupReady = MutableStateFlow(false)
    private val securityRecoveryCompletionToken = MutableStateFlow(0L)
    private val unifiedSetupComplete = MutableStateFlow(false)
    private val matrixSyncStarted = MutableStateFlow(false)
    private val sessionInitializationComplete = MutableStateFlow(false)
    // Local service readiness is distinct from the original LC-06 terminal receipts.
    private val retainedHomeReady = MutableStateFlow(false)
    private val startupSessionInitialization = StartupSessionInitialization()
    private val accountCoreReady = MutableStateFlow(false)
    private val offlineCoreRestored = MutableStateFlow(false)
    private val startupFailureReasonCode = MutableStateFlow<String?>(null)
    private val deletionRecoveryRequired = MutableStateFlow(false)
    private val visibleStartupDestination = MutableStateFlow(StartupDestination.WAIT)
    private val homeInitialContentPrepared = MutableStateFlow(false)
    private val identityResetConvergenceState = MutableStateFlow(IdentityResetConvergenceState.IDLE)
    private val coreReadyEnforcement = MutableStateFlow(
        if (buildMeta.applicationId == YIZHE_INTERNAL_APPLICATION_ID || buildMeta.applicationId.startsWith("com.yizhe.chat.next")) {
            CoreReadyEnforcement.CHECKING
        } else {
            CoreReadyEnforcement.DISABLED
        }
    )
    private val loggedInFlowProcessor = LoggedInEventProcessor(
        snackbarDispatcher = snackbarDispatcher,
        roomMembershipObserver = matrixClient.roomMembershipObserver,
    )

    private val verificationListener = object : SessionVerificationServiceListener {
        override fun onIncomingSessionRequest(verificationRequest: VerificationRequest.Incoming) {
            // Without this launch the rendering and actual state of this Appyx node's children gets out of sync, resulting in a crash.
            // This might be because this method is called back from Rust in a background thread.
            lifecycleScope.launch {
                val receivedAt = Instant.now()

                // Wait until the app is in foreground to display the incoming verification request
                appNavigationStateService.appNavigationState.first { it.isInForeground }

                // TODO there should also be a timeout for > 10 minutes elapsed since the request was created, but the SDK doesn't expose that info yet
                val now = Instant.now()
                val elapsedTimeSinceReceived = Duration.between(receivedAt, now).toKotlinDuration()

                // Discard the incoming verification request if it has timed out
                if (elapsedTimeSinceReceived > 2.minutes) {
                    Timber.w("Incoming verification request ${verificationRequest.details.flowId} discarded due to timeout.")
                    return@launch
                }

                // Wait for the RoomList UI to be ready so the incoming verification screen can be displayed on top of it
                // Otherwise, the RoomList UI may be incorrectly displayed on top
                withTimeout(5.seconds) {
                    backstack.elements.first { elements ->
                        elements.any { it.key.navTarget == NavTarget.Home }
                    }
                }

                backstack.singleTop(NavTarget.IncomingVerificationRequest(verificationRequest))
            }
        }
    }

    private val backgroundLifecycleObserver by lazy {
        LoggedInBackgroundLifecycleObserver(
            sessionCoroutineScope = sessionCoroutineScope,
            appOperationalStateCoordinator = appOperationalStateCoordinator,
            accountIdHash = matrixClient.sessionId.value.sha256Hex(),
            matrixDeviceIdHash = matrixClient.deviceId.value.sha256Hex(),
        )
    }

    override fun onBuilt() {
        StartupTrace.mark("LoggedInFlowNode.onBuilt.begin")
        super.onBuilt()
        lifecycle.subscribe(
            onCreate = {
                ProcessLifecycleOwner.get().lifecycle.addObserver(backgroundLifecycleObserver)
                appNavigationStateService.onNavigateToSession(id, matrixClient.sessionId)
                lifecycleScope.launch {
                    StartupTrace.mark("Startup.session.deletionCheck.begin")
                    if (enterDeletionRecoveryIfPending()) return@launch
                    StartupTrace.mark("Startup.session.deletionCheck.end")
                    try {
                        securitySetupReady.first { it }
                        StartupTrace.mark("Startup.session.securityReady")
                        // Restoring the existing Home is a local presentation decision. The
                        // retained grant never starts sync, authorizes content or completes LC-06.
                        // Admit those side effects below under their original operation identity.
                        retainedHomeReady.value = StartupTrace.measureSuspend("Startup.retainedHome.presentation.check") {
                            accountReactivationCoordinator.canRestoreLocalList(matrixClient)
                        }
                        if (retainedHomeReady.value) StartupTrace.mark("Startup.retainedHome.presentation.ready")
                        val initializeSession: suspend () -> Unit = {
                            StartupTrace.mark("Session.initialize.begin")
                            check(matrixSessionCache.startSync(matrixClient.sessionId)) {
                                "Missing Matrix sync orchestrator for ${matrixClient.sessionId}"
                            }
                            matrixSyncStarted.value = true
                            sessionEnterpriseService.init()
                            activeLiveLocationShareManager.setup()
                            analyticsRoomListStateWatcher.start()
                            loggedInFlowProcessor.observeEvents(sessionCoroutineScope)
                            matrixClient.sessionVerificationService.setListener(verificationListener)
                            mediaPreviewConfigMigration()
                            setupSendingQueue()
                            retainedHomeReady.value = accountReactivationCoordinator.canRestoreLocalList(matrixClient)
                            if (retainedHomeReady.value) StartupTrace.mark("Startup.retainedHome.services.ready")

                            // Optional prefetch has no authority over session or list readiness.
                            sessionCoroutineScope.launch {
                                networkMonitor.connectivity.first { it == NetworkStatus.Connected }
                                runCatching { matrixClient.getMaxFileUploadSize() }
                            }

                            analyticsService.startLongRunningTransaction(AnalyticsLongRunningTransaction.FirstRoomsDisplayed)
                            StartupTrace.mark("Session.initialize.end")
                        }
                        StartupTrace.mark("Startup.session.offlineCheck.begin")
                        val restoreOffline = accountCoreReady.value && accountReactivationCoordinator.canRestoreOffline(matrixClient)
                        StartupTrace.mark("Startup.session.offlineCheck.end ready=$restoreOffline")
                        if (restoreOffline) {
                            startupSessionInitialization.initialize(null, initializeSession)
                        } else {
                            StartupTrace.mark("Startup.operation.begin")
                            appOperationalStateCoordinator.performResuming(
                                loggedInOperationRequest(FullAppLifecycleId.LC_06, "post_login")
                            ) {
                                StartupTrace.mark("Startup.operation.admitted")
                                startupSessionInitialization.initialize(
                                    checkNotNull(io.element.android.security.dataprotection.api.currentLifecycleOperationContext()),
                                    initializeSession,
                                )
                                StartupTrace.mark("Startup.actions.complete.receipts.begin")
                            }
                            StartupTrace.mark("Startup.receipts.end")
                        }
                        // Sync-start alone is not readiness: the sending queue, listeners,
                        // enterprise setup and every required post-login receipt must be done.
                        sessionInitializationComplete.value = true
                        StartupTrace.mark("Startup.session.initialization.complete")
                        startupSessionInitialization.complete()
                    } catch (failure: CancellationException) {
                        startupSessionInitialization.fail(failure)
                        throw failure
                    } catch (failure: Exception) {
                        startupFailureReasonCode.value = POST_LOGIN_STARTUP_FAILURE
                        startupSessionInitialization.fail(failure)
                        Timber.e(failure, "Post-login lifecycle setup failed; keeping the startup gate closed.")
                    }
                }

                lifecycleScope.launch {
                    if (buildMeta.applicationId != YIZHE_INTERNAL_APPLICATION_ID && !buildMeta.applicationId.startsWith("com.yizhe.chat.next")) return@launch
                    securitySetupReady.first { it }
                    coreReadyEnforcement.value = accountReactivationCoordinator
                        .requiresCoreReadyEnforcement(matrixClient)
                        .fold(
                            onSuccess = { required ->
                                if (required ||
                                    buildMeta.applicationId.startsWith("com.yizhe.chat.next")) {
                                        CoreReadyEnforcement.REQUIRED
                                    } else {
                                        CoreReadyEnforcement.DISABLED
                                    }
                            },
                            onFailure = { failure ->
                                Timber.e(failure, "Unable to authenticate the account reactivation boundary; enforcing CORE_READY.")
                                CoreReadyEnforcement.REQUIRED
                            },
                        )
                }

                lifecycleScope.launch {
                    if (enterDeletionRecoveryIfPending()) return@launch
                    accountReactivationCoordinator.hasPendingExplicitIdentityReset(matrixClient)
                        .onSuccess { pending ->
                            if (pending) beginIdentityResetConvergence()
                        }
                        .onFailure { failure ->
                            Timber.w(failure, "Could not restore the pending identity-reset convergence state.")
                            identityResetConvergenceState.value = IdentityResetConvergenceState.RETRY_REQUIRED
                        }
                }

                lifecycleScope.launch {
                    retainedHomeReady.first { it }
                    while (!sessionInitializationComplete.value || !accountCoreReady.value) {
                        delay(500L)
                        if (!accountReactivationCoordinator.canRestoreLocalList(matrixClient)) {
                            retainedHomeReady.value = false
                            StartupTrace.mark("Startup.retainedHome.permission.closed")
                            break
                        }
                    }
                }

                // A list preparation failure must expose recovery instead of an endless Logo.
                // Restored rooms/settings and real admission changes cancel this presentation wait.
                lifecycleScope.launch {
                    combine(visibleStartupDestination, homeInitialContentPrepared, backstack.elements) { destination, prepared, elements ->
                        destination == StartupDestination.HOME && !prepared && elements.any {
                            it.key.navTarget == NavTarget.Home && it.targetState == BackStack.State.ACTIVE
                        }
                    }.distinctUntilChanged().collectLatest { waitingForHome ->
                        if (!waitingForHome) return@collectLatest
                        delay(7_500L)
                        startupFailureReasonCode.value = "HOME_CONTENT_NOT_READY"
                    }
                }

                sessionCoroutineScope.launch { recoveryKeySubmissionService.restorePendingRecovery() }
                lifecycleScope.launch {
                    combine(
                        combine(securitySetupReady, recoveryKeySubmissionService.recoveryState) { ready, recovery -> ready && !recovery.requiresGate },
                        unifiedSetupComplete,
                        matrixSyncStarted,
                        matrixClient.sessionVerificationService.sessionVerifiedStatus,
                        policyRevisionProvider.policyRevision,
                    ) { securityReady, setupComplete, syncStarted, verificationStatus, policyRevision ->
                        Pair(securityReady && setupComplete && syncStarted && verificationStatus is SessionVerifiedStatus.Verified, policyRevision)
                    }
                        // Several upstream services can republish an equivalent READY snapshot while
                        // account activation is committing. Cancelling and restarting on READY -> READY
                        // used to strand the visible startup gate even though the server commit succeeded.
                        // A real READY -> NOT_READY transition is still delivered immediately and cancels
                        // activation, so device revocation and lock/security changes remain fail closed.
                        .distinctUntilChanged()
                        .collectLatest { (prerequisitesReady, _) ->
                        if (!prerequisitesReady) {
                            if (offlineCoreRestored.value && accountReactivationCoordinator.canRestoreOffline(matrixClient)) return@collectLatest
                            offlineCoreRestored.value = false
                            accountCoreReady.value = false
                            return@collectLatest
                        }
                        if (accountCoreReady.value && !accountReactivationCoordinator.hasCurrentCoreReceipt(matrixClient)) {
                            offlineCoreRestored.value = false
                            accountCoreReady.value = false
                        }
                        run {
                            var retryDelayMillis = 1_000L
                            while (!accountCoreReady.value) {
                                try {
                                    withTimeout(LOGGED_IN_STARTUP_TIMEOUT_MILLIS) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                            startupSessionInitialization.activate(
                                                sharedAction = {
                                                    StartupTrace.measureSuspend("Startup.core.activation") {
                                                        accountReactivationCoordinator.activate(matrixClient).getOrThrow()
                                                    }
                                                },
                                                standaloneAction = {
                                                    appOperationalStateCoordinator.performResuming(
                                                        loggedInOperationRequest(FullAppLifecycleId.LC_06, "reactivation")
                                                    ) { accountReactivationCoordinator.activate(matrixClient).getOrThrow() }
                                                },
                                            )
                                        }
                                    }
                                    accountCoreReady.value = true
                                    if (startupFailureReasonCode.value != POST_LOGIN_STARTUP_FAILURE) {
                                        startupFailureReasonCode.value = null
                                    }
                                    // The committed mobile head now exists. Refresh its signed entry grant
                                    // after entry without adding another wait to the user's critical path.
                                    sessionCoroutineScope.launch { runCatching { managementSecurityRefreshService.refreshSecurityAccess() } }
                                    standardHistoryRecoveryCoordinator.start(
                                        accountId = matrixClient.sessionId.value,
                                        matrixDeviceId = matrixClient.deviceId.value,
                                    )
                                    Timber.i("Account reactivation reached signed CORE_READY.")
                                } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                                    Timber.w("Account activation attempt timed out; recovery remains active.")
                                    startupFailureReasonCode.value = CORE_READY_STARTUP_TIMEOUT
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    Timber.w(failure, "Account reactivation has not converged yet.")
                                }
                                if (startupFailureReasonCode.value == POST_LOGIN_STARTUP_FAILURE) return@collectLatest
                                if (!accountCoreReady.value) {
                                    delay(retryDelayMillis)
                                    retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(15_000L)
                                }
                            }
                        }
                    }
                }

                combine(
                    combine(
                        securitySetupReady,
                        unifiedSetupComplete,
                        ftueService.state,
                        matrixClient.sessionVerificationService.sessionVerifiedStatus,
                        combine(
                            accountCoreReady,
                            coreReadyEnforcement,
                            offlineCoreRestored,
                            sessionInitializationComplete,
                            retainedHomeReady,
                        ) { ready, enforcement, offline, initialized, retained ->
                            AccountStartupReadiness(ready, enforcement, offline, initialized, retained)
                        },
                    ) { securityReady, setupComplete, ftueState, verificationStatus, coreState ->
                        StartupSecurityState(
                            securityReady = securityReady,
                            setupComplete = setupComplete,
                            ftueState = ftueState,
                            verificationStatus = verificationStatus,
                            coreReady = coreState.coreReady,
                            coreReadyEnforcement = coreState.enforcement,
                            offlineCoreRestored = coreState.offline,
                            sessionInitialized = coreState.sessionInitialized,
                            retainedHomeReady = coreState.retainedHomeReady,
                        )
                    },
                    startupFailureReasonCode,
                    deletionRecoveryRequired,
                    recoveryKeySubmissionService.recoveryState,
                ) { startupState, failureReasonCode, deletionRecovery, recovery ->
                    startupState.copy(
                        failureReasonCode = failureReasonCode,
                        deletionRecoveryRequired = deletionRecovery,
                        recoveryRequired = recovery.requiresGate,
                    )
                }
                    .onEach { startupState ->
                        val destination = startupState.destination()
                        when (destination) {
                            StartupDestination.SECURITY_SETUP -> backstack.safeRoot(NavTarget.SecuritySetup)
                            StartupDestination.WAIT -> backstack.safeRoot(NavTarget.Placeholder)
                            StartupDestination.HOME -> navigationRestorer.onReady(
                                backstack,
                                wasReady = visibleStartupDestination.value == StartupDestination.HOME,
                            )
                            StartupDestination.DELETION_RECOVERY -> backstack.safeRoot(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.AccountDeactivation)
                            )
                            StartupDestination.ERROR -> backstack.safeRoot(
                                NavTarget.StartupFailure(requireNotNull(startupState.failureReasonCode))
                            )
                        }
                        visibleStartupDestination.value = destination
                    }
                    .launchIn(lifecycleScope)
            },
            onResume = {
                lifecycleScope.launch {
                    if (enterDeletionRecoveryIfPending()) return@launch
                    try {
                        appOperationalStateCoordinator.perform(
                            loggedInOperationRequest(FullAppLifecycleId.LC_02, "foreground")
                        ) {
                            appOperationalStateCoordinator.refresh(
                                matrixClient.sessionId.value.sha256Hex(),
                                matrixClient.deviceId.value.sha256Hex(),
                            )
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        Timber.w(failure, "Foreground lifecycle refresh failed; it will be retried on the next resume.")
                    }
                    if (!securitySetupReady.value) return@launch
                    val availableRoomIds = matrixClient.getJoinedRoomIds().getOrNull() ?: return@launch
                    notificationConversationService.onAvailableRoomsChanged(sessionId = matrixClient.sessionId, roomIds = availableRoomIds)
                }
            },
            onDestroy = {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(backgroundLifecycleObserver)
                appNavigationStateService.onLeavingSession(id)
                loggedInFlowProcessor.stopObserving()
                matrixClient.sessionVerificationService.setListener(null)
                analyticsRoomListStateWatcher.stop()
            }
        )
        StartupTrace.mark("LoggedInFlowNode.onBuilt.end")
    }

    private fun setupSendingQueue() {
        sendingQueue.launchIn(lifecycleScope)
    }

    private fun loggedInOperationRequest(lifecycleId: FullAppLifecycleId, trigger: String) = lifecycleOperationRequest(
        lifecycleId = lifecycleId,
        trigger = trigger,
        accountIdHash = matrixClient.sessionId.value.sha256Hex(),
        matrixDeviceIdHash = matrixClient.deviceId.value.sha256Hex(),
    )

    private suspend fun enterDeletionRecoveryIfPending(): Boolean {
        when (val gate = checkDeletionRecoveryGate {
            permanentAccountDeletionCoordinator.pendingRecovery(matrixClient.sessionId.value.sha256Hex())
        }) {
            DeletionRecoveryGate.Clear -> {
                if (startupFailureReasonCode.value == "DELETION_RECOVERY_CHECK_RETRY_REQUIRED") startupFailureReasonCode.value = null
                return false
            }
            is DeletionRecoveryGate.RetryRequired -> {
                // Unknown remote deletion state is neither a clean account nor permission to start
                // normal work. Keep the startup gate closed and let the existing Retry flow recheck.
                Timber.w(gate.failure, "Deletion recovery check is unavailable; retaining the startup gate.")
                startupFailureReasonCode.value = "DELETION_RECOVERY_CHECK_RETRY_REQUIRED"
                return true
            }
            is DeletionRecoveryGate.Pending -> Unit
        }
        startupFailureReasonCode.value = null
        deletionRecoveryRequired.value = true
        Timber.w("Permanent account deletion is pending; entering the deletion-only recovery surface.")
        return true
    }

    private fun beginIdentityResetConvergence() {
        if (identityResetConvergenceState.value == IdentityResetConvergenceState.RUNNING) return
        identityResetConvergenceState.value = IdentityResetConvergenceState.RUNNING
        lifecycleScope.launch {
            accountReactivationCoordinator.activate(matrixClient)
                .onSuccess {
                    identityResetConvergenceState.value = IdentityResetConvergenceState.IDLE
                    securityRecoveryCompletionToken.value += 1L
                }
                .onFailure { failure ->
                    Timber.w(failure, "Identity reset completed locally but RESET_CONFIRMED convergence needs retry.")
                    identityResetConvergenceState.value = IdentityResetConvergenceState.RETRY_REQUIRED
                }
        }
    }

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    sealed interface NavTarget : Parcelable {
        @Parcelize
        data object Placeholder : NavTarget

        @Parcelize
        data object LoggedInPermanent : NavTarget

        @Parcelize
        data object Home : NavTarget

        @Parcelize
        data class StartupFailure(val reasonCode: String) : NavTarget

        @Parcelize
        data class Room(
            val roomIdOrAlias: RoomIdOrAlias,
            val serverNames: List<String> = emptyList(),
            val trigger: JoinedRoomAnalyticsEvent.Trigger? = null,
            val roomDescription: RoomDescription? = null,
            val initialElement: RoomNavigationTarget = RoomNavigationTarget.Root(),
            val targetId: UUID = UUID.randomUUID(),
        ) : NavTarget

        @Parcelize
        data class UserProfile(
            val userId: UserId,
        ) : NavTarget

        @Parcelize
        data class Settings(
            val initialElement: PreferencesEntryPoint.InitialTarget = PreferencesEntryPoint.InitialTarget.Root
        ) : NavTarget

        @Parcelize
        data object AddFriend : NavTarget

        @Parcelize
        data object FriendScanner : NavTarget

        @Parcelize
        data object MyQrCode : NavTarget

        @Parcelize
        data object CreateRoom : NavTarget

        @Parcelize
        data object CreateSpace : NavTarget

        @Parcelize
        data class SecureBackup(
            val initialElement: SecureBackupEntryPoint.InitialTarget = SecureBackupEntryPoint.InitialTarget.Root
        ) : NavTarget

        @Parcelize
        data object Ftue : NavTarget

        @Parcelize
        data object SecuritySetup : NavTarget

        @Parcelize
        data object LockScreenSetup : NavTarget

        @Parcelize
        data object LinkNewDevice : NavTarget

        @Parcelize
        data object VerifyCurrentSession : NavTarget

        @Parcelize
        data object RoomDirectory : NavTarget

        @Parcelize
        data class IncomingShare(val shareIntentData: ShareIntentData) : NavTarget

        @Parcelize
        data class IncomingVerificationRequest(val data: VerificationRequest.Incoming) : NavTarget
    }

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node {
        return when (navTarget) {
            NavTarget.Placeholder -> node(buildContext) { modifier ->
                Box(
                    modifier = modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = ElementTheme.colors.bgActionPrimaryRest)
                }
            }
            NavTarget.LoggedInPermanent -> {
                val callback = object : LoggedInNode.Callback {
                    override fun navigateToNotificationTroubleshoot() {
                        backstack.push(NavTarget.Settings(PreferencesEntryPoint.InitialTarget.NotificationTroubleshoot))
                    }
                }
                createNode<LoggedInNode>(buildContext, listOf(callback))
            }
            NavTarget.Home -> {
                val callback = object : HomeEntryPoint.Callback {
                    override fun onInitialContentReady() {
                        homeInitialContentPrepared.value = true
                    }
                    override fun navigateToRoom(roomId: RoomId, joinedRoom: JoinedRoom?) {
                        lifecycleScope.launch {
                            attachRoom(
                                roomIdOrAlias = roomId.toRoomIdOrAlias(),
                                initialElement = RoomNavigationTarget.Root(joinedRoom = joinedRoom),
                                clearBackstack = false,
                            )
                        }
                    }

                    override fun navigateToSettings(destination: PreferencesRootDestination) {
                        when (destination) {
                            PreferencesRootDestination.MyQrCode -> backstack.push(NavTarget.MyQrCode)
                            PreferencesRootDestination.AddAccount -> callback.navigateToAddAccount()
                            PreferencesRootDestination.SecureBackup -> backstack.push(NavTarget.SecureBackup())
                            PreferencesRootDestination.LinkNewDevice -> backstack.push(NavTarget.LinkNewDevice)
                            PreferencesRootDestination.BugReport -> callback.navigateToBugReport()
                            PreferencesRootDestination.Analytics -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.AnalyticsSettings)
                            )
                            PreferencesRootDestination.About -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.About)
                            )
                            PreferencesRootDestination.DeveloperSettings -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.DeveloperSettings)
                            )
                            PreferencesRootDestination.NotificationSettings -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.NotificationSettings)
                            )
                            PreferencesRootDestination.ChatSecurity -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.ChatSecurity)
                            )
                            PreferencesRootDestination.LockScreenSettings -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.LockScreenSettings)
                            )
                            PreferencesRootDestination.AdvancedSettings -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.AdvancedSettings)
                            )
                            PreferencesRootDestination.Labs -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.Labs)
                            )
                            is PreferencesRootDestination.UserProfile -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.UserProfile(destination.matrixUser))
                            )
                            PreferencesRootDestination.BlockedUsers -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.BlockedUsers)
                            )
                            PreferencesRootDestination.MessageFavourites -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.MessageFavourites)
                            )
                            PreferencesRootDestination.SignOut -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.SignOut)
                            )
                            PreferencesRootDestination.AccountDeactivation -> backstack.push(
                                NavTarget.Settings(PreferencesEntryPoint.InitialTarget.AccountDeactivation)
                            )
                        }
                    }

                    override fun navigateToAddFriend() {
                        backstack.push(NavTarget.AddFriend)
                    }

                    override fun navigateToFriendScanner() {
                        backstack.push(NavTarget.FriendScanner)
                    }

                    override fun navigateToMyQrCode() {
                        backstack.push(NavTarget.MyQrCode)
                    }

                    override fun navigateToFriendProfile(userId: UserId) {
                        backstack.push(NavTarget.UserProfile(userId))
                    }

                    override fun navigateToCreateRoom() {
                        backstack.push(NavTarget.CreateRoom)
                    }

                    override fun navigateToCreateSpace() {
                        backstack.push(NavTarget.CreateSpace)
                    }

                    override fun navigateToSetUpRecovery() {
                        backstack.push(NavTarget.SecureBackup(initialElement = SecureBackupEntryPoint.InitialTarget.Root))
                    }

                    override fun navigateToEnterRecoveryKey() {
                        backstack.push(NavTarget.SecureBackup(initialElement = SecureBackupEntryPoint.InitialTarget.EnterRecoveryKey))
                    }

                    override fun navigateToRoomSettings(roomId: RoomId) {
                        lifecycleScope.launch {
                            attachRoom(
                                roomIdOrAlias = roomId.toRoomIdOrAlias(),
                                initialElement = RoomNavigationTarget.Details,
                                clearBackstack = false
                            )
                        }
                    }

                    override fun navigateToBugReport() {
                        callback.navigateToBugReport()
                    }
                }
                StartupTrace.measure("LoggedInFlowNode.createHome") {
                    homeEntryPoint.createNode(
                        parentNode = this,
                        buildContext = buildContext,
                        callback = callback,
                    )
                }
            }
            is NavTarget.StartupFailure -> node(buildContext) { modifier ->
                val activity = LocalContext.current as? Activity
                StartupFailureView(
                    reasonCode = navTarget.reasonCode,
                    onRetry = { activity?.recreate() },
                    modifier = modifier,
                )
            }
            is NavTarget.Room -> {
                val joinedRoomCallback = object : JoinedRoomLoadedFlowNode.Callback {
                    override fun onDone() {
                        backstack.pop()
                    }

                    override fun navigateToRoom(roomId: RoomId, serverNames: List<String>, clearBackStack: Boolean) {
                        lifecycleScope.launch {
                            attachRoom(roomIdOrAlias = roomId.toRoomIdOrAlias(), serverNames = serverNames, clearBackstack = clearBackStack)
                        }
                    }

                    override fun handlePermalinkClick(data: PermalinkData, pushToBackstack: Boolean) {
                        when (data) {
                            is PermalinkData.UserLink -> {
                                // Should not happen (handled by MessagesNode)
                                Timber.e("User link clicked: ${data.userId}.")
                            }
                            is PermalinkData.RoomLink -> {
                                if (data.invitationToken != null) {
                                    openGroupShare(data)
                                    return
                                }
                                if (pushToBackstack) {
                                    lifecycleScope.launch {
                                        attachRoom(
                                            roomIdOrAlias = data.roomIdOrAlias,
                                            serverNames = data.viaParameters,
                                            trigger = JoinedRoomAnalyticsEvent.Trigger.Timeline,
                                            initialElement = RoomNavigationTarget.Root(data.eventId),
                                            clearBackstack = false
                                        )
                                    }
                                } else {
                                    backstack.replace(
                                        NavTarget.Room(
                                            roomIdOrAlias = data.roomIdOrAlias,
                                            serverNames = data.viaParameters,
                                            trigger = JoinedRoomAnalyticsEvent.Trigger.Timeline,
                                            initialElement = RoomNavigationTarget.Root(data.eventId),
                                        )
                                    )
                                }
                            }
                            is PermalinkData.FallbackLink,
                            is PermalinkData.RoomEmailInviteLink -> {
                                // Should not happen (handled by MessagesNode)
                            }
                        }
                    }

                    override fun navigateToGlobalNotificationSettings() {
                        backstack.push(NavTarget.Settings(PreferencesEntryPoint.InitialTarget.NotificationSettings))
                    }

                    override fun navigateToDeveloperSettings() {
                        backstack.push(NavTarget.Settings(PreferencesEntryPoint.InitialTarget.DeveloperSettings))
                    }

                    override fun navigateToChatSecurity() {
                        backstack.push(NavTarget.Settings(PreferencesEntryPoint.InitialTarget.ChatSecurity))
                    }
                }
                val inputs = RoomFlowNode.Inputs(
                    roomIdOrAlias = navTarget.roomIdOrAlias,
                    roomDescription = Optional.ofNullable(navTarget.roomDescription),
                    serverNames = navTarget.serverNames,
                    trigger = Optional.ofNullable(navTarget.trigger),
                    initialElement = navTarget.initialElement
                )
                createNode<RoomFlowNode>(buildContext, plugins = listOf(inputs, joinedRoomCallback))
            }
            is NavTarget.UserProfile -> {
                val callback = object : UserProfileEntryPoint.Callback {
                    override fun navigateToRoom(roomId: RoomId) {
                        lifecycleScope.launch {
                            attachRoom(roomIdOrAlias = roomId.toRoomIdOrAlias(), clearBackstack = false)
                        }
                    }
                }
                userProfileEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    params = UserProfileEntryPoint.Params(userId = navTarget.userId),
                    callback = callback,
                )
            }
            is NavTarget.Settings -> {
                val callback = object : PreferencesEntryPoint.Callback {
                    override fun navigateToMyQrCode() {
                        backstack.push(NavTarget.MyQrCode)
                    }

                    override fun navigateToAddAccount() {
                        callback.navigateToAddAccount()
                    }

                    override fun navigateToLinkNewDevice() {
                        backstack.push(NavTarget.LinkNewDevice)
                    }

                    override fun navigateToBugReport() {
                        callback.navigateToBugReport()
                    }

                    override fun navigateToSecureBackup() {
                        backstack.push(NavTarget.SecureBackup())
                    }

                    override fun navigateToSetUpRecoveryKey() {
                        backstack.push(NavTarget.SecureBackup(SecureBackupEntryPoint.InitialTarget.SetUpRecovery))
                    }

                    override fun navigateToEnterRecoveryKey() {
                        backstack.push(NavTarget.SecureBackup(SecureBackupEntryPoint.InitialTarget.EnterRecoveryKey))
                    }

                    override fun navigateToChangeRecoveryKey() {
                        backstack.push(NavTarget.SecureBackup(SecureBackupEntryPoint.InitialTarget.ChangeRecovery))
                    }

                    override fun navigateToRoomNotificationSettings(roomId: RoomId) {
                        lifecycleScope.launch {
                            attachRoom(
                                roomIdOrAlias = roomId.toRoomIdOrAlias(),
                                initialElement = RoomNavigationTarget.NotificationSettings,
                            )
                        }
                    }

                    override fun navigateToEvent(roomId: RoomId, eventId: EventId) {
                        lifecycleScope.launch {
                            attachRoom(
                                roomIdOrAlias = roomId.toRoomIdOrAlias(),
                                initialElement = RoomNavigationTarget.Root(eventId),
                                clearBackstack = false
                            )
                        }
                    }
                }
                val inputs = PreferencesEntryPoint.Params(navTarget.initialElement)
                preferencesEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    params = inputs,
                    callback = callback,
                )
            }
            NavTarget.AddFriend,
            NavTarget.FriendScanner,
            NavTarget.MyQrCode -> {
                val callback = object : StartChatEntryPoint.Callback {
                    override fun onRoomCreated(roomIdOrAlias: RoomIdOrAlias, serverNames: List<String>) {
                        backstack.replace(NavTarget.Room(roomIdOrAlias = roomIdOrAlias, serverNames = serverNames))
                    }

                    override fun navigateToRoomDirectory() {
                        backstack.push(NavTarget.RoomDirectory)
                    }

                    override fun navigateToUserProfile(userId: UserId) {
                        backstack.replace(NavTarget.UserProfile(userId))
                    }
                }

                startChatEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    callback = callback,
                    params = StartChatEntryPoint.Params(
                        initialTarget = when (navTarget) {
                            NavTarget.FriendScanner -> StartChatEntryPoint.InitialTarget.SCANNER
                            NavTarget.MyQrCode -> StartChatEntryPoint.InitialTarget.MY_QR_CODE
                            else -> StartChatEntryPoint.InitialTarget.SEARCH
                        }
                    ),
                )
            }
            NavTarget.CreateRoom -> {
                val callback = object : CreateRoomEntryPoint.Callback {
                    override fun onRoomCreated(roomId: RoomId) {
                        backstack.replaceCreateRoomWithRoom(roomId)
                    }
                }
                createRoomEntryPoint
                    .builder(parentNode = this, buildContext = buildContext, callback = callback)
                    .setIsSpace(false)
                    .build()
            }
            is NavTarget.CreateSpace -> {
                val callback = object : CreateRoomEntryPoint.Callback {
                    override fun onRoomCreated(roomId: RoomId) {
                        lifecycleScope.launch {
                            attachRoom(
                                roomIdOrAlias = roomId.toRoomIdOrAlias(),
                                serverNames = emptyList(),
                                clearBackstack = false,
                            )
                        }
                    }
                }
                createRoomEntryPoint
                    .builder(parentNode = this, buildContext = buildContext, callback = callback)
                    .setIsSpace(true)
                    .build()
            }
            is NavTarget.SecureBackup -> {
                secureBackupEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    params = SecureBackupEntryPoint.Params(initialElement = navTarget.initialElement),
                    callback = object : SecureBackupEntryPoint.Callback {
                        override fun onDone() {
                            val recoveryTarget = navTarget.initialElement == SecureBackupEntryPoint.InitialTarget.EnterRecoveryKey ||
                                navTarget.initialElement == SecureBackupEntryPoint.InitialTarget.ResetIdentity
                            if (navTarget.initialElement == SecureBackupEntryPoint.InitialTarget.ResetIdentity) {
                                // Leave the destructive flow only once its local marker is durable.
                                // The shared startup surface then covers RESET_CONFIRMED convergence;
                                // failure offers retry of that same operation, never another reset.
                                backstack.pop()
                                beginIdentityResetConvergence()
                            } else {
                                backstack.pop()
                                if (recoveryTarget) securityRecoveryCompletionToken.value += 1L
                            }
                        }
                    },
                )
            }
            NavTarget.Ftue -> {
                ftueEntryPoint.createNode(this, buildContext)
            }
            NavTarget.SecuritySetup -> node(buildContext) { modifier ->
                val verificationStatus by matrixClient.sessionVerificationService.sessionVerifiedStatus.collectAsState()
                // Nullable initial values distinguish "not loaded yet" from a real unmet requirement. Returning
                // users must never see the setup workbench merely because these local flows have not emitted.
                val lockScreenSetupRequired by lockScreenService.isSetupRequired()
                    .map<Boolean, Boolean?> { it }
                    .collectAsState(initial = null)
                val analyticsChoiceComplete by analyticsService.didAskUserConsentFlow
                    .map<Boolean, Boolean?> { it }
                    .collectAsState(initial = null)
                val recoveryCompletionToken by securityRecoveryCompletionToken.collectAsState()
                val resetConvergenceState by identityResetConvergenceState.collectAsState()
                SecuritySetupRoute(
                    managementControlService = managementControlService,
                    kmsShareClient = kmsShareClient,
                    recoveryKeySubmissionService = recoveryKeySubmissionService,
                    accountIdHash = matrixClient.sessionId.value.sha256Hex(),
                    matrixDeviceIdHash = matrixClient.deviceId.value.sha256Hex(),
                    appOperationalStateCoordinator = appOperationalStateCoordinator,
                    lifecycleCoroutineScope = sessionCoroutineScope,
                    sessionVerificationStatus = verificationStatus,
                    lockScreenSetupRequired = lockScreenSetupRequired ?: true,
                    analyticsChoiceComplete = analyticsChoiceComplete ?: false,
                    localRequirementsLoaded = lockScreenSetupRequired != null,
                    recoveryCompletionToken = recoveryCompletionToken,
                    identityResetConvergenceState = resetConvergenceState,
                    onApprovalReady = {
                        StartupTrace.mark("Startup.security.approvalReady")
                        securitySetupReady.value = true
                    },
                    onUseAnotherDevice = { backstack.push(NavTarget.VerifyCurrentSession) },
                    onOpenLockScreenSetup = { backstack.push(NavTarget.LockScreenSetup) },
                    onAnalyticsChoice = { consent ->
                        lifecycleScope.launch {
                            analyticsService.setUserConsent(consent)
                            analyticsService.setDidAskUserConsent()
                            ftueService.refresh()
                        }
                    },
                    onRequirementsChanged = ftueService::refresh,
                    onResetIdentity = {
                        backstack.push(NavTarget.SecureBackup(SecureBackupEntryPoint.InitialTarget.ResetIdentity))
                    },
                    onRetryIdentityReset = ::beginIdentityResetConvergence,
                    canRestoreOffline = { accountReactivationCoordinator.canRestoreOffline(matrixClient) },
                    canRestoreRetainedList = { accountReactivationCoordinator.canRestoreLocalList(matrixClient) },
                    verifiedStartupReadiness = managementSecurityRefreshService::verifiedOnlineReadiness,
                    onOfflineReady = {
                        offlineCoreRestored.value = true
                        accountCoreReady.value = true
                        if (startupFailureReasonCode.value != POST_LOGIN_STARTUP_FAILURE) {
                            startupFailureReasonCode.value = null
                        }
                        unifiedSetupComplete.value = true
                        ftueService.onUserCompletedSessionVerification()
                        ftueService.refresh()
                    },
                    onReady = {
                        unifiedSetupComplete.value = true
                        // The unified setup workbench has just observed a genuinely verified
                        // Matrix session. Release the older FTUE acknowledgement latch as part of
                        // the same completion boundary so HOME can be selected without a restart.
                        ftueService.onUserCompletedSessionVerification()
                        ftueService.refresh()
                    },
                    modifier = modifier,
                )
            }
            NavTarget.LockScreenSetup -> {
                lockScreenEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    navTarget = LockScreenEntryPoint.Target.Setup,
                    callback = object : LockScreenEntryPoint.Callback {
                        override fun onSetupDone() {
                            ftueService.refresh()
                            backstack.pop()
                        }
                    },
                )
            }
            NavTarget.LinkNewDevice -> {
                val callback = object : LinkNewDeviceEntryPoint.Callback {
                    override fun onDone() {
                        backstack.pop()
                    }
                }
                linkNewDeviceEntryPoint.createNode(this, buildContext, callback)
            }
            NavTarget.VerifyCurrentSession -> {
                outgoingVerificationEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    params = OutgoingVerificationEntryPoint.Params(
                        showDeviceVerifiedScreen = true,
                        verificationRequest = VerificationRequest.Outgoing.CurrentSession,
                    ),
                    callback = object : OutgoingVerificationEntryPoint.Callback {
                        override fun onDone() {
                            backstack.pop()
                            securityRecoveryCompletionToken.value += 1L
                        }

                        override fun onBack() {
                            backstack.pop()
                        }

                        override fun navigateToLearnMoreAboutEncryption() = Unit
                    },
                )
            }
            NavTarget.RoomDirectory -> {
                roomDirectoryEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    callback = object : RoomDirectoryEntryPoint.Callback {
                        override fun navigateToRoom(roomDescription: RoomDescription) {
                            lifecycleScope.launch {
                                attachRoom(
                                    roomIdOrAlias = roomDescription.roomId.toRoomIdOrAlias(),
                                    roomDescription = roomDescription,
                                    trigger = JoinedRoomAnalyticsEvent.Trigger.RoomDirectory,
                                )
                            }
                        }
                    },
                )
            }
            is NavTarget.IncomingShare -> {
                shareEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    params = ShareEntryPoint.Params(shareIntentData = navTarget.shareIntentData),
                    callback = object : ShareEntryPoint.Callback {
                        override fun onDone(result: ShareResult) {
                            // Remove the incoming share screen
                            backstack.pop()

                            // Navigate to the room if the text/media was shared to a single one
                            result.roomIds.singleOrNull()?.let { roomId ->
                                val roomToHandoff = result.roomToHandoff?.takeIf { it.roomId == roomId }
                                lifecycleScope.launch {
                                    var roomAttached = false
                                    try {
                                        // Wait until the incoming share screen is removed
                                        backstack.elements.first { it.lastOrNull()?.key?.navTarget !is NavTarget.IncomingShare }

                                        // Reuse the room whose timeline already owns the upload local echo.
                                        attachRoom(
                                            roomId.toRoomIdOrAlias(),
                                            initialElement = RoomNavigationTarget.Root(joinedRoom = roomToHandoff),
                                            clearBackstack = false,
                                        )
                                        roomAttached = true
                                    } finally {
                                        // Until attachRoom returns, navigation has not accepted ownership.
                                        if (!roomAttached) {
                                            roomToHandoff?.destroy()
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            }
            is NavTarget.IncomingVerificationRequest -> {
                incomingVerificationEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    params = IncomingVerificationEntryPoint.Params(navTarget.data),
                    callback = object : IncomingVerificationEntryPoint.Callback {
                        override fun onDone() {
                            backstack.pop()
                        }
                    },
                )
            }
        }
    }

    private var pendingGroupShare by androidx.compose.runtime.mutableStateOf<PermalinkData.RoomLink?>(null)
    private var groupShareBusy by androidx.compose.runtime.mutableStateOf(false)
    private var groupShareFailed by androidx.compose.runtime.mutableStateOf(false)

    fun openGroupShare(data: PermalinkData.RoomLink) {
        pendingGroupShare = data
        groupShareFailed = false
    }

    suspend fun attachRoom(
        roomIdOrAlias: RoomIdOrAlias,
        serverNames: List<String> = emptyList(),
        trigger: JoinedRoomAnalyticsEvent.Trigger? = null,
        roomDescription: RoomDescription? = null,
        initialElement: RoomNavigationTarget = RoomNavigationTarget.Root(),
        clearBackstack: Boolean = false,
    ): RoomFlowNode {
        waitForNavTargetAttached { navTarget ->
            navTarget is NavTarget.Home
        }
        attachChild<RoomFlowNode> {
            val roomNavTarget = NavTarget.Room(
                roomIdOrAlias = roomIdOrAlias,
                serverNames = serverNames,
                roomDescription = roomDescription,
                trigger = trigger,
                initialElement = initialElement,
            )
            backstack.accept(AttachRoomOperation(roomNavTarget, clearBackstack))
        }

        // If we don't do this check, we might be returning while a previous node with the same type is still displayed
        // This means we may attach some new nodes to that one, which will be quickly replaced by the one instantiated above
        return waitForChildAttached<RoomFlowNode, NavTarget> {
            it is NavTarget.Room &&
                it.roomIdOrAlias == roomIdOrAlias &&
                it.initialElement == initialElement
        }
    }

    suspend fun attachUser(userId: UserId) {
        waitForNavTargetAttached { navTarget ->
            navTarget is NavTarget.Home
        }
        attachChild<Node> {
            backstack.push(
                NavTarget.UserProfile(
                    userId = userId,
                )
            )
        }
    }

    internal suspend fun attachIncomingShare(shareIntentData: ShareIntentData) {
        waitForNavTargetAttached { navTarget ->
            navTarget is NavTarget.Home
        }
        attachChild<Node> {
            backstack.push(
                NavTarget.IncomingShare(shareIntentData)
            )
        }
    }

    @Composable
    override fun View(modifier: Modifier) {
        val startupSplashController = LocalStartupSplashController.current
        val startupView = LocalView.current
        LaunchedEffect(startupSplashController, startupView) {
            // Home reports readiness from its committed content SideEffect. Observe that signal
            // directly instead of waiting for a second parent Compose frame behind the splash.
            // The existing layout path remains the fallback, and MainActivity still checks App lock.
            combine(homeInitialContentPrepared, visibleStartupDestination, backstack.elements) { prepared, destination, elements ->
                prepared && destination == StartupDestination.HOME && elements.any {
                    it.key.navTarget == NavTarget.Home && it.targetState == BackStack.State.ACTIVE
                }
            }.collect { prepared ->
                if (prepared && startupView.isAttachedToWindow && startupView.isLaidOut && startupView.width > 0 && startupView.height > 0) {
                    StartupTrace.mark("Startup.home.committed.reveal")
                    startupSplashController.revealPreparedDestination()
                }
            }
        }
        val colors by remember {
            enterpriseService.semanticColorsFlow(sessionId = matrixClient.sessionId)
        }.collectAsState(SemanticColorsLightDark.default)
        ElementThemeApp(
            appPreferencesStore = appPreferencesStore,
            featureFlagService = featureFlagService,
            compoundLight = colors.light,
            compoundDark = colors.dark,
            buildMeta = buildMeta,
        ) {
            val shareContext = androidx.compose.ui.platform.LocalContext.current
            pendingGroupShare?.let { data ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { if (!groupShareBusy) pendingGroupShare = null },
                    title = { androidx.compose.material3.Text(shareContext.getString(io.element.android.appnav.R.string.screen_group_invite_title)) },
                    text = { androidx.compose.material3.Text(shareContext.getString(if (groupShareFailed) io.element.android.appnav.R.string.screen_group_invite_error
                        else io.element.android.appnav.R.string.screen_group_invite_confirm)) },
                    confirmButton = { androidx.compose.material3.TextButton(enabled = !groupShareBusy, onClick = {
                        groupShareBusy = true
                        lifecycleScope.launch {
                            try {
                                appOperationalStateCoordinator.perform(loggedInOperationRequest(FullAppLifecycleId.LC_04, "invitation_link")) {
                                    managementControlService.redeemGroupShare(matrixClient.sessionId.value, data.roomIdOrAlias.identifier,
                                        checkNotNull(data.invitationToken))
                                    attachRoom(data.roomIdOrAlias, clearBackstack = false)
                                }
                                pendingGroupShare = null
                            } catch (failure: CancellationException) { throw failure
                            } catch (_: Exception) { groupShareFailed = true
                            } finally { groupShareBusy = false }
                        }
                    }) { androidx.compose.material3.Text(shareContext.getString(io.element.android.appnav.R.string.screen_group_invite_open)) } },
                    dismissButton = { androidx.compose.material3.TextButton(enabled = !groupShareBusy, onClick = { pendingGroupShare = null }) {
                        androidx.compose.material3.Text(shareContext.getString(io.element.android.libraries.ui.strings.CommonStrings.action_cancel))
                    } },
                )
            }
            val isOnline by syncService.isOnline.collectAsState()
            val startupDestination by visibleStartupDestination.collectAsState()
            val homePrepared by homeInitialContentPrepared.collectAsState()
            val startupElements by backstack.elements.collectAsState()
            val homeIsActive = startupElements.any {
                it.key.navTarget == NavTarget.Home && it.targetState == BackStack.State.ACTIVE
            }
            ConnectivityIndicatorContainer(
                isOnline = isOnline,
                modifier = modifier.revealStartupSplashWhenLaidOut(
                    enabled = shouldRevealLoggedInStartup(startupDestination, homeIsActive, homePrepared),
                ),
            ) { contentModifier ->
                Box(modifier = contentModifier) {
                    val ftueState by ftueService.state.collectAsState()
                    val isSecuritySetupReady by securitySetupReady.collectAsState()
                    BackstackView(transitionHandler = rememberLoggedInFlowTransitionHandler(backstack))
                    if (
                        startupDestination != StartupDestination.DELETION_RECOVERY &&
                        isSecuritySetupReady &&
                        ftueState is FtueState.Complete
                    ) {
                        PermanentChild(permanentNavModel = permanentNavModel, navTarget = NavTarget.LoggedInPermanent)
                    }
                }
            }
        }
    }
}

internal fun shouldRevealLoggedInStartup(destination: StartupDestination, homeIsActive: Boolean, homePrepared: Boolean): Boolean = when (destination) {
    StartupDestination.HOME -> !homeIsActive || homePrepared
    StartupDestination.DELETION_RECOVERY -> true
    else -> false
}

internal fun BackStack<LoggedInFlowNode.NavTarget>.replaceCreateRoomWithRoom(roomId: RoomId) {
    replace(
        LoggedInFlowNode.NavTarget.Room(
            roomIdOrAlias = roomId.toRoomIdOrAlias(),
            serverNames = emptyList(),
        )
    )
}

private data class AccountStartupReadiness(
    val coreReady: Boolean,
    val enforcement: CoreReadyEnforcement,
    val offline: Boolean,
    val sessionInitialized: Boolean,
    val retainedHomeReady: Boolean,
)

internal data class StartupSecurityState(
    val securityReady: Boolean,
    val setupComplete: Boolean,
    val ftueState: FtueState,
    val verificationStatus: SessionVerifiedStatus,
    val coreReady: Boolean,
    val sessionInitialized: Boolean,
    /** Stage 4 is restricted to an authenticated same-installation account reactivation boundary. */
    val coreReadyEnforcement: CoreReadyEnforcement = CoreReadyEnforcement.DISABLED,
    val failureReasonCode: String? = null,
    val deletionRecoveryRequired: Boolean = false,
    val offlineCoreRestored: Boolean = false,
    val recoveryRequired: Boolean = false,
    val retainedHomeReady: Boolean = false,
)

internal enum class CoreReadyEnforcement {
    DISABLED,
    CHECKING,
    REQUIRED,
}

internal enum class StartupDestination {
    SECURITY_SETUP,
    WAIT,
    HOME,
    DELETION_RECOVERY,
    ERROR,
}

internal fun StartupSecurityState.destination(): StartupDestination = when {
    deletionRecoveryRequired -> StartupDestination.DELETION_RECOVERY
    failureReasonCode != null -> StartupDestination.ERROR
    recoveryRequired || !securityReady || !setupComplete -> StartupDestination.SECURITY_SETUP
    retainedHomeReady && ftueState is FtueState.Complete && verificationStatus is SessionVerifiedStatus.Verified -> StartupDestination.HOME
    !sessionInitialized -> StartupDestination.WAIT
    offlineCoreRestored && coreReady && coreReadyEnforcement != CoreReadyEnforcement.CHECKING -> StartupDestination.HOME
    ftueState is FtueState.Unknown || verificationStatus is SessionVerifiedStatus.Unknown -> StartupDestination.WAIT
    ftueState is FtueState.Incomplete || verificationStatus !is SessionVerifiedStatus.Verified ->
        StartupDestination.SECURITY_SETUP
    coreReadyEnforcement == CoreReadyEnforcement.CHECKING -> StartupDestination.WAIT
    coreReadyEnforcement == CoreReadyEnforcement.REQUIRED && !coreReady -> StartupDestination.WAIT
    else -> StartupDestination.HOME
}

@Composable
internal fun StartupFailureView(
    reasonCode: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = true) {}
    Column(
        modifier = modifier
            .fillMaxSize()
            .revealStartupSplashWhenLaidOut()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.screen_logged_in_startup_failed_title),
            style = ElementTheme.typography.fontHeadingLgBold,
            color = ElementTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.screen_logged_in_startup_failed_description),
            modifier = Modifier.padding(top = 12.dp),
            style = ElementTheme.typography.fontBodyMdRegular,
            color = ElementTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = reasonCode,
            modifier = Modifier.padding(top = 8.dp),
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Button(
            text = stringResource(R.string.screen_logged_in_startup_retry),
            onClick = onRetry,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

@Parcelize
private class AttachRoomOperation(
    val roomTarget: LoggedInFlowNode.NavTarget.Room,
    val clearBackstack: Boolean,
) : BackStackOperation<LoggedInFlowNode.NavTarget> {
    /**
     * Returns a list containing last [count] elements that match [predicate] while preserving other elements.
     */
    private fun <T> List<T>.keepingLast(count: Int, predicate: (T) -> Boolean): List<T> {
        val matchingIndices = indices.filter { predicate(this[it]) }
        val indicesToRemove = matchingIndices.dropLast(count).toSet()
        return filterIndexed { index, _ -> index !in indicesToRemove }
    }

    override fun isApplicable(elements: NavElements<LoggedInFlowNode.NavTarget, BackStack.State>) = true

    override fun invoke(elements: BackStackElements<LoggedInFlowNode.NavTarget>): BackStackElements<LoggedInFlowNode.NavTarget> {
        return if (clearBackstack) {
            // Makes sure the room list target is alone in the backstack and stashed
            elements.mapNotNull { element ->
                if (element.key.navTarget == LoggedInFlowNode.NavTarget.Home) {
                    element.transitionTo(STASHED, this)
                } else {
                    null
                }
            } + BackStackElement(
                key = NavKey(roomTarget),
                fromState = CREATED,
                targetState = ACTIVE,
                operation = this
            )
        } else {
            val existingRoomElement = elements.find {
                val roomNavTarget = it.key.navTarget as? LoggedInFlowNode.NavTarget.Room
                roomNavTarget?.roomIdOrAlias == roomTarget.roomIdOrAlias
            }

            // Make sure the backstack of rooms can't grow indefinitely when opening permalinks.
            val roomElementCount = elements.count { it.key.navTarget is LoggedInFlowNode.NavTarget.Room }

            Timber.d("Current room nodes: $roomElementCount/$MAX_ROOM_NODE_COUNT")
            // Crate a new list keeping all the elements, but for Room ones just keep the last MAX_ROOM_NODE_COUNT
            val currentElements = elements.keepingLast(MAX_ROOM_NODE_COUNT) { element ->
                element.key.navTarget is LoggedInFlowNode.NavTarget.Room
            }

            // If the room already existed, remove it from the stack and add a new node at the end
            if (existingRoomElement != null) {
                currentElements.mapNotNull { element ->
                    if (element == existingRoomElement) {
                        null
                    } else {
                        element.transitionTo(STASHED, this)
                    }
                } + // Always create a new element, otherwise we wouldn't be navigating to the target event id or child node
                    BackStackElement(
                        key = NavKey(roomTarget),
                        fromState = CREATED,
                        targetState = ACTIVE,
                        operation = this
                    )
            } else {
                // Otherwise, just push the new node to the end of the backstack
                Push<LoggedInFlowNode.NavTarget>(roomTarget).invoke(currentElements)
            }
        }
    }
}
