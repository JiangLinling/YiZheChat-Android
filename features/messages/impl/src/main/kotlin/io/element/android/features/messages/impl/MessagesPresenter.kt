/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import im.vector.app.features.analytics.plan.PinUnpinAction
import io.element.android.appconfig.MessageComposerConfig
import io.element.android.features.location.api.live.ActiveLiveLocationShareManager
import io.element.android.features.location.api.live.isCurrentlySharing
import io.element.android.features.messages.api.MessageReferenceState
import io.element.android.features.messages.api.MessageReferenceStore
import io.element.android.features.messages.api.timeline.HtmlConverterProvider
import io.element.android.features.messages.impl.MessagesState.Threads
import io.element.android.features.messages.impl.actionlist.ActionListState
import io.element.android.features.messages.impl.actionlist.model.TimelineItemAction
import io.element.android.features.messages.impl.crypto.identity.IdentityChangeState
import io.element.android.features.messages.impl.link.LinkState
import io.element.android.features.messages.impl.messagecomposer.MessageComposerEvent
import io.element.android.features.messages.impl.messagecomposer.MessageComposerState
import io.element.android.features.messages.impl.messagecomposer.requiresAuthorizedReplyProjection
import io.element.android.features.messages.impl.pinned.banner.PinnedMessagesBannerState
import io.element.android.features.messages.impl.timeline.MESSAGE_REMOVAL_DESTRUCTION_MILLIS
import io.element.android.features.messages.impl.timeline.MarkAsFullyRead
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.TimelineState
import io.element.android.features.messages.impl.timeline.components.customreaction.CustomReactionState
import io.element.android.features.messages.impl.timeline.components.reactionsummary.ReactionSummaryState
import io.element.android.features.messages.impl.timeline.components.receipt.bottomsheet.ReadReceiptBottomSheetState
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.TimelineItemThreadInfo
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContentWithAttachment
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemPollContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemStateContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.features.messages.impl.timeline.model.event.canBeCopied
import io.element.android.features.messages.impl.timeline.protection.TimelineProtectionState
import io.element.android.features.messages.impl.voicemessages.composer.DefaultVoiceMessageComposerPresenter
import io.element.android.features.roomcall.api.RoomCallState
import io.element.android.features.roommembermoderation.api.RoomMemberModerationEvents
import io.element.android.features.roommembermoderation.api.RoomMemberModerationState
import io.element.android.features.userprofile.api.FriendPrivateProfile
import io.element.android.features.userprofile.api.FriendProfileRepository
import io.element.android.features.userprofile.api.matrixDisplayNameOrNull
import io.element.android.features.userprofile.api.privateDirectDisplayName
import io.element.android.features.userprofile.api.privateDisplayName
import io.element.android.libraries.androidutils.clipboard.ClipboardHelper
import io.element.android.libraries.androidutils.clipboard.ProtectedClipboardSource
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.flatMap
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.libraries.designsystem.utils.snackbar.collectSnackbarMessageAsState
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.toThreadId
import io.element.android.libraries.matrix.api.encryption.EncryptionService
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import io.element.android.libraries.matrix.api.encryption.identity.IdentityState
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomInfo
import io.element.android.libraries.matrix.api.room.RoomMembersState
import io.element.android.libraries.matrix.api.room.directRoomRelationState
import io.element.android.libraries.matrix.api.room.history.RoomHistoryVisibility
import io.element.android.libraries.matrix.api.room.powerlevels.permissionsAsState
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import io.element.android.libraries.matrix.api.timeline.item.event.getAvatarUrl
import io.element.android.libraries.matrix.api.timeline.item.event.getDisplayName
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import io.element.android.libraries.matrix.ui.messages.reply.map
import io.element.android.libraries.matrix.ui.model.getAvatarData
import io.element.android.libraries.matrix.ui.room.getDirectRoomMember
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.recentemojis.api.AddRecentEmoji
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.services.analytics.api.AnalyticsService
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

@AssistedInject
class MessagesPresenter(
    @Assisted private val navigator: MessagesNavigator,
    private val room: JoinedRoom,
    @Assisted private val composerPresenter: Presenter<MessageComposerState>,
    voiceMessageComposerPresenterFactory: DefaultVoiceMessageComposerPresenter.Factory,
    @Assisted private val timelinePresenter: Presenter<TimelineState>,
    private val timelineProtectionPresenter: Presenter<TimelineProtectionState>,
    private val identityChangeStatePresenter: Presenter<IdentityChangeState>,
    private val linkPresenter: Presenter<LinkState>,
    @Assisted private val actionListPresenter: Presenter<ActionListState>,
    private val customReactionPresenter: Presenter<CustomReactionState>,
    private val reactionSummaryPresenter: Presenter<ReactionSummaryState>,
    private val readReceiptBottomSheetPresenter: Presenter<ReadReceiptBottomSheetState>,
    private val pinnedMessagesBannerPresenter: Presenter<PinnedMessagesBannerState>,
    private val roomCallStatePresenter: Presenter<RoomCallState>,
    private val roomMemberModerationPresenter: Presenter<RoomMemberModerationState>,
    @Assisted private val snackbarDispatcher: SnackbarDispatcher,
    private val dispatchers: CoroutineDispatchers,
    private val clipboardHelper: ClipboardHelper,
    private val htmlConverterProvider: HtmlConverterProvider,
    private val buildMeta: BuildMeta,
    @Assisted private val timelineController: TimelineController,
    private val permalinkParser: PermalinkParser,
    private val analyticsService: AnalyticsService,
    private val encryptionService: EncryptionService,
    private val featureFlagService: FeatureFlagService,
    private val addRecentEmoji: AddRecentEmoji,
    private val markAsFullyRead: MarkAsFullyRead,
    private val liveLocationShareManager: ActiveLiveLocationShareManager,
    private val messageReferenceStore: MessageReferenceStore,
    private val friendProfileRepository: FriendProfileRepository,
    private val sessionPreferencesStore: SessionPreferencesStore,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val matrixClient: MatrixClient,
) : Presenter<MessagesState> {
    @AssistedFactory
    interface Factory {
        fun create(
            snackbarDispatcher: SnackbarDispatcher,
            navigator: MessagesNavigator,
            composerPresenter: Presenter<MessageComposerState>,
            timelinePresenter: Presenter<TimelineState>,
            actionListPresenter: Presenter<ActionListState>,
            timelineController: TimelineController,
        ): MessagesPresenter
    }

    private val voiceMessageComposerPresenter = voiceMessageComposerPresenterFactory.create(
        timelineMode = timelineController.mainTimelineMode()
    )

    private val markingAsReadAndExiting = AtomicBoolean(false)
    private val withdrawnDrafts = mutableStateMapOf<io.element.android.libraries.matrix.api.core.EventId, WithdrawnDraft>()
    private val messageRemovalOperations = java.util.Collections.synchronizedSet(
        mutableSetOf<io.element.android.libraries.matrix.api.core.EventId>()
    )
    private val pinnedEventMutationMutex = Mutex()
    private var messageRemovalVisibilityBridgeIds by mutableStateOf(persistentSetOf<EventId>())

    @Composable
    override fun present(): MessagesState {
        htmlConverterProvider.Update()

        val coroutineScope = rememberCoroutineScope()
        val roomInfo by room.roomInfoFlow.collectAsState()
        val localCoroutineScope = rememberCoroutineScope()
        val composerState = composerPresenter.present()
        val voiceMessageComposerState = voiceMessageComposerPresenter.present()
        val timelineState = timelinePresenter.present()
        val historyRecoveryStatus = timelineState.historyRecoveryStatus
        val historyRecoveryNoticeToken = historyRecoveryNoticeToken(
            status = historyRecoveryStatus,
            candidateCount = timelineState.hiddenHistoryCandidateCount,
        )
        val historyRecoveryBannerAcknowledged by remember(room.roomId, historyRecoveryNoticeToken) {
            sessionPreferencesStore.isHistoryRecoveryBannerAcknowledged(
                roomId = room.roomId.value,
                status = historyRecoveryNoticeToken,
            )
        }.collectAsState(initial = false)
        var locallyAcknowledgedHistoryRecoveryNotice by remember(room.roomId) {
            mutableStateOf<String?>(null)
        }
        LaunchedEffect(historyRecoveryStatus) {
            if (historyRecoveryStatus.clearsHistoryRecoveryBannerAcknowledgement()) {
                locallyAcknowledgedHistoryRecoveryNotice = null
                sessionPreferencesStore.clearHistoryRecoveryBannerAcknowledgements(room.roomId.value)
            }
        }
        val messageReferenceState by messageReferenceStore.state.collectAsState()
        val friendProfiles by friendProfileRepository.profiles.collectAsState()
        val timelineProtectionState = timelineProtectionPresenter.present()
        val identityChangeState = identityChangeStatePresenter.present()
        val actionListState = actionListPresenter.present()
        val linkState = linkPresenter.present()
        val customReactionState = customReactionPresenter.present()
        val reactionSummaryState = reactionSummaryPresenter.present()
        val readReceiptBottomSheetState = readReceiptBottomSheetPresenter.present()
        val pinnedMessagesBannerState = pinnedMessagesBannerPresenter.present()
        val roomCallState = roomCallStatePresenter.present()
        val roomMemberModerationState = roomMemberModerationPresenter.present()
        LaunchedEffect(messageReferenceStore) {
            messageReferenceStore.load()
        }
        LaunchedEffect(friendProfileRepository) {
            friendProfileRepository.load()
        }
        val visibleTimelineState = remember(
            timelineState,
            messageReferenceState,
            friendProfiles,
            room.roomId,
            messageRemovalVisibilityBridgeIds,
        ) {
            timelineState.copy(
                timelineItems = timelineState.timelineItems.mapNotNull { item ->
                    item.withoutLocallyDeletedEvents(
                        roomId = room.roomId,
                        state = messageReferenceState,
                        retainedEventIds = timelineState.retainedBurnAfterReadEventIds +
                            timelineState.retainedMessageRemovalEventIds +
                            messageRemovalVisibilityBridgeIds,
                    )
                        ?.withPrivateProfiles(friendProfiles)
                }.toImmutableList()
            )
        }
        val threadsList by produceState(persistentListOf()) {
            room.threadsListService.subscribeToItemUpdates()
                .onStart { room.threadsListService.paginate() }
                .collectLatest { value = it.toImmutableList() }
        }

        val restoredReply = composerState.mode as? MessageComposerMode.Reply
        val restoredReplyTarget = visibleTimelineState.timelineItems.filterIsInstance<TimelineItem.Event>()
            .firstOrNull { it.eventId == restoredReply?.eventId }
        LaunchedEffect(restoredReply, restoredReplyTarget, timelineProtectionState) {
            val details = restoredReply?.replyToDetails as? InReplyToDetails.Ready ?: return@LaunchedEffect
            Timber.d("ReplyRestore state=%s visible=%s shell=%s burn=%s authorized=%s", 
                (details.eventContent as? MessageContent)?.protectedMessageStatus ?: "none",
                restoredReplyTarget?.content?.type ?: "absent", restoredReplyTarget?.content?.isProtectedContentShell,
                restoredReplyTarget?.content?.isBurnAfterRead, details.isTextContentAuthorizedProjection)
            if (!requiresAuthorizedReplyProjection(details.copy(isTextContentAuthorizedProjection = false))) return@LaunchedEffect
            val target = restoredReplyTarget
            val content = target?.content as? TimelineItemTextBasedContent
            if (target == null || content == null || !content.canBeCopied()) {
                if (details.isTextContentAuthorizedProjection) {
                    composerState.eventSink(MessageComposerEvent.InvalidateReplyProjection(details))
                }
                return@LaunchedEffect
            }
            val hideImage = timelineProtectionState.hideMediaContent(target.eventId, target.isMine)
            if (details.isTextContentAuthorizedProjection && details.textContent == content.plainText &&
                restoredReply.hideImage == hideImage) return@LaunchedEffect
            // Reuse only the same currently visible, authorized projection as an explicit reply.
            // The composer verifies that the captured target/details are still current before applying it.
            composerState.eventSink(MessageComposerEvent.RestoreReplyProjection(
                expectedDetails = details,
                textContent = content.plainText,
                hideImage = hideImage,
            ))
        }

        val canOpenThreadList by featureFlagService.isFeatureEnabledFlow(FeatureFlags.RoomThreadList).collectAsState(initial = false)
        val isCurrentlySharingLiveLocationInRoom by remember(room.roomId) {
            liveLocationShareManager.isCurrentlySharing(room.roomId)
        }.collectAsState()
        val pausedShares by liveLocationShareManager.pausedShares.collectAsState()
        val pausedLiveLocationShare = pausedShares[room.roomId]

        val matrixUserEventPermissions by room.permissionsAsState(UserEventPermissions.DEFAULT) { perms ->
            perms.userEventPermissions()
        }
        val directRoomRelationState = roomInfo.directRoomRelationState()
        val userEventPermissions = if (directRoomRelationState.canInteract) {
            matrixUserEventPermissions
        } else {
            matrixUserEventPermissions.copy(
                canSendMessage = false,
                canSendReaction = false,
                canPinUnpin = false,
            )
        }

        var hasDismissedInviteDialog by rememberSaveable {
            mutableStateOf(false)
        }
        LaunchedEffect(Unit) {
            // Remove the unread flag on entering but don't send read receipts
            // as those will be handled by the timeline.
            withContext(dispatchers.io) {
                room.setUnreadFlag(isUnread = false)

                // If for some reason the encryption state is unknown, fetch it
                if (roomInfo.isEncrypted == null) {
                    room.getUpdatedIsEncrypted()
                }
            }
        }

        val inviteProgress = remember { mutableStateOf<AsyncData<Unit>>(AsyncData.Uninitialized) }
        var showReinvitePrompt by remember { mutableStateOf(false) }
        val composerHasFocus by remember { derivedStateOf { composerState.textEditorState.hasFocus() } }
        LaunchedEffect(hasDismissedInviteDialog, composerHasFocus, roomInfo) {
            withContext(dispatchers.io) {
                showReinvitePrompt = !hasDismissedInviteDialog && composerHasFocus && roomInfo.isDm && roomInfo.activeMembersCount == 1L
            }
        }

        val snackbarMessage by snackbarDispatcher.collectSnackbarMessageAsState()
        var favouriteSnackbarMessage by remember { mutableStateOf<SnackbarMessage?>(null) }

        var dmUserVerificationState by remember { mutableStateOf<IdentityState?>(null) }

        val membersState by room.membersStateFlow.collectAsState()
        val dmRoomMember by room.getDirectRoomMember(membersState)
        val resolvedRoomName = if (roomInfo.isDm) {
            val matrixDisplayName = dmRoomMember?.displayName ?: roomInfo.heroes().firstOrNull()?.name
            dmRoomMember?.userId?.let { userId -> friendProfiles.privateDirectDisplayName(userId, matrixDisplayName) }
                ?: matrixDisplayName.matrixDisplayNameOrNull()
                ?: roomInfo.heroes().firstOrNull()?.id?.takeUnless { it.startsWith("onboarding-") }
                ?: roomInfo.name?.matrixDisplayNameOrNull()
        } else {
            roomInfo.name
        }
        val roomAvatar = roomInfo.avatarData().copy(name = resolvedRoomName)
        val heroes = roomInfo.heroes().map { hero ->
            if (roomInfo.isDm && hero.id == dmRoomMember?.userId?.value) hero.copy(name = resolvedRoomName) else hero
        }.toImmutableList()
        val roomMemberIdentityStateChanges = identityChangeState.roomMemberIdentityStateChanges

        // The top bar should show a "history" icon if:
        //   * The room is encrypted, and:
        //   * The room's history_visibility allows future users to see content.
        val topBarSharedHistoryIcon = if (roomInfo.isDm) SharedHistoryIcon.NONE else roomInfo.sharedHistoryIcon()

        LifecycleResumeEffect(dmRoomMember, roomInfo.isEncrypted) {
            if (roomInfo.isEncrypted == true) {
                val dmRoomMemberId = dmRoomMember?.userId
                localCoroutineScope.launch {
                    dmRoomMemberId?.let { userId ->
                        dmUserVerificationState = roomMemberIdentityStateChanges.find { it.identityRoomMember.userId == userId }?.identityState
                            ?: encryptionService.getUserIdentity(userId).getOrNull()
                    }
                }
            }
            onPauseOrDispose {}
        }

        fun handleEvent(event: MessagesEvent) {
            when (event) {
                is MessagesEvent.HandleAction -> {
                    localCoroutineScope.handleTimelineAction(
                        action = event.action,
                        targetEvent = event.event,
                        composerState = composerState,
                        enableTextFormatting = composerState.showTextFormatting,
                        timelineState = visibleTimelineState,
                        timelineProtectionState = timelineProtectionState,
                        onRoomActionSnackbarMessage = { favouriteSnackbarMessage = it },
                    )
                }
                is MessagesEvent.ToggleReaction -> {
                    sessionCoroutineScope.toggleReaction(event.emoji, event.eventOrTransactionId)
                }
                is MessagesEvent.ForwardEvents -> navigator.forwardEvents(event.eventIds)
                is MessagesEvent.FavouriteEvents -> localCoroutineScope.launch {
                    runCatching { messageReferenceStore.addFavourites(room.roomId, event.eventIds) }
                        .onSuccess { favouriteSnackbarMessage = SnackbarMessage(R.string.message_favourited) }
                        .onFailure {
                            Timber.e(it, "Failed to save selected message references")
                            favouriteSnackbarMessage = SnackbarMessage(CommonStrings.common_error)
                        }
                }
                is MessagesEvent.DismissFavouriteSnackbar -> {
                    if (favouriteSnackbarMessage?.id == event.messageId) {
                        favouriteSnackbarMessage = null
                    }
                }
                is MessagesEvent.DeleteLocalEvents -> localCoroutineScope.launch {
                    runCatching { messageReferenceStore.deleteLocally(room.roomId, event.eventIds) }
                        .onFailure {
                            Timber.e(it, "Failed to delete selected messages locally")
                            snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_error))
                        }
                }
                is MessagesEvent.UnpinEvent -> localCoroutineScope.launch {
                    handleUnpinEvent(event.eventId)
                }
                is MessagesEvent.ReEditWithdrawn -> {
                    val draft = withdrawnDrafts.remove(event.eventId)
                    if (draft != null && System.nanoTime() < draft.expiresAtNanos) {
                        composerState.eventSink(MessageComposerEvent.RestoreWithdrawnText(draft.text))
                    }
                }
                is MessagesEvent.ResolveUnsignedDeviceAndStartCall -> localCoroutineScope.launch {
                    val failure = event.failedEvent.localSendState as? LocalEventSendState.Failed.VerifiedUserHasUnsignedDevice
                    val sendHandle = event.failedEvent.sendhandle
                    if (failure == null || sendHandle == null) {
                        // The local echo can change while the confirmation is visible. Keep the call blocked
                        // unless this exact unsigned-device failure can still be explicitly resolved.
                        snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_error))
                        return@launch
                    }
                    room.ignoreDeviceTrustAndResend(failure.devices, sendHandle)
                        .onSuccess {
                            navigator.navigateToRoomCall(room.roomId, event.isAudioCall)
                        }
                        .onFailure { throwable ->
                            Timber.e(throwable, "Failed to authorize unsigned device before starting call")
                            snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_error))
                        }
                }
                MessagesEvent.ClearWithdrawnDrafts -> {
                    withdrawnDrafts.clear()
                    composerState.eventSink(MessageComposerEvent.ClearWithdrawnText)
                }
                is MessagesEvent.InviteDialogDismissed -> {
                    hasDismissedInviteDialog = true

                    if (event.action == InviteDialogAction.Invite) {
                        localCoroutineScope.reinviteOtherUser(inviteProgress)
                    }
                }
                is MessagesEvent.OnUserClicked -> {
                    navigator.navigateToMember(event.user.userId)
                }
                MessagesEvent.StopLiveLocationShare -> {
                    localCoroutineScope.launch {
                        liveLocationShareManager.stopShare(room.roomId)
                            .onFailure {
                                Timber.e(it, "Failed to stop live location share for roomId=${room.roomId}")
                                snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_error))
                            }
                    }
                }
                MessagesEvent.ShowLiveLocationShare -> {
                    navigator.navigateToCurrentLiveLocation()
                }
                is MessagesEvent.OpenHistoryRecovery -> {
                    // Hide first so opening Chat Security never leaves a flashing banner behind.
                    // This only acknowledges the notice; it cannot grant access or change recovery state.
                    locallyAcknowledgedHistoryRecoveryNotice = historyRecoveryNoticeToken
                    localCoroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        sessionPreferencesStore.acknowledgeHistoryRecoveryBanner(
                            roomId = room.roomId.value,
                            status = historyRecoveryNoticeToken,
                        )
                    }
                    navigator.navigateToChatSecurity()
                }
                is MessagesEvent.MarkAsFullyReadAndExit -> if (!markingAsReadAndExiting.getAndSet(true)) {
                    withdrawnDrafts.clear()
                    composerState.eventSink(MessageComposerEvent.ClearWithdrawnText)
                    val closeStartedAt = System.nanoTime()
                    navigator.close()
                    Timber.tag(TIMELINE_PERFORMANCE_TAG).d(
                        "stage=room_close duration_ms=%d",
                        (System.nanoTime() - closeStartedAt) / 1_000_000L,
                    )
                    sessionCoroutineScope.launch {
                        val latestEventId = room.liveTimeline.getLatestEventId().getOrElse {
                            Timber.w(it, "Failed to get latest event id to mark as fully read")
                            null
                        }
                        latestEventId?.let { eventId ->
                            sessionCoroutineScope.launch {
                                markAsFullyRead(room.roomId, eventId)
                            }
                        }
                    }.invokeOnCompletion {
                        markingAsReadAndExiting.set(false)
                    }
                }
            }
        }

        return MessagesState(
            roomId = room.roomId,
            roomName = resolvedRoomName,
            roomAvatar = roomAvatar,
            heroes = heroes,
            userEventPermissions = userEventPermissions,
            composerState = composerState,
            voiceMessageComposerState = voiceMessageComposerState,
            timelineState = visibleTimelineState,
            timelineProtectionState = timelineProtectionState,
            identityChangeState = identityChangeState,
            linkState = linkState,
            actionListState = actionListState,
            customReactionState = customReactionState,
            reactionSummaryState = reactionSummaryState,
            readReceiptBottomSheetState = readReceiptBottomSheetState,
            snackbarMessage = snackbarMessage,
            favouriteSnackbarMessage = favouriteSnackbarMessage,
            inviteProgress = inviteProgress.value,
            showReinvitePrompt = showReinvitePrompt,
            enableTextFormatting = MessageComposerConfig.ENABLE_RICH_TEXT_EDITING,
            roomCallState = roomCallState,
            appName = buildMeta.applicationName,
            pinnedMessagesBannerState = pinnedMessagesBannerState,
            dmUserVerificationState = dmUserVerificationState,
            roomMemberModerationState = roomMemberModerationState,
            topBarSharedHistoryIcon = topBarSharedHistoryIcon,
            successorRoom = roomInfo.successorRoom,
            threads = Threads(
                hasThreads = canOpenThreadList && threadsList.isNotEmpty(),
                // TODO calculate this properly based on the thread list and the read state of each thread
                hasUnreadThreads = false,
            ),
            showLiveLocationShareBanner = (isCurrentlySharingLiveLocationInRoom || pausedLiveLocationShare != null) &&
                timelineState.timelineMode !is Timeline.Mode.Thread,
            pausedLiveLocationShare = pausedLiveLocationShare,
            reEditableWithdrawnEventIds = withdrawnDrafts.keys.toSet(),
            showHistoryRecoveryBanner = shouldDisplayHistoryRecoveryBanner(
                status = historyRecoveryStatus,
                acknowledged = historyRecoveryBannerAcknowledged ||
                    locallyAcknowledgedHistoryRecoveryNotice == historyRecoveryNoticeToken,
            ),
            directRoomRelationState = directRoomRelationState,
            dmOtherUserId = dmRoomMember?.userId.takeIf { roomInfo.isDm },
            eventSink = ::handleEvent,
        )
    }

    private fun RoomInfo.sharedHistoryIcon(): SharedHistoryIcon {
        if (isEncrypted == true) {
            if (historyVisibility == RoomHistoryVisibility.Shared) {
                return SharedHistoryIcon.SHARED
            } else if (historyVisibility == RoomHistoryVisibility.WorldReadable) {
                return SharedHistoryIcon.WORLD_READABLE
            }
        }

        return SharedHistoryIcon.NONE
    }

    private fun RoomInfo.avatarData(): AvatarData {
        return AvatarData(
            id = id.value,
            name = name,
            url = avatarUrl,
            size = AvatarSize.TimelineRoom
        )
    }

    private fun RoomInfo.heroes(): List<AvatarData> {
        return heroes.map { user ->
            user.getAvatarData(size = AvatarSize.TimelineRoom)
        }
    }

    private fun CoroutineScope.handleTimelineAction(
        action: TimelineItemAction,
        targetEvent: TimelineItem.Event,
        composerState: MessageComposerState,
        timelineProtectionState: TimelineProtectionState,
        enableTextFormatting: Boolean,
        timelineState: TimelineState,
        onRoomActionSnackbarMessage: (SnackbarMessage) -> Unit,
    ) = launch {
        when (action) {
            TimelineItemAction.Retry -> timelineController.retryFailedEvent(targetEvent).onFailure {
                onRoomActionSnackbarMessage(SnackbarMessage(R.string.error_message_retry_failed))
            }
            TimelineItemAction.CopyText -> handleCopyContents(targetEvent, timelineState, onRoomActionSnackbarMessage)
            // View captures only the current copy-eligible timeline projection.
            TimelineItemAction.AddToCommon -> Unit
            TimelineItemAction.CopyUnavailable -> onRoomActionSnackbarMessage(
                SnackbarMessage(R.string.screen_room_timeline_copy_not_allowed)
            )
            TimelineItemAction.CopyCaption -> handleCopyCaption(targetEvent, timelineState, onRoomActionSnackbarMessage)
            TimelineItemAction.CopyLink -> handleCopyLink(targetEvent, onRoomActionSnackbarMessage)
            TimelineItemAction.Redact,
            TimelineItemAction.Withdraw -> handleAnimatedMessageRemoval(targetEvent, timelineState) {
                handleActionRedact(targetEvent)
            }
            TimelineItemAction.DeleteLocal -> handleAnimatedMessageRemoval(
                event = targetEvent,
                timelineState = timelineState,
                bridgeLocalDeletionFilter = true,
            ) {
                handleDeleteLocal(targetEvent)
            }
            TimelineItemAction.Favourite,
            TimelineItemAction.Unfavourite -> handleFavourite(targetEvent, onRoomActionSnackbarMessage)
            TimelineItemAction.MultiSelect -> Unit
            TimelineItemAction.Edit,
            TimelineItemAction.EditPoll -> handleActionEdit(targetEvent, composerState, enableTextFormatting)
            TimelineItemAction.AddCaption -> handleActionAddCaption(targetEvent, composerState)
            TimelineItemAction.EditCaption -> handleActionEditCaption(targetEvent, composerState)
            TimelineItemAction.RemoveCaption -> handleRemoveCaption(targetEvent)
            TimelineItemAction.Reply -> handleActionReply(targetEvent, composerState, timelineProtectionState)
            TimelineItemAction.ReplyInThread -> {
                val displayThreads = featureFlagService.isFeatureEnabled(FeatureFlags.Threads)
                if (displayThreads) {
                    // Get either the thread id this event is in, or the event id if it's not in a thread so we can start one
                    val threadId = when (targetEvent.threadInfo) {
                        is TimelineItemThreadInfo.ThreadResponse -> targetEvent.threadInfo.threadRootId
                        is TimelineItemThreadInfo.ThreadRoot, null -> targetEvent.eventId?.toThreadId()
                    } ?: return@launch
                    navigator.navigateToThread(threadId, null)
                } else {
                    handleActionReply(targetEvent, composerState, timelineProtectionState)
                }
            }
            TimelineItemAction.ViewSource -> handleShowDebugInfoAction(targetEvent)
            TimelineItemAction.Forward -> handleForwardAction(targetEvent)
            TimelineItemAction.ReportContent -> handleReportAction(targetEvent)
            TimelineItemAction.EndPoll -> handleEndPollAction(targetEvent, timelineState)
            TimelineItemAction.Pin -> handlePinAction(targetEvent)
            TimelineItemAction.Unpin -> handleUnpinAction(targetEvent)
            TimelineItemAction.ViewInTimeline -> Unit
        }
    }

    private suspend fun handleRemoveCaption(targetEvent: TimelineItem.Event) {
        timelineController.invokeOnCurrentTimeline {
            editCaption(
                eventOrTransactionId = targetEvent.eventOrTransactionId,
                caption = null,
                formattedCaption = null,
            )
        }
    }

    private suspend fun handlePinAction(targetEvent: TimelineItem.Event) {
        if (targetEvent.eventId == null) return
        handlePinnedEventMutation(targetEvent.eventId, shouldBePinned = true)
    }

    private suspend fun handleUnpinAction(targetEvent: TimelineItem.Event) {
        val eventId = targetEvent.eventId ?: return
        handleUnpinEvent(eventId)
    }

    private suspend fun handleUnpinEvent(eventId: io.element.android.libraries.matrix.api.core.EventId) {
        handlePinnedEventMutation(eventId, shouldBePinned = false)
    }

    /**
     * Matrix pinning rewrites the room's complete pinned-event state. Parallel mutations can both
     * start from the same old snapshot and make the later response erase the earlier user's action.
     * Keep one room mutation in flight and wait for the authenticated room state to converge before
     * the next queued mutation is allowed to read it.
     */
    private suspend fun handlePinnedEventMutation(eventId: EventId, shouldBePinned: Boolean) =
        pinnedEventMutationMutex.withLock {
            val kind = if (shouldBePinned) PinUnpinAction.Kind.Pin else PinUnpinAction.Kind.Unpin
            analyticsService.capture(
                PinUnpinAction(
                    from = PinUnpinAction.From.Timeline,
                    kind = kind,
                )
            )
            var mutationResult: Result<Boolean>? = null
            timelineController.invokeOnCurrentTimeline {
                mutationResult = if (shouldBePinned) pinEvent(eventId) else unpinEvent(eventId)
            }
            val result = mutationResult ?: Result.failure(IllegalStateException("Pinned event mutation did not run."))
            result.onFailure { failure ->
                Timber.e(failure, "Failed to ${kind.name.lowercase()} event $eventId")
                snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_error))
            }
            if (result.isFailure) return@withLock

            val converged = withTimeoutOrNull(PINNED_EVENT_CONVERGENCE_TIMEOUT_MILLIS) {
                room.roomInfoFlow.first { roomInfo ->
                    (eventId in roomInfo.pinnedEventIds) == shouldBePinned
                }
            } != null
            if (!converged) {
                // Do not invent local room state. The SDK/server result may still arrive later, but the
                // next mutation must not be presented as successful against an unconfirmed snapshot.
                Timber.w("Pinned event state did not converge for $eventId")
                snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_error))
            }
        }

    private fun CoroutineScope.toggleReaction(
        emoji: String,
        eventOrTransactionId: EventOrTransactionId,
    ) = launch(dispatchers.io) {
        runCatchingExceptions {
            val added = appOperationalStateCoordinator.perform(
                messageLifecycleOperationRequest(
                    lifecycleId = FullAppLifecycleId.LC_15,
                    trigger = "reaction",
                    eventIdentity = eventOrTransactionId.rawIdentity(),
                )
            ) {
                var mutationResult: Result<Boolean> = Result.failure(IllegalStateException("No active timeline"))
                timelineController.invokeOnCurrentTimeline {
                    mutationResult = toggleReaction(emoji, eventOrTransactionId)
                }
                mutationResult.getOrThrow()
            }
            // Recent emojis are a local convenience projection. They must not gate the lifecycle
            // operation after Matrix has already accepted the reaction mutation.
            if (added) {
                addRecentEmoji(emoji).onFailure { Timber.w(it, "Failed to remember recent reaction emoji") }
            }
        }.onFailure { Timber.e(it, "Protected reaction failed") }
    }

    private fun CoroutineScope.reinviteOtherUser(inviteProgress: MutableState<AsyncData<Unit>>) = launch(dispatchers.io) {
        inviteProgress.value = AsyncData.Loading()
        runCatchingExceptions {
            val memberList = when (val memberState = room.membersStateFlow.value) {
                is RoomMembersState.Ready -> memberState.roomMembers
                is RoomMembersState.Error -> memberState.prevRoomMembers.orEmpty()
                else -> emptyList()
            }

            val member = memberList.first { it.userId != room.sessionId }
            room.inviteUserById(member.userId).onFailure { t ->
                Timber.e(t, "Failed to reinvite DM partner")
            }.getOrThrow()
        }.fold(
            onSuccess = {
                inviteProgress.value = AsyncData.Success(Unit)
            },
            onFailure = {
                inviteProgress.value = AsyncData.Failure(it)
            }
        )
    }

    private suspend fun handleAnimatedMessageRemoval(
        event: TimelineItem.Event,
        timelineState: TimelineState,
        bridgeLocalDeletionFilter: Boolean = false,
        operation: suspend () -> Result<Unit>,
    ) {
        val eventId = event.eventId
        if (eventId == null) {
            operation()
            return
        }
        if (!messageRemovalOperations.add(eventId)) return
        if (bridgeLocalDeletionFilter) {
            // deleteLocally() publishes its durable reference immediately. Keep this exact event eligible
            // for the already-authorized terminal transition until TimelinePresenter finishes its 460 ms
            // destruction window; otherwise the outer local-delete filter can dispose the row first.
            // The bridge never stores content: if the timeline replaces the item with a fail-closed shell,
            // the current shell is what remains visible and stale plaintext is not resurrected.
            messageRemovalVisibilityBridgeIds = messageRemovalVisibilityBridgeIds.add(eventId)
        }
        timelineState.eventSink(TimelineEvent.PrepareMessageRemoval(eventId))
        var committed = false
        var settled = false
        try {
            val result = operation()
            if (result.isSuccess) {
                settled = true
                timelineState.eventSink(TimelineEvent.CommitMessageRemoval(eventId))
                committed = true
                if (bridgeLocalDeletionFilter) {
                    delay(MESSAGE_REMOVAL_DESTRUCTION_MILLIS)
                }
            } else {
                settled = true
                timelineState.eventSink(TimelineEvent.CancelMessageRemoval(eventId))
            }
        } finally {
            if (!settled && !committed) timelineState.eventSink(TimelineEvent.CancelMessageRemoval(eventId))
            if (bridgeLocalDeletionFilter) {
                messageRemovalVisibilityBridgeIds = messageRemovalVisibilityBridgeIds.remove(eventId)
            }
            messageRemovalOperations.remove(eventId)
        }
    }

    private suspend fun handleActionRedact(event: TimelineItem.Event): Result<Unit> {
        Timber.tag(REDACTION_LATENCY_TAG).d(
            "stage=T0 identity=%s epoch_ms=%d monotonic_ns=%d",
            event.eventId?.value ?: event.transactionId?.value ?: event.id.value,
            System.currentTimeMillis(),
            System.nanoTime(),
        )
        val localOwnerId = timelineController.optimisticLocalEchoId(event.id, event.displayId)
        if (event.eventId == null && localOwnerId != null) {
            return timelineController.discardOptimisticLocalEcho(localOwnerId)
                .onFailure { failure ->
                    Timber.e(failure, "Failed to discard protected retry transaction")
                    snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_error))
                }
        }
        return runCatchingExceptions {
            appOperationalStateCoordinator.perform(
                messageLifecycleOperationRequest(
                    lifecycleId = FullAppLifecycleId.LC_21,
                    trigger = "user_redact",
                    eventIdentity = event.eventOrTransactionId.rawIdentity(),
                )
            ) {
                val volatileText = (event.content as? TimelineItemTextBasedContent)
                    ?.plainText
                    ?.takeIf { event.isMine && !event.content.isBurnAfterRead && event.clipboardSource == MessageClipboardSource.Unprotected }
                var redactionResult: Result<Unit> = Result.failure(IllegalStateException("No active timeline"))
                timelineController.invokeOnCurrentTimeline {
                    redactionResult = redactEvent(eventOrTransactionId = event.eventOrTransactionId, reason = null)
                        .onSuccess {
                            val eventId = event.eventId
                            if (eventId != null && volatileText != null) {
                                val expiresAt = System.nanoTime() + REEDIT_WINDOW_NANOS
                                withdrawnDrafts[eventId] = WithdrawnDraft(volatileText, expiresAt)
                                sessionCoroutineScope.launch {
                                    delay(REEDIT_WINDOW_MILLIS)
                                    if (withdrawnDrafts[eventId]?.expiresAtNanos == expiresAt) withdrawnDrafts.remove(eventId)
                                }
                            }
                        }
                }
                redactionResult.getOrThrow()
            }
        }.onFailure {
            Timber.e(it, "Secure redaction failed")
            snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_error))
        }
    }

    private fun messageLifecycleOperationRequest(
        lifecycleId: FullAppLifecycleId,
        trigger: String,
        eventIdentity: String,
    ) = lifecycleOperationRequest(
        lifecycleId = lifecycleId,
        trigger = trigger,
        accountIdHash = room.sessionId.value.sha256().also {
            require(matrixClient.sessionId == room.sessionId) { "Message mutation session owner does not match the room." }
        },
        matrixDeviceIdHash = matrixClient.deviceId.value.sha256(),
        roomIdHash = room.roomId.value.sha256(),
        eventIdHash = eventIdentity.sha256(),
    )

    private fun handleActionEdit(
        targetEvent: TimelineItem.Event,
        composerState: MessageComposerState,
        enableTextFormatting: Boolean,
    ) {
        when (targetEvent.content) {
            is TimelineItemPollContent -> {
                if (targetEvent.eventId == null) return
                navigator.navigateToEditPoll(targetEvent.eventId)
            }
            else -> {
                val composerMode = MessageComposerMode.Edit(
                    targetEvent.eventOrTransactionId,
                    (targetEvent.content as? TimelineItemTextBasedContent)?.let {
                        if (enableTextFormatting) {
                            it.htmlBody ?: it.body
                        } else {
                            it.body
                        }
                    }.orEmpty(),
                )
                composerState.eventSink(
                    MessageComposerEvent.SetMode(composerMode)
                )
            }
        }
    }

    private suspend fun handleActionAddCaption(
        targetEvent: TimelineItem.Event,
        composerState: MessageComposerState,
    ) {
        val composerMode = MessageComposerMode.EditCaption(
            eventOrTransactionId = targetEvent.eventOrTransactionId,
            content = "",
        )
        composerState.eventSink(
            MessageComposerEvent.SetMode(composerMode)
        )
    }

    private suspend fun handleActionEditCaption(
        targetEvent: TimelineItem.Event,
        composerState: MessageComposerState,
    ) {
        val composerMode = MessageComposerMode.EditCaption(
            eventOrTransactionId = targetEvent.eventOrTransactionId,
            content = (targetEvent.content as? TimelineItemEventContentWithAttachment)?.caption.orEmpty(),
        )
        composerState.eventSink(
            MessageComposerEvent.SetMode(composerMode)
        )
    }

    private suspend fun handleActionReply(
        targetEvent: TimelineItem.Event,
        composerState: MessageComposerState,
        timelineProtectionState: TimelineProtectionState,
    ) {
        if (targetEvent.eventId == null) return
        timelineController.invokeOnCurrentTimeline {
            val loadedReplyDetails = loadReplyDetails(targetEvent.eventId).map(permalinkParser)
            // The selected timeline item is already an authorized display projection. Reuse its
            // in-memory plain-text summary when the SDK reply payload is only an encrypted
            // operation carrier. Draft persistence stores the relation event ID only.
            val replyToDetails = if (loadedReplyDetails is InReplyToDetails.Ready && targetEvent.content is TimelineItemTextBasedContent &&
                targetEvent.content.canBeCopied()) {
                loadedReplyDetails.copy(
                    textContent = targetEvent.content.plainText,
                    isTextContentAuthorizedProjection = true,
                )
            } else {
                loadedReplyDetails
            }
            val composerMode = MessageComposerMode.Reply(
                replyToDetails = replyToDetails,
                hideImage = timelineProtectionState.hideMediaContent(targetEvent.eventId, targetEvent.isMine),
            )
            composerState.eventSink(
                MessageComposerEvent.SetMode(composerMode)
            )
        }
    }

    private fun handleShowDebugInfoAction(event: TimelineItem.Event) {
        navigator.navigateToEventDebugInfo(event.eventId, event.debugInfo)
    }

    private fun handleForwardAction(event: TimelineItem.Event) {
        if (event.eventId == null) return
        navigator.forwardEvent(event.eventId)
    }

    private fun handleReportAction(event: TimelineItem.Event) {
        if (event.eventId == null) return
        navigator.navigateToReportMessage(event.eventId, event.senderId)
    }

    private fun handleEndPollAction(
        event: TimelineItem.Event,
        timelineState: TimelineState,
    ) {
        event.eventId?.let { timelineState.eventSink(TimelineEvent.EndPoll(it)) }
    }

    private suspend fun handleCopyLink(
        event: TimelineItem.Event,
        onSnackbarMessage: (SnackbarMessage) -> Unit,
    ) {
        event.eventId ?: return
        room.getPermalinkFor(event.eventId).fold(
            onSuccess = { permalink ->
                if (clipboardHelper.copyPlainText(permalink)) {
                    onSnackbarMessage(SnackbarMessage(CommonStrings.common_link_copied_to_clipboard))
                } else {
                    onSnackbarMessage(SnackbarMessage(R.string.screen_room_timeline_copy_not_allowed))
                }
            },
            onFailure = {
                Timber.e(it, "Failed to get permalink for event ${event.eventId}")
                onSnackbarMessage(SnackbarMessage(CommonStrings.common_error))
            }
        )
    }

    private fun handleCopyContents(
        event: TimelineItem.Event,
        timelineState: TimelineState,
        onSnackbarMessage: (SnackbarMessage) -> Unit,
    ) {
        val currentEvent = timelineState.currentCopyTarget(event) ?: return copyRejected(onSnackbarMessage)
        if (currentEvent.content != event.content || currentEvent.clipboardSource != event.clipboardSource) {
            return copyRejected(onSnackbarMessage)
        }
        val content = when (val currentContent = currentEvent.content) {
            is TimelineItemTextBasedContent -> currentContent.body
            is TimelineItemStateContent -> currentContent.body
            else -> return
        }
        if (clipboardHelper.copyEventPlainText(content, currentEvent.clipboardSource)) {
            onSnackbarMessage(SnackbarMessage(R.string.screen_room_timeline_message_copied))
        } else {
            copyRejected(onSnackbarMessage)
        }
    }

    private fun handleCopyCaption(
        event: TimelineItem.Event,
        timelineState: TimelineState,
        onSnackbarMessage: (SnackbarMessage) -> Unit,
    ) {
        val currentEvent = timelineState.currentCopyTarget(event) ?: return copyRejected(onSnackbarMessage)
        if (currentEvent.content != event.content || currentEvent.clipboardSource != event.clipboardSource) {
            return copyRejected(onSnackbarMessage)
        }
        val content = (currentEvent.content as? TimelineItemEventContentWithAttachment)?.caption ?: return
        if (clipboardHelper.copyEventPlainText(content, currentEvent.clipboardSource)) {
            onSnackbarMessage(SnackbarMessage(CommonStrings.common_copied_to_clipboard))
        } else {
            copyRejected(onSnackbarMessage)
        }
    }

    private fun copyRejected(onSnackbarMessage: (SnackbarMessage) -> Unit) {
        onSnackbarMessage(SnackbarMessage(R.string.screen_room_timeline_copy_not_allowed))
    }

    private suspend fun handleDeleteLocal(event: TimelineItem.Event): Result<Unit> {
        val eventId = event.eventId ?: return Result.failure(IllegalArgumentException("Missing event id"))
        return runCatching {
            messageReferenceStore.deleteLocally(room.roomId, listOf(eventId))
        }.onFailure {
            Timber.e(it, "Failed to persist local message deletion")
            snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_error))
        }
    }

    private suspend fun handleFavourite(
        event: TimelineItem.Event,
        onSnackbarMessage: (SnackbarMessage) -> Unit,
    ) {
        val eventId = event.eventId ?: return
        runCatching {
            messageReferenceStore.toggleFavourite(room.roomId, eventId)
        }.onSuccess { added ->
            onSnackbarMessage(SnackbarMessage(if (added) R.string.message_favourited else R.string.message_unfavourited))
        }.onFailure {
            Timber.e(it, "Failed to update encrypted message favourite reference")
            onSnackbarMessage(SnackbarMessage(CommonStrings.common_error))
        }
    }

    private companion object {
        const val TIMELINE_PERFORMANCE_TAG = "YiZheTimelinePerf"
        const val REDACTION_LATENCY_TAG = "YiZheRedactionLatency"
        const val REEDIT_WINDOW_MILLIS = 120_000L
        const val REEDIT_WINDOW_NANOS = REEDIT_WINDOW_MILLIS * 1_000_000L
        const val PINNED_EVENT_CONVERGENCE_TIMEOUT_MILLIS = 15_000L
    }

    private data class WithdrawnDraft(val text: String, val expiresAtNanos: Long)
}

internal fun shouldDisplayHistoryRecoveryBanner(
    status: io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus,
    acknowledged: Boolean,
): Boolean = status.shouldShowRoomRecoveryBanner() && !acknowledged

/**
 * Acknowledgement is scoped to the observed candidate set size, not just the broad recovery state.
 * A later batch of genuinely new hidden history must therefore become visible again instead of
 * inheriting an unrelated acknowledgement from an older recovery attempt.
 */
internal fun historyRecoveryNoticeToken(
    status: HistoryRecoveryStatus,
    candidateCount: Int,
): String {
    require(candidateCount >= 0)
    return "${status.name}:$candidateCount"
}

internal fun TimelineItem.withoutLocallyDeletedEvents(
    roomId: RoomId,
    state: MessageReferenceState,
    retainedEventIds: Set<EventId> = emptySet(),
): TimelineItem? = when (this) {
    is TimelineItem.Event -> {
        if (
            eventId !in retainedEventIds && (
                eventId?.let { state.isLocallyDeleted(roomId, it) } == true ||
                    state.conversationCutoff(roomId)?.let { sentTimeMillis <= it } == true
                )
        ) {
            null
        } else {
            this
        }
    }
    is TimelineItem.GroupedEvents -> {
        val visibleEvents = events.filterNot { event ->
            event.eventId !in retainedEventIds && (
                event.eventId?.let { state.isLocallyDeleted(roomId, it) } == true ||
                    state.conversationCutoff(roomId)?.let { event.sentTimeMillis <= it } == true
                )
        }.toImmutableList()
        if (visibleEvents.isEmpty()) {
            null
        } else {
            if (visibleEvents.size == events.size) this else copy(events = visibleEvents)
        }
    }
    is TimelineItem.Virtual -> this
}

private fun HistoryRecoveryStatus.clearsHistoryRecoveryBannerAcknowledgement(): Boolean = when (this) {
    HistoryRecoveryStatus.IDLE,
    HistoryRecoveryStatus.STANDARD_AUTHORIZED,
    HistoryRecoveryStatus.IDENTITY_RESET_UNRECOVERABLE -> true
    else -> false
}

internal fun TimelineItem.withPrivateProfiles(
    profiles: Map<io.element.android.libraries.matrix.api.core.UserId, FriendPrivateProfile>,
): TimelineItem = when (this) {
    is TimelineItem.Event -> withPrivateProfile(profiles)
    is TimelineItem.GroupedEvents -> {
        val mappedEvents = events.map { it.withPrivateProfile(profiles) }.toImmutableList()
        val mappedReceipts = aggregatedReadReceipts.map { receipt ->
            val userId = io.element.android.libraries.matrix.api.core.UserId(receipt.avatarData.id)
            val name = profiles.privateDisplayName(userId, receipt.avatarData.name)
            if (name == receipt.avatarData.name) receipt else receipt.copy(avatarData = receipt.avatarData.copy(name = name))
        }.toImmutableList()
        if (mappedEvents.indices.all { mappedEvents[it] === events[it] } && mappedReceipts == aggregatedReadReceipts) {
            this
        } else {
            copy(events = mappedEvents, aggregatedReadReceipts = mappedReceipts)
        }
    }
    is TimelineItem.Virtual -> this
}

private fun TimelineItem.Event.withPrivateProfile(
    profiles: Map<io.element.android.libraries.matrix.api.core.UserId, FriendPrivateProfile>,
): TimelineItem.Event {
    val senderName = profiles.privateDisplayName(senderId, senderProfile.getDisplayName())
    val mappedSender = senderProfile.withPrivateName(senderId, profiles)
    val mappedReply = (inReplyTo as? InReplyToDetails.Ready)?.let { reply ->
        val profile = reply.senderProfile.withPrivateName(reply.senderId, profiles)
        if (profile == reply.senderProfile) reply else reply.copy(senderProfile = profile)
    } ?: inReplyTo
    val mappedReceipts = readReceiptState.receipts.map { receipt ->
        val userId = io.element.android.libraries.matrix.api.core.UserId(receipt.avatarData.id)
        val name = profiles.privateDisplayName(userId, receipt.avatarData.name)
        if (name == receipt.avatarData.name) receipt else receipt.copy(avatarData = receipt.avatarData.copy(name = name))
    }.toImmutableList()
    // Event construction reads the native security shield. A private-name projection
    // with no changed fields must preserve the already-current source row.
    // Changed profiles, receipts and source/security rows still take the normal path.
    if (mappedSender == senderProfile && senderName == senderAvatar.name &&
        mappedReply === inReplyTo && mappedReceipts == readReceiptState.receipts
    ) return this
    return copy(
        senderProfile = mappedSender,
        senderAvatar = senderAvatar.copy(name = senderName),
        inReplyTo = mappedReply,
        readReceiptState = readReceiptState.copy(
            receipts = mappedReceipts,
        ),
    )
}

private fun ProfileDetails.withPrivateName(
    userId: io.element.android.libraries.matrix.api.core.UserId,
    profiles: Map<io.element.android.libraries.matrix.api.core.UserId, FriendPrivateProfile>,
): ProfileDetails {
    val privateProfile = profiles[userId] ?: return this
    return ProfileDetails.Ready(
        displayName = privateProfile.displayName(getDisplayName()),
        displayNameAmbiguous = false,
        avatarUrl = getAvatarUrl(),
    )
}

private fun EventOrTransactionId.rawIdentity(): String = when (this) {
    is EventOrTransactionId.Event -> id.value
    is EventOrTransactionId.Transaction -> id.value
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun TimelineState.currentCopyTarget(target: TimelineItem.Event): TimelineItem.Event? = timelineItems
    .asSequence()
    .flatMap { item ->
        when (item) {
            is TimelineItem.Event -> sequenceOf(item)
            is TimelineItem.GroupedEvents -> item.events.asSequence()
            is TimelineItem.Virtual -> emptySequence()
        }
    }
    .firstOrNull { current -> current.displayId == target.displayId }

private fun ClipboardHelper.copyEventPlainText(
    text: String,
    source: MessageClipboardSource?,
): Boolean = when (source) {
    MessageClipboardSource.Unprotected -> copyPlainText(text)
    is MessageClipboardSource.Protected -> copyProtectedPlainText(
        text = text,
        source = ProtectedClipboardSource(
            sessionId = source.sessionId,
            roomId = source.roomId,
            eventId = source.eventId,
            contentVersion = source.contentVersion,
            contentIds = source.contentIds,
            authorizationRevision = source.authorizationRevision,
            expiresAtEpochMillis = source.expiresAtEpochMillis,
        ),
    )
    null -> false
}
