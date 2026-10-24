/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.roomlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.zacsweers.metro.Inject
import im.vector.app.features.analytics.plan.Interaction
import io.element.android.features.announcement.api.Announcement
import io.element.android.features.announcement.api.AnnouncementService
import io.element.android.features.home.impl.datasource.RoomListDataSource
import io.element.android.features.home.impl.datasource.contactsRoomSummaries
import io.element.android.features.home.impl.filters.RoomListFilter.Rooms
import io.element.android.features.home.impl.filters.RoomListFiltersState
import io.element.android.features.home.impl.filters.into
import io.element.android.features.home.impl.notifications.reconciledOutcome
import io.element.android.features.home.impl.notifications.toNotification
import io.element.android.features.home.impl.search.RoomListSearchEvent
import io.element.android.features.home.impl.search.RoomListSearchState
import io.element.android.features.home.impl.spacefilters.SpaceFiltersState
import io.element.android.features.home.impl.spacefilters.into
import io.element.android.features.home.impl.spacefilters.selectedFilter
import io.element.android.features.invite.api.SeenInvitesStore
import io.element.android.features.invite.api.acceptdecline.AcceptDeclineInviteEvents.AcceptInvite
import io.element.android.features.invite.api.acceptdecline.AcceptDeclineInviteEvents.DeclineInvite
import io.element.android.features.invite.api.acceptdecline.AcceptDeclineInviteState
import io.element.android.features.leaveroom.api.LeaveRoomEvent
import io.element.android.features.leaveroom.api.LeaveRoomState
import io.element.android.features.messages.api.MessageReferenceStore
import io.element.android.features.messages.api.draft.ComposerDraftPreviewService
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.fullscreenintent.api.FullScreenIntentPermissionsState
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.encryption.RecoveryState
import io.element.android.libraries.matrix.api.notificationsettings.NotificationSettingsService
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.roomlist.RoomList
import io.element.android.libraries.matrix.api.roomlist.RoomListFilter
import io.element.android.libraries.matrix.api.roomlist.RoomSummary
import io.element.android.libraries.matrix.api.timeline.ReceiptType
import io.element.android.libraries.matrix.ui.safety.rememberHideInvitesAvatar
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.push.api.battery.BatteryOptimizationState
import io.element.android.libraries.push.api.notifications.NotificationCleaner
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.security.securestorage.api.SecureStorageException
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.analytics.api.watchers.AnalyticsColdStartWatcher
import io.element.android.services.analyticsproviders.api.trackers.captureInteraction
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import timber.log.Timber

@Inject
class RoomListPresenter(
    private val client: MatrixClient,
    private val leaveRoomPresenter: Presenter<LeaveRoomState>,
    private val roomListDataSource: RoomListDataSource,
    private val filtersPresenter: Presenter<RoomListFiltersState>,
    private val searchPresenter: Presenter<RoomListSearchState>,
    private val sessionPreferencesStore: SessionPreferencesStore,
    private val analyticsService: AnalyticsService,
    private val acceptDeclineInvitePresenter: Presenter<AcceptDeclineInviteState>,
    private val fullScreenIntentPermissionsPresenter: Presenter<FullScreenIntentPermissionsState>,
    private val batteryOptimizationPresenter: Presenter<BatteryOptimizationState>,
    private val notificationCleaner: NotificationCleaner,
    private val appPreferencesStore: AppPreferencesStore,
    private val seenInvitesStore: SeenInvitesStore,
    private val announcementService: AnnouncementService,
    private val coldStartWatcher: AnalyticsColdStartWatcher,
    private val spaceFiltersPresenter: Presenter<SpaceFiltersState>,
    private val featureFlagService: FeatureFlagService,
    private val draftPreviewService: ComposerDraftPreviewService,
    private val notificationSettingsService: NotificationSettingsService,
    private val messageReferenceStore: MessageReferenceStore,
    private val staleRoomReconciler: StaleRoomReconciler,
    private val protectedPreviewAuthorizationGate: ProtectedPreviewAuthorizationGate,
) : Presenter<RoomListState> {
    private val encryptionService = client.encryptionService

    @Composable
    override fun present(): RoomListState {
        val coroutineScope = rememberCoroutineScope()
        val leaveRoomState = leaveRoomPresenter.present()
        val filtersState = filtersPresenter.present()
        val searchState = searchPresenter.present()
        val spaceFiltersState = spaceFiltersPresenter.present()
        val acceptDeclineInviteState = acceptDeclineInvitePresenter.present()
        val draftPreviews by draftPreviewService.previews.collectAsState()
        val messageReferenceState by messageReferenceStore.state.collectAsState()
        val allRooms = remember(client) { client.roomListService.allRooms }
        val allRoomSummaries by produceState<List<RoomSummary>?>(initialValue = null, key1 = allRooms) {
            allRooms.summaries.collect { value = it }
        }
        val allRoomsLoadingState by allRooms.loadingState.collectAsState()
        val contactsSummaries = remember(allRoomSummaries, allRoomsLoadingState) {
            contactsRoomSummaries(allRoomSummaries, allRoomsLoadingState)
        }
        val previewAccess by protectedPreviewAuthorizationGate.accessState.collectAsState()
        val authorizationRevision by protectedPreviewAuthorizationGate.authorizationRevision.collectAsState()
        val notificationRecords by key(previewAccess, authorizationRevision) {
            remember {
                if (previewAccess.allowsPreviewAttempt) seenInvitesStore.notifications() else flowOf(emptyList())
            }.collectAsState(emptyList())
        }
        var inviteActionsInPlace by remember { mutableStateOf(false) }
        LaunchedEffect(contactsSummaries, previewAccess, authorizationRevision) {
            val rooms = (contactsSummaries as? AsyncData.Success)?.data ?: return@LaunchedEffect
            updateProtectedNotificationProjection(protectedPreviewAuthorizationGate) {
                seenInvitesStore.observeNotifications(rooms.mapNotNull { it.toNotification() }, completeSnapshot = true)
                seenInvitesStore.updateNotificationProfiles(rooms.associate { it.roomId to (it.name to it.avatarData.url) })
                seenInvitesStore.updateNotificationKinds(rooms.associate { room -> room.roomId to when {
                    room.isSpace -> io.element.android.features.invite.api.InviteNotification.Kind.SPACE
                    room.isDm -> io.element.android.features.invite.api.InviteNotification.Kind.FRIEND
                    else -> io.element.android.features.invite.api.InviteNotification.Kind.GROUP
                } })
            }
        }
        LaunchedEffect(contactsSummaries, allRoomSummaries, notificationRecords, previewAccess, authorizationRevision) {
            val rooms = (contactsSummaries as? AsyncData.Success)?.data ?: return@LaunchedEffect
            updateProtectedNotificationProjection(protectedPreviewAuthorizationGate) {
                notificationRecords.forEach { record ->
                    val membership = allRoomSummaries?.firstOrNull { it.roomId == record.roomId }?.info?.currentUserMembership
                    val ended = membership == io.element.android.libraries.matrix.api.room.CurrentUserMembership.LEFT ||
                        membership == io.element.android.libraries.matrix.api.room.CurrentUserMembership.BANNED
                    val outcome = if (record.outcome == io.element.android.features.invite.api.InviteNotification.Outcome.PENDING && ended) {
                        io.element.android.features.invite.api.InviteNotification.Outcome.UNAVAILABLE
                    } else reconciledOutcome(record, rooms.firstOrNull { it.roomId == record.roomId })
                    outcome?.let {
                        seenInvitesStore.recordOutcome(record.roomId, it, seen = false, expectedSequence = record.sequence)
                    }
                }
            }
        }
        val seenRoomInvites by remember { seenInvitesStore.seenRoomIds() }.collectAsState(emptySet())
        val unreadSummary = remember(allRoomSummaries, seenRoomInvites) {
            calculateHomeUnreadSummary(allRoomSummaries.orEmpty(), seenRoomInvites)
        }

        LaunchedEffect(Unit) {
            roomListDataSource.launchIn(this)
            messageReferenceStore.load()
        }

        LaunchedEffect(Unit) {
            roomListDataSource.roomSummariesFlow.first { it.isNotEmpty() }
            staleRoomReconciler()
        }

        var securityBannerDismissed by rememberSaveable { mutableStateOf(false) }
        val showNewNotificationSoundBanner by remember {
            announcementService.announcementsToShowFlow().map { announcements ->
                announcements.contains(Announcement.NewNotificationSound)
            }
        }.collectAsState(false)

        // Avatar indicator
        val hideInvitesAvatar by client.rememberHideInvitesAvatar()

        val contextMenu = remember { mutableStateOf<RoomListState.ContextMenu>(RoomListState.ContextMenu.Hidden) }
        val declineInviteMenu = remember { mutableStateOf<RoomListState.DeclineInviteMenu>(RoomListState.DeclineInviteMenu.Hidden) }

        fun handleEvent(event: RoomListEvent) {
            when (event) {
                is RoomListEvent.UpdateVisibleRange -> coroutineScope.launch {
                    roomListDataSource.updateVisibleRange(event.range)
                }
                is RoomListEvent.UpdatePreviewRooms -> roomListDataSource.updatePreviewRooms(event.roomIds)
                RoomListEvent.DismissRequestVerificationPrompt -> securityBannerDismissed = true
                RoomListEvent.DismissBanner -> securityBannerDismissed = true
                RoomListEvent.DismissNewNotificationSoundBanner -> coroutineScope.launch {
                    announcementService.onAnnouncementDismissed(Announcement.NewNotificationSound)
                }
                RoomListEvent.ToggleSearchResults -> searchState.eventSink(RoomListSearchEvent.ToggleSearchVisibility)
                is RoomListEvent.ShowContextMenu -> {
                    coroutineScope.showContextMenu(event, contextMenu)
                }
                is RoomListEvent.HideContextMenu -> {
                    contextMenu.value = RoomListState.ContextMenu.Hidden
                }
                is RoomListEvent.LeaveRoom -> {
                    leaveRoomState.eventSink(LeaveRoomEvent.LeaveRoom(event.roomId, needsConfirmation = event.needsConfirmation))
                }
                is RoomListEvent.SetRoomIsFavorite -> coroutineScope.setRoomIsFavorite(event.roomId, event.isFavorite)
                is RoomListEvent.SetRoomMuted -> coroutineScope.setRoomMuted(event.roomId, event.isMuted)
                is RoomListEvent.MarkAsRead -> coroutineScope.markAsRead(event.roomId)
                is RoomListEvent.MarkAsUnread -> coroutineScope.markAsUnread(event.roomId)
                is RoomListEvent.AcceptInvite -> {
                    acceptDeclineInviteState.eventSink(
                        AcceptInvite(event.roomSummary.toInviteData())
                    )
                }
                is RoomListEvent.DeclineInvite -> {
                    acceptDeclineInviteState.eventSink(
                        DeclineInvite(event.roomSummary.toInviteData(), blockUser = event.blockUser, shouldConfirm = false)
                    )
                }
                is RoomListEvent.ShowDeclineInviteMenu -> declineInviteMenu.value = RoomListState.DeclineInviteMenu.Shown(event.roomSummary)
                RoomListEvent.HideDeclineInviteMenu -> declineInviteMenu.value = RoomListState.DeclineInviteMenu.Hidden
                is RoomListEvent.SetInviteActionsInPlace -> inviteActionsInPlace = event.enabled
                RoomListEvent.ResetInviteActions -> acceptDeclineInviteState.eventSink(
                    io.element.android.features.invite.api.acceptdecline.AcceptDeclineInviteEvents.ResetActions
                )
                is RoomListEvent.MarkInvitesAsSeen -> coroutineScope.launch {
                    updateProtectedNotificationProjection(protectedPreviewAuthorizationGate) {
                        event.roomIds.forEach { seenInvitesStore.markNotificationSeen(it) }
                    }
                }
                is RoomListEvent.DeleteConversation -> coroutineScope.deleteConversation(event.roomId, event.cutoffTimestamp)
            }
        }

        LaunchedEffect(filtersState.filterSelectionStates, spaceFiltersState.selectedFilter()) {
            val selectedFilters = filtersState.selectedFilters().map { filter -> filter.into() }
            val selectedSpaceFilter = spaceFiltersState.selectedFilter().into()
            val allFilters = RoomListFilter.All(selectedFilters + listOfNotNull(selectedSpaceFilter))
            roomListDataSource.updateFilter(allFilters)
        }

        val canReportRoom by produceState(false) { value = client.canReportRoom() }
        val showUnreadCount by produceState(true) {
            value = featureFlagService.isFeatureEnabled(FeatureFlags.UnreadIndicatorCount)
        }

        val contentState = roomListContentState(
            securityBannerDismissed,
            showNewNotificationSoundBanner,
            showUnreadCount,
            draftPreviews,
            messageReferenceState.conversationCutoffs,
            seenRoomInvites,
            filterMentions = filtersState.selectedFilters().contains(io.element.android.features.home.impl.filters.RoomListFilter.Mentions),
        )

        return RoomListState(
            contextMenu = contextMenu.value,
            declineInviteMenu = declineInviteMenu.value,
            leaveRoomState = leaveRoomState,
            filtersState = filtersState,
            searchState = searchState,
            spaceFiltersState = spaceFiltersState,
            contentState = contentState,
            unreadSummary = unreadSummary,
            notifications = notificationRecords.toImmutableList(),
            inviteActionsInPlace = inviteActionsInPlace,
            contactsSummaries = contactsSummaries,
            seenRoomInvites = seenRoomInvites.toImmutableSet(),
            acceptDeclineInviteState = acceptDeclineInviteState,
            hideInvitesAvatars = hideInvitesAvatar,
            canReportRoom = canReportRoom,
            eventSink = ::handleEvent,
        )
    }

    @Composable
    private fun rememberSecurityBannerState(
        securityBannerDismissed: Boolean,
    ): State<SecurityBannerState> {
        val currentSecurityBannerDismissed by rememberUpdatedState(securityBannerDismissed)
        val recoveryState by encryptionService.recoveryStateStateFlow.collectAsState()
        return remember {
            derivedStateOf {
                calculateBannerState(
                    securityBannerDismissed = currentSecurityBannerDismissed,
                    recoveryState = recoveryState,
                )
            }
        }
    }

    private fun calculateBannerState(
        securityBannerDismissed: Boolean,
        recoveryState: RecoveryState,
    ): SecurityBannerState {
        if (securityBannerDismissed) {
            return SecurityBannerState.None
        }

        when (recoveryState) {
            RecoveryState.DISABLED -> return SecurityBannerState.SetUpRecovery
            RecoveryState.INCOMPLETE -> return SecurityBannerState.RecoveryKeyConfirmation
            RecoveryState.UNKNOWN,
            RecoveryState.WAITING_FOR_SYNC,
            RecoveryState.ENABLED -> Unit
        }

        return SecurityBannerState.None
    }

    @Composable
    private fun roomListContentState(
        securityBannerDismissed: Boolean,
        showNewNotificationSoundBanner: Boolean,
        showUnreadCount: Boolean,
        draftPreviews: Map<RoomId, String>,
        conversationCutoffs: Map<String, Long>,
        seenRoomInvites: Set<RoomId>,
        filterMentions: Boolean,
    ): RoomListContentState {
        val roomSummaries by produceState(initialValue = AsyncData.Loading()) {
            roomListDataSource.roomSummariesFlow.collect { value = AsyncData.Success(it) }
        }
        val roomIds = roomSummaries.dataOrNull().orEmpty().map { it.roomId }
        LaunchedEffect(roomIds) {
            draftPreviewService.refresh(roomIds)
        }
        val loadingState by roomListDataSource.loadingState.collectAsState()
        val showEmpty by remember {
            derivedStateOf {
                (loadingState as? RoomList.LoadingState.Loaded)?.numberOfRooms == 0 &&
                    roomSummaries is AsyncData.Success && roomSummaries.dataOrNull().isNullOrEmpty()
            }
        }
        val showSkeleton by remember {
            derivedStateOf {
                roomSummaries is AsyncData.Loading ||
                    (loadingState == RoomList.LoadingState.NotLoaded && roomSummaries.dataOrNull().isNullOrEmpty())
            }
        }
        val securityBannerState by rememberSecurityBannerState(securityBannerDismissed)
        return when {
            showEmpty -> RoomListContentState.Empty(
                securityBannerState = securityBannerState,
                fullScreenIntentPermissionsState = fullScreenIntentPermissionsPresenter.present(),
                batteryOptimizationState = batteryOptimizationPresenter.present(),
                showNewNotificationSoundBanner = showNewNotificationSoundBanner,
            )
            showSkeleton -> RoomListContentState.Skeleton(count = 16)
            else -> {
                coldStartWatcher.onRoomListVisible()

                RoomListContentState.Rooms(
                    securityBannerState = securityBannerState,
                    showNewNotificationSoundBanner = showNewNotificationSoundBanner,
                    showUnreadCount = showUnreadCount,
                    fullScreenIntentPermissionsState = fullScreenIntentPermissionsPresenter.present(),
                    batteryOptimizationState = batteryOptimizationPresenter.present(),
                    summaries = roomSummaries.dataOrNull().orEmpty()
                        .asSequence()
                        .filter { !filterMentions || it.numberOfUnreadMentions > 0 }
                        .filter { summary ->
                            val cutoff = conversationCutoffs[summary.roomId.value]
                            cutoff == null || summary.latestEventTimestamp > cutoff
                        }
                        .map { summary -> summary.copy(draftPreview = draftPreviews[summary.roomId]) }
                        .sortedByDescending { it.isFavorite }
                        .toList()
                        .toImmutableList(),
                    seenRoomInvites = seenRoomInvites.toImmutableSet(),
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun CoroutineScope.showContextMenu(event: RoomListEvent.ShowContextMenu, contextMenuState: MutableState<RoomListState.ContextMenu>) = launch {
        val initialState = RoomListState.ContextMenu.Shown(
            roomId = event.roomSummary.roomId,
            roomName = event.displayName ?: event.roomSummary.name,
            isDm = event.roomSummary.isDm,
            isFavorite = event.roomSummary.isFavorite,
            isMuted = event.roomSummary.userDefinedNotificationMode == RoomNotificationMode.MUTE,
            hasNewContent = event.roomSummary.hasNewContent,
            latestEventTimestamp = event.roomSummary.latestEventTimestamp,
        )
        contextMenuState.value = initialState

        client.getRoom(event.roomSummary.roomId)?.use { room ->

            val isShowingContextMenuFlow = snapshotFlow { contextMenuState.value is RoomListState.ContextMenu.Shown }
                .distinctUntilChanged()

            val roomControlsFlow = room.roomInfoFlow
                .map { it.isFavorite to (it.userDefinedNotificationMode == RoomNotificationMode.MUTE) }
                .distinctUntilChanged()

            roomControlsFlow
                .onEach { (isFavorite, isMuted) ->
                    contextMenuState.value = initialState.copy(isFavorite = isFavorite, isMuted = isMuted)
                }
                .flatMapLatest { isShowingContextMenuFlow }
                .takeWhile { isShowingContextMenu -> isShowingContextMenu }
                .collect()
        }
    }

    private fun CoroutineScope.setRoomIsFavorite(roomId: RoomId, isFavorite: Boolean) = launch {
        client.getRoom(roomId)?.use { room ->
            room.setIsFavorite(isFavorite)
                .onSuccess {
                    analyticsService.captureInteraction(name = Interaction.Name.MobileRoomListRoomContextMenuFavouriteToggle)
                }
        }
    }

    private fun CoroutineScope.setRoomMuted(roomId: RoomId, isMuted: Boolean) = launch {
        if (isMuted) {
            notificationSettingsService.muteRoom(roomId)
        } else {
            notificationSettingsService.restoreDefaultRoomNotificationMode(roomId)
        }
    }

    private fun CoroutineScope.markAsRead(roomId: RoomId) = launch {
        notificationCleaner.clearMessagesForRoom(client.sessionId, roomId)
        client.getRoom(roomId)?.use { room ->
            room.setUnreadFlag(isUnread = false)
            val receiptType = if (sessionPreferencesStore.isSendPublicReadReceiptsEnabled().first()) {
                ReceiptType.READ
            } else {
                ReceiptType.READ_PRIVATE
            }
            room.markAsRead(receiptType)
                .onSuccess {
                    analyticsService.captureInteraction(name = Interaction.Name.MobileRoomListRoomContextMenuUnreadToggle)
                }
        }
    }

    private fun CoroutineScope.markAsUnread(roomId: RoomId) = launch {
        client.getRoom(roomId)?.use { room ->
            room.setUnreadFlag(isUnread = true)
                .onSuccess {
                    analyticsService.captureInteraction(name = Interaction.Name.MobileRoomListRoomContextMenuUnreadToggle)
                }
        }
    }

    private fun CoroutineScope.deleteConversation(roomId: RoomId, cutoffTimestamp: Long) = launch {
        messageReferenceStore.deleteConversation(roomId, cutoffTimestamp)
        client.getRoom(roomId)?.use { room ->
            room.clearEventCacheStorage()
        }
    }
}

/** Vault revocation can race a background room update. Keep the stored projection intact and retry on authorization refresh. */
internal suspend fun updateProtectedNotificationProjection(
    authorizationGate: ProtectedPreviewAuthorizationGate,
    update: suspend () -> Unit,
): Boolean {
    if (!authorizationGate.canAttemptPreview()) return false
    return try {
        update()
        true
    } catch (_: SecureStorageException) {
        // Never replace an unreadable encrypted history with an empty successful snapshot.
        Timber.w("Notification history update deferred because its protected storage is unavailable.")
        false
    }
}
