/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.root

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.preferences.impl.R
import io.element.android.features.preferences.impl.components.SettingsNavigationRow
import io.element.android.features.preferences.impl.user.UserPreferences
import io.element.android.libraries.architecture.coverage.ExcludeFromCoverage
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.components.preferences.PreferencePage
import io.element.android.libraries.designsystem.preview.ElementPreviewDark
import io.element.android.libraries.designsystem.preview.ElementPreviewLight
import io.element.android.libraries.designsystem.preview.PreviewWithLargeHeight
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.ListItemStyle
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.CommonDrawables
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarHost
import io.element.android.libraries.designsystem.utils.snackbar.rememberSnackbarHostState
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.ui.components.MatrixUserRow
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun PreferencesRootView(
    state: PreferencesRootState,
    onBackClick: () -> Unit,
    showBackButton: Boolean = true,
    showTopBar: Boolean = true,
    applySystemBarsPadding: Boolean = true,
    onAddAccountClick: () -> Unit,
    onSecureBackupClick: () -> Unit,
    onManageAccountClick: (url: String) -> Unit,
    onLinkNewDeviceClick: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenRageShake: () -> Unit,
    onOpenLockScreenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenLabs: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenChatSecurity: () -> Unit,
    onOpenUserProfile: (MatrixUser) -> Unit,
    onOpenBlockedUsers: () -> Unit,
    onOpenMessageFavourites: () -> Unit,
    onSignOutClick: () -> Unit,
    onDeactivateClick: () -> Unit,
    modifier: Modifier = Modifier,
    onMyQrCodeClick: () -> Unit = {},
) {
    val snackbarHostState = rememberSnackbarHostState(snackbarMessage = state.snackbarMessage)
    var showAccountSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showAccountSettings) { showAccountSettings = false }
    if (showAccountSettings) {
        PreferencePage(
            modifier = modifier,
            onBackClick = { showAccountSettings = false },
            applySystemBarsPadding = applySystemBarsPadding,
            title = stringResource(R.string.screen_settings_accounts_devices),
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) {
            if (state.isMultiAccountEnabled) {
                MultiAccountSection(state = state, onAddAccountClick = onAddAccountClick)
            }
            ManageAccountSection(
                state = state,
                onManageAccountClick = onManageAccountClick,
                onLinkNewDeviceClick = onLinkNewDeviceClick,
                onOpenBlockedUsers = onOpenBlockedUsers,
            )
        }
        return
    }

    // Include pref from other modules
    PreferencePage(
        modifier = modifier,
        onBackClick = onBackClick,
        showBackButton = showBackButton,
        showTopBar = showTopBar,
        applySystemBarsPadding = applySystemBarsPadding,
        title = stringResource(id = CommonStrings.common_settings),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) {
        UserPreferences(
            modifier = Modifier.clickable {
                onOpenUserProfile(state.myUser)
            },
            matrixUser = state.myUser,
            onMyQrCodeClick = onMyQrCodeClick,
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(id = R.string.screen_message_favourites_title)) },
            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.FavouriteSolid())),
            trailingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChevronRight())),
            onClick = onOpenMessageFavourites,
        )
        if (state.isMultiAccountEnabled || state.accountManagementUrl != null || state.showLinkNewDevice || state.showBlockedUsersItem) {
            SettingsNavigationRow(
                title = stringResource(R.string.screen_settings_accounts_devices),
                icon = CompoundIcons.Devices(),
                onClick = { showAccountSettings = true },
            )
        }
        SecuritySection(
            onOpenChatSecurity = onOpenChatSecurity,
        )
        GeneralSection(
            onOpenNotificationSettings = onOpenNotificationSettings,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            canReportBug = state.canReportBug,
            onOpenRageShake = onOpenRageShake,
        )
        HorizontalDivider()
        AboutSection(
            state = state,
            onSignOutClick = onSignOutClick,
            onDeactivateClick = onDeactivateClick,
        )
        // Version
        Footer(
            version = state.version,
            showUpdateDot = state.availableUpdate != null,
            onClick = onOpenAbout,
        )
    }
}

@Composable
private fun ColumnScope.MultiAccountSection(
    state: PreferencesRootState,
    onAddAccountClick: () -> Unit,
) {
    HorizontalDivider(
        thickness = 8.dp,
        color = ElementTheme.colors.bgSubtleSecondary,
    )
    state.otherSessions.forEach { matrixUser ->
        MatrixUserRow(
            modifier = Modifier
                .clickable {
                    state.eventSink(PreferencesRootEvent.SwitchToSession(matrixUser.userId))
                }
                .padding(top = 2.dp, bottom = 2.dp, end = 8.dp),
            matrixUser = matrixUser,
            avatarSize = AvatarSize.AccountItem,
            verticalSpaceWidth = 16.dp,
        )
    }
    ListItem(
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Plus())),
        headlineContent = {
            Text(stringResource(CommonStrings.common_add_another_account))
        },
        onClick = onAddAccountClick,
    )
    HorizontalDivider(
        thickness = 8.dp,
        color = ElementTheme.colors.bgSubtleSecondary,
    )
}

@Composable
private fun ColumnScope.SecuritySection(
    onOpenChatSecurity: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(id = R.string.screen_chat_security_title)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Shield())),
        trailingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChevronRight())),
        onClick = onOpenChatSecurity,
    )
}

@Composable
private fun ColumnScope.ManageAccountSection(
    state: PreferencesRootState,
    onManageAccountClick: (url: String) -> Unit,
    onLinkNewDeviceClick: () -> Unit,
    onOpenBlockedUsers: () -> Unit,
) {
    state.accountManagementUrl?.let { url ->
        ListItem(
            headlineContent = { Text(stringResource(id = CommonStrings.action_manage_account_and_devices)) },
            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.UserProfile())),
            trailingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.PopOut())),
            onClick = { onManageAccountClick(url) },
        )
    }
    if (state.showLinkNewDevice) {
        ListItem(
            headlineContent = { Text(stringResource(id = CommonStrings.common_link_new_device)) },
            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Devices())),
            onClick = onLinkNewDeviceClick,
        )
    }
    if (state.showBlockedUsersItem) {
        ListItem(
            headlineContent = { Text(stringResource(id = CommonStrings.common_blocked_users)) },
            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Block())),
            onClick = onOpenBlockedUsers,
            trailingContent = ListItemContent.Text(state.nbOfBlockedUsers.toString()),
        )
    }
}

@Composable
private fun ColumnScope.GeneralSection(
    onOpenNotificationSettings: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    canReportBug: Boolean,
    onOpenRageShake: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(id = R.string.screen_notification_settings_title)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Notifications())),
        trailingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChevronRight())),
        onClick = onOpenNotificationSettings,
    )
    ListItem(
        headlineContent = { Text(stringResource(id = CommonStrings.common_advanced_settings)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Settings())),
        trailingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChevronRight())),
        onClick = onOpenAdvancedSettings,
    )
    if (canReportBug) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.screen_preferences_problem_feedback)) },
            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChatProblem())),
            trailingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChevronRight())),
            onClick = onOpenRageShake,
        )
    }
}

@Composable
private fun ColumnScope.AboutSection(
    state: PreferencesRootState,
    onSignOutClick: () -> Unit,
    onDeactivateClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(id = R.string.screen_preferences_sign_out)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Close())),
        style = ListItemStyle.Destructive,
        onClick = onSignOutClick,
    )
    if (state.canDeactivateAccount) {
        ListItem(
            headlineContent = { Text(stringResource(id = CommonStrings.action_delete_account)) },
            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Delete())),
            style = ListItemStyle.Destructive,
            onClick = onDeactivateClick,
        )
    }
}

@Composable
private fun ColumnScope.Footer(
    version: String,
    showUpdateDot: Boolean,
    onClick: () -> Unit,
) {
    val updateAvailableDescription = stringResource(R.string.screen_about_update_available_badge)
    Row(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .testTag(PREFERENCES_UPDATE_FOOTER_TEST_TAG)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            textAlign = TextAlign.Center,
            text = version,
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textSecondary,
        )
        if (showUpdateDot) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .semantics {
                        contentDescription = updateAvailableDescription
                    }
                    .background(ElementTheme.colors.iconCriticalPrimary, CircleShape)
            )
        }
    }
}

internal const val PREFERENCES_UPDATE_FOOTER_TEST_TAG = "preferences_update_footer"

@PreviewWithLargeHeight
@Composable
internal fun PreferencesRootViewLightPreview(@PreviewParameter(PreferencesRootStateProvider::class) state: PreferencesRootState) =
    ElementPreviewLight(
        drawableFallbackForImages = CommonDrawables.sample_avatar,
    ) { ContentToPreview(state) }

@PreviewWithLargeHeight
@Composable
internal fun PreferencesRootViewDarkPreview(@PreviewParameter(PreferencesRootStateProvider::class) state: PreferencesRootState) =
    ElementPreviewDark(
        drawableFallbackForImages = CommonDrawables.sample_avatar,
    ) { ContentToPreview(state) }

@ExcludeFromCoverage
@Composable
private fun ContentToPreview(state: PreferencesRootState) {
    PreferencesRootView(
        state = state,
        onBackClick = {},
        onAddAccountClick = {},
        onOpenAnalytics = {},
        onOpenRageShake = {},
        onOpenDeveloperSettings = {},
        onOpenAdvancedSettings = {},
        onOpenLabs = {},
        onOpenAbout = {},
        onSecureBackupClick = {},
        onManageAccountClick = {},
        onLinkNewDeviceClick = {},
        onOpenNotificationSettings = {},
        onOpenChatSecurity = {},
        onOpenLockScreenSettings = {},
        onOpenUserProfile = {},
        onOpenBlockedUsers = {},
        onOpenMessageFavourites = {},
        onSignOutClick = {},
        onDeactivateClick = {},
    )
}
