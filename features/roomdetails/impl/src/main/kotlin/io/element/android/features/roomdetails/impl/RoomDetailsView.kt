/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import im.vector.app.features.analytics.plan.Interaction
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.userprofile.api.UserProfileVerificationState
import io.element.android.libraries.architecture.coverage.ExcludeFromCoverage
import io.element.android.libraries.designsystem.atomic.atoms.MatrixBadgeAtom
import io.element.android.libraries.designsystem.atomic.molecules.MatrixBadgeRowMolecule
import io.element.android.libraries.designsystem.components.ClickableLinkText
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.components.preferences.PreferenceCategory
import io.element.android.libraries.designsystem.components.preferences.PreferenceSwitch
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.ElementPreviewDark
import io.element.android.libraries.designsystem.preview.ElementPreviewLight
import io.element.android.libraries.designsystem.preview.PreviewWithExtraLargeHeight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.ListItemStyle
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarHost
import io.element.android.libraries.designsystem.utils.snackbar.rememberSnackbarHostState
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.ui.model.getAvatarData
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.testtags.testTag
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.services.analytics.compose.LocalAnalyticsService
import io.element.android.services.analyticsproviders.api.trackers.captureInteraction
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Ref: https://www.figma.com/design/pDlJZGBsri47FNTXMnEdXB/Compound-Android-Templates?node-id=21-120385
 */
@Composable
fun RoomDetailsView(
    state: RoomDetailsState,
    goBack: () -> Unit,
    onActionClick: (RoomDetailsAction) -> Unit,
    onShareRoom: () -> Unit,
    openRoomMemberList: () -> Unit,
    openRoomNotificationSettings: () -> Unit,
    invitePeople: () -> Unit,
    openAvatarPreview: (name: String, url: String) -> Unit,
    openPollHistory: () -> Unit,
    openMediaGallery: () -> Unit,
    onFindChatContentClick: () -> Unit,
    openAdminSettings: () -> Unit,
    onJoinCallClick: (CallIntent) -> Unit,
    onPinnedMessagesClick: () -> Unit,
    onKnockRequestsClick: () -> Unit,
    onSecurityAndPrivacyClick: () -> Unit,
    onProfileClick: (UserId) -> Unit,
    onReportRoomClick: () -> Unit,
    modifier: Modifier = Modifier,
    leaveRoomView: @Composable () -> Unit,
) {
    ChatSettings(
        state = state,
        otherMember = (state.roomType as? RoomDetailsType.Dm)?.otherMember,
        goBack = goBack,
        onProfileClick = onProfileClick,
        onFindChatContentClick = onFindChatContentClick,
        openMediaGallery = openMediaGallery,
        openRoomNotificationSettings = openRoomNotificationSettings,
        onPinnedMessagesClick = onPinnedMessagesClick,
        onActionClick = onActionClick,
        openRoomMemberList = openRoomMemberList,
        invitePeople = invitePeople,
        openPollHistory = openPollHistory,
        openAdminSettings = openAdminSettings,
        onKnockRequestsClick = onKnockRequestsClick,
        onSecurityAndPrivacyClick = onSecurityAndPrivacyClick,
        onShareRoom = onShareRoom,
        onReportRoomClick = onReportRoomClick,
        leaveRoomView = leaveRoomView,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSettings(
    state: RoomDetailsState,
    otherMember: RoomMember?,
    goBack: () -> Unit,
    onProfileClick: (UserId) -> Unit,
    onFindChatContentClick: () -> Unit,
    openMediaGallery: () -> Unit,
    openRoomNotificationSettings: () -> Unit,
    onPinnedMessagesClick: () -> Unit,
    onActionClick: (RoomDetailsAction) -> Unit,
    openRoomMemberList: () -> Unit,
    invitePeople: () -> Unit,
    openPollHistory: () -> Unit,
    openAdminSettings: () -> Unit,
    onKnockRequestsClick: () -> Unit,
    onSecurityAndPrivacyClick: () -> Unit,
    onShareRoom: () -> Unit,
    onReportRoomClick: () -> Unit,
    leaveRoomView: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showingSecurity by remember { mutableStateOf(false) }
    val snackbarHostState = rememberSnackbarHostState(snackbarMessage = state.snackbarMessage)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(
                    if (state.isDirect) R.string.screen_room_details_chat_settings else R.string.screen_room_details_group_settings
                )) },
                navigationIcon = { BackButton(onClick = goBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
        ) {
            leaveRoomView()
            PreferenceCategory(showTopDivider = false) {
                ListItem(
                    headlineContent = { Text(state.roomName, style = ElementTheme.typography.fontBodyLgMedium) },
                    supportingContent = {
                        Text(stringResource(
                            when {
                                !state.isDirect -> R.string.screen_room_details_group_info
                                otherMember == null -> R.string.screen_room_details_profile_unavailable
                                else -> R.string.screen_room_details_view_profile
                            }
                        ))
                    },
                    leadingContent = ListItemContent.Custom {
                        Avatar(
                            avatarData = AvatarData(
                                otherMember?.userId?.value ?: state.roomId.value, state.roomName,
                                otherMember?.avatarUrl ?: state.roomAvatarUrl, AvatarSize.DmCreationConfirmation,
                            ),
                            avatarType = if (state.isDirect) AvatarType.User else AvatarType.Room(
                                heroes = state.heroes.map { it.getAvatarData(AvatarSize.TimelineRoom) }.toImmutableList(),
                                isTombstoned = state.isTombstoned,
                            ),
                            modifier = Modifier.testTag(TestTags.roomDetailAvatar),
                        )
                    },
                    trailingContent = if ((state.isDirect && otherMember != null) || (!state.isDirect && state.canEdit)) {
                        ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChevronRight()))
                    } else null,
                    onClick = if (state.isDirect) {
                        otherMember?.let { member -> { onProfileClick(member.userId) } }
                    } else if (state.canEdit) {
                        { onActionClick(RoomDetailsAction.Edit) }
                    } else null,
                )
            }
            if (!state.isDirect) {
                PreferenceCategory {
                    MembersItem(state.memberCount, state.hasMemberVerificationViolations, openRoomMemberList)
                    if (state.canInvite) InviteItem(invitePeople)
                    if (state.canShowKnockRequests) KnockRequestsItem(state.knockRequestsCount, onKnockRequestsClick)
                    if (state.displayRolesAndPermissionsSettings) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.screen_room_details_group_management)) },
                            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Admin())),
                            onClick = openAdminSettings,
                        )
                    }
                }
                if (state.roomTopic !is RoomTopicState.Hidden) TopicSection(state.roomTopic, onActionClick)
            }
            PreferenceCategory {
                FindChatContentItem(onClick = onFindChatContentClick)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.screen_room_details_chat_media)) },
                    leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Image())),
                    onClick = openMediaGallery,
                )
                PinnedMessagesItem(state.pinnedMessagesCount, onPinnedMessagesClick)
                if (!state.isDirect) PollsItem(openPollHistory)
            }
            PreferenceCategory {
                state.roomNotificationSettings?.let { settings ->
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.screen_room_details_notification_options)) },
                        onClick = openRoomNotificationSettings,
                    )
                    PreferenceSwitch(
                        icon = CompoundIcons.NotificationsOff(),
                        title = stringResource(R.string.screen_room_details_do_not_disturb),
                        isChecked = settings.mode == RoomNotificationMode.MUTE,
                        onCheckedChange = { muted ->
                            state.eventSink(if (muted) RoomDetailsEvent.MuteNotification else RoomDetailsEvent.UnmuteNotification)
                        },
                    )
                }
                PreferenceSwitch(
                    icon = CompoundIcons.Pin(),
                    title = stringResource(R.string.screen_room_details_pin_chat),
                    isChecked = state.isFavorite,
                    onCheckedChange = { state.eventSink(RoomDetailsEvent.SetFavorite(it)) },
                )
            }
            PreferenceCategory {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.screen_room_details_chat_security)) },
                    leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Lock())),
                    trailingContent = ListItemContent.Text(
                        stringResource(
                            if (state.isEncrypted) R.string.screen_room_details_badge_encrypted
                            else R.string.screen_room_details_badge_not_encrypted
                        )
                    ),
                    onClick = { showingSecurity = true },
                )
                if (!state.isDirect) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.screen_room_details_share_group)) },
                        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ShareAndroid())),
                        onClick = onShareRoom,
                    )
                }
            }
            if (!state.isDirect) {
                OtherActionsSection(
                    canReportRoom = state.canReportRoom,
                    showLeaveGroup = true,
                    onReportRoomClick = onReportRoomClick,
                    onLeaveRoomClick = { state.eventSink(RoomDetailsEvent.LeaveRoom(needsConfirmation = true)) },
                )
            }
        }
    }
    if (showingSecurity) {
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismissRequest = { showingSecurity = false },
            scrollable = false,
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.screen_room_details_chat_security)) },
                supportingContent = {
                    Text(stringResource(
                        if (state.isEncrypted) R.string.screen_room_details_chat_encrypted_description
                        else R.string.screen_room_details_chat_unencrypted_description
                    ))
                },
                leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Lock())),
            )
            if (state.isDirect && otherMember != null) {
                ProfileItem(
                    verificationState = state.dmOtherMemberDetailsState?.verificationState ?: UserProfileVerificationState.UNKNOWN,
                    onClick = {
                        showingSecurity = false
                        onProfileClick(otherMember.userId)
                    },
                )
            } else if (!state.isDirect) {
                BadgeList(
                    roomBadge = state.roomBadges.filter { it.name.startsWith("SHARED_HISTORY") }.toImmutableList(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (state.canShowSecurityAndPrivacy) {
                    SecurityAndPrivacyItem(onClick = {
                        showingSecurity = false
                        onSecurityAndPrivacyClick()
                    })
                }
            }
        }
    }
}

@Composable
private fun KnockRequestsItem(knockRequestsCount: Int?, onKnockRequestsClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.screen_room_details_requests_to_join_title)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.AskToJoin())),
        trailingContent = if (knockRequestsCount == null || knockRequestsCount == 0) {
            null
        } else {
            ListItemContent.Counter(knockRequestsCount)
        },
        onClick = onKnockRequestsClick,
    )
}

@Composable
private fun FindChatContentItem(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.screen_room_details_find_chat)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Search())),
        onClick = onClick,
    )
}

@Composable
private fun BadgeList(
    roomBadge: ImmutableList<RoomBadge>,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (roomBadge.isNotEmpty()) {
            MatrixBadgeRowMolecule(
                data = roomBadge.map {
                    it.toMatrixBadgeData()
                }.toImmutableList(),
            )
        }
    }
}

@Composable
private fun RoomBadge.toMatrixBadgeData(): MatrixBadgeAtom.MatrixBadgeData {
    return when (this) {
        RoomBadge.ENCRYPTED -> {
            MatrixBadgeAtom.MatrixBadgeData(
                text = stringResource(R.string.screen_room_details_badge_encrypted),
                icon = CompoundIcons.LockSolid(),
                type = MatrixBadgeAtom.Type.Positive,
            )
        }
        RoomBadge.NOT_ENCRYPTED -> {
            MatrixBadgeAtom.MatrixBadgeData(
                text = stringResource(R.string.screen_room_details_badge_not_encrypted),
                icon = CompoundIcons.LockOff(),
                type = MatrixBadgeAtom.Type.Info,
            )
        }
        RoomBadge.PUBLIC -> {
            MatrixBadgeAtom.MatrixBadgeData(
                text = stringResource(R.string.screen_room_details_badge_public),
                icon = CompoundIcons.Public(),
                type = MatrixBadgeAtom.Type.Info,
            )
        }
        RoomBadge.SHARED_HISTORY_HIDDEN -> {
            MatrixBadgeAtom.MatrixBadgeData(
                text = stringResource(R.string.crypto_history_sharing_room_info_hidden_badge_content),
                icon = CompoundIcons.VisibilityOff(),
                type = MatrixBadgeAtom.Type.Info
            )
        }
        RoomBadge.SHARED_HISTORY_SHARED -> {
            MatrixBadgeAtom.MatrixBadgeData(
                text = stringResource(R.string.crypto_history_sharing_room_info_shared_badge_content),
                icon = CompoundIcons.History(),
                type = MatrixBadgeAtom.Type.Info
            )
        }
        RoomBadge.SHARED_HISTORY_WORLD_READABLE -> {
            MatrixBadgeAtom.MatrixBadgeData(
                text = stringResource(R.string.crypto_history_sharing_room_info_world_readable_badge_content),
                icon = CompoundIcons.UserProfileSolid(),
                type = MatrixBadgeAtom.Type.Info
            )
        }
    }
}

@Composable
private fun TopicSection(
    roomTopic: RoomTopicState,
    onActionClick: (RoomDetailsAction) -> Unit,
) {
    PreferenceCategory(
        showTopDivider = false,
    ) {
        if (roomTopic is RoomTopicState.CanAddTopic) {
            ListItem(
                leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Plus())),
                headlineContent = {
                    Text(stringResource(id = R.string.screen_room_details_add_topic_title))
                },
                onClick = {
                    onActionClick(RoomDetailsAction.AddTopic)
                },
            )
        } else if (roomTopic is RoomTopicState.ExistingTopic) {
            ClickableLinkText(
                text = roomTopic.topic,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                interactionSource = remember { MutableInteractionSource() },
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.tertiary,
                ),
            )
        }
    }
}


@Composable
private fun SecurityAndPrivacyItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.screen_room_details_security_and_privacy_title)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Lock())),
        onClick = onClick,
        modifier = modifier,
    )
}


@Composable
private fun ProfileItem(
    verificationState: UserProfileVerificationState,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.UserProfile())),
        headlineContent = { Text(stringResource(id = R.string.screen_room_details_friend_profile)) },
        trailingContent = when (verificationState) {
            UserProfileVerificationState.VERIFIED -> ListItemContent.Icon(
                iconSource = IconSource.Vector(CompoundIcons.Verified()),
                tintColor = ElementTheme.colors.iconSuccessPrimary,
            )
            UserProfileVerificationState.VERIFICATION_VIOLATION -> ListItemContent.Icon(
                iconSource = IconSource.Vector(CompoundIcons.ErrorSolid()),
                tintColor = ElementTheme.colors.iconCriticalPrimary,
            )
            else -> null
        },
        onClick = onClick,
    )
}

@Composable
private fun MembersItem(
    memberCount: Long,
    hasVerificationViolations: Boolean,
    openRoomMemberList: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.screen_room_details_group_members)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.User())),
        trailingContent = if (hasVerificationViolations) {
            ListItemContent.Icon(
                iconSource = IconSource.Vector(CompoundIcons.ErrorSolid()),
                tintColor = ElementTheme.colors.textCriticalPrimary,
            )
        } else {
            ListItemContent.Text(memberCount.toString())
        },
        onClick = openRoomMemberList,
    )
}

@Composable
private fun InviteItem(
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.screen_room_details_add_members)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.UserAdd())),
        onClick = onClick,
    )
}

@Composable
private fun PinnedMessagesItem(
    pinnedMessagesCount: Int?,
    onPinnedMessagesClick: () -> Unit,
) {
    val analyticsService = LocalAnalyticsService.current
    ListItem(
        headlineContent = { Text(stringResource(R.string.screen_room_details_chat_pinned_messages)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Pin())),
        trailingContent =
            if (pinnedMessagesCount == null) {
                ListItemContent.Custom {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            } else {
                ListItemContent.Text(pinnedMessagesCount.toString())
            },
        onClick = {
            analyticsService.captureInteraction(Interaction.Name.PinnedMessageRoomInfoButton)
            onPinnedMessagesClick()
        }
    )
}

@Composable
private fun PollsItem(
    openPollHistory: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.screen_polls_history_title)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Polls())),
        onClick = openPollHistory,
    )
}


@Composable
private fun OtherActionsSection(
    canReportRoom: Boolean,
    showLeaveGroup: Boolean,
    onReportRoomClick: () -> Unit,
    onLeaveRoomClick: () -> Unit,
) {
    PreferenceCategory {
        if (canReportRoom) {
            ListItem(
                headlineContent = {
                    Text(stringResource(CommonStrings.action_report_room))
                },
                leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChatProblem())),
                style = ListItemStyle.Destructive,
                onClick = onReportRoomClick,
            )
        }
        if (showLeaveGroup) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.screen_room_details_leave_group)) },
                leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Leave())),
                style = ListItemStyle.Destructive,
                onClick = onLeaveRoomClick,
            )
        }
    }
}


@PreviewWithExtraLargeHeight
@Composable
internal fun RoomDetailsPreview(@PreviewParameter(RoomDetailsStateProvider::class) state: RoomDetailsState) =
    ElementPreviewLight { ContentToPreview(state) }

@PreviewWithExtraLargeHeight
@Composable
internal fun RoomDetailsDarkPreview(@PreviewParameter(RoomDetailsStateProvider::class) state: RoomDetailsState) =
    ElementPreviewDark { ContentToPreview(state) }

@PreviewWithExtraLargeHeight
@Composable
internal fun RoomDetailsA11yPreview() = ElementPreview {
    ContentToPreview(
        state = aRoomDetailsState(displayAdminSettings = true)
    )
}

@ExcludeFromCoverage
@Composable
private fun ContentToPreview(state: RoomDetailsState) {
    RoomDetailsView(
        state = state,
        goBack = {},
        onActionClick = {},
        onShareRoom = {},
        openRoomMemberList = {},
        openRoomNotificationSettings = {},
        invitePeople = {},
        openAvatarPreview = { _, _ -> },
        openPollHistory = {},
        openMediaGallery = {},
        onFindChatContentClick = {},
        openAdminSettings = {},
        onJoinCallClick = {},
        onPinnedMessagesClick = {},
        onKnockRequestsClick = {},
        onSecurityAndPrivacyClick = {},
        onProfileClick = {},
        onReportRoomClick = {},
        leaveRoomView = {},
    )
}
