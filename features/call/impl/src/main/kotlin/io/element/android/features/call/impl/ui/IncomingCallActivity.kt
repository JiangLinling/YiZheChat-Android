/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.zacsweers.metro.Inject
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.features.call.api.CallData
import io.element.android.features.call.api.ElementCallEntryPoint
import io.element.android.features.call.impl.di.CallBindings
import io.element.android.features.call.impl.notifications.CallNotificationData
import io.element.android.features.call.impl.security.observeCallWindowSecurity
import io.element.android.features.call.impl.utils.ActiveCallManager
import io.element.android.features.call.impl.utils.CallState
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicyRevisionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Activity that's displayed as a full screen intent when an incoming call is received.
 */
class IncomingCallActivity : AppCompatActivity() {
    companion object {
        /**
         * Extra key for the notification data.
         */
        const val EXTRA_NOTIFICATION_DATA = "EXTRA_NOTIFICATION_DATA"
    }

    @Inject
    lateinit var elementCallEntryPoint: ElementCallEntryPoint

    @Inject
    lateinit var activeCallManager: ActiveCallManager

    @Inject
    lateinit var appPreferencesStore: AppPreferencesStore

    @Inject
    lateinit var featureFlagService: FeatureFlagService

    @Inject
    lateinit var enterpriseService: EnterpriseService

    @Inject
    lateinit var buildMeta: BuildMeta

    @Inject
    lateinit var dataProtectionPolicyProvider: DataProtectionPolicyProvider

    @Inject
    lateinit var dataProtectionPolicyRevisionProvider: DataProtectionPolicyRevisionProvider

    @AppCoroutineScope
    @Inject lateinit var appCoroutineScope: CoroutineScope

    @Inject
    lateinit var previewAuthorization: io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
    private var shownInvitation: CallNotificationData? = null
    private var answeringInvitation: String? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val replacement = IntentCompat.getParcelableExtra(intent, EXTRA_NOTIFICATION_DATA, CallNotificationData::class.java)
        val current = (activeCallManager.activeCall.value?.callState as? CallState.Ringing)?.notificationData
        if (replacement?.eventId == current?.eventId && replacement?.sessionId == current?.sessionId) setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("Incoming call full-screen activity created")

        bindings<CallBindings>().inject(this)
        observeCallWindowSecurity(dataProtectionPolicyProvider, dataProtectionPolicyRevisionProvider)
        if (io.element.android.appconfig.NextDeploymentConfig.isNextDeployment) {
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }

        // Manifest attributes let SystemUI launch us over the keyguard before onCreate runs.
        // Keep the runtime calls for devices/OEMs that do not fully honour those attributes.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val notificationData = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_NOTIFICATION_DATA, CallNotificationData::class.java) }
        shownInvitation = notificationData
        if (notificationData != null) {
            setContent {
                val colors by remember {
                    enterpriseService.semanticColorsFlow(sessionId = notificationData.sessionId)
                }.collectAsState(SemanticColorsLightDark.default)
                CallTheme(
                    appPreferencesStore = appPreferencesStore,
                    featureFlagService = featureFlagService,
                    compoundLight = colors.light,
                    compoundDark = colors.dark,
                    buildMeta = buildMeta,
                ) {
                    val active by activeCallManager.activeCall.collectAsState()
                    val current = if (io.element.android.appconfig.NextDeploymentConfig.isNextDeployment)
                        (active?.callState as? CallState.Ringing)?.notificationData ?: notificationData
                    else notificationData
                    val access by previewAuthorization.accessState.collectAsState()
                    val display = if (access.allowsPreviewAttempt) current else current.copy(
                        senderName = getString(io.element.android.features.call.impl.R.string.call_private_caller_name),
                        avatarUrl = null, roomName = null,
                    )
                    IncomingCallScreen(
                        notificationData = display,
                        onAnswer = { onAnswer(current) },
                        onCancel = { onCancel(current) },
                    )
                }
            }
        } else {
            // No data, finish the activity
            finish()
            return
        }

        activeCallManager.activeCall
            .filter {
                val data = (it?.callState as? CallState.Ringing)?.notificationData
                data == null || (!io.element.android.appconfig.NextDeploymentConfig.isNextDeployment &&
                    (data.eventId != notificationData.eventId || data.sessionId != notificationData.sessionId))
            }
            .onEach { finish() }
            .launchIn(lifecycleScope)
    }

    private fun onAnswer(notificationData: CallNotificationData) {
        val current = (activeCallManager.activeCall.value?.callState as? CallState.Ringing)?.notificationData
        if (current?.eventId != notificationData.eventId || current.sessionId != notificationData.sessionId ||
            answeringInvitation == notificationData.eventId.value) return
        answeringInvitation = notificationData.eventId.value
        elementCallEntryPoint.startCall(
            CallData(
                sessionId = notificationData.sessionId,
                roomId = notificationData.roomId,
                isAudioCall = notificationData.audioOnly,
                notificationEventId = notificationData.eventId.value,
            )
        )
    }

    private fun onCancel(data: CallNotificationData) {
        appCoroutineScope.launch {
            activeCallManager.hangUpCall(callData = CallData(
                data.sessionId, data.roomId, data.audioOnly, data.eventId.value, data.eventId.value,
            ), notificationData = data)
        }
    }
}
