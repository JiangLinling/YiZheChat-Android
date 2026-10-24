/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl

import android.icu.text.Transliterator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.home.impl.components.HomeTopBar
import io.element.android.features.home.impl.components.RoomListContentView
import io.element.android.features.home.impl.components.RoomListMenuAction
import io.element.android.features.home.impl.components.RoomSummaryRow
import io.element.android.features.home.impl.filters.RoomListFilter
import io.element.android.features.home.impl.filters.RoomListFiltersEvent
import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.model.referencesDeletedDirectPeer
import io.element.android.features.home.impl.notifications.NotificationCenterView
import io.element.android.features.home.impl.notifications.visibleNotifications
import io.element.android.features.home.impl.roomlist.RoomListContentState
import io.element.android.features.home.impl.roomlist.RoomListContextMenu
import io.element.android.features.home.impl.roomlist.RoomListDeclineInviteMenu
import io.element.android.features.home.impl.roomlist.RoomListEvent
import io.element.android.features.home.impl.roomlist.RoomListState
import io.element.android.features.home.impl.search.RoomListSearchView
import io.element.android.features.home.impl.spacefilters.SpaceFiltersEvent
import io.element.android.features.home.impl.spacefilters.SpaceFiltersState
import io.element.android.features.home.impl.spacefilters.SpaceFiltersView
import io.element.android.features.userprofile.api.privateDirectDisplayName
import io.element.android.libraries.androidutils.throttler.FirstThrottler
import io.element.android.libraries.designsystem.atomic.atoms.UnreadIndicatorAtom
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.NavigationBar
import io.element.android.libraries.designsystem.theme.components.NavigationBarIcon
import io.element.android.libraries.designsystem.theme.components.NavigationBarItem
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarHost
import io.element.android.libraries.designsystem.utils.snackbar.rememberSnackbarHostState
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.spaces.SpaceRoom
import io.element.android.libraries.matrix.ui.model.getAvatarData
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeView(
    homeState: HomeState,
    discoveryAvailable: Boolean = false,
    onDiscoveryClick: () -> Unit = {},
    onRoomClick: (RoomId) -> Unit,
    onSetUpRecoveryClick: () -> Unit,
    onConfirmRecoveryKeyClick: () -> Unit,
    onAddFriendClick: () -> Unit,
    onScanFriendClick: () -> Unit = {},
    onMyQrCodeClick: () -> Unit = {},
    onInviteNewUserClick: () -> Unit = {},
    onFriendClick: (UserId) -> Unit = {},
    onCreateRoomClick: () -> Unit,
    onCreateSpaceClick: () -> Unit,
    onRoomSettingsClick: (roomId: RoomId) -> Unit,
    onMenuActionClick: (RoomListMenuAction) -> Unit,
    onReportRoomClick: (roomId: RoomId) -> Unit,
    onDeclineInviteAndBlockUser: (roomSummary: RoomListRoomSummary) -> Unit,
    onMoveToBackground: () -> Unit,
    acceptDeclineInviteView: @Composable () -> Unit,
    settingsRootContent: @Composable (Modifier) -> Unit = {},
    modifier: Modifier = Modifier,
    leaveRoomView: @Composable () -> Unit,
) {
    val state: RoomListState = homeState.roomListState
    val coroutineScope = rememberCoroutineScope()
    val firstThrottler = remember { FirstThrottler(300, coroutineScope) }
    Box(modifier) {
        if (state.contextMenu is RoomListState.ContextMenu.Shown) {
            RoomListContextMenu(
                contextMenu = state.contextMenu,
                canReportRoom = state.canReportRoom,
                eventSink = state.eventSink,
                onRoomSettingsClick = onRoomSettingsClick,
                onReportRoomClick = onReportRoomClick,
            )
        }
        if (state.declineInviteMenu is RoomListState.DeclineInviteMenu.Shown) {
            RoomListDeclineInviteMenu(
                menu = state.declineInviteMenu,
                canReportRoom = state.canReportRoom,
                eventSink = state.eventSink,
                onDeclineAndBlockClick = onDeclineInviteAndBlockUser,
            )
        }

        leaveRoomView()

        HomeScaffold(
            state = homeState,
            discoveryAvailable = discoveryAvailable,
            onDiscoveryClick = { if (firstThrottler.canHandle()) onDiscoveryClick() },
            onSetUpRecoveryClick = onSetUpRecoveryClick,
            onConfirmRecoveryKeyClick = onConfirmRecoveryKeyClick,
            onRoomClick = { if (firstThrottler.canHandle()) onRoomClick(it) },
            onAddFriendClick = { if (firstThrottler.canHandle()) onAddFriendClick() },
            onScanFriendClick = { if (firstThrottler.canHandle()) onScanFriendClick() },
            onMyQrCodeClick = { if (firstThrottler.canHandle()) onMyQrCodeClick() },
            onInviteNewUserClick = { if (firstThrottler.canHandle()) onInviteNewUserClick() },
            onFriendClick = { if (firstThrottler.canHandle()) onFriendClick(it) },
            onCreateRoomClick = { if (firstThrottler.canHandle()) onCreateRoomClick() },
            onCreateSpaceClick = { if (firstThrottler.canHandle()) onCreateSpaceClick() },
            onMenuActionClick = onMenuActionClick,
            onMoveToBackground = onMoveToBackground,
            settingsRootContent = settingsRootContent,
        )
        // This overlaid view will only be visible when state.displaySearchResults is true
        RoomListSearchView(
            state = state.searchState,
            eventSink = state.eventSink,
            hideInvitesAvatars = state.hideInvitesAvatars,
            onRoomClick = { if (firstThrottler.canHandle()) onRoomClick(it) },
            onFriendClick = { if (firstThrottler.canHandle()) onFriendClick(it) },
            modifier = Modifier
                .fillMaxSize()
                .background(ElementTheme.colors.bgCanvasDefault)
        )
        acceptDeclineInviteView()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScaffold(
    state: HomeState,
    discoveryAvailable: Boolean,
    onDiscoveryClick: () -> Unit,
    onSetUpRecoveryClick: () -> Unit,
    onConfirmRecoveryKeyClick: () -> Unit,
    onRoomClick: (RoomId) -> Unit,
    onAddFriendClick: () -> Unit,
    onScanFriendClick: () -> Unit,
    onMyQrCodeClick: () -> Unit,
    onInviteNewUserClick: () -> Unit,
    onFriendClick: (UserId) -> Unit,
    onCreateRoomClick: () -> Unit,
    onCreateSpaceClick: () -> Unit,
    onMenuActionClick: (RoomListMenuAction) -> Unit,
    onMoveToBackground: () -> Unit,
    settingsRootContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun onRoomClick(room: RoomListRoomSummary) {
        onRoomClick(room.roomId)
    }

    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(appBarState)
    val snackbarHostState = rememberSnackbarHostState(snackbarMessage = state.snackbarMessage)
    val roomListState: RoomListState = state.roomListState
    val notificationState = roomListState.copy(notifications = visibleNotifications(
        roomListState.notifications, roomListState.contactsSummaries.dataOrNull().orEmpty(), state.isDeletedMatrixUserId
    ).toImmutableList())
    val unreadSummary = roomListState.unreadSummary
    val unreadCount = unreadSummary.joinedRoomUnreadCount
    var notificationsOpen by rememberSaveable { mutableStateOf(false) }
    var notificationKind by rememberSaveable { mutableStateOf("ALL") }
    var contactsPage by rememberSaveable { mutableStateOf(ContactsPage.Directory) }
    val isSystemMessagesPage = roomListState.filtersState.selectedFilters().contains(RoomListFilter.Invites)
    val isCollapsibleRoomFilterVisible = state.currentHomeNavigationBarItem == HomeNavigationBarItem.Chats &&
        state.displayRoomListFilters &&
        !isSystemMessagesPage

    BackHandler {
        if (isSystemMessagesPage) {
            roomListState.filtersState.eventSink(RoomListFiltersEvent.ClearSelectedFilters)
        } else if (state.currentHomeNavigationBarItem == HomeNavigationBarItem.Contacts && contactsPage != ContactsPage.Directory) {
            contactsPage = ContactsPage.Directory
        } else if (state.currentHomeNavigationBarItem != HomeNavigationBarItem.Chats) {
            state.eventSink(HomeEvent.SelectHomeNavigationBarItem(HomeNavigationBarItem.Chats))
        } else {
            val spaceFiltersState = state.roomListState.spaceFiltersState
            if (spaceFiltersState is SpaceFiltersState.Selected) {
                spaceFiltersState.eventSink(SpaceFiltersEvent.Selected.ClearSelection)
            } else {
                onMoveToBackground()
            }
        }
    }

    val roomsLazyListState = rememberLazyListState()
    val spacesLazyListState = rememberLazyListState()
    val tabStateHolder = rememberSaveableStateHolder()
    val contactsDirectoryData = rememberContactsDirectoryData(state)

    if (notificationsOpen || isSystemMessagesPage) {
        NotificationCenterView(
            state = notificationState,
            initialKind = notificationKind,
            onBack = {
                notificationsOpen = false
                if (isSystemMessagesPage) roomListState.filtersState.eventSink(RoomListFiltersEvent.ClearSelectedFilters)
            },
            onRoomClick = onRoomClick,
            onFriendClick = onFriendClick,
            displayName = state::friendDisplayName,
            isDeletedUser = state.isDeletedMatrixUserId,
        )
        return
    }

    Scaffold(
        // An enter-always connection without its collapsible filter layout has no finite height
        // limit and consumes every upward drag before the active LazyColumn can scroll.
        modifier = if (isCollapsibleRoomFilterVisible) {
            modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        } else {
            modifier
        },
        topBar = {
            if (state.currentHomeNavigationBarItem != HomeNavigationBarItem.Me) {
                HomeTopBar(
                    selectedNavigationItem = state.currentHomeNavigationBarItem,
                    currentUserAndNeighbors = state.currentUserAndNeighbors,
                    showAvatarIndicator = state.showAvatarIndicator,
                    areSearchResultsDisplayed = roomListState.searchState.isSearchActive,
                    onToggleSearch = { roomListState.eventSink(RoomListEvent.ToggleSearchResults) },
                    onMenuActionClick = onMenuActionClick,
                    onOpenSettings = {
                        state.eventSink(HomeEvent.SelectHomeNavigationBarItem(HomeNavigationBarItem.Me))
                    },
                    onAccountSwitch = {
                        state.eventSink(HomeEvent.SwitchToAccount(it))
                    },
                    scrollBehavior = scrollBehavior,
                    displayFilters = isCollapsibleRoomFilterVisible,
                    filtersState = roomListState.filtersState,
                    spaceFiltersState = roomListState.spaceFiltersState,
                    canReportBug = state.canReportBug,
                    unreadCount = unreadCount,
                    notificationUnreadCount = notificationState.notifications.count { !it.seen },
                    onOpenNotifications = { notificationKind = "ALL"; notificationsOpen = true },
                    isSystemMessagesPage = isSystemMessagesPage,
                    onSystemMessagesBack = { roomListState.filtersState.eventSink(RoomListFiltersEvent.ClearSelectedFilters) },
                    contactsPage = contactsPage,
                    onContactsBack = { contactsPage = ContactsPage.Directory },
                    onAddFriendClick = onAddFriendClick,
                    onScanFriendClick = onScanFriendClick,
                    onMyQrCodeClick = onMyQrCodeClick,
                    onInviteNewUserClick = onInviteNewUserClick,
                    onCreateRoomClick = onCreateRoomClick,
                    onCreateSpaceClick = onCreateSpaceClick,
                    modifier = Modifier.background(ElementTheme.colors.bgCanvasDefault)
                )
            }
        },
        bottomBar = {
            val coroutineScope = rememberCoroutineScope()
            HomeBottomBar(
                discoveryAvailable = discoveryAvailable,
                onDiscoveryClick = onDiscoveryClick,
                currentHomeNavigationBarItem = state.currentHomeNavigationBarItem,
                unreadCount = unreadCount,
                contactsUnreadCount = notificationState.notifications.count { !it.seen },
                onItemClick = { item ->
                    if (item != HomeNavigationBarItem.Contacts) {
                        contactsPage = ContactsPage.Directory
                    }
                    // scroll to top if selecting the same item
                    if (item == state.currentHomeNavigationBarItem) {
                        val lazyListStateTarget = when (item) {
                            HomeNavigationBarItem.Chats -> roomsLazyListState
                            HomeNavigationBarItem.Contacts -> spacesLazyListState
                            HomeNavigationBarItem.Me -> null
                        }
                        if (lazyListStateTarget != null) {
                            coroutineScope.launch {
                                if (lazyListStateTarget.firstVisibleItemIndex > 10) {
                                    lazyListStateTarget.scrollToItem(10)
                                }
                                // Also reset the scrollBehavior height offset as it's not triggered by programmatic scrolls
                                scrollBehavior.state.heightOffset = 0f
                                lazyListStateTarget.animateScrollToItem(0)
                            }
                        }
                    } else {
                        state.eventSink(HomeEvent.SelectHomeNavigationBarItem(item))
                    }
                },
            )
        },
        content = { padding ->
            val contentPadding = PaddingValues(bottom = 8.dp)
            when (state.currentHomeNavigationBarItem) {
                HomeNavigationBarItem.Chats -> {
                    RoomListContentView(
                        contentState = roomListState.contentState,
                        unreadSummary = unreadSummary,
                        filtersState = roomListState.filtersState,
                        spaceFiltersState = roomListState.spaceFiltersState,
                        lazyListState = roomsLazyListState,
                        hideInvitesAvatars = roomListState.hideInvitesAvatars,
                        eventSink = roomListState.eventSink,
                        syncState = state.syncState,
                        isRefreshing = state.isRefreshing,
                        onRetrySync = { state.eventSink(HomeEvent.RetrySync) },
                        onSetUpRecoveryClick = onSetUpRecoveryClick,
                        onConfirmRecoveryKeyClick = onConfirmRecoveryKeyClick,
                        onRoomClick = ::onRoomClick,
                        roomDisplayName = { room -> state.friendDisplayName(room) },
                        onCreateRoomClick = onCreateRoomClick,
                        contentPadding = contentPadding,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                PaddingValues(
                                    start = padding.calculateStartPadding(LocalLayoutDirection.current),
                                    end = padding.calculateEndPadding(LocalLayoutDirection.current),
                                    // Remove these two lines once https://issuetracker.google.com/issues/436432313 has been fixed
                                    bottom = padding.calculateBottomPadding(),
                                    top = padding.calculateTopPadding()
                                )
                            )
                            .consumeWindowInsets(padding)
                    )
                    SpaceFiltersView(roomListState.spaceFiltersState)
                }
                HomeNavigationBarItem.Contacts -> ContactsView(
                    state = state,
                    directoryData = contactsDirectoryData,
                    page = contactsPage,
                    contentPadding = padding,
                    lazyListState = spacesLazyListState,
                    onAddFriendClick = onAddFriendClick,
                    onPageChange = {
                        if (it == ContactsPage.FriendRequests) {
                            notificationKind = "ALL"
                            notificationsOpen = true
                        } else contactsPage = it
                    },
                    onRoomClick = onRoomClick,
                    onFriendClick = onFriendClick,
                )
                HomeNavigationBarItem.Me -> SettingsTabRoot(
                    contentPadding = padding,
                    stateHolder = tabStateHolder,
                    settingsRootContent = settingsRootContent,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    )
}

@Composable
private fun HomeBottomBar(
    discoveryAvailable: Boolean,
    onDiscoveryClick: () -> Unit,
    currentHomeNavigationBarItem: HomeNavigationBarItem,
    unreadCount: Long,
    contactsUnreadCount: Int,
    onItemClick: (HomeNavigationBarItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemColors = NavigationBarItemDefaults.colors().copy(
        selectedIconColor = ElementTheme.colors.iconAccentPrimary,
        selectedTextColor = ElementTheme.colors.textActionAccent,
        selectedIndicatorColor = Color.Transparent,
        unselectedIconColor = ElementTheme.colors.iconSecondary,
        unselectedTextColor = ElementTheme.colors.textSecondary,
    )
    NavigationBar(modifier = modifier) {
        HomeNavigationBarItem.entries.forEach { item ->
            if (item == HomeNavigationBarItem.Me && discoveryAvailable) {
                NavigationBarItem(
                    icon = { Icon(imageVector = CompoundIcons.Explore(), contentDescription = null) },
                    label = { Text(stringResource(R.string.screen_home_discovery)) },
                    selected = false,
                    onClick = onDiscoveryClick,
                    colors = itemColors,
                )
            }
            val isSelected = currentHomeNavigationBarItem == item
            NavigationBarItem(
                icon = {
                    NavigationBarIcon(
                        imageVector = item.icon(isSelected),
                        count = when (item) {
                            HomeNavigationBarItem.Chats -> unreadCount.coerceAtMost(100).toInt()
                            HomeNavigationBarItem.Contacts -> contactsUnreadCount.coerceAtMost(100)
                            HomeNavigationBarItem.Me -> 0
                        },
                        isCritical = when (item) {
                            HomeNavigationBarItem.Chats -> unreadCount > 0
                            HomeNavigationBarItem.Contacts -> contactsUnreadCount > 0
                            HomeNavigationBarItem.Me -> false
                        },
                    )
                },
                label = { Text(stringResource(item.labelRes)) },
                selected = isSelected,
                onClick = { onItemClick(item) },
                colors = itemColors,
            )
        }
    }
}

enum class ContactsPage {
    Directory,
    FriendRequests,
    Groups,
    Spaces,
}

/**
 * Keeps the expensive ICU transliterator initialization and contact sorting away from the UI
 * thread. The projection starts while the Chats tab is visible, and the last completed value is
 * retained while an updated room/profile snapshot is being rebuilt.
 */
@Composable
private fun rememberContactsDirectoryData(state: HomeState): ContactsDirectoryData {
    val source = state.roomListState.contactsSummaries
    val summaries = source.dataOrNull().orEmpty()
    val visibleSummaries = remember(summaries, state.isDeletedMatrixUserId) {
        summaries.filterNot { it.referencesDeletedDirectPeer(state.isDeletedMatrixUserId) }
    }
    val displayNames = remember(visibleSummaries, state.friendProfiles) {
        visibleSummaries.associateWith(state::friendDisplayName)
    }
    val directoryData by produceState(
        initialValue = ContactsDirectoryData(isLoading = true),
        key1 = visibleSummaries,
        key2 = displayNames,
        key3 = source.isSuccess(),
    ) {
        if (!source.isSuccess()) {
            value = ContactsDirectoryData(isLoading = true)
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            buildContactsDirectoryData(
                summaries = visibleSummaries,
                displayName = displayNames::getValue,
                sortKey = ::contactSortKey,
            )
        }
    }
    return directoryData
}

@Composable
private fun ContactsView(
    state: HomeState,
    directoryData: ContactsDirectoryData,
    page: ContactsPage,
    contentPadding: PaddingValues,
    lazyListState: androidx.compose.foundation.lazy.LazyListState,
    onAddFriendClick: () -> Unit,
    onPageChange: (ContactsPage) -> Unit,
    onRoomClick: (RoomId) -> Unit,
    onFriendClick: (UserId) -> Unit,
) {
    val notices = visibleNotifications(state.roomListState.notifications,
        state.roomListState.contactsSummaries.dataOrNull().orEmpty(), state.isDeletedMatrixUserId)
    val unreadSummary = state.roomListState.unreadSummary
    val friendSections = directoryData.friendSections
    val sectionIndices = directoryData.sectionIndices
    val contactListScope = rememberCoroutineScope()
    var contactIndexScrollJob by remember { mutableStateOf<Job?>(null) }
    val groups = directoryData.groups

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding),
    ) {
        if (directoryData.isLoading && page != ContactsPage.Spaces) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        when (page) {
            ContactsPage.Directory -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    item {
                        HomeActionRow(
                            icon = CompoundIcons.UserAdd(),
                            iconBackground = ElementTheme.colors.bgAccentRest,
                            title = stringResource(R.string.screen_home_new_friends),
                            subtitle = notices.count { !it.outgoing && it.outcome == io.element.android.features.invite.api.InviteNotification.Outcome.PENDING }.takeIf { it > 0 }?.let {
                                stringResource(R.string.screen_home_pending_requests, it)
                            },
                            badgeCount = notices.count { !it.seen },
                            onClick = { onPageChange(ContactsPage.FriendRequests) },
                        )
                        HomeActionRow(
                            icon = CompoundIcons.UserProfile(),
                            iconBackground = ElementTheme.colors.bgAccentRest,
                            title = stringResource(R.string.screen_home_groups),
                            onClick = { onPageChange(ContactsPage.Groups) },
                        )
                        HomeActionRow(
                            icon = CompoundIcons.Space(),
                            iconBackground = ElementTheme.colors.bgAccentRest,
                            title = stringResource(R.string.screen_home_spaces),
                            onClick = { onPageChange(ContactsPage.Spaces) },
                        )
                    }
                    if (friendSections.isEmpty()) {
                        item { EmptyDirectoryLabel(stringResource(R.string.screen_home_no_friends)) }
                    } else {
                        friendSections.forEach { (section, sectionFriends) ->
                            item(key = "section_$section") { DirectorySectionHeader(section) }
                            items(sectionFriends, key = { it.room.id }) { friend ->
                                val userId = friend.room.heroes.firstOrNull()?.id?.let(::UserId)
                                DirectoryRoomRow(
                                    room = friend.room,
                                    avatarType = AvatarType.User,
                                    onRoomClick = onRoomClick,
                                    displayName = friend.displayName,
                                    onClick = userId?.let { { onFriendClick(it) } },
                                )
                            }
                        }
                    }
                }
                AlphabetIndex(
                    onEntrySelected = { entry ->
                        val index = contactSectionTargetIndex(entry, sectionIndices)
                        contactIndexScrollJob?.cancel()
                        contactIndexScrollJob = contactListScope.launch {
                            lazyListState.scrollToItem(index)
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            ContactsPage.FriendRequests -> Unit
            ContactsPage.Groups -> {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                    if (groups.isEmpty()) {
                        item { EmptyDirectoryLabel(stringResource(R.string.screen_home_no_groups)) }
                    } else {
                        items(groups, key = { it.id }) { room ->
                            DirectoryRoomRow(
                                room = room,
                                avatarType = AvatarType.Room(heroes = room.heroes, isTombstoned = room.isTombstoned),
                                onRoomClick = onRoomClick,
                            )
                        }
                    }
                }
            }
            ContactsPage.Spaces -> {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                    if (state.homeSpacesState.spaceRooms.isEmpty()) {
                        item { EmptyDirectoryLabel(stringResource(R.string.screen_home_no_spaces)) }
                    } else {
                        items(state.homeSpacesState.spaceRooms, key = { it.roomId.value }) { space ->
                            DirectorySpaceRow(space = space, onRoomClick = onRoomClick)
                        }
                    }
                }
            }
        }
    }
}

private fun HomeState.friendDisplayName(room: RoomListRoomSummary): String {
    return contactDisplayName(room, friendProfiles)
}

private val contactNameTransliterator by lazy {
    Transliterator.getInstance("Han-Latin/Names; Latin-ASCII")
}

private val contactNameTransliteratorLock = Any()

internal fun contactSortKey(displayName: String): String {
    val trimmed = displayName.trim()
    // Plain names need no transliteration. Keep punctuation on the original ICU path:
    // Han-Latin/Names can insert spaces around punctuation even in ASCII identifiers.
    if (trimmed.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == ' ' || it == '_' }) {
        return trimmed.lowercase()
    }
    return synchronized(contactNameTransliteratorLock) {
        contactNameTransliterator.transliterate(trimmed).lowercase()
    }
}

@Composable
private fun DirectorySectionHeader(title: String) {
    Text(
        text = title,
        style = ElementTheme.typography.fontBodySmMedium,
        color = ElementTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(ElementTheme.colors.bgSubtleSecondary)
            .padding(horizontal = 20.dp, vertical = 5.dp),
    )
}

@Composable
private fun DirectoryRoomRow(
    room: RoomListRoomSummary,
    avatarType: AvatarType,
    onRoomClick: (RoomId) -> Unit,
    displayName: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable { onClick?.invoke() ?: onRoomClick(room.roomId) }
            .padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            avatarData = displayName?.let { room.avatarData.copy(name = it) } ?: room.avatarData,
            avatarType = avatarType,
            forcedAvatarSize = 42.dp,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = displayName ?: room.name ?: room.roomId.value,
            style = ElementTheme.typography.fontBodyLgRegular,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 76.dp))
}

@Composable
private fun DirectorySpaceRow(space: SpaceRoom, onRoomClick: (RoomId) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable { onRoomClick(space.roomId) }
            .padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            avatarData = space.getAvatarData(AvatarSize.RoomListItem),
            avatarType = AvatarType.Space(isTombstoned = false),
            forcedAvatarSize = 42.dp,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = space.displayName,
            style = ElementTheme.typography.fontBodyLgRegular,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 76.dp))
}

@Composable
private fun EmptyDirectoryLabel(text: String) {
    Text(
        text = text,
        style = ElementTheme.typography.fontBodyMdRegular,
        color = ElementTheme.colors.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}

@Composable
private fun AlphabetIndex(
    onEntrySelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedEntry by remember { mutableStateOf<String?>(null) }
    val currentOnEntrySelected by rememberUpdatedState(onEntrySelected)

    fun selectEntry(entry: String) {
        if (entry != selectedEntry) {
            selectedEntry = entry
            currentOnEntrySelected(entry)
        }
    }

    Box(
        modifier = modifier.width(104.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        selectedEntry?.let { entry ->
            Box(
                modifier = Modifier
                    .padding(end = 36.dp)
                    .size(64.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(ElementTheme.colors.bgSubtleSecondary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry,
                    color = ElementTheme.colors.textPrimary,
                    style = ElementTheme.typography.fontHeadingLgBold,
                )
            }
        }

        Column(
            modifier = Modifier
                .padding(end = 3.dp)
                .width(28.dp)
                .pointerInput(Unit) {
                    fun entryAt(y: Float): String {
                        val entryHeight = size.height.toFloat() / contactAlphabetEntries.size
                        val index = (y / entryHeight).toInt().coerceIn(contactAlphabetEntries.indices)
                        return contactAlphabetEntries[index]
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        selectEntry(entryAt(down.position.y))
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            selectEntry(entryAt(change.position.y))
                            change.consume()
                        }
                        selectedEntry = null
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            contactAlphabetEntries.forEach { entry ->
                val isSelected = entry == selectedEntry
                Text(
                    text = entry,
                    color = if (isSelected) Color.White else ElementTheme.colors.textPrimary,
                    style = ElementTheme.typography.fontBodySmRegular.copy(
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(20.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isSelected) ElementTheme.colors.bgAccentRest else Color.Transparent)
                        .clickable { currentOnEntrySelected(entry) },
                )
            }
        }
    }
}

private val contactAlphabetEntries = listOf("↑", "☆") + ('A'..'Z').map(Char::toString) + "#"

internal fun contactSectionTargetIndex(entry: String, sectionIndices: Map<String, Int>): Int {
    if (entry == "↑") return 0
    if (entry == "☆") return sectionIndices.values.firstOrNull() ?: 0
    sectionIndices[entry]?.let { return it }

    val targetPosition = contactAlphabetEntries.indexOf(entry).takeIf { it >= 0 } ?: return 0
    contactAlphabetEntries.drop(targetPosition + 1).firstNotNullOfOrNull(sectionIndices::get)?.let { return it }
    return contactAlphabetEntries.take(targetPosition).asReversed().firstNotNullOfOrNull(sectionIndices::get) ?: 0
}

@Composable
private fun SettingsTabRoot(
    contentPadding: PaddingValues,
    stateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    settingsRootContent: @Composable (Modifier) -> Unit,
) {
    stateHolder.SaveableStateProvider("settings-root") {
        settingsRootContent(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
        )
    }
}

@Composable
private fun HomeActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    iconBackground: Color? = null,
    badgeCount: Int = 0,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(iconBackground ?: Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(25.dp),
                tint = if (iconBackground == null) ElementTheme.colors.iconPrimary else ElementTheme.colors.iconOnSolidPrimary,
            )
        }
        Spacer(modifier = Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = ElementTheme.typography.fontBodyLgMedium)
            if (subtitle != null) {
                Text(text = subtitle, style = ElementTheme.typography.fontBodyMdRegular, color = ElementTheme.colors.textSecondary)
            }
        }
        if (badgeCount > 0) {
            UnreadIndicatorAtom(
                count = badgeCount.coerceAtMost(100).toLong(),
                color = ElementTheme.colors.bgCriticalPrimary,
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 76.dp))
}

internal fun RoomListRoomSummary.contentType() = displayType.ordinal

@PreviewsDayNight
@Composable
internal fun HomeViewPreview(@PreviewParameter(HomeStateProvider::class) state: HomeState) = ElementPreview {
    HomeView(
        homeState = state,
        onRoomClick = {},
        onSetUpRecoveryClick = {},
        onConfirmRecoveryKeyClick = {},
        onAddFriendClick = {},
        onCreateRoomClick = {},
        onCreateSpaceClick = {},
        onRoomSettingsClick = {},
        onReportRoomClick = {},
        onMenuActionClick = {},
        onDeclineInviteAndBlockUser = {},
        onMoveToBackground = {},
        acceptDeclineInviteView = {},
        leaveRoomView = {}
    )
}

@Preview
@Composable
internal fun HomeViewA11yPreview() = ElementPreview {
    HomeView(
        homeState = aHomeState(),
        onRoomClick = {},
        onSetUpRecoveryClick = {},
        onConfirmRecoveryKeyClick = {},
        onAddFriendClick = {},
        onCreateRoomClick = {},
        onCreateSpaceClick = {},
        onRoomSettingsClick = {},
        onReportRoomClick = {},
        onMenuActionClick = {},
        onDeclineInviteAndBlockUser = {},
        onMoveToBackground = {},
        acceptDeclineInviteView = {},
        leaveRoomView = {}
    )
}
