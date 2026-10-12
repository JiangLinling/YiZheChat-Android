/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.logout.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.logout.api.LogoutEntryPoint
import io.element.android.features.logout.impl.tools.isBackingUp
import io.element.android.features.logout.impl.ui.LogoutActionDialog
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.designsystem.atomic.pages.FlowStepPage
import io.element.android.libraries.designsystem.components.BigIcon
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.LinearProgressIndicator
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.progressIndicatorTrackColor
import io.element.android.libraries.matrix.api.encryption.BackupState
import io.element.android.libraries.matrix.api.encryption.BackupUploadState
import io.element.android.libraries.matrix.api.encryption.RecoveryState
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.testtags.testTag
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun LogoutView(
    state: LogoutState,
    onRecoveryActionClick: (LogoutEntryPoint.RecoveryDestination) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val eventSink = state.eventSink

    FlowStepPage(
        onBackClick = onBackClick,
        title = title(state),
        subTitle = subtitle(state),
        iconStyle = BigIcon.Style.Default(CompoundIcons.KeySolid()),
        modifier = modifier,
        buttons = {
            Buttons(
                state = state,
                onRecoveryActionClick = onRecoveryActionClick,
                onLogoutClick = {
                    eventSink(LogoutEvents.Logout(ignoreSdkError = false))
                }
            )
        },
    ) {
        Content(state)
    }

    LogoutActionDialog(
        state.logoutAction,
        onConfirmClick = {
            eventSink(LogoutEvents.Logout(ignoreSdkError = false))
        },
        onForceLogoutClick = {
            eventSink(LogoutEvents.Logout(ignoreSdkError = true))
        },
        onDismissDialog = {
            eventSink(LogoutEvents.CloseDialogs)
        },
    )
    if (state.showUnbackedKeysWarning) {
        ConfirmationDialog(
            title = stringResource(R.string.screen_signout_unbacked_keys_title),
            content = stringResource(R.string.screen_signout_unbacked_keys_content),
            submitText = stringResource(R.string.screen_signout_unbacked_keys_submit),
            cancelText = stringResource(R.string.screen_signout_unbacked_keys_wait),
            destructiveSubmit = true,
            onSubmitClick = { eventSink(LogoutEvents.ConfirmLogoutWithoutBackup) },
            onDismiss = { eventSink(LogoutEvents.CloseDialogs) },
        )
    }
}

@Composable
private fun title(state: LogoutState): String {
    return when {
        state.backupUploadState.isBackingUp() -> stringResource(id = R.string.screen_signout_key_backup_ongoing_title)
        state.isLastDevice -> {
            if (state.recoveryState != RecoveryState.ENABLED) {
                stringResource(id = R.string.screen_signout_recovery_disabled_title)
            } else if (state.backupState == BackupState.UNKNOWN && state.doesBackupExistOnServer.not()) {
                stringResource(id = R.string.screen_signout_key_backup_disabled_title)
            } else {
                stringResource(id = R.string.screen_signout_save_recovery_key_title)
            }
        }
        else -> stringResource(CommonStrings.action_signout)
    }
}

@Composable
private fun subtitle(state: LogoutState): String? {
    return when {
        state.backupUploadState.problemOrNull() == BackupUploadProblem.CONNECTION ->
            stringResource(R.string.screen_signout_key_backup_offline_subtitle)
        state.backupUploadState.problemOrNull() == BackupUploadProblem.BACKUP_DISABLED ->
            stringResource(R.string.screen_signout_key_backup_disabled_state_subtitle)
        state.backupUploadState.problemOrNull() == BackupUploadProblem.STATUS_LAGGED ->
            stringResource(R.string.screen_signout_key_backup_lagged_subtitle)
        state.backupUploadState.problemOrNull() == BackupUploadProblem.UPLOAD_FAILED ->
            stringResource(R.string.screen_signout_key_backup_failed_subtitle)
        state.backupUploadState.isBackingUp() -> stringResource(id = R.string.screen_signout_key_backup_ongoing_subtitle)
        state.isLastDevice -> stringResource(id = R.string.screen_signout_key_backup_disabled_subtitle)
        else -> null
    }
}

@Composable
private fun ColumnScope.Buttons(
    state: LogoutState,
    onLogoutClick: () -> Unit,
    onRecoveryActionClick: (LogoutEntryPoint.RecoveryDestination) -> Unit,
) {
    val logoutAction = state.logoutAction
    val recoveryDestination = state.recoveryDestination()
    if (recoveryDestination != null) {
        val recoveryActionText = when (recoveryDestination) {
            LogoutEntryPoint.RecoveryDestination.SET_UP -> R.string.screen_signout_prepare_recovery_key_action
            LogoutEntryPoint.RecoveryDestination.CONFIRM -> R.string.screen_signout_confirm_recovery_key_action
            LogoutEntryPoint.RecoveryDestination.CHANGE -> R.string.screen_signout_change_recovery_key_action
        }
        val onRecoveryClick = { onRecoveryActionClick(recoveryDestination) }
        if (recoveryDestination == LogoutEntryPoint.RecoveryDestination.CHANGE) {
            OutlinedButton(
                text = stringResource(recoveryActionText),
                modifier = Modifier.fillMaxWidth(),
                onClick = onRecoveryClick,
            )
        } else {
            Button(
                text = stringResource(recoveryActionText),
                modifier = Modifier.fillMaxWidth(),
                onClick = onRecoveryClick,
            )
        }
    }
    val recoveryNeedsAttention = state.recoveryState != RecoveryState.ENABLED
    val signOutSubmitRes = when {
        logoutAction is AsyncAction.Loading -> R.string.screen_signout_in_progress_dialog_content
        state.backupUploadState.isBackingUp() || recoveryNeedsAttention -> CommonStrings.action_signout_anyway
        else -> CommonStrings.action_signout
    }
    if (recoveryNeedsAttention) {
        OutlinedButton(
            text = stringResource(id = signOutSubmitRes),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.signOut),
            showProgress = logoutAction is AsyncAction.Loading,
            destructive = true,
            onClick = onLogoutClick,
        )
    } else {
        Button(
            text = stringResource(id = signOutSubmitRes),
            showProgress = logoutAction is AsyncAction.Loading,
            destructive = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.signOut),
            onClick = onLogoutClick,
        )
    }
}

private fun LogoutState.recoveryDestination(): LogoutEntryPoint.RecoveryDestination? = when (recoveryState) {
    RecoveryState.DISABLED -> LogoutEntryPoint.RecoveryDestination.SET_UP
    RecoveryState.INCOMPLETE -> LogoutEntryPoint.RecoveryDestination.CONFIRM
    RecoveryState.ENABLED -> LogoutEntryPoint.RecoveryDestination.CHANGE
    RecoveryState.UNKNOWN,
    RecoveryState.WAITING_FOR_SYNC -> null
}

@Composable
private fun Content(
    state: LogoutState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 60.dp, start = 20.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.screen_signout_recovery_readiness_title),
            style = ElementTheme.typography.fontBodyMdMedium,
        )
        Text(
            text = recoveryReadinessSummary(state),
            style = ElementTheme.typography.fontBodySmRegular,
        )
        Text(
            text = stringResource(R.string.screen_signout_non_recoverable_notice),
            style = ElementTheme.typography.fontBodySmRegular,
        )
        when (state.backupUploadState) {
            is BackupUploadState.Uploading -> {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = { state.backupUploadState.backedUpCount.toFloat() / state.backupUploadState.totalCount.toFloat() },
                    trackColor = ElementTheme.colors.progressIndicatorTrackColor,
                )
                Text(
                    modifier = Modifier.align(Alignment.End),
                    text = "${state.backupUploadState.backedUpCount} / ${state.backupUploadState.totalCount}",
                    style = ElementTheme.typography.fontBodySmRegular,
                )
            }
            BackupUploadState.Waiting -> {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = ElementTheme.colors.progressIndicatorTrackColor,
                )
                if (state.waitingForALongTime) {
                    Text(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        text = stringResource(CommonStrings.common_please_check_internet_connection),
                        style = ElementTheme.typography.fontBodySmRegular,
                    )
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun recoveryReadinessSummary(state: LogoutState): String {
    val recovery = when (state.recoveryState) {
        RecoveryState.ENABLED -> stringResource(R.string.screen_signout_readiness_ready)
        RecoveryState.UNKNOWN,
        RecoveryState.WAITING_FOR_SYNC -> stringResource(R.string.screen_signout_readiness_checking)
        RecoveryState.DISABLED,
        RecoveryState.INCOMPLETE -> stringResource(R.string.screen_signout_readiness_attention)
    }
    val backupConfigured = state.backupState == BackupState.ENABLED || state.doesBackupExistOnServer
    val backup = if (backupConfigured && state.backupUploadState.isCompleteForDeviceRemoval()) {
        stringResource(R.string.screen_signout_readiness_ready)
    } else {
        stringResource(R.string.screen_signout_readiness_attention)
    }
    val oldDevice = if (state.isLastDevice) {
        stringResource(R.string.screen_signout_readiness_unavailable)
    } else {
        stringResource(R.string.screen_signout_readiness_available)
    }
    return stringResource(
        R.string.screen_signout_recovery_readiness_summary,
        recovery,
        backup,
        oldDevice,
    )
}

@PreviewsDayNight
@Composable
internal fun LogoutViewPreview(
    @PreviewParameter(LogoutStateProvider::class) state: LogoutState,
) = ElementPreview {
    LogoutView(
        state,
        onRecoveryActionClick = {},
        onBackClick = {},
    )
}
