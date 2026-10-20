/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import im.vector.app.features.analytics.plan.MobileScreen
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.call.api.CallData
import io.element.android.features.call.impl.data.WidgetMessage
import io.element.android.features.call.impl.security.CallJoinAttempt
import io.element.android.features.call.impl.security.CallProtectionService
import io.element.android.features.call.impl.security.CallProtectionSession
import io.element.android.features.call.impl.utils.ActiveCallManager
import io.element.android.features.call.impl.utils.CallWidgetProvider
import io.element.android.features.call.impl.utils.WidgetMessageInterceptor
import io.element.android.features.call.impl.utils.WidgetMessageSerializer
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.sync.SyncState
import io.element.android.libraries.matrix.api.widget.MatrixWidgetDriver
import io.element.android.libraries.network.useragent.UserAgentProvider
import io.element.android.features.networkmonitor.api.NetworkMonitor
import io.element.android.features.networkmonitor.api.NetworkStatus
import io.element.android.services.analytics.api.ScreenTracker
import io.element.android.services.appnavstate.api.AppForegroundStateService
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.dataprotection.api.withOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.cancelAndJoin
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@AssistedInject
class CallScreenPresenter(
    @Assisted private val callData: CallData,
    @Assisted private val navigator: CallScreenNavigator,
    private val callWidgetProvider: CallWidgetProvider,
    userAgentProvider: UserAgentProvider,
    private val clock: SystemClock,
    private val dispatchers: CoroutineDispatchers,
    private val matrixClientsProvider: MatrixClientProvider,
    private val screenTracker: ScreenTracker,
    private val activeCallManager: ActiveCallManager,
    private val languageTagProvider: LanguageTagProvider,
    private val appForegroundStateService: AppForegroundStateService,
    @AppCoroutineScope
    private val appCoroutineScope: CoroutineScope,
    private val widgetMessageSerializer: WidgetMessageSerializer,
    private val callProtectionService: CallProtectionService,
    private val previewAuthorization: io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val networkMonitor: NetworkMonitor,
) : Presenter<CallScreenState> {
    @AssistedFactory
    interface Factory {
        fun create(callData: CallData, navigator: CallScreenNavigator): CallScreenPresenter
    }

    private val userAgent = userAgentProvider.provide()
    private val setupStarted = TimeSource.Monotonic.markNow()

    private fun logSetupPhase(phase: String) {
        Timber.tag("RTCSetup").d("phase=%s elapsedMs=%d", phase, setupStarted.elapsedNow().inWholeMilliseconds)
    }
    // Start the IO handoff before main-thread WebView destruction can delay departure.
    private val cleanupStart = if (NextDeploymentConfig.isNextDeployment) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT

    @Composable
    override fun present(): CallScreenState {
        val coroutineScope = rememberCoroutineScope()
        val urlState = remember { mutableStateOf<AsyncData<String>>(AsyncData.Uninitialized) }
        val preparationUrl = remember { mutableStateOf<String?>(null) }
        val callWidgetDriver = remember { mutableStateOf<MatrixWidgetDriver?>(null) }
        val messageInterceptor = remember { mutableStateOf<WidgetMessageInterceptor?>(null) }
        val protectionSession = remember { mutableStateOf<CallProtectionSession?>(null) }
        var isWidgetLoaded by rememberSaveable { mutableStateOf(false) }
        var startupContentLoaded by remember { mutableStateOf(false) }
        var ignoreWebViewError by rememberSaveable { mutableStateOf(false) }
        var webViewError by remember { mutableStateOf<String?>(null) }
        var terminationInitiated by remember { mutableStateOf(false) }
        var contactEndedNotice by remember { mutableStateOf(false) }
        LaunchedEffect(startupContentLoaded, urlState.value, terminationInitiated) {
            if (startupContentLoaded && urlState.value is AsyncData.Success && !terminationInitiated && !isWidgetLoaded) {
                isWidgetLoaded = true
                logSetupPhase("content_loaded")
            }
        }
        var connectedAtMillis by remember { mutableStateOf<Long?>(null) }
        var connectedSeconds by remember { mutableStateOf(0L) }
        LaunchedEffect(connectedAtMillis, terminationInitiated) {
            val started = connectedAtMillis ?: return@LaunchedEffect
            while (!terminationInitiated) {
                connectedSeconds = maxOf(connectedSeconds, ((clock.epochMillis() - started) / 1000).coerceAtLeast(0))
                delay(1.seconds)
            }
        }
        val mediaReadyJob = remember { mutableStateOf<Job?>(null) }
        val joinAttempt = remember { CallJoinAttempt() }
        val preparationJob = remember { mutableStateOf<Job?>(null) }
        val previewAccess by previewAuthorization.accessState.collectAsState()
        val networkStatus by networkMonitor.connectivity.collectAsState()
        val networkBlocked by networkMonitor.isNetworkBlocked.collectAsState()
        val isNetworkDisconnected = networkStatus == NetworkStatus.Disconnected || networkBlocked
        var peerName by remember { mutableStateOf<String?>(null) }
        var peerAvatarUrl by remember { mutableStateOf<String?>(null) }
        var isGroupCall by remember { mutableStateOf(false) }
        LaunchedEffect(callData) {
            val room = matrixClientsProvider.getOrNull(callData.sessionId)?.getRoom(callData.roomId)
            room?.info()?.let { peerName = it.name; peerAvatarUrl = it.avatarUrl; isGroupCall = !it.isDm }
        }
        val callOperationId = rememberSaveable { UUID.randomUUID().toString() }
        val joinOperationRequest = remember(callOperationId) { callOperationRequest(callOperationId) }
        val languageTag = languageTagProvider.provideLanguageTag()
        val theme = if (NextDeploymentConfig.isNextDeployment || !ElementTheme.isLightTheme) "dark" else "light"

        fun terminateOnCallStateFailure(failure: Exception) {
            Timber.w(failure, "Protected call state update failed; ending media and retaining finalization evidence.")
            if (terminationInitiated) return
            terminationInitiated = true
            joinAttempt.cancel()
            isWidgetLoaded = false
            navigator.close()
            appCoroutineScope.launch(start = cleanupStart) {
                releaseLocalCallAndFinalize(callWidgetDriver, callOperationId, protectionSession, preparationJob.value)
            }
        }

        DisposableEffect(Unit) {
            preparationJob.value = coroutineScope.launch {
                try {
                  logSetupPhase("lifecycle_requested")
                  appOperationalStateCoordinator.withOperation(joinOperationRequest) {
                    logSetupPhase("lifecycle_ready")
                    fetchRoomCallUrl(
                        callData = callData,
                        urlState = urlState,
                        preparationUrl = preparationUrl,
                        callWidgetDriver = callWidgetDriver,
                        protectionSession = protectionSession,
                        joinAttempt = joinAttempt,
                        operationId = callOperationId,
                        languageTag = languageTag,
                        theme = theme,
                    )
                  }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    urlState.value = AsyncData.Failure(failure)
                }
            }
            onDispose {
                joinAttempt.cancel()
                if (!terminationInitiated) {
                    terminationInitiated = true
                    joinAttempt.cancel()
                    mediaReadyJob.value?.cancel()
                    appCoroutineScope.launch(start = cleanupStart) {
                        releaseLocalCallAndFinalize(callWidgetDriver, callOperationId, protectionSession, preparationJob.value)
                    }
                }
            }
        }
        screenTracker.TrackScreen(screen = MobileScreen.ScreenName.RoomCall)
        HandleMatrixClientSyncState()

        LaunchedEffect(activeCallManager, callData) {
            var observedThisCall = false
            activeCallManager.activeCall.collect { activeCall ->
                val isThisCall = activeCall?.callData?.let {
                    it.sessionId == callData.sessionId && it.roomId == callData.roomId &&
                        (!NextDeploymentConfig.isNextDeployment || it.attemptId == callData.attemptId)
                } == true
                if (isThisCall && activeCall?.remoteEnded != true) {
                    observedThisCall = true
                } else if ((observedThisCall || isThisCall) && !terminationInitiated) {
                    // ActiveCallManager owns Matrix/RTC membership. Once it observes that the
                    // connected DM has ended remotely, close the native surface immediately too.
                    terminationInitiated = true
                    joinAttempt.cancel()
                    mediaReadyJob.value?.cancel()
                    isWidgetLoaded = false
                    navigator.close()
                    appCoroutineScope.launch(start = cleanupStart) {
                        releaseLocalCallAndFinalize(callWidgetDriver, callOperationId, protectionSession, preparationJob.value)
                    }
                }
            }
        }

        callWidgetDriver.value?.let { driver ->
            LaunchedEffect(driver) {
                callWidgetProvider.observeContactRevocation(callData.sessionId, callData.roomId, callOperationId).collect { failure ->
                    if (!terminationInitiated) {
                        terminationInitiated = true
                        contactEndedNotice = true
                        joinAttempt.cancel()
                        mediaReadyJob.value?.cancel()
                        messageInterceptor.value?.takeIf { isWidgetLoaded }?.let { sendHangupMessage(driver.id, it) }
                        isWidgetLoaded = false
                        preparationUrl.value = null
                        urlState.value = AsyncData.Failure(failure)
                        // Remove the WebView/media immediately, but keep an actionable native
                        // explanation. The original LC20 operation owns departure/finalization.
                        appCoroutineScope.launch(start = cleanupStart) {
                            releaseLocalCallAndFinalize(callWidgetDriver, callOperationId, protectionSession, preparationJob.value)
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                driver.incomingMessages
                    .onEach {
                        // Relay message to the WebView
                        messageInterceptor.value?.sendMessage(it)
                    }
                    .launchIn(this)

                if (NextDeploymentConfig.isNextDeployment) {
                    driver.outgoingCallInvitation.onEach { eventId ->
                        if (eventId != null) protectCallStateUpdate(::terminateOnCallStateFailure) {
                            activeCallManager.bindOutgoingInvitation(callData, io.element.android.libraries.matrix.api.core.EventId(eventId))
                        }
                    }.launchIn(this)
                    // Media/view lifetime ends immediately on revocation. Keep only the native
                    // signaling worker alive until its bounded departure has been attempted.
                    appCoroutineScope.launch(dispatchers.io) { driver.run() }
                } else {
                    driver.run()
                }
            }
        }

        protectionSession.value?.let { session ->
            LaunchedEffect(session) {
                callProtectionService.observeDeviceAccess(session).collect { allowed ->
                    if (!allowed && !terminationInitiated) {
                        terminationInitiated = true
                        mediaReadyJob.value?.cancel()
                        Timber.w("Protected call access was revoked; terminating the active call.")
                        messageInterceptor.value?.takeIf { isWidgetLoaded }?.let { interceptor ->
                            callWidgetDriver.value?.id?.let { widgetId -> sendHangupMessage(widgetId, interceptor) }
                        }
                        isWidgetLoaded = false
                        navigator.close()
                        appCoroutineScope.launch(start = cleanupStart) {
                            releaseLocalCallAndFinalize(callWidgetDriver, callOperationId, protectionSession, preparationJob.value)
                        }
                    }
                }
            }
        }

        messageInterceptor.value?.let { interceptor ->
            LaunchedEffect(Unit) {
                interceptor.interceptedMessages
                    .onEach {
                        if (NextDeploymentConfig.isNextDeployment) {
                            // Protected local HTML may negotiate the protocol during native
                            // confirmation. Any identity, write, key or media request still waits.
                            val passive = isPassiveCallStartupMessage(it, callWidgetDriver.value?.id)
                            if (!passive) {
                                snapshotFlow { urlState.value to terminationInitiated }.first { (state, ended) ->
                                    ended || state is AsyncData.Success || state is AsyncData.Failure
                                }
                            }
                            if (terminationInitiated || urlState.value is AsyncData.Failure ||
                                (!passive && urlState.value !is AsyncData.Success)
                            ) return@onEach
                        }
                        // We are receiving messages from the WebView, consider that the application is loaded
                        ignoreWebViewError = true
                        val parsedMessage = parseMessage(it)
                        if (parsedMessage?.direction == WidgetMessage.Direction.FromWidget && parsedMessage.action.isHostAction()) {
                            acknowledgeHostAction(parsedMessage, interceptor)
                            if (parsedMessage.action == WidgetMessage.Action.MediaReady && NextDeploymentConfig.isNextDeployment &&
                                !terminationInitiated && parsedMessage.response == null && parsedMessage.widgetId == callWidgetDriver.value?.id
                            ) {
                                val mediaData = parsedMessage.data as? kotlinx.serialization.json.JsonObject
                                if (mediaData?.get("protocol") == kotlinx.serialization.json.JsonPrimitive("next.rtc-media-ready.v1") &&
                                    mediaData["operationId"] == kotlinx.serialization.json.JsonPrimitive(callOperationId) &&
                                    mediaReadyJob.value == null
                                ) {
                                    // A loaded widget is not an answered call. Only this admitted,
                                    // generation-bound decoded-media report starts the visible clock.
                                logSetupPhase("media_ready")
                                connectedAtMillis = clock.epochMillis()
                                    // Protected record writes can wait on KMS. Keep renewal and hangup
                                    // messages flowing, while retaining the admitted authorization context.
                                    // Start immediately so the manager records readiness before hangup;
                                    // this screen owns and cancels the write when the call terminates.
                                    mediaReadyJob.value = coroutineScope.launch(
                                        LifecycleOperationContext(callOperationId, FullAppLifecycleId.LC_20),
                                        start = CoroutineStart.UNDISPATCHED,
                                    ) {
                                        protectCallStateUpdate(::terminateOnCallStateFailure) {
                                            activeCallManager.mediaConnected(callData)
                                        }
                                    }
                                }
                            }
                            if (parsedMessage.action == WidgetMessage.Action.Close ||
                                (NextDeploymentConfig.isNextDeployment && parsedMessage.action == WidgetMessage.Action.HangUp &&
                                    parsedMessage.response == null && parsedMessage.widgetId == callWidgetDriver.value?.id)
                            ) {
                                if (!terminationInitiated) {
                                    terminationInitiated = true
                                    mediaReadyJob.value?.cancel()
                                    navigator.close()
                                    appCoroutineScope.launch(start = cleanupStart) {
                                        releaseLocalCallAndFinalize(callWidgetDriver, callOperationId, protectionSession, preparationJob.value)
                                    }
                                }
                            }
                        } else {
                            // Matrix actions are handled by the Rust widget driver. Unknown messages are
                            // forwarded as well so a newer driver can handle actions this app does not parse.
                            callWidgetDriver.value?.send(it)
                            if (parsedMessage?.direction == WidgetMessage.Direction.FromWidget &&
                                parsedMessage.action == WidgetMessage.Action.ContentLoaded && !terminationInitiated
                            ) {
                                if (NextDeploymentConfig.isNextDeployment && urlState.value !is AsyncData.Success) {
                                    startupContentLoaded = true
                                } else {
                                    isWidgetLoaded = true
                                    logSetupPhase("content_loaded")
                                }
                            }
                        }
                    }
                    .launchIn(this)
            }

            LaunchedEffect(interceptor, isWidgetLoaded, isNetworkDisconnected, terminationInitiated) {
                // Wait for the call to be joined, if it takes too long, we display an error
                // An offline device cannot load/join. Restart the loading budget after network
                // recovery instead of turning a recoverable interruption into a generic error.
                if (isWidgetLoaded || isNetworkDisconnected || terminationInitiated) return@LaunchedEffect
                if (NextDeploymentConfig.isNextDeployment) {
                    snapshotFlow { urlState.value to terminationInitiated }.first { (state, ended) ->
                        ended || state is AsyncData.Success || state is AsyncData.Failure
                    }
                    if (terminationInitiated || urlState.value !is AsyncData.Success) return@LaunchedEffect
                }
                delay(10.seconds)

                if (!isWidgetLoaded) {
                    Timber.w("The call took too long to load. Displaying an error before exiting.")

                    // This will display a simple 'Sorry, an error occurred' dialog and force the user to exit the call
                    webViewError = ""
                }
            }
        }

        fun handleEvent(event: CallScreenEvent) {
            when (event) {
                is CallScreenEvent.Hangup -> {
                    if (terminationInitiated) {
                        if (contactEndedNotice) {
                            contactEndedNotice = false
                            navigator.close()
                        }
                        return
                    }
                    terminationInitiated = true
                    joinAttempt.cancel()
                    mediaReadyJob.value?.cancel()
                    val widgetId = callWidgetDriver.value?.id
                    val interceptor = messageInterceptor.value
                    if (widgetId != null && interceptor != null && isWidgetLoaded) {
                        // If the call was joined, we need to hang up first. Then the UI will be dismissed automatically.
                        isWidgetLoaded = false

                        appCoroutineScope.launch(start = cleanupStart) {
                            sendHangupMessage(widgetId, interceptor)
                            // Give the WebView one frame to consume the host hangup signal. Closing
                            // the local surface must not wait for a remote lifecycle round trip.
                            delay(150.milliseconds)
                            kotlinx.coroutines.withContext(dispatchers.main) { navigator.close() }
                            releaseLocalCallAndFinalize(callWidgetDriver, callOperationId, protectionSession, preparationJob.value)
                        }
                    } else {
                        navigator.close()
                        appCoroutineScope.launch(start = cleanupStart) {
                            releaseLocalCallAndFinalize(callWidgetDriver, callOperationId, protectionSession, preparationJob.value)
                        }
                    }
                }
                is CallScreenEvent.SetupMessageChannels -> {
                    messageInterceptor.value = event.widgetMessageInterceptor
                }
                is CallScreenEvent.OnWebViewError -> {
                    if (!ignoreWebViewError && !isNetworkDisconnected) {
                        webViewError = event.description.orEmpty()
                    }
                    // Else ignore the error, give a chance the Element Call to recover by itself.
                }
            }
        }

        return CallScreenState(
            urlState = urlState.value,
            webViewError = webViewError,
            userAgent = userAgent,
            isCallActive = isWidgetLoaded,
            eventSink = ::handleEvent,
            peerName = peerName.takeIf { previewAccess.allowsPreviewAttempt },
            peerAvatarUrl = peerAvatarUrl.takeIf { previewAccess.allowsPreviewAttempt },
            isAudioCall = callData.isAudioCall, isIncoming = callData.notificationEventId != null,
            isGroupCall = isGroupCall,
            isNetworkDisconnected = isNetworkDisconnected,
            isTerminating = terminationInitiated,
            isMediaConnected = connectedAtMillis != null && !terminationInitiated,
            connectedSeconds = connectedSeconds,
            preparationUrl = preparationUrl.value,
        )
    }

    private suspend fun fetchRoomCallUrl(
        callData: CallData,
        urlState: MutableState<AsyncData<String>>,
        preparationUrl: MutableState<String?>,
        callWidgetDriver: MutableState<MatrixWidgetDriver?>,
        protectionSession: MutableState<CallProtectionSession?>,
        joinAttempt: CallJoinAttempt,
        operationId: String,
        languageTag: String?,
        theme: String?,
    ) {
        var invitationRoom: io.element.android.libraries.matrix.api.room.JoinedRoom? = null
        suspend fun attachNativeCall(): String {
            activeCallManager.validateIncomingCall(callData)
            logSetupPhase("widget_started")
            val result = callWidgetProvider.getWidget(
                sessionId = callData.sessionId,
                roomId = callData.roomId,
                operationId = operationId,
                clientId = UUID.randomUUID().toString(),
                isAudioCall = callData.isAudioCall,
                isIncoming = callData.notificationEventId != null,
                languageTag = languageTag,
                theme = theme,
            ).getOrThrow()
            logSetupPhase("widget_ready")
            if (!joinAttempt.isActive()) {
                // Admission completed after this screen already ended. Release the newly created
                // driver before it can publish media or replace the current call generation.
                result.driver.close()
                throw CancellationException("RTC join attempt was cancelled before attachment.")
            }
            // The first validation precedes preparation; the final validation below
            // still checks the current invitation after both native confirmations.
            // Do not construct another invitation timeline between those two boundaries.
            callWidgetDriver.value = result.driver
            protectionSession.value = result.protectionSession
            invitationRoom = result.invitationRoom
            if (NextDeploymentConfig.isNextDeployment) {
                preparationUrl.value = result.url
                logSetupPhase("local_page_preparation")
            }
            // Only project a joined native call after the existing authorization and widget
            // admission path has succeeded for the still-current attempt.
            activeCallManager.checkpointCall(callData, operationId)
            logSetupPhase("checkpoint_ready")
            activeCallManager.joinedCall(callData)
            logSetupPhase("native_joined")
            Timber.d("Call widget driver initialized for sessionId: ${callData.sessionId}, roomId: ${callData.roomId}")
            return result.url
        }
        urlState.runCatchingUpdatingState {
            logSetupPhase("admission_started")
            if (NextDeploymentConfig.isNextDeployment) {
                // These receipts attest only to native setup. Publish the URL
                // after they are accepted, so provider join cannot race ahead
                // of Android's required LC-20 steps. Media success remains RTC's fact.
                val url = appOperationalStateCoordinator.performCallAdmission(callOperationRequest(operationId)) {
                    attachNativeCall()
                }
                if (!joinAttempt.isActive()) throw CancellationException("RTC preparation was cancelled.")
                activeCallManager.validateIncomingCall(callData, invitationRoom)
                logSetupPhase("admission_ready")
                url
            } else {
                attachNativeCall()
            }
        }
    }

    @Composable
    private fun HandleMatrixClientSyncState() {
        val coroutineScope = rememberCoroutineScope()
        DisposableEffect(Unit) {
            val client = matrixClientsProvider.getOrNull(callData.sessionId) ?: return@DisposableEffect onDispose {
                Timber.w("No MatrixClient found for sessionId, can't send call notification: ${callData.sessionId}")
            }
            coroutineScope.launch {
                Timber.d("Observing sync state in-call for sessionId: ${callData.sessionId}")
                client.syncService.syncState
                    .collect { state ->
                        if (state != SyncState.Running) {
                            appForegroundStateService.updateIsInCallState(true)
                        }
                    }
            }
            onDispose {
                Timber.d("Stopped observing sync state in-call for sessionId: ${callData.sessionId}")
                // Make sure we mark the call as ended in the app state
                appForegroundStateService.updateIsInCallState(false)
            }
        }
    }

    private fun parseMessage(message: String): WidgetMessage? {
        return widgetMessageSerializer.deserialize(message).getOrNull()
    }

    private fun sendHangupMessage(widgetId: String, messageInterceptor: WidgetMessageInterceptor) {
        val message = WidgetMessage(
            direction = WidgetMessage.Direction.ToWidget,
            widgetId = widgetId,
            requestId = "widgetapi-${clock.epochMillis()}",
            action = WidgetMessage.Action.HangUp,
            data = null,
            response = null,
        )
        messageInterceptor.sendMessage(widgetMessageSerializer.serialize(message))
    }

    private fun acknowledgeHostAction(message: WidgetMessage, messageInterceptor: WidgetMessageInterceptor) {
        val response = message.copy(
            response = buildJsonObject {},
        )
        messageInterceptor.sendMessage(widgetMessageSerializer.serialize(response))
    }

    private fun WidgetMessage.Action.isHostAction(): Boolean = when (this) {
        WidgetMessage.Action.Join,
        WidgetMessage.Action.Close,
        WidgetMessage.Action.SetAlwaysOnScreen,
        WidgetMessage.Action.DeviceMute -> true
        WidgetMessage.Action.MediaReady,
        WidgetMessage.Action.HangUp -> NextDeploymentConfig.isNextDeployment
        else -> false
    }

    private fun callOperationRequest(operationId: String, trigger: String = "join") = lifecycleOperationRequest(
        lifecycleId = FullAppLifecycleId.LC_20,
        trigger = trigger,
        operationId = operationId,
        roomIdHash = MessageDigest.getInstance("SHA-256")
            .digest(callData.roomId.value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
    )

    private suspend fun closeWidgetDriver(widgetDriver: MatrixWidgetDriver?) {
        kotlinx.coroutines.withContext(dispatchers.io) {
            try {
                if (NextDeploymentConfig.isNextDeployment) {
                    kotlinx.coroutines.withTimeoutOrNull(5_000.milliseconds) { widgetDriver?.stopCallMembership() }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Timber.w("RTC immediate departure unavailable; delayed leave remains the fallback.")
            } finally {
                widgetDriver?.close()
            }
        }
    }

    private suspend fun releaseLocalCallAndFinalize(
        driverState: MutableState<MatrixWidgetDriver?>,
        operationId: String,
        protectionState: MutableState<CallProtectionSession?>,
        preparation: Job?,
    ) {
        // Do not attest cleanup while a cancelled preparation can still attach resources.
        preparation?.cancelAndJoin()
        val widgetDriver = driverState.value
        val protectionSession = protectionState.value
        // Local media, notifications, wake locks and the current-call projection must be released
        // even when the control plane is unreachable. The returned completion keeps Matrix state
        // and receipts inside LC-20; a failed authority start therefore remains pending/failing.
        val driverCloseFailure = runCatching { closeWidgetDriver(widgetDriver) }.exceptionOrNull()
        runCatching {
            widgetDriver?.outgoingCallInvitation?.value?.let {
                activeCallManager.bindOutgoingInvitation(callData, io.element.android.libraries.matrix.api.core.EventId(it))
            }
        }.onFailure { Timber.w(it, "Call invitation checkpoint remains pending") }
        val releaseResult = runCatching { activeCallManager.prepareHangUpCall(callData) }
            .onFailure { failure ->
                Timber.e(failure, "Unable to release all local call resources")
            }
        val completion = releaseResult.getOrNull()
        if (NextDeploymentConfig.isNextDeployment) {
            val handedOff = runCatching {
                activeCallManager.finalizeReleasedCall(callData, driverCloseFailure == null && releaseResult.isSuccess,
                    noInvitationConfirmed = widgetDriver != null && widgetDriver.outgoingCallInvitation.value == null &&
                        callData.notificationEventId == null && driverCloseFailure == null)
            }.getOrElse {
                Timber.e(it, "Unable to commit durable call cleanup; no completion receipt will be sent")
                return
            }
            if (handedOff) return
        }
        if (completion == null && !NextDeploymentConfig.isNextDeployment) {
            driverCloseFailure?.let { Timber.e(it, "Unable to close the local call widget driver") }
            return
        }
        runCatching {
            // Cancellation can race the initial operation response before a native active call
            // exists. Reconcile the same idempotency key, then attest the genuine local teardown.
            if (completion == null) appOperationalStateCoordinator.start(callOperationRequest(operationId))
            val request = if (NextDeploymentConfig.isNextDeployment) {
                callOperationRequest("$operationId-hangup", trigger = "hangup")
            } else {
                callOperationRequest(operationId)
            }
            appOperationalStateCoordinator.perform(request) {
                // No active call is a valid early cancellation; failed local cleanup is not.
                releaseResult.exceptionOrNull()?.let { throw it }
                if (NextDeploymentConfig.isNextDeployment && protectionSession != null) {
                    // Start the authoritative hangup immediately, but do not try to encrypt a
                    // call record through a locked Vault. Keep this exact operation pending and
                    // finish its genuine Android receipts after policy-authorized recovery.
                    withTimeout(10.minutes) {
                        awaitProtectedCallFinalization(callProtectionService.observeDeviceAccess(protectionSession), completion ?: {})
                    }
                } else {
                    completion?.invoke()
                }
                // A driver close failure means local media teardown is incomplete. Propagate it
                // after the Matrix action so LC-20 cannot record a successful client receipt.
                driverCloseFailure?.let { throw it }
            }
        }.onFailure { failure ->
            Timber.e(failure, "Local call ended but authoritative LC-20 completion is pending")
        }
    }
}
