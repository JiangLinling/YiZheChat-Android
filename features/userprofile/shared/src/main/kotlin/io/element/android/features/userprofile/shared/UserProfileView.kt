/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.userprofile.shared

import io.element.android.libraries.matrix.ui.components.contactFailureMessageResource

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.startchat.api.ConfirmingStartDmWithMatrixUser
import io.element.android.features.userprofile.api.UserProfileEvents
import io.element.android.features.userprofile.api.FriendPrivateProfile
import io.element.android.features.userprofile.api.UserProfileState
import io.element.android.features.userprofile.api.UserProfileVerificationState
import io.element.android.features.userprofile.shared.blockuser.BlockUserDialogs
import io.element.android.features.userprofile.shared.blockuser.BlockUserSection
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.designsystem.components.async.AsyncActionView
import io.element.android.libraries.designsystem.components.async.AsyncActionViewDefaults
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarHost
import io.element.android.libraries.designsystem.utils.snackbar.rememberSnackbarHostState
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.ui.components.CreateDmConfirmationBottomSheet
import io.element.android.libraries.ui.strings.CommonStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileView(
    state: UserProfileState,
    onShareUser: () -> Unit,
    onOpenDm: (RoomId) -> Unit,
    onStartCall: (RoomId, CallIntent) -> Unit,
    goBack: () -> Unit,
    openAvatarPreview: (username: String, url: String) -> Unit,
    onVerifyClick: (UserId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showingOptions by remember { mutableStateOf(false) }
    val snackbarHostState = rememberSnackbarHostState(snackbarMessage = state.snackbarMessage)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_room_member_details_title)) },
                navigationIcon = { BackButton(onClick = goBack) },
                actions = {
                    if (!state.isCurrentUser) {
                        IconButton(onClick = { showingOptions = true }) {
                            Icon(CompoundIcons.OverflowHorizontal(), contentDescription = stringResource(R.string.screen_user_profile_more))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState())
        ) {
            UserProfileHeaderSection(
                compact = !state.isCurrentUser,
                remarkName = state.privateProfile.remarkName.takeUnless { state.isCurrentUser },
                avatarUrl = state.avatarUrl,
                userId = state.userId,
                userName = state.userName,
                verificationState = state.verificationState,
                openAvatarPreview = { avatarUrl ->
                    openAvatarPreview(state.userName ?: state.userId.value, avatarUrl)
                },
                onUserIdClick = {
                    state.eventSink(UserProfileEvents.CopyToClipboard(state.userId.value))
                },
                withdrawVerificationClick = { state.eventSink(UserProfileEvents.WithdrawVerification) },
            )
            if (!state.isCurrentUser) {
                PrivateProfileSection(state)
                AccountInfoSection(state)
                VerifyUserSection(state, onVerifyClick = { onVerifyClick(state.userId) })
                Spacer(Modifier.height(12.dp))
            }
            UserProfileMainActionsSection(
                isCurrentUser = state.isCurrentUser,
                hasDm = state.dmRoomId != null,
                canCall = state.canCall,
                isBlocked = state.isBlocked.dataOrNull() == true,
                onShareUser = onShareUser,
                onStartDM = { state.eventSink(UserProfileEvents.StartDM) },
                onCall = { intent -> state.dmRoomId?.let { onStartCall(it, intent) } }
            )
            Spacer(modifier = Modifier.height(26.dp))
            if (!state.isCurrentUser) {
                BlockUserDialogs(state)
                if (showingOptions) {
                    ModalBottomSheet(
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        onDismissRequest = { showingOptions = false },
                        scrollable = false,
                    ) {
                        ListItem(
                            headlineContent = { Text(stringResource(CommonStrings.action_share)) },
                            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ShareAndroid())),
                            onClick = { showingOptions = false; onShareUser() },
                        )
                        if (state.canRemoveFriend) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.screen_user_profile_remove_friend)) },
                                onClick = {
                                    showingOptions = false
                                    state.eventSink(UserProfileEvents.RemoveFriend())
                                },
                            )
                        }
                        BlockUserSection(state)
                    }
                }
            }
            AsyncActionView(
                async = state.removeFriendAction,
                progressDialog = {
                    AsyncActionViewDefaults.ProgressDialog(stringResource(R.string.screen_user_profile_removing_friend))
                },
                onSuccess = {},
                errorMessage = {
                    stringResource(R.string.screen_user_profile_remove_friend_error) + state.removeFriendDiagnostic?.let {
                        "\n" + stringResource(R.string.screen_user_profile_remove_friend_diagnostic, it)
                    }.orEmpty()
                },
                onRetry = { state.eventSink(UserProfileEvents.RemoveFriend(needsConfirmation = false)) },
                onErrorDismiss = { state.eventSink(UserProfileEvents.ClearRemoveFriendState) },
                confirmationDialog = {
                    ConfirmationDialog(
                        title = stringResource(R.string.screen_user_profile_remove_friend),
                        content = stringResource(R.string.screen_user_profile_remove_friend_confirmation, state.userName ?: state.userId.value),
                        submitText = stringResource(R.string.screen_user_profile_remove_friend),
                        onSubmitClick = { state.eventSink(UserProfileEvents.RemoveFriend(needsConfirmation = false)) },
                        onDismiss = { state.eventSink(UserProfileEvents.ClearRemoveFriendState) },
                    )
                },
            )
            if (state.removeFriendAction is AsyncAction.Success) {
                ConfirmationDialog(
                    title = stringResource(R.string.screen_user_profile_remove_friend_done),
                    content = stringResource(R.string.screen_user_profile_remove_friend_sync),
                    onSubmitClick = {
                        state.eventSink(UserProfileEvents.ClearRemoveFriendState)
                        goBack()
                    },
                    onDismiss = { state.eventSink(UserProfileEvents.ClearRemoveFriendState) },
                )
            }
            AsyncActionView(
                async = state.startDmActionState,
                progressDialog = {
                    AsyncActionViewDefaults.ProgressDialog(
                        progressText = stringResource(CommonStrings.common_starting_chat),
                    )
                },
                onSuccess = onOpenDm,
                errorMessage = { stringResource(it.contactFailureMessageResource() ?: R.string.screen_start_chat_error_starting_chat) },
                onRetry = { state.eventSink(UserProfileEvents.StartDM) },
                onErrorDismiss = { state.eventSink(UserProfileEvents.ClearStartDMState) },
                confirmationDialog = { data ->
                    if (data is ConfirmingStartDmWithMatrixUser) {
                        CreateDmConfirmationBottomSheet(
                            matrixUser = data.matrixUser,
                            isUserIdentityUnknown = data.isUserIdentityUnknown,
                            onSendInvite = {
                                state.eventSink(UserProfileEvents.StartDM)
                            },
                            onDismiss = {
                                state.eventSink(UserProfileEvents.ClearStartDMState)
                            },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun PrivateProfileSection(state: UserProfileState) {
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(state.savePrivateProfileAction) {
        if (state.savePrivateProfileAction is AsyncAction.Success) {
            editing = false
            state.eventSink(UserProfileEvents.ClearSavePrivateProfileState)
        }
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.screen_user_profile_private_details)) },
        supportingContent = {
            val summary = listOf(
                state.privateProfile.remarkName,
                state.privateProfile.phone,
                state.privateProfile.tags,
                state.privateProfile.description,
            )
                .firstOrNull { it.isNotBlank() }
            summary?.let { Text(it, maxLines = 1) }
        },
        trailingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChevronRight())),
        onClick = { editing = true },
    )
    if (editing) {
        PrivateProfileDialog(
            initial = state.privateProfile,
            saving = state.savePrivateProfileAction is AsyncAction.Loading,
            onDismiss = {
                if (state.savePrivateProfileAction !is AsyncAction.Loading) {
                    editing = false
                    state.eventSink(UserProfileEvents.ClearSavePrivateProfileState)
                }
            },
            onSave = {
                state.eventSink(UserProfileEvents.SavePrivateProfile(it))
            },
        )
        AsyncActionView(
            async = state.savePrivateProfileAction,
            onSuccess = {},
            errorMessage = { stringResource(R.string.screen_user_profile_save_details_error) },
            onErrorDismiss = { state.eventSink(UserProfileEvents.ClearSavePrivateProfileState) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountInfoSection(state: UserProfileState) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.screen_user_profile_account_info)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.UserProfile())),
        trailingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChevronRight())),
        onClick = { expanded = true },
    )
    if (expanded) {
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismissRequest = { expanded = false },
            scrollable = false,
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.screen_user_profile_account_info)) },
                supportingContent = { Text(state.userId.value) },
                trailingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Copy())),
                onClick = { state.eventSink(UserProfileEvents.CopyToClipboard(state.userId.value)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivateProfileDialog(
    initial: FriendPrivateProfile,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (FriendPrivateProfile) -> Unit,
) {
    var remark by remember(initial) { mutableStateOf(initial.remarkName) }
    var phone by remember(initial) { mutableStateOf(initial.phone) }
    var tags by remember(initial) { mutableStateOf(initial.tags) }
    var description by remember(initial) { mutableStateOf(initial.description) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
        scrollable = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                TextButton(
                    text = stringResource(CommonStrings.action_cancel),
                    onClick = onDismiss,
                    enabled = !saving,
                )
                Text(
                    text = stringResource(R.string.screen_user_profile_edit_private_details),
                    modifier = Modifier.weight(1f),
                    style = io.element.android.compound.theme.ElementTheme.typography.fontHeadingSmMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(
                    text = stringResource(CommonStrings.action_save),
                    enabled = !saving,
                    onClick = {
                        onSave(initial.copy(remarkName = remark, phone = phone, tags = tags, description = description))
                    },
                )
            }
            Text(
                text = stringResource(R.string.screen_user_profile_private_details_description),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = io.element.android.compound.theme.ElementTheme.typography.fontBodySmRegular,
                color = io.element.android.compound.theme.ElementTheme.colors.textSecondary,
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                TextField(
                    enabled = !saving,
                    value = remark,
                    onValueChange = { remark = it },
                    label = stringResource(R.string.screen_user_profile_remark_name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    enabled = !saving,
                    value = phone,
                    onValueChange = { phone = it },
                    label = stringResource(R.string.screen_user_profile_phone),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    enabled = !saving,
                    value = tags,
                    onValueChange = { tags = it },
                    label = stringResource(R.string.screen_user_profile_tags),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    enabled = !saving,
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(R.string.screen_user_profile_description),
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun VerifyUserSection(
    state: UserProfileState,
    onVerifyClick: () -> Unit,
) {
    if (state.verificationState == UserProfileVerificationState.UNVERIFIED) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.screen_user_profile_verify_identity)) },
            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Lock())),
            onClick = onVerifyClick,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun UserProfileViewPreview(
    @PreviewParameter(UserProfileStateProvider::class) state: UserProfileState
) = ElementPreview {
    UserProfileView(
        state = state,
        onShareUser = {},
        goBack = {},
        onOpenDm = {},
        onStartCall = { _, _ -> },
        openAvatarPreview = { _, _ -> },
        onVerifyClick = {},
    )
}
