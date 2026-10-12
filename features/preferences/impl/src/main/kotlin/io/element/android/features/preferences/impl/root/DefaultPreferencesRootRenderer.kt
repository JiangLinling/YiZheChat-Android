/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.root

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.ContributesBinding
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.logout.api.direct.DirectLogoutEvents
import io.element.android.features.logout.api.direct.DirectLogoutView
import io.element.android.features.preferences.api.PreferencesRootDestination
import io.element.android.features.preferences.api.PreferencesRootRenderer
import io.element.android.libraries.androidutils.browser.openUrlInChromeCustomTab
import io.element.android.libraries.di.SessionScope

@ContributesBinding(SessionScope::class)
class DefaultPreferencesRootRenderer(
    private val presenter: PreferencesRootPresenter,
    private val directLogoutView: DirectLogoutView,
) : PreferencesRootRenderer {
    @Composable
    override fun Render(
        onNavigate: (PreferencesRootDestination) -> Unit,
        modifier: Modifier,
    ) {
        val state = presenter.present()
        val activity = requireNotNull(LocalActivity.current)
        val isDark = ElementTheme.isLightTheme.not()

        PreferencesRootView(
            state = state,
            modifier = modifier,
            onBackClick = {},
            showBackButton = false,
            showTopBar = false,
            applySystemBarsPadding = false,
            onAddAccountClick = { onNavigate(PreferencesRootDestination.AddAccount) },
            onMyQrCodeClick = { onNavigate(PreferencesRootDestination.MyQrCode) },
            onOpenRageShake = { onNavigate(PreferencesRootDestination.BugReport) },
            onOpenAnalytics = { onNavigate(PreferencesRootDestination.Analytics) },
            onOpenAbout = { onNavigate(PreferencesRootDestination.About) },
            onSecureBackupClick = { onNavigate(PreferencesRootDestination.SecureBackup) },
            onOpenDeveloperSettings = { onNavigate(PreferencesRootDestination.DeveloperSettings) },
            onOpenAdvancedSettings = { onNavigate(PreferencesRootDestination.AdvancedSettings) },
            onOpenLabs = { onNavigate(PreferencesRootDestination.Labs) },
            onLinkNewDeviceClick = { onNavigate(PreferencesRootDestination.LinkNewDevice) },
            onManageAccountClick = { url ->
                activity.openUrlInChromeCustomTab(null, darkTheme = isDark, url = url)
            },
            onOpenNotificationSettings = { onNavigate(PreferencesRootDestination.NotificationSettings) },
            onOpenChatSecurity = { onNavigate(PreferencesRootDestination.ChatSecurity) },
            onOpenLockScreenSettings = { onNavigate(PreferencesRootDestination.LockScreenSettings) },
            onOpenUserProfile = { onNavigate(PreferencesRootDestination.UserProfile(it)) },
            onOpenBlockedUsers = { onNavigate(PreferencesRootDestination.BlockedUsers) },
            onOpenMessageFavourites = { onNavigate(PreferencesRootDestination.MessageFavourites) },
            onSignOutClick = {
                if (state.directLogoutState.canDoDirectSignOut) {
                    state.directLogoutState.eventSink(DirectLogoutEvents.Logout(ignoreSdkError = false))
                } else {
                    onNavigate(PreferencesRootDestination.SignOut)
                }
            },
            onDeactivateClick = { onNavigate(PreferencesRootDestination.AccountDeactivation) },
        )

        directLogoutView.Render(state = state.directLogoutState)
    }
}
