/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav

import android.app.Activity
import android.content.Intent
import android.os.Parcelable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.navigation.NavElements
import com.bumble.appyx.core.navigation.NavKey
import com.bumble.appyx.core.navigation.transition.JumpToEndTransitionHandler
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.node
import com.bumble.appyx.core.plugin.Plugin
import com.bumble.appyx.core.state.MutableSavedStateMap
import com.bumble.appyx.core.state.SavedStateMap
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import com.bumble.appyx.navmodel.backstack.transitionhandler.rememberBackstackSlider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import im.vector.app.features.analytics.plan.JoinedRoom
import io.element.android.annotations.ContributesNode
import io.element.android.appnav.di.MatrixSessionCache
import io.element.android.appnav.intent.IntentResolver
import io.element.android.appnav.intent.ResolvedIntent
import io.element.android.appnav.room.RoomFlowNode
import io.element.android.appnav.room.RoomNavigationTarget
import io.element.android.appnav.root.RootNavState
import io.element.android.appnav.root.RootNavStateFlowFactory
import io.element.android.appnav.root.RootPresenter
import io.element.android.appnav.root.RootView
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.announcement.api.AnnouncementService
import io.element.android.features.login.api.LoginParams
import io.element.android.features.login.api.accesscontrol.AccountProviderAccessControl
import io.element.android.features.rageshake.api.bugreport.BugReportEntryPoint
import io.element.android.features.share.api.ShareIntentData
import io.element.android.features.signedout.api.SignedOutEntryPoint
import io.element.android.libraries.accountselect.api.AccountSelectEntryPoint
import io.element.android.libraries.androidutils.startup.StartupTrace
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.architecture.BaseFlowNode
import io.element.android.libraries.architecture.appyx.rememberDelegateTransitionHandler
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.architecture.waitForChildAttached
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.uri.ensureProtocol
import io.element.android.libraries.deeplink.api.DeeplinkData
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.asEventId
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.libraries.oauth.api.OAuthAction
import io.element.android.libraries.oauth.api.OAuthActionFlow
import io.element.android.libraries.sessionstorage.api.LoggedInState
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.dataprotection.api.ManagementSecurityRefreshService
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.services.analytics.api.AnalyticsLongRunningTransaction
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.analytics.api.watchers.AnalyticsColdStartWatcher
import io.element.android.services.appnavstate.api.ROOM_OPENED_FROM_NOTIFICATION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.parcelize.Parcelize
import timber.log.Timber

private const val ROOT_SESSION_RESTORE_TIMEOUT_MILLIS = 15_000L
private const val ROOT_SESSION_RESTORE_FAILURE = "SESSION_RESTORE_FAILED"

@ContributesNode(AppScope::class)
@AssistedInject
class RootFlowNode(
    @Assisted val buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val sessionStore: SessionStore,
    private val accountProviderAccessControl: AccountProviderAccessControl,
    private val navStateFlowFactory: RootNavStateFlowFactory,
    private val matrixSessionCache: MatrixSessionCache,
    private val presenter: RootPresenter,
    private val bugReportEntryPoint: BugReportEntryPoint,
    private val signedOutEntryPoint: SignedOutEntryPoint,
    private val accountSelectEntryPoint: AccountSelectEntryPoint,
    private val intentResolver: IntentResolver,
    private val oAuthActionFlow: OAuthActionFlow,
    private val featureFlagService: FeatureFlagService,
    private val announcementService: AnnouncementService,
    private val analyticsService: AnalyticsService,
    private val analyticsColdStartWatcher: AnalyticsColdStartWatcher,
    private val managementSecurityRefreshService: ManagementSecurityRefreshService,
    private val dispatchers: CoroutineDispatchers,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : BaseFlowNode<RootFlowNode.NavTarget>(
    backstack = BackStack(
        initialElement = NavTarget.Boot,
        savedStateMap = null,
    ),
    buildContext = buildContext,
    plugins = plugins
) {
    private val revealStartupAtRoot = MutableStateFlow(false)

    override fun onBuilt() {
        StartupTrace.mark("RootFlowNode.onBuilt")
        analyticsColdStartWatcher.start()
        appCoroutineScope.launch(dispatchers.io) {
            val savedStateMap = buildContext.savedStateMap
            StartupTrace.measure("RootFlowNode.savedSessionRestore") {
                matrixSessionCache.restoreWithSavedState(savedStateMap)
            }
            // Read the authoritative persisted login state before restoring an Android navigation snapshot.
            // A saved NotLoggedInFlow can outlive a successful session restore; rendering it first exposes a
            // real login/onboarding frame before the state flow corrects the backstack on the next main-loop turn.
            val initialNavState = navStateFlowFactory.create(savedStateMap).first()
            withContext(dispatchers.main) {
                val savedTargets = savedNavigationTargets(savedStateMap)
                val restoredSessionAvailable = (initialNavState.loggedInState as? LoggedInState.LoggedIn)
                    ?.takeIf { it.isTokenValid }
                    ?.let { matrixSessionCache.getOrNull(SessionId(it.sessionId)) != null }
                    ?: true
                val canRestoreNavigation = restoredSessionAvailable &&
                    restoredNavigationMatches(initialNavState, savedTargets)
                if (canRestoreNavigation) {
                    restoreSavedState(savedStateMap)
                    revealStartupAtRoot.value = restoredRootDestinationIsVisible(initialNavState, savedTargets)
                } else {
                    applyNavState(initialNavState)
                }
                observeNavState(initialNavState)
            }
        }
        super.onBuilt()
    }

    override fun onSaveInstanceState(state: MutableSavedStateMap) {
        super.onSaveInstanceState(state)
        matrixSessionCache.saveIntoSavedState(state)
        navStateFlowFactory.saveIntoSavedState(state)
    }

    private fun observeNavState(initialNavState: RootNavState) {
        var pendingInitialState: RootNavState? = initialNavState
        navStateFlowFactory.create(buildContext.savedStateMap)
            .distinctUntilChanged()
            .onEach { navState ->
                if (pendingInitialState == navState) {
                    pendingInitialState = null
                    return@onEach
                }
                pendingInitialState = null
                applyNavState(navState)
            }
            .launchIn(lifecycleScope)
    }

    private fun applyNavState(navState: RootNavState) {
        Timber.v("navState=$navState")
        when (navState.loggedInState) {
            is LoggedInState.LoggedIn -> {
                if (navState.loggedInState.isTokenValid) {
                    val sessionId = SessionId(navState.loggedInState.sessionId)
                    if (matrixSessionCache.getOrNull(sessionId) != null) {
                        switchToLoggedInFlow(sessionId, navState.cacheIndex)
                    } else {
                        // The local account is already known: draw a useful Home shell while
                        // the Matrix client is restored, instead of exposing login/onboarding.
                        backstack.safeRoot(NavTarget.LoggedInBoot)
                        appCoroutineScope.launch(dispatchers.io) {
                            val restoredSessionId = withTimeoutOrNull(ROOT_SESSION_RESTORE_TIMEOUT_MILLIS) {
                                StartupTrace.measureSuspend("RootFlowNode.sessionRestore") {
                                    restoreLatestSession()
                                }
                            }
                            withContext(dispatchers.main) {
                                if (restoredSessionId != null) {
                                    switchToLoggedInFlow(restoredSessionId, navState.cacheIndex)
                                } else {
                                    backstack.safeRoot(NavTarget.StartupFailure(ROOT_SESSION_RESTORE_FAILURE))
                                    revealStartupAtRoot.value = true
                                }
                            }
                        }
                    }
                } else {
                    switchToSignedOutFlow(SessionId(navState.loggedInState.sessionId))
                }
            }
            LoggedInState.NotLoggedIn -> switchToNotLoggedInFlow(null)
        }
    }

    /**
     * Restore the saved state for navigation in the current backstack.
     *
     * **WARNING:** this is an unsafe operation abusing the internals of the Appyx library, but it's the only way allow async state
     * restoration and not having to block the main thread when the app starts.
     *
     * Modify with utmost care and double check any possible Appyx updates that might break this.
     */
    @Suppress("UNCHECKED_CAST")
    private fun restoreSavedState(savedStateMap: SavedStateMap?) {
        if (savedStateMap == null) return

        // 'NavModel' is the key used for storing the nav model state data in the map in Appyx
        val savedElements = savedStateMap["NavModel"] as? NavElements<NavTarget, BackStack.State>
        if (savedElements != null) {
            backstack.accept(ReplaceAllOperation(savedElements))
        }
    }

    /**
     * Extract the saved state for navigation in the [navTarget].
     *
     * **WARNING:** this is an unsafe operation abusing the internals of the Appyx library, but it's the only way allow async state
     * restoration and not having to block the main thread when the app starts.
     *
     * Modify with utmost care and double check any possible Appyx updates that might break this.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractSavedStateForNavTarget(navTarget: NavTarget, savedStateMap: SavedStateMap?): SavedStateMap? {
        // 'ChildrenState' is the key used for storing the children state data in the map in Appyx
        val childrenState = savedStateMap?.get("ChildrenState") as? Map<NavKey<NavTarget>, SavedStateMap> ?: return null
        return childrenState.entries.find { (key, _) -> key.navTarget == navTarget }?.value
    }

    private fun switchToLoggedInFlow(sessionId: SessionId, navId: Int) {
        revealStartupAtRoot.value = false
        backstack.safeRoot(NavTarget.LoggedInFlow(sessionId, navId))
    }

    private fun switchToNotLoggedInFlow(params: LoginParams?) {
        matrixSessionCache.removeAll()
        backstack.safeRoot(NavTarget.NotLoggedInFlow(params))
        revealStartupAtRoot.value = true
    }

    private fun switchToSignedOutFlow(sessionId: SessionId) {
        backstack.safeRoot(NavTarget.SignedOutFlow(sessionId))
        revealStartupAtRoot.value = true
    }

    private suspend fun restoreLatestSession(): SessionId? {
        val latestSessionId = sessionStore.getLatestSessionId()
            ?: return null
        val initialRestore = matrixSessionCache.getOrRestore(latestSessionId)
        initialRestore.getOrNull()?.let {
            Timber.v("Succeed to restore session $latestSessionId")
            return latestSessionId
        }
        Timber.w(initialRestore.exceptionOrNull(), "Initial secure session restore is unavailable; refreshing security access")
        val refresh = runCatching { managementSecurityRefreshService.refreshSecurityAccess() }
            .onFailure { Timber.w(it, "Failed to refresh security access before session restore retry") }
            .getOrNull()
        if (refresh?.status !in setOf(ManagementSyncStatus.SYNCHRONIZED, ManagementSyncStatus.DISABLED)) {
            Timber.e(initialRestore.exceptionOrNull(), "Failed to restore session $latestSessionId")
            return null
        }
        return matrixSessionCache.getOrRestore(latestSessionId).fold(
            onSuccess = {
                Timber.v("Succeed to restore session $latestSessionId after security refresh")
                latestSessionId
            },
            onFailure = {
                Timber.e(it, "Failed to restore session $latestSessionId")
                null
            },
        )
    }

    private fun onOpenBugReport() {
        backstack.push(NavTarget.BugReport)
    }

    private fun bootNode(buildContext: BuildContext): Node = node(buildContext) { modifier ->
        StartupWaitingView(modifier)
    }

    @Composable
    override fun View(modifier: Modifier) {
        val state = presenter.present()
        val revealStartup by revealStartupAtRoot.collectAsState()
        RootView(
            state = state,
            modifier = modifier.revealStartupSplashWhenLaidOut(revealStartup),
            onOpenBugReport = this::onOpenBugReport,
        ) {
            val backstackSlider = rememberBackstackSlider<NavTarget>(
                transitionSpec = { spring(stiffness = Spring.StiffnessMediumLow) },
            )
            val startupJump = remember { JumpToEndTransitionHandler<NavTarget, BackStack.State>() }
            val transitionHandler = rememberDelegateTransitionHandler<NavTarget, BackStack.State> { navTarget ->
                when (navTarget) {
                    is NavTarget.Boot,
                    is NavTarget.LoggedInBoot,
                    is NavTarget.LoggedInFlow,
                    is NavTarget.NotLoggedInFlow,
                    is NavTarget.StartupFailure -> startupJump
                    else -> backstackSlider
                }
            }
            BackstackView(transitionHandler = transitionHandler)
            announcementService.Render(Modifier)
        }
    }

    sealed interface NavTarget : Parcelable {
        @Parcelize data object Boot : NavTarget

        @Parcelize data object LoggedInBoot : NavTarget

        @Parcelize data class AccountSelect(
            val currentSessionId: SessionId,
            val shareIntentData: ShareIntentData?,
            val permalinkData: PermalinkData?,
        ) : NavTarget

        @Parcelize data class NotLoggedInFlow(
            val params: LoginParams?
        ) : NavTarget

        @Parcelize data class LoggedInFlow(
            val sessionId: SessionId, val navId: Int
        ) : NavTarget

        @Parcelize data class SignedOutFlow(
            val sessionId: SessionId
        ) : NavTarget

        @Parcelize data class StartupFailure(val reasonCode: String) : NavTarget

        @Parcelize data object BugReport : NavTarget
    }

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node {
        return when (navTarget) {
            is NavTarget.LoggedInFlow -> {
                val matrixClient = matrixSessionCache.getOrNull(navTarget.sessionId)
                    ?: return bootNode(buildContext).also {
                        Timber.w("Couldn't find any session, keeping the Boot UI")
                    }
                val inputs = LoggedInAppScopeFlowNode.Inputs(matrixClient)
                val callback = object : LoggedInAppScopeFlowNode.Callback {
                    override fun navigateToBugReport() {
                        backstack.push(NavTarget.BugReport)
                    }

                    override fun navigateToAddAccount() {
                        backstack.push(NavTarget.NotLoggedInFlow(null))
                    }
                }
                val savedNavState = extractSavedStateForNavTarget(navTarget, this.buildContext.savedStateMap)
                val buildContext = if (savedNavState != null) {
                    Timber.d("Creating a $navTarget with restored saved state")
                    buildContext.copy(savedStateMap = savedNavState)
                } else {
                    buildContext.copy(savedStateMap = savedNavState)
                }
                createNode<LoggedInAppScopeFlowNode>(buildContext, plugins = listOf(inputs, callback))
            }
            is NavTarget.NotLoggedInFlow -> {
                val callback = object : NotLoggedInFlowNode.Callback {
                    override fun navigateToBugReport() {
                        backstack.push(NavTarget.BugReport)
                    }

                    override fun onDone() {
                        backstack.pop()
                    }
                }
                val params = NotLoggedInFlowNode.Params(
                    loginParams = navTarget.params,
                )
                createNode<NotLoggedInFlowNode>(buildContext, plugins = listOf(params, callback))
            }
            is NavTarget.SignedOutFlow -> {
                signedOutEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    params = SignedOutEntryPoint.Params(
                        sessionId = navTarget.sessionId,
                    ),
                )
            }
            is NavTarget.StartupFailure -> node(buildContext) { modifier ->
                val activity = LocalContext.current as? Activity
                StartupFailureView(
                    reasonCode = navTarget.reasonCode,
                    onRetry = { activity?.recreate() },
                    modifier = modifier,
                )
            }
            NavTarget.Boot -> bootNode(buildContext)
            NavTarget.LoggedInBoot -> bootNode(buildContext)
            NavTarget.BugReport -> {
                val callback = object : BugReportEntryPoint.Callback {
                    override fun onDone() {
                        backstack.pop()
                    }
                }
                bugReportEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    callback = callback,
                )
            }
            is NavTarget.AccountSelect -> {
                val callback: AccountSelectEntryPoint.Callback = object : AccountSelectEntryPoint.Callback {
                    override fun onAccountSelected(sessionId: SessionId) {
                        lifecycleScope.launch {
                            if (sessionId == navTarget.currentSessionId) {
                                // Ensure that the account selection Node is removed from the backstack
                                // Do not pop when the account is changed to avoid a UI flicker.
                                backstack.pop()
                            }
                            attachSession(sessionId).apply {
                                if (navTarget.shareIntentData != null) {
                                    attachIncomingShare(navTarget.shareIntentData)
                                } else if (navTarget.permalinkData != null) {
                                    attachPermalinkData(navTarget.permalinkData)
                                }
                            }
                        }
                    }

                    override fun onCancel() {
                        backstack.pop()
                    }
                }
                accountSelectEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    callback = callback,
                )
            }
        }
    }

    suspend fun handleIntent(intent: Intent) {
        val resolvedIntent = intentResolver.resolve(intent) ?: return
        when (resolvedIntent) {
            is ResolvedIntent.Navigation -> {
                val openingRoomFromNotification = intent.getBooleanExtra(ROOM_OPENED_FROM_NOTIFICATION, false)
                if (openingRoomFromNotification && resolvedIntent.deeplinkData is DeeplinkData.Room) {
                    analyticsService.startLongRunningTransaction(AnalyticsLongRunningTransaction.NotificationToMessage)
                }
                navigateTo(resolvedIntent.deeplinkData)
            }
            is ResolvedIntent.Login -> onLoginLink(resolvedIntent.params)
            is ResolvedIntent.OAuth -> onOAuthAction(resolvedIntent.oAuthAction)
            is ResolvedIntent.Permalink -> navigateTo(resolvedIntent.permalinkData)
            is ResolvedIntent.IncomingShare -> onIncomingShare(resolvedIntent.shareIntentData)
        }
    }

    private suspend fun onLoginLink(params: LoginParams) {
        if (accountProviderAccessControl.isAllowedToConnectToAccountProvider(params.accountProvider.ensureProtocol())) {
            // Is there a session already?
            val sessions = sessionStore.getAllSessions()
            if (sessions.isNotEmpty()) {
                if (featureFlagService.isFeatureEnabled(FeatureFlags.MultiAccount)) {
                    val loginHintMatrixId = params.loginHint?.removePrefix("mxid:")
                    val existingAccount = sessions.find { it.userId == loginHintMatrixId }
                    if (existingAccount != null) {
                        // We have an existing account matching the login hint, ensure this is the current session
                        sessionStore.setLatestSession(existingAccount.userId)
                    } else {
                        val latestSessionId = sessions.maxBy { it.lastUsageIndex }.userId
                        attachSession(SessionId(latestSessionId))
                        backstack.push(NavTarget.NotLoggedInFlow(params))
                    }
                } else {
                    Timber.w("Login link ignored, multi account is disabled")
                }
            } else {
                switchToNotLoggedInFlow(params)
            }
        } else {
            Timber.w("Login link ignored, we are not allowed to connect to the homeserver")
        }
    }

    private suspend fun onIncomingShare(shareIntentData: ShareIntentData) {
        // Is there a session already?
        val latestSessionId = sessionStore.getLatestSessionId()
        if (latestSessionId == null) {
            // No session, open login
            switchToNotLoggedInFlow(null)
        } else {
            // wait for the current session to be restored
            val loggedInFlowNode = attachSession(latestSessionId)
            if (sessionStore.numberOfSessions() > 1) {
                // Several accounts, let the user choose which one to use
                backstack.push(
                    NavTarget.AccountSelect(
                        currentSessionId = latestSessionId,
                        shareIntentData = shareIntentData,
                        permalinkData = null,
                    )
                )
            } else {
                // Only one account, directly attach the incoming share node.
                loggedInFlowNode.attachIncomingShare(shareIntentData)
            }
        }
    }

    private suspend fun navigateTo(permalinkData: PermalinkData) {
        Timber.d("Navigating to $permalinkData")
        // Is there a session already?
        val latestSessionId = sessionStore.getLatestSessionId()
        if (latestSessionId == null) {
            // No session, open login
            switchToNotLoggedInFlow(null)
        } else {
            // wait for the current session to be restored
            val loggedInFlowNode = attachSession(latestSessionId)
            when (permalinkData) {
                is PermalinkData.FallbackLink -> Unit
                is PermalinkData.RoomEmailInviteLink -> Unit
                else -> {
                    if (sessionStore.numberOfSessions() > 1) {
                        // Several accounts, let the user choose which one to use
                        backstack.push(
                            NavTarget.AccountSelect(
                                currentSessionId = latestSessionId,
                                shareIntentData = null,
                                permalinkData = permalinkData,
                            )
                        )
                    } else {
                        // Only one account, directly attach the room or the user node.
                        loggedInFlowNode.attachPermalinkData(permalinkData)
                    }
                }
            }
        }
    }

    private suspend fun LoggedInFlowNode.attachPermalinkData(permalinkData: PermalinkData) {
        when (permalinkData) {
            is PermalinkData.FallbackLink -> Unit
            is PermalinkData.RoomEmailInviteLink -> Unit
            is PermalinkData.RoomLink -> {
                if (permalinkData.invitationToken != null) {
                    openGroupShare(permalinkData)
                    return
                }
                // If there is a thread id, focus on it in the main timeline
                val focusedEventId = if (permalinkData.threadId != null) {
                    permalinkData.threadId?.asEventId()
                } else {
                    permalinkData.eventId
                }
                attachRoom(
                    roomIdOrAlias = permalinkData.roomIdOrAlias,
                    trigger = JoinedRoom.Trigger.MobilePermalink,
                    serverNames = permalinkData.viaParameters,
                    initialElement = RoomNavigationTarget.Root(eventId = focusedEventId),
                    clearBackstack = true
                ).maybeAttachThread(permalinkData.threadId, permalinkData.eventId)
            }
            is PermalinkData.UserLink -> {
                attachUser(permalinkData.userId)
            }
        }
    }

    private suspend fun RoomFlowNode.maybeAttachThread(threadId: ThreadId?, focusedEventId: EventId?) {
        if (threadId != null) {
            attachThread(threadId, focusedEventId)
        }
    }

    private suspend fun navigateTo(deeplinkData: DeeplinkData) {
        Timber.d("Navigating to $deeplinkData")
        attachSession(deeplinkData.sessionId).let { loggedInFlowNode ->
            when (deeplinkData) {
                is DeeplinkData.Root -> Unit // The room list will always be shown, observing FtueState
                is DeeplinkData.Room -> {
                    loggedInFlowNode.attachRoom(
                        roomIdOrAlias = deeplinkData.roomId.toRoomIdOrAlias(),
                        initialElement = RoomNavigationTarget.Root(eventId = deeplinkData.threadId?.asEventId() ?: deeplinkData.eventId),
                        clearBackstack = true,
                    ).maybeAttachThread(deeplinkData.threadId, deeplinkData.eventId)
                }
            }
        }
    }

    private fun onOAuthAction(oAuthAction: OAuthAction) {
        oAuthActionFlow.post(oAuthAction)
    }

    private suspend fun attachSession(sessionId: SessionId): LoggedInFlowNode {
        // Ensure that the session is the latest one
        sessionStore.setLatestSession(sessionId.value)
        return waitForChildAttached<LoggedInAppScopeFlowNode, NavTarget> { navTarget ->
            navTarget is NavTarget.LoggedInFlow && navTarget.sessionId == sessionId
        }.attachSession()
    }
}

private fun restoredRootDestinationIsVisible(
    navState: RootNavState,
    restoredTargets: List<RootFlowNode.NavTarget>,
): Boolean = when (val loggedInState = navState.loggedInState) {
    LoggedInState.NotLoggedIn -> true
    is LoggedInState.LoggedIn -> !loggedInState.isTokenValid || restoredTargets.any { target ->
        target is RootFlowNode.NavTarget.AccountSelect ||
            target is RootFlowNode.NavTarget.BugReport ||
            target is RootFlowNode.NavTarget.SignedOutFlow ||
            target is RootFlowNode.NavTarget.NotLoggedInFlow ||
            target is RootFlowNode.NavTarget.StartupFailure
    }
}

internal fun restoredNavigationMatches(
    navState: RootNavState,
    restoredTargets: List<RootFlowNode.NavTarget>,
): Boolean = when (val loggedInState = navState.loggedInState) {
    LoggedInState.NotLoggedIn -> restoredTargets.any { it is RootFlowNode.NavTarget.NotLoggedInFlow }
    is LoggedInState.LoggedIn -> if (loggedInState.isTokenValid) {
        restoredTargets.any { target ->
            target is RootFlowNode.NavTarget.LoggedInFlow && target.sessionId.value == loggedInState.sessionId
        }
    } else {
        restoredTargets.any { target ->
            target is RootFlowNode.NavTarget.SignedOutFlow && target.sessionId.value == loggedInState.sessionId
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun savedNavigationTargets(savedStateMap: SavedStateMap?): List<RootFlowNode.NavTarget> =
    (savedStateMap?.get("NavModel") as? NavElements<RootFlowNode.NavTarget, BackStack.State>)
        ?.map { it.key.navTarget }
        .orEmpty()

private suspend fun SessionStore.getLatestSessionId() = getLatestSession()?.userId?.let(::SessionId)

@Composable
fun StartupWaitingView(modifier: Modifier = Modifier) {
    if (LocalCompactStartup.current) {
        Box(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        return
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(cn.yizhesy.yizhechat.appicon.R.color.yizhe_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(cn.yizhesy.yizhechat.appicon.R.drawable.startup_logo),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .size(112.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 124.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = colorResource(cn.yizhesy.yizhechat.appicon.R.color.yizhe_startup_indicator),
                trackColor = Color.Transparent,
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.screen_startup_preparing_security_environment),
                color = colorResource(cn.yizhesy.yizhechat.appicon.R.color.yizhe_startup_text_primary),
                style = ElementTheme.typography.fontBodyMdMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.screen_startup_verifying_device_and_policy),
                color = colorResource(cn.yizhesy.yizhechat.appicon.R.color.yizhe_startup_text_secondary),
                style = ElementTheme.typography.fontBodySmRegular,
            )
        }
    }
}
