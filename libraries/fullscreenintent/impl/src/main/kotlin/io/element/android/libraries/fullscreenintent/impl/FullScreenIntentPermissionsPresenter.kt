/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.fullscreenintent.impl

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.fullscreenintent.api.FullScreenIntentPermissionsEvents
import io.element.android.libraries.fullscreenintent.api.FullScreenIntentPermissionsState
import io.element.android.libraries.fullscreenintent.api.MissingCallPermission
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import io.element.android.libraries.push.api.notifications.CallNotificationPolicyService
import io.element.android.services.toolbox.api.intent.ExternalIntentLauncher
import io.element.android.services.toolbox.api.sdk.BuildVersionSdkIntProvider
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@SingleIn(AppScope::class)
@Inject
class FullScreenIntentPermissionsPresenter(
    private val buildVersionSdkIntProvider: BuildVersionSdkIntProvider,
    private val externalIntentLauncher: ExternalIntentLauncher,
    private val buildMeta: BuildMeta,
    private val notificationManagerCompat: NotificationManagerCompat,
    private val callNotificationPolicyService: CallNotificationPolicyService,
    private val backgroundActivityPermissionService: BackgroundActivityPermissionService,
    preferencesDataStoreFactory: PreferenceDataStoreFactory,
) : Presenter<FullScreenIntentPermissionsState> {
    companion object {
        private const val PREF_KEY_FULL_SCREEN_INTENT_BANNER_DISMISSED = "PREF_KEY_FULL_SCREEN_INTENT_BANNER_DISMISSED"
        private const val PREF_KEY_BACKGROUND_ACTIVITY_BANNER_DISMISSED = "PREF_KEY_BACKGROUND_ACTIVITY_BANNER_DISMISSED"
        private const val PREF_KEY_DND_ACCESS_BANNER_DISMISSED = "PREF_KEY_DND_ACCESS_BANNER_DISMISSED"
    }

    private val dataStore = preferencesDataStoreFactory.create("full_screen_intent_permissions")

    private data class BannerDismissals(
        val fullScreenIntent: Boolean,
        val backgroundActivity: Boolean,
        val dndAccess: Boolean,
    )

    private val bannerDismissals = dataStore.data.map { prefs ->
        BannerDismissals(
            fullScreenIntent = prefs[booleanPreferencesKey(PREF_KEY_FULL_SCREEN_INTENT_BANNER_DISMISSED)] ?: false,
            backgroundActivity = prefs[booleanPreferencesKey(PREF_KEY_BACKGROUND_ACTIVITY_BANNER_DISMISSED)] ?: false,
            dndAccess = prefs[booleanPreferencesKey(PREF_KEY_DND_ACCESS_BANNER_DISMISSED)] ?: false,
        )
    }

    private suspend fun dismissFullScreenIntentBanner() {
        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey(PREF_KEY_FULL_SCREEN_INTENT_BANNER_DISMISSED)] = true
        }
    }

    private suspend fun dismissDndAccessBanner() {
        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey(PREF_KEY_DND_ACCESS_BANNER_DISMISSED)] = true
        }
    }

    private suspend fun dismissBackgroundActivityBanner() {
        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey(PREF_KEY_BACKGROUND_ACTIVITY_BANNER_DISMISSED)] = true
        }
    }

    @Composable
    override fun present(): FullScreenIntentPermissionsState {
        val coroutineScope = rememberCoroutineScope()
        var permissionRefresh by remember { mutableIntStateOf(0) }
        val isFullScreenIntentGranted = remember(permissionRefresh) { notificationManagerCompat.canUseFullScreenIntent() }
        val isBackgroundActivityGranted = remember(permissionRefresh) {
            backgroundActivityPermissionService.isPermissionGranted()
        }
        val isDndAccessGranted = remember(permissionRefresh) {
            callNotificationPolicyService.isDoNotDisturbAccessGranted()
        }
        val dismissals by bannerDismissals.collectAsState(
            initial = BannerDismissals(fullScreenIntent = true, backgroundActivity = true, dndAccess = true)
        )
        val missingPermission = when {
            !isFullScreenIntentGranted -> MissingCallPermission.FullScreenIntent
            !isBackgroundActivityGranted -> MissingCallPermission.BackgroundActivity
            !isDndAccessGranted -> MissingCallPermission.DoNotDisturbAccess
            else -> null
        }

        fun handleEvent(event: FullScreenIntentPermissionsEvents) {
            when (event) {
                FullScreenIntentPermissionsEvents.Dismiss -> coroutineScope.launch {
                    when (missingPermission) {
                        MissingCallPermission.FullScreenIntent -> dismissFullScreenIntentBanner()
                        MissingCallPermission.BackgroundActivity -> dismissBackgroundActivityBanner()
                        MissingCallPermission.DoNotDisturbAccess -> dismissDndAccessBanner()
                        null -> Unit
                    }
                }
                FullScreenIntentPermissionsEvents.OpenSettings -> when (missingPermission) {
                    MissingCallPermission.FullScreenIntent -> openFullScreenIntentSettings()
                    MissingCallPermission.BackgroundActivity -> openBackgroundActivitySettings()
                    MissingCallPermission.DoNotDisturbAccess -> openDoNotDisturbAccessSettings()
                    null -> Unit
                }
                FullScreenIntentPermissionsEvents.Refresh -> {
                    permissionRefresh++
                    if (callNotificationPolicyService.isDoNotDisturbAccessGranted()) {
                        callNotificationPolicyService.refreshRingingCallChannel()
                    }
                }
            }
        }

        return FullScreenIntentPermissionsState(
            permissionGranted = isFullScreenIntentGranted && isBackgroundActivityGranted,
            doNotDisturbAccessGranted = isDndAccessGranted,
            missingPermission = missingPermission,
            shouldDisplayBanner = when (missingPermission) {
                MissingCallPermission.FullScreenIntent -> !dismissals.fullScreenIntent
                MissingCallPermission.BackgroundActivity -> !dismissals.backgroundActivity
                MissingCallPermission.DoNotDisturbAccess -> !dismissals.dndAccess
                null -> false
            },
            eventSink = ::handleEvent,
            messageNotificationChannelId = callNotificationPolicyService.messageNotificationChannelId(),
            ringingNotificationChannelId = callNotificationPolicyService.ringingNotificationChannelId(),
        )
    }

    private fun openFullScreenIntentSettings() {
        if (buildVersionSdkIntProvider.isAtLeast(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    "package:${buildMeta.applicationId}".toUri()
                )
                externalIntentLauncher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, buildMeta.applicationId)
                externalIntentLauncher.launch(intent)
            }
        }
    }

    private fun openBackgroundActivitySettings() {
        try {
            externalIntentLauncher.launch(backgroundActivityPermissionService.createPermissionSettingsIntent())
        } catch (_: ActivityNotFoundException) {
            externalIntentLauncher.launch(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${buildMeta.applicationId}".toUri())
            )
        }
    }

    private fun openDoNotDisturbAccessSettings() {
        val settingsIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        try {
            externalIntentLauncher.launch(settingsIntent)
        } catch (_: ActivityNotFoundException) {
            externalIntentLauncher.launch(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, buildMeta.applicationId)
            )
        }
    }
}
