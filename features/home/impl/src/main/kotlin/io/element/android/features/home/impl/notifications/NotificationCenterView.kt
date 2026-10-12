/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.home.impl.notifications

import androidx.activity.compose.BackHandler
import io.element.android.libraries.designsystem.theme.components.DropdownMenuItem
import io.element.android.libraries.designsystem.theme.components.DropdownMenu
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import java.util.Calendar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.home.impl.R
import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.features.home.impl.roomlist.RoomListEvent
import io.element.android.features.home.impl.roomlist.RoomListState
import io.element.android.features.home.impl.roomlist.aRoomListState
import io.element.android.features.invite.api.InviteNotification
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.DirectRoomRelationState
import io.element.android.libraries.matrix.api.room.history.RoomHistoryVisibility
import io.element.android.libraries.ui.strings.CommonStrings
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationCenterView(
    state: RoomListState,
    initialKind: String,
    onBack: () -> Unit,
    onRoomClick: (RoomId) -> Unit,
    onFriendClick: (UserId) -> Unit,
    displayName: (RoomListRoomSummary) -> String,
    isDeletedUser: (String) -> Boolean = { false },
) {
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val rooms = (state.contactsSummaries as? AsyncData.Success)?.data.orEmpty()
    val loading = state.contactsSummaries !is AsyncData.Success
    val action = state.acceptDeclineInviteState
    val busy = action.acceptAction is AsyncAction.Loading || action.declineAction is AsyncAction.Loading
    DisposableEffect(Unit) {
        state.eventSink(RoomListEvent.SetInviteActionsInPlace(true))
        onDispose { state.eventSink(RoomListEvent.SetInviteActionsInPlace(false)) }
    }
    LaunchedEffect(action.acceptAction, action.declineAction) {
        if (action.acceptAction is AsyncAction.Success || action.declineAction is AsyncAction.Success) {
            state.eventSink(RoomListEvent.ResetInviteActions)
        }
    }
    fun back() { if (detailId != null) detailId = null else onBack() }
    BackHandler(onBack = ::back)
    val details = state.notifications.firstOrNull { it.roomId.value == detailId }
    val records = if (details != null) listOf(details) else filteredNotifications(state.notifications, kind, filter)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var previousSequence by remember(kind, filter) { mutableStateOf<Long?>(null) }
    var previousFirstRoom by remember(kind, filter) { mutableStateOf<String?>(null) }
    var newNotification by remember(kind, filter) { mutableStateOf(false) }
    LaunchedEffect(kind, filter, detailId) { listState.scrollToItem(0); newNotification = false }
    LaunchedEffect(records.firstOrNull()?.sequence) {
        val sequence = records.firstOrNull()?.sequence ?: return@LaunchedEffect
        if (previousSequence != null && sequence > requireNotNull(previousSequence)) {
            val firstVisibleKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
            if (listState.firstVisibleItemScrollOffset == 0 &&
                (listState.firstVisibleItemIndex == 0 || firstVisibleKey == previousFirstRoom)) listState.scrollToItem(0)
            else newNotification = true
        }
        previousSequence = sequence
        previousFirstRoom = records.firstOrNull()?.roomId?.value
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (details != null) details.kind.titleRes() else R.string.screen_notifications_title)) },
                navigationIcon = { BackButton(onClick = ::back) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ElementTheme.colors.bgCanvasDefault),
                actions = {
                    if (details == null) TextButton(
                        text = stringResource(R.string.screen_notifications_read_all),
                        size = ButtonSize.MediumLowPadding,
                        enabled = records.any { !it.seen },
                        onClick = { state.eventSink(RoomListEvent.MarkInvitesAsSeen(records.filter { !it.seen }.map { it.roomId }.toSet())) },
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading && state.notifications.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                if (details == null) {
                    NotificationFilters(
                        status = filter,
                        kind = kind,
                        onStatusChange = { filter = it },
                        onKindChange = { kind = it },
                    )
                }
                if (newNotification && details == null) TextButton(
                    text = stringResource(R.string.screen_notifications_new), size = ButtonSize.MediumLowPadding,
                    modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch { listState.animateScrollToItem(0); newNotification = false }
                    },
                )
                LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (records.isEmpty()) item {
                        Text(stringResource(R.string.screen_notifications_empty), color = ElementTheme.colors.textSecondary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp))
                    }
                    items(records, key = { it.roomId.value }) { notice ->
                        val room = rooms.firstOrNull { it.roomId == notice.roomId }
                        NotificationCard(notice, room, state, busy, displayName, isDeletedUser,
                            detailed = details != null,
                            onDetails = {
                                state.eventSink(RoomListEvent.MarkInvitesAsSeen(setOf(notice.roomId)))
                                detailId = notice.roomId.value
                            },
                            onProfile = {
                                state.eventSink(RoomListEvent.MarkInvitesAsSeen(setOf(notice.roomId)))
                                notice.senderId?.takeIf { it.startsWith("@") && it.contains(':') && !isDeletedUser(it) }?.let { onFriendClick(UserId(it)) }
                            },
                            onOpen = {
                                state.eventSink(RoomListEvent.MarkInvitesAsSeen(setOf(notice.roomId)))
                                onRoomClick(notice.roomId)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationFilters(
    status: String,
    kind: String,
    onStatusChange: (String) -> Unit,
    onKindChange: (String) -> Unit,
) {
    var showTypes by rememberSaveable { mutableStateOf(false) }
    val statuses = listOf(
        "ALL" to R.string.screen_notifications_all,
        "PENDING" to R.string.screen_notifications_pending,
        "HANDLED" to R.string.screen_notifications_handled,
    )
    val types = listOf(
        "ALL" to R.string.screen_notifications_all_types,
        "FRIEND" to R.string.screen_notifications_friend,
        "GROUP" to R.string.screen_notifications_group,
        "SPACE" to R.string.screen_roomlist_invite_kind_space,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f)) {
            statuses.forEach { (value, label) ->
                val isSelected = status == value
                Box(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .semantics { selected = isSelected }
                        .clickable(role = Role.Tab) { onStatusChange(value) }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(label),
                        modifier = Modifier.padding(vertical = 14.dp),
                        style = if (isSelected) ElementTheme.typography.fontBodyMdMedium else ElementTheme.typography.fontBodyMdRegular,
                        color = if (isSelected) ElementTheme.colors.textPrimary else ElementTheme.colors.textSecondary,
                    )
                    if (isSelected) Box(
                        Modifier.align(Alignment.BottomCenter).width(18.dp).padding(bottom = 4.dp).height(2.dp)
                            .background(ElementTheme.colors.bgAccentRest, RoundedCornerShape(1.dp)),
                    )
                }
            }
        }
        Box {
            Row(
                modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { showTypes = true }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(when (kind) {
                        "FRIEND" -> R.string.screen_notifications_type_friend
                        "GROUP" -> R.string.screen_notifications_type_group
                        "SPACE" -> R.string.screen_roomlist_invite_kind_space
                        else -> R.string.screen_notifications_type
                    }),
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary,
                )
                Icon(CompoundIcons.ChevronDown(), contentDescription = null,
                    tint = ElementTheme.colors.iconSecondary, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = showTypes, onDismissRequest = { showTypes = false }) {
                types.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label), style = ElementTheme.typography.fontBodyMdRegular) },
                        modifier = Modifier.semantics { selected = kind == value },
                        onClick = {
                            onKindChange(value)
                            showTypes = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    notice: InviteNotification,
    room: RoomListRoomSummary?,
    state: RoomListState,
    busy: Boolean,
    displayName: (RoomListRoomSummary) -> String,
    isDeletedUser: (String) -> Boolean,
    detailed: Boolean,
    onDetails: () -> Unit,
    onProfile: () -> Unit,
    onOpen: () -> Unit,
) {
    val canRespond = notice.outcome == InviteNotification.Outcome.PENDING && !notice.outgoing &&
        room?.displayType == RoomSummaryDisplayType.INVITE
    val canOpen = room?.displayType == RoomSummaryDisplayType.ROOM &&
        (notice.kind != InviteNotification.Kind.FRIEND || room.directRoomRelationState() == DirectRoomRelationState.ACCEPTED)
    val processing = busy && state.acceptDeclineInviteState.actionRoomId == notice.roomId
    val canViewProfile = notice.kind == InviteNotification.Kind.FRIEND &&
        notice.senderId?.let { it.startsWith("@") && it.contains(':') && !isDeletedUser(it) } == true
    val failed = state.acceptDeclineInviteState.actionRoomId == notice.roomId &&
        (state.acceptDeclineInviteState.acceptAction is AsyncAction.Failure || state.acceptDeclineInviteState.declineAction is AsyncAction.Failure)
    val unreadDescription = stringResource(R.string.screen_notifications_unread)
    val title = room?.let(displayName)?.takeIf { it.isNotBlank() } ?: notice.displayName?.takeIf { it.isNotBlank() }
        ?: notice.senderId?.takeIf { notice.kind == InviteNotification.Kind.FRIEND }?.substringBefore(':')?.removePrefix("@")
        ?: stringResource(notice.kind.titleRes())
    val status = when (notice.outcome) {
        InviteNotification.Outcome.ACCEPTED -> if (!canOpen) R.string.screen_notifications_accepted else
            when (notice.kind) {
                InviteNotification.Kind.FRIEND -> R.string.screen_notifications_added
                InviteNotification.Kind.SPACE -> R.string.screen_notifications_joined_space
                InviteNotification.Kind.GROUP -> R.string.screen_notifications_joined
            }
        InviteNotification.Outcome.DECLINED -> R.string.screen_notifications_declined
        InviteNotification.Outcome.UNAVAILABLE -> if (notice.kind == InviteNotification.Kind.FRIEND)
            R.string.screen_notifications_request_unavailable else R.string.screen_notifications_unavailable
        InviteNotification.Outcome.PENDING -> if (notice.outgoing) R.string.screen_notifications_waiting else
            if (canRespond) R.string.screen_notifications_pending else R.string.screen_notifications_confirming
    }
    val compactAction = !detailed && (canRespond && notice.kind != InviteNotification.Kind.FRIEND ||
        canOpen && notice.outcome == InviteNotification.Outcome.ACCEPTED)
    Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgCanvasDefaultLevel1, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                Avatar(
                    avatarData = room?.avatarData ?: AvatarData(notice.roomId.value, title, notice.avatarUrl, AvatarSize.RoomListItem),
                    forcedAvatarSize = 44.dp,
                    hideImage = state.hideInvitesAvatars && (room?.displayType == RoomSummaryDisplayType.INVITE ||
                        room == null && !notice.outgoing && notice.outcome != InviteNotification.Outcome.ACCEPTED),
                    contentDescription = stringResource(R.string.screen_notifications_details),
                    avatarType = if (notice.kind == InviteNotification.Kind.FRIEND) AvatarType.User else AvatarType.Room(),
                    modifier = Modifier.minimumInteractiveComponentSize().clickable(onClick = if (canViewProfile) onProfile else onDetails),
                )
                if (!notice.seen) Box(Modifier.align(Alignment.TopEnd).size(8.dp)
                    .background(ElementTheme.colors.iconCriticalPrimary, RoundedCornerShape(50))
                    .semantics { contentDescription = unreadDescription })
            }
            Column(Modifier.weight(1f).clickable(onClick = onDetails), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = ElementTheme.typography.fontBodyMdMedium, maxLines = if (detailed) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis)
                Text(stringResource(notice.kind.titleRes()) + " · " + stringResource(status),
                    color = ElementTheme.colors.textSecondary, style = ElementTheme.typography.fontBodySmRegular,
                    maxLines = if (detailed) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                Text(notificationDate(notice.notificationTime, detailed), style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary)
            }
            if (compactAction) CompactNotificationButton(
                text = stringResource(if (canRespond) R.string.screen_notifications_view_invite else
                    when (notice.kind) {
                        InviteNotification.Kind.FRIEND -> CommonStrings.action_message
                        InviteNotification.Kind.SPACE -> R.string.screen_notifications_open_space
                        InviteNotification.Kind.GROUP -> R.string.screen_notifications_open_group
                    }),
                enabled = !canRespond || !busy, onClick = if (canRespond) onDetails else onOpen,
            )
        }
        if (detailed && notice.kind != InviteNotification.Kind.FRIEND) room?.inviteSender?.takeUnless { isDeletedUser(it.userId.value) }?.let { sender ->
            Text(stringResource(R.string.screen_notifications_invited_by, sender.displayName),
                style = ElementTheme.typography.fontBodyMdRegular, color = ElementTheme.colors.textSecondary)
        }
        if (detailed && notice.kind != InviteNotification.Kind.FRIEND && room != null) {
            if (room.joinedMembersCount > 0) Text(stringResource(R.string.screen_notifications_members, room.joinedMembersCount))
            if (room.isEncrypted != null) Text(stringResource(if (room.isEncrypted == true) R.string.screen_notifications_encrypted
                else R.string.screen_notifications_not_encrypted), color = ElementTheme.colors.textSecondary)
            val historyLabel = when (room.historyVisibility) {
                RoomHistoryVisibility.Joined -> R.string.screen_notifications_history_joined
                RoomHistoryVisibility.Invited -> R.string.screen_notifications_history_invited
                RoomHistoryVisibility.Shared, RoomHistoryVisibility.WorldReadable -> R.string.screen_notifications_history_shared
                else -> null
            }
            historyLabel?.let { Text(stringResource(it), color = ElementTheme.colors.textSecondary) }
        }
        if (failed) {
            Text(stringResource(R.string.screen_notifications_failed), color = ElementTheme.colors.textCriticalPrimary)
        }
        if (!compactAction && (canRespond || canOpen && notice.outcome == InviteNotification.Outcome.ACCEPTED))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canRespond) {
                if (notice.kind == InviteNotification.Kind.FRIEND || detailed) {
                    Button(text = stringResource(if (processing) R.string.screen_notifications_processing else
                        when (notice.kind) {
                            InviteNotification.Kind.FRIEND -> R.string.screen_notifications_agree
                            InviteNotification.Kind.SPACE -> R.string.screen_notifications_join_space
                            InviteNotification.Kind.GROUP -> R.string.screen_notifications_join
                        }),
                        enabled = !busy, showProgress = processing, size = ButtonSize.Small,
                        modifier = Modifier.weight(1f), onClick = {
                            state.eventSink(RoomListEvent.MarkInvitesAsSeen(setOf(notice.roomId)))
                            state.eventSink(RoomListEvent.AcceptInvite(requireNotNull(room)))
                        })
                    OutlinedButton(text = stringResource(R.string.screen_notifications_decline), enabled = !busy,
                        modifier = Modifier.weight(1f), size = ButtonSize.Small, onClick = {
                            state.eventSink(RoomListEvent.DeclineInvite(requireNotNull(room), blockUser = false))
                        })
                } else Button(text = stringResource(R.string.screen_notifications_view_invite), onClick = onDetails,
                    size = ButtonSize.Small, enabled = !busy)
            } else if (canOpen && notice.outcome == InviteNotification.Outcome.ACCEPTED) {
                Button(text = stringResource(when (notice.kind) {
                    InviteNotification.Kind.FRIEND -> CommonStrings.action_message
                    InviteNotification.Kind.SPACE -> R.string.screen_notifications_open_space
                    InviteNotification.Kind.GROUP -> R.string.screen_notifications_open_group
                }), onClick = onOpen, size = ButtonSize.Small)
            }
        }
    }
}

@Composable
private fun CompactNotificationButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.width(80.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = ElementTheme.colors.bgAccentRest, contentColor = ElementTheme.colors.textOnSolidPrimary,
        ),
    ) { Text(text, style = ElementTheme.typography.fontBodySmMedium, maxLines = 2) }
}

@Composable
private fun notificationDate(timestamp: Long, detailed: Boolean): String {
    if (timestamp <= 0) return stringResource(R.string.screen_notifications_time_unknown)
    val date = Date(timestamp)
    if (detailed) return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(date)
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
    return when {
        timestamp >= today.timeInMillis -> time
        timestamp >= yesterday.timeInMillis -> stringResource(R.string.screen_notifications_yesterday, time)
        else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(date)
    }
}

private fun InviteNotification.Kind.titleRes(): Int = when (this) {
    InviteNotification.Kind.FRIEND -> R.string.screen_notifications_friend
    InviteNotification.Kind.GROUP -> R.string.screen_notifications_group
    InviteNotification.Kind.SPACE -> R.string.screen_roomlist_invite_kind_space
}

@PreviewsDayNight
@Composable
private fun NotificationCenterEmptyPreview() = ElementPreview {
    NotificationCenterView(aRoomListState().copy(contactsSummaries = AsyncData.Success(kotlinx.collections.immutable.persistentListOf())), "ALL", {}, {}, {}, { it.name.orEmpty() })
}

@PreviewsDayNight
@Composable
private fun NotificationCenterRequestsPreview() = ElementPreview {
    val friend = io.element.android.features.home.impl.model.aRoomListRoomSummary(
        id = "!friend:example.test", name = "林一", isDm = true, displayType = RoomSummaryDisplayType.INVITE)
    val group = io.element.android.features.home.impl.model.aRoomListRoomSummary(
        id = "!group:example.test", name = "产品讨论组", displayType = RoomSummaryDisplayType.INVITE)
    NotificationCenterView(aRoomListState().copy(
        contactsSummaries = AsyncData.Success(kotlinx.collections.immutable.persistentListOf(friend, group)),
        notifications = kotlinx.collections.immutable.persistentListOf(
            requireNotNull(friend.toNotification()), requireNotNull(group.toNotification()),
            InviteNotification(RoomId("!sent:example.test"), InviteNotification.Kind.FRIEND, "@peer:example.test", 0, outgoing = true),
            InviteNotification(RoomId("!handled:example.test"), InviteNotification.Kind.GROUP, null, 0,
                outcome = InviteNotification.Outcome.DECLINED, seen = true),
        )), "ALL", {}, {}, {}, { it.name.orEmpty() })
}
