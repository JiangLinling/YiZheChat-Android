/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.app.KeyguardManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Rational
import android.view.WindowManager
import android.webkit.PermissionRequest
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.IntentCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import dev.zacsweers.metro.Inject
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.features.call.api.CallData
import io.element.android.features.call.impl.DefaultElementCallEntryPoint
import io.element.android.features.call.impl.di.CallBindings
import io.element.android.features.call.impl.pip.PictureInPictureEvent
import io.element.android.features.call.impl.pip.PictureInPicturePresenter
import io.element.android.features.call.impl.pip.PictureInPictureState
import io.element.android.features.call.impl.pip.PipView
import io.element.android.features.call.impl.security.observeCallWindowSecurity
import io.element.android.features.call.impl.services.CallForegroundService
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.audio.api.AudioSessionRequest
import io.element.android.libraries.audio.api.AudioSessionRole
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.designsystem.utils.hasCompactHeightWindowSize
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicyRevisionProvider
import java.util.UUID
import timber.log.Timber

private val loggerTag = LoggerTag("ElementCallActivity")

class ElementCallActivity :
    AppCompatActivity(),
    CallScreenNavigator,
    PipView {
    @Inject lateinit var activeCallManager: io.element.android.features.call.impl.utils.ActiveCallManager
    @Inject lateinit var presenterFactory: CallScreenPresenter.Factory
    @Inject lateinit var appPreferencesStore: AppPreferencesStore
    @Inject lateinit var featureFlagService: FeatureFlagService
    @Inject lateinit var enterpriseService: EnterpriseService
    @Inject lateinit var pictureInPicturePresenter: PictureInPicturePresenter
    @Inject lateinit var buildMeta: BuildMeta
    @Inject lateinit var audioFocus: AudioFocus
    @Inject lateinit var dataProtectionPolicyProvider: DataProtectionPolicyProvider
    @Inject lateinit var dataProtectionPolicyRevisionProvider: DataProtectionPolicyRevisionProvider

    private lateinit var presenter: Presenter<CallScreenState>

    private var requestPermissionCallback: RequestPermissionCallback? = null

    private val requestPermissionsLauncher = registerPermissionResultLauncher()

    private val webViewTarget = mutableStateOf<CallData?>(null)

    private var eventSink: ((CallScreenEvent) -> Unit)? = null
    private var mediaCallActive = false
    private var wasTopResumed = false
    private var recentsReceiverRegistered = false
    private val recentsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS &&
                intent.getStringExtra("reason") == "recentapps" &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                requestActiveCallPip("system recents request")
            }
        }
    }
    private val audioOwnerId = "rtc-call:${UUID.randomUUID()}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bindings<CallBindings>().inject(this)
        observeCallWindowSecurity(dataProtectionPolicyProvider, dataProtectionPolicyRevisionProvider)
        if (io.element.android.appconfig.NextDeploymentConfig.isNextDeployment) {
            // Receive the protected system task-switch signal before the Activity is paused.
            // Never send CLOSE_SYSTEM_DIALOGS or request overlay/background-start privileges.
            ContextCompat.registerReceiver(this, recentsReceiver,
                IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), ContextCompat.RECEIVER_EXPORTED)
            recentsReceiverRegistered = true
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        setCallData(intent)
        // If presenter is not created at this point, it means we have no call to display, the Activity is finishing, so return early
        if (!::presenter.isInitialized) {
            return
        }

        pictureInPicturePresenter.setPipView(this)

        Timber.d("Created ElementCallActivity with call type: ${webViewTarget.value}")

        setContent {
            val pipState = pictureInPicturePresenter.present()
            ListenToAndroidEvents(pipState)
            val colors by remember(webViewTarget.value?.sessionId) {
                enterpriseService.semanticColorsFlow(sessionId = webViewTarget.value?.sessionId)
            }.collectAsState(SemanticColorsLightDark.default)

            // When the height is compact, hide the system bars by default to maximize the space for the call, using immersive mode
            val hasCompactHeight = hasCompactHeightWindowSize()
            DisposableEffect(hasCompactHeight, pipState.isInPictureInPicture) {
                if (hasCompactHeight && !pipState.isInPictureInPicture) {
                    val window = this@ElementCallActivity.window ?: return@DisposableEffect onDispose {}
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                    val systemBarInsets = WindowInsetsCompat.Type.systemBars()
                    insetsController.hide(systemBarInsets)

                    insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

                    onDispose {
                        insetsController.show(systemBarInsets)
                        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                    }
                } else {
                    onDispose {}
                }
            }

            CallTheme(
                appPreferencesStore = appPreferencesStore,
                featureFlagService = featureFlagService,
                compoundLight = colors.light,
                compoundDark = colors.dark,
                buildMeta = buildMeta,
            ) {
                val state = presenter.present()
                eventSink = state.eventSink
                mediaCallActive = state.isCallActive && !state.isTerminating
                LaunchedEffect(state.isCallActive, state.isTerminating) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setPipParams()
                    if (state.isCallActive) {
                        setCallIsActive()
                    }
                }
                CallScreenView(
                    state = state,
                    pipState = pipState,
                    onConsoleMessage = {
                        // Never persist Element Call console payloads: they can contain SDP, ICE, tokens or user content.
                    },
                    requestPermissions = { permissions, callback ->
                        Timber.tag("RTCSetup").d("phase=permissions_requested video=%s", Manifest.permission.CAMERA in permissions)
                        requestPermissionCallback = callback
                        requestPermissionsLauncher.launch(permissions)
                    }
                )
            }
        }
    }

    private fun setCallIsActive() {
        val acquired = audioFocus.acquire(
            AudioSessionRequest(
                ownerId = audioOwnerId,
                role = AudioSessionRole.RtcCall,
                operationId = audioOwnerId,
                onOwnershipLost = {
                // If the audio focus is lost, we do not stop the call.
                Timber.tag(loggerTag.value).w("Audio focus lost")
                },
            )
        )
        if (!acquired) Timber.tag(loggerTag.value).w("RTC audio ownership was denied")
        CallForegroundService.start(this, includeCamera = webViewTarget.value?.isAudioCall == false)
    }

    @Composable
    private fun ListenToAndroidEvents(pipState: PictureInPictureState) {
        val pipEventSink by rememberUpdatedState(pipState.eventSink)
        DisposableEffect(Unit) {
            val listener = Runnable {
                if (requestPermissionCallback != null) {
                    Timber.tag(loggerTag.value).w("Ignoring onUserLeaveHint event because user is asked to grant permissions")
                } else {
                    if (io.element.android.appconfig.NextDeploymentConfig.isNextDeployment && mediaCallActive) {
                        // The SDK event below is asynchronous. Native PiP must be
                        // requested in the leave callback itself, before Recents
                        // changes the platform eligibility of this Activity.
                        Timber.tag(loggerTag.value).d("Active call user leave hint PiP observed")
                        requestActiveCallPip("user leave hint")
                    }
                    pipEventSink(PictureInPictureEvent.EnterPictureInPicture)
                }
            }
            addOnUserLeaveHintListener(listener)
            onDispose {
                removeOnUserLeaveHintListener(listener)
            }
        }
        DisposableEffect(Unit) {
            val onPictureInPictureModeChangedListener = Consumer { _: PictureInPictureModeChangedInfo ->
                pipEventSink(PictureInPictureEvent.OnPictureInPictureModeChanged(isInPictureInPictureMode))
                if (!io.element.android.appconfig.NextDeploymentConfig.isNextDeployment &&
                    !isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    Timber.tag(loggerTag.value).d("Exiting PiP mode: Hangup the call")
                    eventSink?.invoke(CallScreenEvent.Hangup)
                }
            }
            addOnPictureInPictureModeChangedListener(onPictureInPictureModeChangedListener)
            onDispose {
                removeOnPictureInPictureModeChangedListener(onPictureInPictureModeChangedListener)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setCallData(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::presenter.isInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setPipParams()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        val leavingTop = wasTopResumed && !isTopResumedActivity
        wasTopResumed = isTopResumedActivity
        super.onTopResumedActivityChanged(isTopResumedActivity)
        // Request PiP while this Activity is still RESUMED, before an OEM Recents
        // transition pauses camera capture. Permission dialogs, real device locks
        // and revoked call authorization continue through their existing guards.
        if (leavingTop && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            requestActiveCallPip("top-resumed handoff")
        }
    }

    override fun onPause() {
        // Some OEM Recents transitions skip onUserLeaveHint/automatic PiP. Keep the
        // existing authorized media surface visible before the process stops; this
        // does not unlock the Vault or override device/policy revocation.
        requestActiveCallPip("pause")
        super.onPause()
    }

    private fun requestActiveCallPip(trigger: String) {
        if (io.element.android.appconfig.NextDeploymentConfig.isNextDeployment &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mediaCallActive &&
            requestPermissionCallback == null && !isFinishing && !isChangingConfigurations &&
            !isInPictureInPictureMode &&
            getSystemService(PowerManager::class.java)?.isInteractive == true &&
            getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false
        ) {
            // Android 16 refuses an explicit request while PAUSING if its params
            // also ask the system to auto-enter. Recents is not a Home transition,
            // so that automatic transition may never run. This fallback is manual.
            runCatching { enterPictureInPictureMode(getPictureInPictureParams(manualEntry = true)) }
                .onSuccess { Timber.tag(loggerTag.value).d("Active call $trigger PiP entry result: $it") }
                .onFailure { Timber.tag(loggerTag.value).w("Active call $trigger PiP entry unavailable") }
        }
    }

    override fun onDestroy() {
        if (recentsReceiverRegistered) {
            unregisterReceiver(recentsReceiver)
            recentsReceiverRegistered = false
        }
        super.onDestroy()
        audioFocus.release(audioOwnerId)
        CallForegroundService.stop(this)
        pictureInPicturePresenter.setPipView(null)
    }

    override fun finish() {
        // Also remove the task from recents
        finishAndRemoveTask()
    }

    override fun close() {
        finish()
    }

    private fun setCallData(intent: Intent?) {
        val callData = intent?.let {
            IntentCompat.getParcelableExtra(intent, DefaultElementCallEntryPoint.EXTRA_CALL_TYPE, CallData::class.java)
        }
        val currentCallData = webViewTarget.value
        if (io.element.android.appconfig.NextDeploymentConfig.isNextDeployment && callData != null &&
            !io.element.android.features.call.impl.utils.IntentProvider.canOpenInvitation(callData, activeCallManager.activeCall.value?.callData)) {
            // A stale notification must not recreate this Activity and tear down a newer call.
            if (currentCallData == null) finish()
            return
        }
        if (currentCallData == null) {
            if (callData == null) {
                Timber.tag(loggerTag.value).d("Re-opened the activity but we have no url to load or a cached one, finish the activity")
                finish()
            } else {
                Timber.tag(loggerTag.value).d("Set the call type and create the presenter")
                webViewTarget.value = callData
                presenter = presenterFactory.create(callData, this)
            }
        } else {
            if (callData == null) {
                Timber.tag(loggerTag.value).d("Coming back from notification, do nothing")
            } else if (callData != currentCallData) {
                Timber.tag(loggerTag.value).d("User starts another call, restart the Activity")
                setIntent(intent)
                recreate()
            } else {
                // Starting the same call again, should not happen, the UI is preventing this. But maybe when using external links.
                Timber.tag(loggerTag.value).d("Starting the same call again, do nothing")
            }
        }
    }

    private fun registerPermissionResultLauncher(): ActivityResultLauncher<Array<String>> {
        return registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            Timber.tag("RTCSetup").d("phase=permissions_result")
            val callback = requestPermissionCallback ?: return@registerForActivityResult
            val permissionsToGrant = mutableListOf<String>()
            permissions.forEach { (permission, granted) ->
                if (granted) {
                    val webKitPermission = when (permission) {
                        Manifest.permission.CAMERA -> PermissionRequest.RESOURCE_VIDEO_CAPTURE
                        Manifest.permission.RECORD_AUDIO -> PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        else -> return@forEach
                    }
                    permissionsToGrant.add(webKitPermission)
                }
            }
            // Android requires an active camera foreground-service type before WebView starts
            // capturing. Promote the already running call service first; granting WebKit before
            // this update can make the first camera session open and immediately close.
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in permissionsToGrant) {
                CallForegroundService.start(this, includeCamera = true) { cameraServiceReady ->
                    Timber.tag("RTCSetup").d("phase=camera_service_ready success=%s", cameraServiceReady)
                    val resources = if (cameraServiceReady) {
                        permissionsToGrant
                    } else {
                        permissionsToGrant.filterNot { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                    }
                    callback(resources.toTypedArray())
                    requestPermissionCallback = null
                }
            } else {
                callback(permissionsToGrant.toTypedArray())
                requestPermissionCallback = null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun setPipParams() {
        setPictureInPictureParams(getPictureInPictureParams())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun enterPipMode(): Boolean {
        return if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            enterPictureInPictureMode(getPictureInPictureParams(manualEntry = true))
        } else {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getPictureInPictureParams(manualEntry: Boolean = false): PictureInPictureParams {
        return PictureInPictureParams.Builder()
            // Portrait for calls seems more appropriate
            .setAspectRatio(Rational(3, 5))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(
                        !io.element.android.appconfig.NextDeploymentConfig.isNextDeployment || (!manualEntry && mediaCallActive)
                    )
                }
            }
            .build()
    }

    override fun hangUp() {
        eventSink?.invoke(CallScreenEvent.Hangup)
    }
}

internal fun mapWebkitPermissions(permissions: Array<String>): List<String> {
    return permissions.mapNotNull { permission ->
        when (permission) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
            else -> null
        }
    }
}
