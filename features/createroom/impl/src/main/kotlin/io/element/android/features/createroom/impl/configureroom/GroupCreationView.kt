/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.createroom.impl.configureroom

import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.createroom.impl.R
import io.element.android.libraries.designsystem.components.async.AsyncActionView
import io.element.android.libraries.designsystem.components.async.AsyncActionViewDefaults
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.ui.components.AvatarActionBottomSheet
import io.element.android.libraries.matrix.ui.room.address.RoomAddressField
import io.element.android.libraries.permissions.api.PermissionsView
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.security.contentprotection.api.RoomLifecycleProfileKind
import kotlin.jvm.optionals.getOrNull

@Composable
internal fun GroupCreationView(
    state: ConfigureRoomState,
    onBackClick: () -> Unit,
    onCreateRoomSuccess: (RoomId) -> Unit,
    modifier: Modifier = Modifier,
    initialConfirmation: Boolean = false,
) {
    GroupCreationContent(state, onBackClick, onCreateRoomSuccess, modifier, initialConfirmation)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupCreationContent(
    state: ConfigureRoomState,
    onBackClick: () -> Unit,
    onCreateRoomSuccess: (RoomId) -> Unit,
    modifier: Modifier = Modifier,
    initialConfirmation: Boolean = false,
) {
    var confirming by rememberSaveable { mutableStateOf(initialConfirmation) }
    var query by rememberSaveable { mutableStateOf("") }
    var expandedSetting by rememberSaveable { mutableStateOf<String?>(null) }
    var avatarActionsVisible by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val selected = state.config.invites
    val editable = !state.isConfigurationLocked
    val back = {
        if (editable) {
            focusManager.clearFocus()
            if (confirming) confirming = false else onBackClick()
        }
    }
    BackHandler(enabled = confirming || !editable, onBack = back)
    Scaffold(
        modifier = modifier,
        containerColor = ElementTheme.colors.bgCanvasDefault,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(if (confirming) R.string.screen_create_room_confirm_group else R.string.screen_create_room_start_group),
                    style = ElementTheme.typography.fontBodyLgMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ElementTheme.colors.bgCanvasDefault),
                navigationIcon = { BackButton(onClick = back) },
                actions = {
                    TextButton(
                        colors = ButtonDefaults.textButtonColors(contentColor = ElementTheme.colors.textActionPrimary,
                            disabledContentColor = ElementTheme.colors.textSecondary.copy(alpha = 0.45f)),
                        enabled = if (confirming) !state.createRoomAction.isLoading() && (state.isConfigurationLocked || state.isValid)
                            else editable && selected.isNotEmpty() && !state.contactsLoading,
                        modifier = Modifier.padding(end = 12.dp),
                        onClick = {
                            focusManager.clearFocus()
                            if (confirming) state.eventSink(ConfigureRoomEvents.CreateRoom) else confirming = true
                        },
                    ) {
                        Text(if (confirming) stringResource(if (state.isConfigurationLocked) CommonStrings.action_retry else CommonStrings.action_create)
                            else stringResource(R.string.screen_create_room_next_count, selected.size),
                            style = ElementTheme.typography.fontBodyMdMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        ) {
            if (!confirming) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(14.dp)).background(ElementTheme.colors.bgCanvasDisabled.copy(alpha = 0.32f))
                            .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(imageVector = CompoundIcons.Search(), contentDescription = null,
                            modifier = Modifier.size(18.dp), tint = ElementTheme.colors.iconSecondary)
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = ElementTheme.typography.fontBodyMdRegular.copy(fontSize = 14.sp, lineHeight = 20.sp,
                                color = ElementTheme.colors.textPrimary),
                            cursorBrush = SolidColor(ElementTheme.colors.textActionPrimary),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (query.isEmpty()) Text(stringResource(R.string.screen_create_room_search_contacts),
                                    style = ElementTheme.typography.fontBodyMdRegular.copy(fontSize = 14.sp, lineHeight = 20.sp),
                                    color = ElementTheme.colors.textSecondary)
                                inner()
                            },
                        )
                    }
                }
                if (selected.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.screen_create_room_selected_members, selected.size,
                                selected.joinToString("、") { it.displayName ?: it.userId.value }),
                            style = ElementTheme.typography.fontBodySmRegular.copy(fontSize = 12.sp, lineHeight = 18.sp),
                            color = ElementTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                val visibleContacts = state.contacts.filter {
                    it.displayName.orEmpty().contains(query.trim(), ignoreCase = true) || it.userId.value.contains(query.trim(), ignoreCase = true)
                }
                item {
                    GroupSection(stringResource(if (state.contactsLoading) R.string.screen_create_room_contacts_loading
                        else R.string.screen_create_room_contacts_title))
                }
                items(visibleContacts, key = { it.userId.value }) { user ->
                    ListItem(
                        modifier = Modifier.background(if (selected.any { it.userId == user.userId })
                            ElementTheme.colors.bgCanvasDisabled.copy(alpha = 0.24f) else ElementTheme.colors.bgCanvasDefault),
                        enabled = editable,
                        leadingContent = ListItemContent.Custom {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ListItemContent.Checkbox(selected.any { it.userId == user.userId }, compact = true).View(true)
                                Avatar(
                                    avatarData = AvatarData(user.userId.value, user.displayName, user.avatarUrl, AvatarSize.UserListItem),
                                    avatarType = AvatarType.User,
                                    modifier = Modifier.padding(start = 10.dp),
                                )
                            }
                        },
                        headlineContent = { Text(user.displayName ?: user.userId.value,
                            style = ElementTheme.typography.fontBodyMdRegular.copy(fontSize = 15.sp, lineHeight = 22.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { state.eventSink(ConfigureRoomEvents.ToggleInvite(user)) },
                    )
                }
                if (!state.contactsLoading && visibleContacts.isEmpty()) {
                    item { GroupSection(stringResource(if (query.isBlank()) R.string.screen_create_room_contacts_empty
                        else R.string.screen_create_room_contacts_no_match)) }
                }
            } else {
                item {
                    GroupSection(stringResource(R.string.screen_create_room_group_details), prominent = true)
                    Row(
                        modifier = Modifier.fillMaxWidth().background(ElementTheme.colors.bgCanvasDefault)
                            .heightIn(min = 54.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.screen_create_room_group_name),
                            style = ElementTheme.typography.fontBodyMdRegular.copy(fontSize = 14.sp, lineHeight = 20.sp),
                            modifier = Modifier.padding(end = 24.dp))
                        BasicTextField(
                            value = state.config.roomName.orEmpty(),
                            onValueChange = { state.eventSink(ConfigureRoomEvents.RoomNameChanged(it)) },
                            enabled = editable,
                            singleLine = true,
                            textStyle = ElementTheme.typography.fontBodyMdRegular.copy(fontSize = 14.sp, lineHeight = 20.sp, color = ElementTheme.colors.textPrimary),
                            cursorBrush = SolidColor(ElementTheme.colors.textActionPrimary),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (state.config.roomName.isNullOrEmpty()) {
                                    Text(state.config.effectiveRoomName.orEmpty(),
                                        style = ElementTheme.typography.fontBodyMdRegular.copy(fontSize = 14.sp, lineHeight = 20.sp),
                                        color = ElementTheme.colors.textSecondary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                inner()
                            },
                        )
                    }
                    GroupSettingRow(stringResource(R.string.screen_create_room_group_members),
                        stringResource(R.string.screen_create_room_members_count, selected.size + 1), editable) { confirming = false }
                    GroupDashedDivider()
                    GroupSection(stringResource(R.string.screen_create_room_group_settings), prominent = true)
                    GroupSettingRow(stringResource(R.string.screen_create_room_message_retention_title),
                        stringResource(if (state.config.lifecycleProfileKind == RoomLifecycleProfileKind.CONFIDENTIAL)
                            R.string.screen_create_room_message_retention_confidential_title
                        else R.string.screen_create_room_message_retention_standard_title), editable) {
                        expandedSetting = if (expandedSetting == "retention") null else "retention"
                    }
                    if (expandedSetting == "retention") {
                        RoomLifecycleOptions(state.config.lifecycleProfileKind,
                            { state.eventSink(ConfigureRoomEvents.LifecycleProfileChanged(it)); expandedSetting = null },
                            Modifier.background(ElementTheme.colors.bgCanvasDefault))
                    }
                    GroupSettingRow(stringResource(R.string.screen_create_room_join_method), joinRuleTitle(state.config.visibilityState.joinRuleItem),
                        editable) { expandedSetting = if (expandedSetting == "access") null else "access" }
                    if (expandedSetting == "access") {
                        RoomJoinRuleOptions(state.availableJoinRules, state.config.visibilityState.joinRuleItem,
                            { state.eventSink(ConfigureRoomEvents.JoinRuleChanged(it)); expandedSetting = null }, state.config.parentSpace,
                            Modifier.background(ElementTheme.colors.bgCanvasDefault))
                    }
                    if (state.config.visibilityState !is RoomVisibilityState.Private) {
                        RoomAddressField(
                            address = state.config.visibilityState.roomAddress().getOrNull().orEmpty(),
                            homeserverName = state.homeserverName,
                            addressValidity = state.roomAddressValidity,
                            onAddressChange = { state.eventSink(ConfigureRoomEvents.RoomAddressChanged(it)) },
                            label = null,
                            supportingText = stringResource(R.string.screen_create_room_room_address_section_footer),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    GroupSection(stringResource(if (state.config.lifecycleProfileKind == RoomLifecycleProfileKind.CONFIDENTIAL)
                        R.string.screen_create_room_confidential_policy_note else R.string.screen_create_room_standard_policy_note))
                    GroupDashedDivider()
                    GroupSettingRow(stringResource(R.string.screen_create_room_more_details), stringResource(R.string.screen_create_room_optional),
                        editable, sectionTitle = true) { expandedSetting = if (expandedSetting == "details") null else "details" }
                    if (expandedSetting == "details") {
                        GroupSettingRow(stringResource(R.string.screen_create_room_group_avatar),
                            stringResource(CommonStrings.action_edit), editable) { avatarActionsVisible = true }
                        state.config.avatarUri?.let { uri ->
                            Avatar(AvatarData("group", state.config.effectiveRoomName, uri, AvatarSize.RoomListItem), AvatarType.Room(),
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        RoomTopic(state.config.topic.orEmpty(), { state.eventSink(ConfigureRoomEvents.TopicChanged(it)) },
                            Modifier.fillMaxWidth().padding(16.dp))
                    }
                    if (expandedSetting == "details" && state.spaces.isNotEmpty()) {
                        SelectParentSpaceOptions(state.spaces, state.config.parentSpace,
                            { state.eventSink(ConfigureRoomEvents.SetParentSpace(it)) })
                    }
                }
            }
        }
    }
    AvatarActionBottomSheet(state.avatarActions, avatarActionsVisible,
        onDismiss = { avatarActionsVisible = false }, onSelectAction = { state.eventSink(ConfigureRoomEvents.HandleAvatarAction(it)) })
    AsyncActionView(
        async = state.createRoomAction,
        progressDialog = { AsyncActionViewDefaults.ProgressDialog(progressText = stringResource(CommonStrings.common_creating_room)) },
        onSuccess = onCreateRoomSuccess,
        errorMessage = { error ->
            stringResource(
                if (error.message.orEmpty().contains("LC12_ROOM_CREATE_PROTOCOL_UNAVAILABLE"))
                    R.string.screen_create_room_group_service_unavailable
                else R.string.screen_create_room_error_creating_room
            )
        },
        onRetry = { state.eventSink(ConfigureRoomEvents.CreateRoom) },
        onErrorDismiss = { state.eventSink(ConfigureRoomEvents.CancelCreateRoom) },
    )
    PermissionsView(state.cameraPermissionState)
}

@Composable
private fun GroupSection(text: String, prominent: Boolean = false) {
    Text(
        text = text,
        style = if (prominent) ElementTheme.typography.fontBodyMdMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
            else ElementTheme.typography.fontBodySmRegular.copy(fontSize = 12.sp, lineHeight = 18.sp),
        color = if (prominent) ElementTheme.colors.textPrimary else ElementTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = if (prominent) 12.dp else 10.dp)
            .then(if (prominent) Modifier.semantics { heading() } else Modifier),
    )
}

@Composable
private fun GroupDashedDivider() {
    val color = ElementTheme.colors.borderDisabled
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
    }
}

@Composable
private fun GroupSettingRow(title: String, value: String, enabled: Boolean, sectionTitle: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title,
            modifier = if (sectionTitle) Modifier.semantics { heading() } else Modifier,
            style = (if (sectionTitle) ElementTheme.typography.fontBodyMdMedium else ElementTheme.typography.fontBodyMdRegular)
                .copy(fontSize = 14.sp, lineHeight = 20.sp)) },
        trailingContent = ListItemContent.Custom {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = ElementTheme.typography.fontBodySmRegular.copy(fontSize = 12.sp, lineHeight = 18.sp), color = ElementTheme.colors.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(imageVector = CompoundIcons.ChevronRight(), contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = ElementTheme.colors.iconSecondary)
            }
        },
        enabled = enabled,
        modifier = Modifier.background(ElementTheme.colors.bgCanvasDefault),
        onClick = onClick,
    )
}

@Composable
private fun joinRuleTitle(item: JoinRuleItem): String = stringResource(when (item) {
    JoinRuleItem.PrivateVisibility.Private -> R.string.screen_create_room_invitation_only
    JoinRuleItem.PublicVisibility.Public -> R.string.screen_create_room_public_join
    JoinRuleItem.PublicVisibility.AskToJoin -> R.string.screen_create_room_room_access_section_knocking_option_title
    is JoinRuleItem.PrivateVisibility.Restricted -> R.string.screen_create_room_room_access_section_restricted_option_title
    is JoinRuleItem.PrivateVisibility.AskToJoinRestricted -> R.string.screen_create_room_room_access_section_knocking_restricted_option_title
})

@PreviewsDayNight
@Composable
internal fun GroupMembersPreview(@PreviewParameter(ConfigureRoomStateProvider::class) state: ConfigureRoomState) = ElementPreview {
    GroupCreationView(state, {}, {})
}

@PreviewsDayNight
@Composable
internal fun GroupConfirmationPreview(@PreviewParameter(ConfigureRoomStateProvider::class) state: ConfigureRoomState) = ElementPreview {
    GroupCreationView(state, {}, {}, initialConfirmation = true)
}
