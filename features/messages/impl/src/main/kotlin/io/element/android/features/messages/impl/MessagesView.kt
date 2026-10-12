/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.location.api.LiveLocationSharingBanner
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.messages.impl.actionlist.ActionListEvent
import io.element.android.features.messages.impl.actionlist.ActionListState
import io.element.android.features.messages.impl.actionlist.ActionListView
import io.element.android.features.messages.impl.actionlist.model.TimelineItemAction
import io.element.android.features.messages.impl.commoninfo.CommonInfoMessageCapture
import io.element.android.features.messages.impl.commoninfo.CommonInfoPanel
import io.element.android.features.messages.impl.commoninfo.captureCommonInfoMessage
import io.element.android.features.messages.impl.commoninfo.commonInfoMessageText
import io.element.android.features.messages.impl.crypto.identity.IdentityChangeStateView
import io.element.android.features.messages.impl.link.LinkEvent
import io.element.android.features.messages.impl.link.LinkView
import io.element.android.features.messages.impl.messagecomposer.AttachmentsPanel
import io.element.android.features.messages.impl.messagecomposer.DisabledComposerView
import io.element.android.features.messages.impl.messagecomposer.MessageComposerEvent
import io.element.android.features.messages.impl.messagecomposer.MessageComposerView
import io.element.android.features.messages.impl.messagecomposer.RelayEditorDialog
import io.element.android.features.messages.impl.messagecomposer.suggestions.SuggestionsPickerView
import io.element.android.features.messages.impl.pinned.banner.PinnedMessagesBannerState
import io.element.android.features.messages.impl.pinned.banner.PinnedMessagesBannerView
import io.element.android.features.messages.impl.pinned.banner.PinnedMessagesBannerViewDefaults
import io.element.android.features.messages.impl.timeline.FOCUS_ON_PINNED_EVENT_DEBOUNCE_DURATION_IN_MILLIS
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.TimelineView
import io.element.android.features.messages.impl.timeline.aGroupedEvents
import io.element.android.features.messages.impl.timeline.aTimelineItemDaySeparator
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineState
import io.element.android.features.messages.impl.timeline.components.GroupCallBanner
import io.element.android.features.messages.impl.timeline.components.CallMenuItem
import io.element.android.features.messages.impl.timeline.components.customreaction.CustomReactionBottomSheet
import io.element.android.features.messages.impl.timeline.components.customreaction.CustomReactionEvent
import io.element.android.features.messages.impl.timeline.components.event.TimelineItemTextView
import io.element.android.features.messages.impl.timeline.components.reactionsummary.ReactionSummaryEvent
import io.element.android.features.messages.impl.timeline.components.reactionsummary.ReactionSummaryView
import io.element.android.features.messages.impl.timeline.components.receipt.bottomsheet.ReadReceiptBottomSheet
import io.element.android.features.messages.impl.timeline.components.receipt.bottomsheet.ReadReceiptBottomSheetEvent
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.TimelineItemGroupPosition
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemAudioContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemFileContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRelayContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRtcNotificationContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVideoContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVoiceContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemStateEventContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.features.messages.impl.timeline.model.event.canBeForwarded
import io.element.android.features.messages.impl.topbars.MessagesViewTopBar
import io.element.android.features.messages.impl.topbars.ThreadTopBar
import io.element.android.features.messages.impl.voicemessages.composer.VoiceMessagePermissionRationaleDialog
import io.element.android.features.messages.impl.voicemessages.composer.VoiceRecordingPermissionLostDialog
import io.element.android.features.messages.impl.voicemessages.composer.VoiceMessageSendingFailedDialog
import io.element.android.libraries.matrix.api.room.DirectRoomRelationState
import io.element.android.features.roomcall.api.RoomCallState
import io.element.android.libraries.androidutils.ui.hideKeyboard
import io.element.android.libraries.designsystem.atomic.molecules.ComposerAlertMolecule
import io.element.android.libraries.designsystem.components.ExpandableBottomSheetLayout
import io.element.android.libraries.designsystem.components.ExpandableBottomSheetLayoutState
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.rememberExpandableBottomSheetLayoutState
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.text.toAnnotatedString
import io.element.android.libraries.designsystem.text.toDp
import io.element.android.libraries.designsystem.theme.components.BottomSheetDragHandle
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.HideKeyboardWhenDisposed
import io.element.android.libraries.designsystem.utils.KeepScreenOn
import io.element.android.libraries.designsystem.utils.OnLifecycleEvent
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarHost
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.libraries.designsystem.utils.snackbar.rememberSnackbarHostState
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.encryption.identity.IdentityState
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.room.tombstone.SuccessorRoom
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import io.element.android.libraries.textcomposer.ElementRichTextEditorStyle
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.wysiwyg.compose.EditorStyledText
import io.element.android.wysiwyg.link.Link
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

@Composable
private fun RoomActionSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { data ->
        Surface(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .widthIn(max = 280.dp)
                .wrapContentWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
        ) {
            Text(
                text = data.visuals.message,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = ElementTheme.typography.fontBodyMdMedium,
                maxLines = 2,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessagesView(
    state: MessagesState,
    onBackClick: () -> Unit,
    onRoomDetailsClick: () -> Unit,
    onEventContentClick: (isLive: Boolean, event: TimelineItem.Event) -> Boolean,
    onUserDataClick: (UserId) -> Unit,
    onLinkClick: (String, Boolean) -> Unit,
    onSendLocationClick: () -> Unit,
    onCreatePollClick: () -> Unit,
    onJoinCallClick: (isAudioCall: Boolean) -> Unit,
    onViewAllPinnedMessagesClick: () -> Unit,
    onThreadsListClick: () -> Unit,
    roomActionSnackbarMessage: SnackbarMessage? = null,
    onRoomActionSnackbarDismissed: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    forceJumpToBottomVisibility: Boolean = false,
    knockRequestsBannerView: @Composable () -> Unit,
) {
    OnLifecycleEvent { _, event ->
        state.voiceMessageComposerState.eventSink(VoiceMessageComposerEvent.LifecycleEvent(event))
        if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
            if (state.reEditableWithdrawnEventIds.isNotEmpty() || state.composerState.containsVolatileWithdrawnText) {
                state.eventSink(MessagesEvent.ClearWithdrawnDrafts)
            }
            if (state.timelineState.activeBurnAfterReadEventId != null) {
                state.timelineState.eventSink(TimelineEvent.CloseBurnAfterRead)
            }
        }
    }

    KeepScreenOn(state.voiceMessageComposerState.keepScreenOn)

    HideKeyboardWhenDisposed()

    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedEvents by remember { mutableStateOf<List<TimelineItem.Event>>(emptyList()) }
    var pendingLocalDeleteEventIds by remember { mutableStateOf<List<EventId>?>(null) }
    var activeRelayEventId by remember { mutableStateOf<EventId?>(null) }
    var pendingUnsignedDeviceCall by remember { mutableStateOf<Pair<TimelineItem.Event, Boolean>?>(null) }
    var commonMessageCapture by remember(state.composerState.commonInfoRepository) { mutableStateOf<CommonInfoMessageCapture?>(null) }
    val currentTimelineItems by rememberUpdatedState(state.timelineState.timelineItems)
    val commonScope = rememberCoroutineScope()
    val selectedEventIds = selectedEvents.mapNotNullTo(linkedSetOf()) { it.eventId }

    fun requestCall(isAudioCall: Boolean) {
        val verifiedUserFailure = state.timelineState.timelineItems
            .asSequence()
            .filterIsInstance<TimelineItem.Event>()
            .firstOrNull { item ->
                item.isMine && item.localSendState is LocalEventSendState.Failed.VerifiedUser
            }
        when (verifiedUserFailure?.localSendState) {
            is LocalEventSendState.Failed.VerifiedUserHasUnsignedDevice -> {
                pendingUnsignedDeviceCall = verifiedUserFailure to isAudioCall
            }
            is LocalEventSendState.Failed.VerifiedUserChangedIdentity -> {
                // An identity change requires the existing stronger verification flow. Never
                // downgrade it to the unsigned-device “send anyway” confirmation used by calls.
                state.timelineState.eventSink(TimelineEvent.ComputeVerifiedUserSendFailure(verifiedUserFailure))
            }
            else -> onJoinCallClick(isAudioCall)
        }
    }

    fun toggleSelection(event: TimelineItem.Event) {
        val eventId = event.eventId ?: return
        if (event.content.isBurnAfterRead || !event.content.canBeForwarded()) return
        selectedEvents = if (selectedEvents.any { it.eventId == eventId }) {
            selectedEvents.filterNot { it.eventId == eventId }
        } else {
            selectedEvents + event
        }
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedEvents = emptyList()
    }

    BackHandler(enabled = multiSelectMode) {
        exitMultiSelect()
    }

    val snackbarHostState = rememberSnackbarHostState(snackbarMessage = state.snackbarMessage)
    val roomActionSnackbarHostState = remember { SnackbarHostState() }
    val activeRoomActionSnackbarMessage = roomActionSnackbarMessage ?: state.favouriteSnackbarMessage
    val roomActionSnackbarText = activeRoomActionSnackbarMessage?.let { stringResource(it.messageResId) }
    LaunchedEffect(activeRoomActionSnackbarMessage?.id) {
        if (activeRoomActionSnackbarMessage != null && roomActionSnackbarText != null) {
            try {
                roomActionSnackbarHostState.showSnackbar(
                    message = roomActionSnackbarText,
                    duration = activeRoomActionSnackbarMessage.duration,
                )
            } finally {
                if (roomActionSnackbarMessage?.id == activeRoomActionSnackbarMessage.id) {
                    onRoomActionSnackbarDismissed(activeRoomActionSnackbarMessage.id)
                } else {
                    state.eventSink(MessagesEvent.DismissFavouriteSnackbar(activeRoomActionSnackbarMessage.id))
                }
            }
        }
    }

    var stableContainerHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val targetBottomInsetPx = maxOf(
        WindowInsets.imeAnimationTarget.getBottom(density),
        WindowInsets.navigationBars.getBottom(density),
    )
    val availableHeightAtImeTargetPx = (stableContainerHeightPx - targetBottomInsetPx).coerceAtLeast(0)
    val maxComposerHeightPx = if (availableHeightAtImeTargetPx == 0) {
        INITIAL_MAX_COMPOSER_HEIGHT_PX
    } else {
        (availableHeightAtImeTargetPx * MAX_COMPOSER_HEIGHT_RATIO).toInt()
    }

    // This is needed because the composer is inside an AndroidView that can't be affected by the FocusManager in Compose
    val localView = LocalView.current

    fun hidingKeyboard(block: () -> Unit) {
        localView.hideKeyboard()
        block()
    }

    fun onContentClick(event: TimelineItem.Event) {
        Timber.v("onMessageClick= ${event.id}")
        val relay = event.content as? TimelineItemRelayContent
        if (relay != null) {
            activeRelayEventId = event.eventId
            localView.hideKeyboard()
            return
        }
        val callRecord = event.content as? TimelineItemRtcNotificationContent
        if (callRecord != null) {
            when (val roomCallState = state.roomCallState) {
                RoomCallState.Unavailable -> Unit
                is RoomCallState.StandBy -> if (
                    roomCallState.canStartCall &&
                    (callRecord.callIntent != CallIntent.AUDIO || roomCallState.isDM)
                ) {
                    localView.hideKeyboard()
                    requestCall(callRecord.callIntent == CallIntent.AUDIO)
                }
                is RoomCallState.OnGoing -> if (roomCallState.canJoinCall && !roomCallState.isUserLocallyInTheCall) {
                    localView.hideKeyboard()
                    requestCall(roomCallState.isAudioCall)
                }
            }
            return
        }
        if (event.content.isBurnAfterReadUnopened) {
            // The sender sees only the fail-closed delivery shell. A recipient grant is never created for
            // the sender, so exposing an open action here would be both misleading and a security downgrade.
            if (!event.isMine) {
                event.eventId?.let { state.timelineState.eventSink(TimelineEvent.OpenBurnAfterRead(it)) }
            }
            localView.hideKeyboard()
            return
        }
        val hideKeyboard = onEventContentClick(state.timelineState.isLive, event)
        if (hideKeyboard) {
            localView.hideKeyboard()
        }
    }

    fun onMessageLongClick(event: TimelineItem.Event, anchorBounds: IntRect) {
        Timber.v("OnMessageLongClicked= ${event.id}")
        hidingKeyboard {
            state.actionListState.eventSink(
                ActionListEvent.ComputeForMessage(
                    event = event,
                    userEventPermissions = state.userEventPermissions,
                    anchorBounds = anchorBounds,
                )
            )
        }
    }

    // A long press can land while a just-sent message is still an optimistic local echo. Keep the
    // popup anchored, but upgrade its actions as soon as the same stable timeline row becomes the
    // authenticated event. Without this, the open popup permanently kept the local-only subset
    // (copy/withdraw/reactions) until the user dismissed it and long-pressed again.
    val openActionTarget = state.actionListState.target
    LaunchedEffect(openActionTarget, state.timelineState.timelineItems) {
        val selectedEvent = openActionTarget.eventOrNull() ?: return@LaunchedEffect
        val currentEvent = state.timelineState.timelineItems
            .filterIsInstance<TimelineItem.Event>()
            .firstOrNull { it.identifier() == selectedEvent.identifier() }
            ?: return@LaunchedEffect
        if (currentEvent.hasDifferentActionCapabilitiesFrom(selectedEvent)) {
            state.actionListState.eventSink(
                ActionListEvent.ComputeForMessage(
                    event = currentEvent,
                    userEventPermissions = state.userEventPermissions,
                    anchorBounds = openActionTarget.anchorBounds(),
                )
            )
        }
    }

    fun onActionSelected(action: TimelineItemAction, event: TimelineItem.Event) {
        if (action == TimelineItemAction.AddToCommon) {
            if (state.composerState.commonInfoRepository == null) return
            val current = currentTimelineItems.filterIsInstance<TimelineItem.Event>().firstOrNull { it.eventId == event.eventId } ?: return
            commonMessageCapture = captureCommonInfoMessage(current)?.takeIf { capture ->
                state.composerState.commonInfoRepository?.canImportSource(capture.source) == true
            }
        } else if (action == TimelineItemAction.MultiSelect) {
            multiSelectMode = true
            selectedEvents = listOf(event)
        } else {
            state.eventSink(MessagesEvent.HandleAction(action, event))
        }
    }

    fun onEmojiReactionClick(emoji: String, event: TimelineItem.Event) {
        state.eventSink(MessagesEvent.ToggleReaction(emoji, event.eventOrTransactionId))
    }

    fun onEmojiReactionLongClick(emoji: String, event: TimelineItem.Event) {
        if (event.eventId == null) return
        state.reactionSummaryState.eventSink(ReactionSummaryEvent.ShowReactionSummary(event.eventId, event.reactionsState.reactions, emoji))
    }

    fun onMoreReactionsClick(event: TimelineItem.Event) {
        state.customReactionState.eventSink(CustomReactionEvent.ShowCustomReactionSheet(event))
    }

    val commonCapture = commonMessageCapture
    val commonCaptureValid = commonCapture?.isValid(state.timelineState.timelineItems) == true &&
        commonCapture?.source?.let { state.composerState.commonInfoRepository?.canImportSource(it) } == true
    LaunchedEffect(commonCapture, commonCaptureValid) { if (!commonCaptureValid) commonMessageCapture = null }
    LaunchedEffect(commonCapture) {
        val source = commonCapture?.source as? io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource.Protected
            ?: return@LaunchedEffect
        delay((source.expiresAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L))
        if (commonMessageCapture == commonCapture) commonMessageCapture = null
    }

    val expandableState = rememberExpandableBottomSheetLayoutState()
    ExpandableBottomSheetLayout(
        modifier = modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    // Observe the stable window bounds outside animated IME/navigation padding.
                    // The cap is derived from the IME animation target, so it changes once per
                    // transition rather than invalidating the expandable layout on every frame.
                    stableContainerHeightPx = size.height
                }
                .imePadding()
                .navigationBarsPadding(),
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.statusBars,
                topBar = {
                    Column {
                        if (state.timelineState.timelineMode is Timeline.Mode.Thread) {
                            ThreadTopBar(
                                roomName = state.roomName,
                                roomAvatarData = state.roomAvatar,
                                heroes = state.heroes,
                                isTombstoned = state.isTombstoned,
                                onBackClick = onBackClick,
                            )
                        } else {
                            MessagesViewTopBar(
                                roomName = state.roomName,
                                roomAvatar = state.roomAvatar,
                                isTombstoned = state.isTombstoned,
                                heroes = state.heroes,
                                dmUserIdentityState = state.dmUserVerificationState,
                                sharedHistoryIcon = state.topBarSharedHistoryIcon,
                                onBackClick = { hidingKeyboard { onBackClick() } },
                                onRoomDetailsClick = {
                                    hidingKeyboard {
                                        state.dmOtherUserId?.let(onUserDataClick) ?: onRoomDetailsClick()
                                    }
                                },
                                menuActions = {
                                    MessagesMenuActions(
                                        displayThreads = state.timelineState.timelineMode !is Timeline.Mode.Thread && state.threads.hasThreads,
                                        roomCallState = if (state.directRoomRelationState == DirectRoomRelationState.NOT_DIRECT &&
                                            state.roomCallState is RoomCallState.OnGoing) RoomCallState.Unavailable else state.roomCallState,
                                        onJoinCallClick = ::requestCall,
                                        onThreadsListClick = onThreadsListClick,
                                        onRoomDetailsClick = { hidingKeyboard { onRoomDetailsClick() } },
                                    )
                                }
                            )
                        }
                        val ongoingCall = state.roomCallState as? RoomCallState.OnGoing
                        if (state.directRoomRelationState == DirectRoomRelationState.NOT_DIRECT &&
                            ongoingCall != null && !ongoingCall.isUserLocallyInTheCall) {
                            GroupCallBanner(
                                call = ongoingCall,
                                onJoin = { requestCall(ongoingCall.isAudioCall) },
                            )
                        }
                    }
                },
                content = { padding ->
                    Box(
                        modifier = Modifier
                                .padding(padding)
                                .consumeWindowInsets(padding)
                    ) {
                        MessagesViewContent(
                            floatingNavigationBlocked = state.voiceMessageComposerState.keepScreenOn ||
                                state.actionListState.target !is ActionListState.Target.None ||
                                state.readReceiptBottomSheetState.selectedEvent != null || state.showReinvitePrompt ||
                                state.customReactionState.target !is io.element.android.features.messages.impl.timeline.components.customreaction.CustomReactionState.Target.None ||
                                state.reactionSummaryState.target != null || activeRelayEventId != null ||
                                pendingLocalDeleteEventIds != null || pendingUnsignedDeviceCall != null || commonMessageCapture != null ||
                                state.timelineState.openingBurnAfterReadEventId != null || state.timelineState.activeBurnAfterReadEventId != null ||
                                state.timelineState.burnAfterReadOpenRejectedEventId != null || state.timelineState.messageShieldDialogData != null ||
                                state.voiceMessageComposerState.showPermissionRationaleDialog || state.voiceMessageComposerState.showSendFailureDialog ||
                                state.voiceMessageComposerState.showRecordingPermissionLostDialog,
                            state = state,
                            onContentClick = ::onContentClick,
                            onMessageLongClick = ::onMessageLongClick,
                            onUserDataClick = {
                                hidingKeyboard {
                                    state.eventSink(MessagesEvent.OnUserClicked(it))
                                }
                            },
                            onLinkClick = { link, customTab ->
                                if (customTab) {
                                    onLinkClick(link.url, true)
                                    // Do not check those links, they are internal link only
                                } else {
                                    state.linkState.eventSink(LinkEvent.OnLinkClick(link))
                                }
                            },
                            onReactionClick = ::onEmojiReactionClick,
                            onReactionLongClick = ::onEmojiReactionLongClick,
                            onMoreReactionsClick = ::onMoreReactionsClick,
                            onReadReceiptClick = { event ->
                                state.readReceiptBottomSheetState.eventSink(ReadReceiptBottomSheetEvent.EventSelected(event))
                            },
                            onSwipeToReply = { targetEvent ->
                                state.eventSink(MessagesEvent.HandleAction(TimelineItemAction.Reply, targetEvent))
                            },
                            forceJumpToBottomVisibility = forceJumpToBottomVisibility,
                            onViewAllPinnedMessagesClick = onViewAllPinnedMessagesClick,
                            knockRequestsBannerView = knockRequestsBannerView,
                            selectionMode = multiSelectMode,
                            selectedEventIds = selectedEventIds,
                            onSelectionToggle = ::toggleSelection,
                        )

                        SuggestionsPickerView(
                            modifier = Modifier
                                    .shadow(10.dp)
                                    .background(ElementTheme.colors.bgCanvasDefault)
                                    .align(Alignment.BottomStart)
                                    .heightIn(max = 230.dp),
                            roomId = state.roomId,
                            roomName = state.roomName,
                            roomAvatarData = state.roomAvatar,
                            suggestions = state.composerState.suggestions,
                            onSelectSuggestion = {
                                state.composerState.eventSink(MessageComposerEvent.InsertSuggestion(it))
                            }
                        )
                    }
                },
                snackbarHost = {
                    if (activeRoomActionSnackbarMessage != null) {
                        RoomActionSnackbarHost(hostState = roomActionSnackbarHostState)
                    } else {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.widthIn(max = 280.dp),
                        )
                    }
                },
            )
        },
        bottomSheetContent = {
            if (multiSelectMode) {
                MultiSelectActionBar(
                    selectedCount = selectedEvents.size,
                    onCancel = ::exitMultiSelect,
                    onForward = {
                        val eventIds = selectedEvents.sortedBy { it.sentTimeMillis }.mapNotNull { it.eventId }
                        if (eventIds.isNotEmpty()) state.eventSink(MessagesEvent.ForwardEvents(eventIds))
                        exitMultiSelect()
                    },
                    onFavourite = {
                        val eventIds = selectedEvents.mapNotNull { it.eventId }
                        if (eventIds.isNotEmpty()) state.eventSink(MessagesEvent.FavouriteEvents(eventIds))
                        exitMultiSelect()
                    },
                    onDelete = {
                        pendingLocalDeleteEventIds = selectedEvents.mapNotNull { it.eventId }
                    },
                )
            } else {
                MessagesViewComposerBottomSheetContents(
                    state = state,
                    commonEditorId = commonCapture?.takeIf { commonCaptureValid }?.draft?.id,
                    commonEditor = if (commonCapture != null && commonCaptureValid) state.composerState.commonInfoRepository?.let { repository ->
                        { panelModifier, close ->
                            CommonInfoPanel(repository = repository, initialItem = commonCapture.draft,
                                embedded = true, modifier = panelModifier,
                                canSave = { commonCapture.isValid(currentTimelineItems) && repository.canImportSource(commonCapture.source) },
                                onDismiss = { commonMessageCapture = null; close() },
                                onSaved = { commonMessageCapture = null; close() },
                                onInsert = { item, complete -> state.composerState.eventSink(MessageComposerEvent.InsertCommonInfo(item, complete)) },
                                onSend = { item, complete -> state.composerState.eventSink(MessageComposerEvent.SendCommonInfo(item, complete)) })
                        }
                    } else null,
                    onSendLocationClick = onSendLocationClick,
                    onCreatePollClick = onCreatePollClick,
                    onLinkClick = { url, customTab -> onLinkClick(url, customTab) },
                    onRoomSuccessorClick = { roomId ->
                        state.timelineState.eventSink(TimelineEvent.NavigateToPredecessorOrSuccessorRoom(roomId = roomId))
                    },
                )
            }
        },
        sheetDragHandle = @Composable { toggleAction ->
            if (multiSelectMode) {
                Unit
            } else if (state.composerState.showTextFormatting) {
                val expandA11yLabel = stringResource(CommonStrings.a11y_expand_message_text_field)
                val collapseA11yLabel = stringResource(CommonStrings.a11y_collapse_message_text_field)
                BottomSheetDragHandle(
                    modifier = Modifier.semantics {
                        role = Role.Button
                        // Accessibility action to toggle the bottom sheet state
                        val label = when (expandableState.position) {
                            ExpandableBottomSheetLayoutState.Position.COLLAPSED, ExpandableBottomSheetLayoutState.Position.DRAGGING -> expandA11yLabel
                            ExpandableBottomSheetLayoutState.Position.EXPANDED -> collapseA11yLabel
                        }
                        onClick(label) {
                            toggleAction()
                            true
                        }
                    }
                )
            } else {
                LaunchedEffect(Unit) {
                    // Ensure that the bottom sheet is collapsed
                    if (expandableState.position == ExpandableBottomSheetLayoutState.Position.EXPANDED) {
                        toggleAction()
                    }
                }
            }
        },
        isSwipeGestureEnabled = !multiSelectMode && state.composerState.showTextFormatting,
        state = expandableState,
        sheetShape = if (state.composerState.showTextFormatting || state.composerState.suggestions.isNotEmpty()) {
            MaterialTheme.shapes.large
        } else {
            RectangleShape
        },
        maxBottomSheetContentHeight = maxComposerHeightPx.toDp(),
    )

    activeRelayEventId?.let { relayEventId ->
        val activeEvent = state.timelineState.timelineItems
            .filterIsInstance<TimelineItem.Event>()
            .firstOrNull { it.eventId == relayEventId }
        val relay = activeEvent?.content as? TimelineItemRelayContent
        LaunchedEffect(relayEventId, relay?.closed) {
            if (relay == null) activeRelayEventId = null
        }
        if (relay != null) {
            RelayEditorDialog(
                initialTitle = relay.title,
                entries = relay.entries,
                itemDetails = relay.itemDetails,
                editableItemIds = relay.editableItemIds,
                initiator = activeEvent.safeSenderName,
                isCreating = false,
                canClose = activeEvent.isMine,
                closed = relay.closed,
                onSubmit = { _, entry, complete ->
                    state.composerState.eventSink(MessageComposerEvent.AppendRelay(relayEventId, entry, complete))
                },
                onCloseRelay = { complete ->
                    state.composerState.eventSink(MessageComposerEvent.CloseRelay(relayEventId, complete))
                },
                onEditEntry = { itemId, revision, entry, operationId, complete ->
                    state.composerState.eventSink(MessageComposerEvent.EditRelayEntry(relayEventId, itemId, revision, entry, complete, operationId))
                },
                loadPendingEdit = { complete ->
                    state.composerState.eventSink(MessageComposerEvent.LoadRelayEditRecovery(relayEventId, complete))
                },
                onDismiss = { activeRelayEventId = null },
            )
        }
    }

    state.timelineState.openingBurnAfterReadEventId?.let {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.screen_room_burn_after_read_opening_title)) },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.screen_room_burn_after_read_loading))
                }
            },
            confirmButton = {},
        )
    }

    state.timelineState.burnAfterReadOpenRejectedEventId?.let {
        AlertDialog(
            onDismissRequest = { state.timelineState.eventSink(TimelineEvent.DismissBurnAfterReadOpenFailure) },
            title = { Text(stringResource(R.string.screen_room_burn_after_read_rejected_title)) },
            text = { Text(stringResource(R.string.screen_room_burn_after_read_rejected_body)) },
            confirmButton = {
                TextButton(onClick = { state.timelineState.eventSink(TimelineEvent.DismissBurnAfterReadOpenFailure) }) {
                    Text(stringResource(CommonStrings.action_ok))
                }
            },
        )
    }

    state.timelineState.activeBurnAfterReadEventId?.let { activeEventId ->
        val activeEvent = state.timelineState.activeBurnAfterReadPreview
            ?.takeIf { it.eventId == activeEventId }
        val activeContent = activeEvent?.content
        val contentUnavailable = activeContent == null || activeContent.isBurnAfterReadUnopened
        LaunchedEffect(activeEventId, contentUnavailable) {
            if (contentUnavailable) {
                // An open request only marks the dialog active after KMS authorization succeeds. The Matrix
                // projection is committed synchronously, but the UI mapping still crosses multiple flows.
                // Keep this bounded fallback so a failed projection never leaves a live session behind.
                delay(BURN_AFTER_READ_PROJECTION_TIMEOUT_MILLIS)
                state.timelineState.eventSink(TimelineEvent.CloseBurnAfterRead)
            }
        }
        AlertDialog(
            onDismissRequest = { state.timelineState.eventSink(TimelineEvent.CloseBurnAfterRead) },
            icon = {
                Icon(
                    imageVector = CompoundIcons.VisibilityOn(),
                    contentDescription = null,
                    tint = ElementTheme.colors.iconAccentPrimary,
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.screen_room_burn_after_read_title),
                    style = ElementTheme.typography.fontHeadingSmMedium,
                    color = ElementTheme.colors.textPrimary,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ElementTheme.colors.bgSubtleSecondary, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (val content = activeContent?.takeUnless { it.isBurnAfterReadUnopened }) {
                        is TimelineItemTextBasedContent -> TimelineItemTextView(
                            content = content,
                            onLinkClick = {},
                            onLinkLongClick = {},
                        )
                        is TimelineItemImageContent -> {
                            AsyncImage(
                                model = MediaRequestData(
                                    source = content.mediaSource,
                                    kind = MediaRequestData.Kind.File(
                                        fileName = content.filename,
                                        mimeType = content.mimeType,
                                    ),
                                    cacheable = false,
                                ),
                                contentDescription = stringResource(CommonStrings.common_image),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 480.dp),
                            )
                            BurnAfterReadCaption(content.caption, content.formattedCaption)
                        }
                        is TimelineItemVideoContent -> BurnAfterReadAttachmentAction(
                            filename = content.filename,
                            caption = content.caption,
                            formattedCaption = content.formattedCaption,
                            onClick = { activeEvent?.let(::onContentClick) },
                        )
                        is TimelineItemAudioContent -> BurnAfterReadAttachmentAction(
                            filename = content.filename,
                            caption = content.caption,
                            formattedCaption = content.formattedCaption,
                            onClick = { activeEvent?.let(::onContentClick) },
                        )
                        is TimelineItemVoiceContent -> BurnAfterReadAttachmentAction(
                            filename = content.filename,
                            caption = content.caption,
                            formattedCaption = content.formattedCaption,
                            onClick = { activeEvent?.let(::onContentClick) },
                        )
                        is TimelineItemFileContent -> if (content.mimeType.supportsSecureOneTimePreview()) {
                            BurnAfterReadAttachmentAction(
                                filename = content.filename,
                                caption = content.caption,
                                formattedCaption = content.formattedCaption,
                                onClick = { activeEvent?.let(::onContentClick) },
                            )
                        } else {
                            Text(stringResource(R.string.screen_room_burn_after_read_office_unsupported))
                        }
                        else -> Text(stringResource(R.string.screen_room_burn_after_read_loading))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { state.timelineState.eventSink(TimelineEvent.CloseBurnAfterRead) }) {
                    Text(stringResource(R.string.screen_room_burn_after_read_close))
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = ElementTheme.colors.bgCanvasDefault,
        )
    }

    var endPollConfirmingEvent: TimelineItem.Event? by remember { mutableStateOf(null) }
    var locallyDeletingEvent: TimelineItem.Event? by remember { mutableStateOf(null) }
    var withdrawingEvent: TimelineItem.Event? by remember { mutableStateOf(null) }

    if (endPollConfirmingEvent != null) {
        ConfirmationDialog(
            content = stringResource(id = CommonStrings.common_poll_end_confirmation),
            onSubmitClick = {
                endPollConfirmingEvent?.let { event ->
                    onActionSelected(TimelineItemAction.EndPoll, event)
                }
                endPollConfirmingEvent = null
            },
            onDismiss = { endPollConfirmingEvent = null },
        )
    }

    pendingUnsignedDeviceCall?.let { (failedEvent, isAudioCall) ->
        ConfirmationDialog(
            title = stringResource(R.string.call_unsigned_device_confirmation_title),
            content = stringResource(R.string.call_unsigned_device_confirmation_content),
            submitText = stringResource(R.string.call_unsigned_device_confirmation_submit),
            cancelText = stringResource(CommonStrings.action_cancel),
            onSubmitClick = {
                state.eventSink(MessagesEvent.ResolveUnsignedDeviceAndStartCall(failedEvent, isAudioCall))
                pendingUnsignedDeviceCall = null
            },
            onDismiss = { pendingUnsignedDeviceCall = null },
        )
    }

    if (locallyDeletingEvent != null) {
        ConfirmationDialog(
            content = stringResource(R.string.message_delete_local_confirmation),
            submitText = stringResource(CommonStrings.action_delete),
            destructiveSubmit = true,
            onSubmitClick = {
                locallyDeletingEvent?.let { event -> onActionSelected(TimelineItemAction.DeleteLocal, event) }
                locallyDeletingEvent = null
            },
            onDismiss = { locallyDeletingEvent = null },
        )
    }

    if (pendingLocalDeleteEventIds != null) {
        ConfirmationDialog(
            content = stringResource(R.string.message_delete_local_confirmation),
            submitText = stringResource(CommonStrings.action_delete),
            destructiveSubmit = true,
            onSubmitClick = {
                pendingLocalDeleteEventIds?.takeIf { it.isNotEmpty() }?.let { eventIds ->
                    state.eventSink(MessagesEvent.DeleteLocalEvents(eventIds))
                }
                pendingLocalDeleteEventIds = null
                exitMultiSelect()
            },
            onDismiss = { pendingLocalDeleteEventIds = null },
        )
    }

    if (withdrawingEvent != null) {
        ConfirmationDialog(
            content = stringResource(R.string.message_withdraw_confirmation),
            submitText = stringResource(R.string.action_withdraw_message),
            onSubmitClick = {
                withdrawingEvent?.let { event -> onActionSelected(TimelineItemAction.Withdraw, event) }
                withdrawingEvent = null
            },
            onDismiss = { withdrawingEvent = null },
        )
    }


    ActionListView(
        state = state.actionListState,
        onSelectAction = { action: TimelineItemAction, event: TimelineItem.Event ->
            if (action == TimelineItemAction.EndPoll) {
                endPollConfirmingEvent = event
            } else if (action == TimelineItemAction.DeleteLocal) {
                locallyDeletingEvent = event
            } else if (action == TimelineItemAction.Withdraw) {
                withdrawingEvent = event
            } else {
                onActionSelected(action, event)
            }
        },
        onCustomReactionClick = { event ->
            state.customReactionState.eventSink(CustomReactionEvent.ShowCustomReactionSheet(event))
        },
        onEmojiReactionClick = ::onEmojiReactionClick,
        onVerifiedUserSendFailureClick = { event ->
            state.timelineState.eventSink(TimelineEvent.ComputeVerifiedUserSendFailure(event))
        },
    )

    CustomReactionBottomSheet(
        state = state.customReactionState,
        onSelectEmoji = { uniqueId, emoji ->
            state.eventSink(MessagesEvent.ToggleReaction(emoji.unicode, uniqueId))
        }
    )

    ReactionSummaryView(state = state.reactionSummaryState)
    ReadReceiptBottomSheet(
        state = state.readReceiptBottomSheetState,
        onUserDataClick = onUserDataClick,
    )
    ReinviteDialog(state = state)
    LinkView(
        onLinkValid = { link ->
            onLinkClick(link.url, false)
        },
        state = state.linkState,
    )
}

private fun ActionListState.Target.eventOrNull(): TimelineItem.Event? = when (this) {
    ActionListState.Target.None -> null
    is ActionListState.Target.Loading -> event
    is ActionListState.Target.Success -> event
}

private fun ActionListState.Target.anchorBounds(): IntRect =
    (this as? ActionListState.Target.Success)?.anchorBounds ?: IntRect.Zero

internal fun TimelineItem.Event.hasDifferentActionCapabilitiesFrom(previous: TimelineItem.Event): Boolean =
    eventId != previous.eventId ||
        localSendState?.javaClass != previous.localSendState?.javaClass ||
        isEditable != previous.isEditable ||
        canBeRepliedTo != previous.canBeRepliedTo ||
        commonInfoMessageText(this) != commonInfoMessageText(previous) ||
        content::class != previous.content::class

private const val INITIAL_MAX_COMPOSER_HEIGHT_PX = 120
private const val MAX_COMPOSER_HEIGHT_RATIO = 0.5f
private const val BURN_AFTER_READ_PROJECTION_TIMEOUT_MILLIS = 3_000L

@Composable
private fun BurnAfterReadAttachmentAction(
    filename: String,
    caption: String?,
    formattedCaption: CharSequence?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = filename,
            textAlign = TextAlign.Center,
            style = ElementTheme.typography.fontBodyMdMedium,
            color = ElementTheme.colors.textPrimary,
        )
        BurnAfterReadCaption(caption, formattedCaption)
        TextButton(onClick = onClick) {
            Text(stringResource(R.string.screen_room_burn_after_read_open_attachment))
        }
    }
}

@Composable
private fun BurnAfterReadCaption(caption: String?, formattedCaption: CharSequence?) {
    val text = formattedCaption ?: caption ?: return
    CompositionLocalProvider(
        LocalContentColor provides ElementTheme.colors.textPrimary,
        LocalTextStyle provides ElementTheme.typography.fontBodyLgRegular,
    ) {
        EditorStyledText(
            text = text,
            onLinkClickedListener = {},
            onLinkLongClickedListener = {},
            style = ElementRichTextEditorStyle.textStyle(),
            releaseOnDetach = false,
        )
    }
}

private fun String?.supportsSecureOneTimePreview(): Boolean =
    !this.isNullOrBlank()

@Composable
internal fun RowScope.MessagesMenuActions(
    displayThreads: Boolean,
    roomCallState: RoomCallState,
    onJoinCallClick: (isAudioCall: Boolean) -> Unit,
    onThreadsListClick: () -> Unit,
    onRoomDetailsClick: () -> Unit,
) {
    if (displayThreads) {
        Icon(
            modifier = Modifier.clickable(enabled = true, onClick = onThreadsListClick),
            imageVector = CompoundIcons.ThreadsSolid(),
            contentDescription = stringResource(CommonStrings.common_threads),
        )
        Spacer(Modifier.width(8.dp))
    }
    CallMenuItem(
        roomCallState = roomCallState,
        onJoinCallClick = onJoinCallClick,
    )
    IconButton(onClick = onRoomDetailsClick) {
        Icon(
            imageVector = CompoundIcons.OverflowHorizontal(),
            contentDescription = stringResource(CommonStrings.common_other_options),
        )
    }
}

@Composable
private fun ReinviteDialog(state: MessagesState) {
    if (state.showReinvitePrompt) {
        ConfirmationDialog(
            title = stringResource(id = R.string.screen_room_invite_again_alert_title),
            content = stringResource(id = R.string.screen_room_invite_again_alert_message),
            cancelText = stringResource(id = CommonStrings.action_cancel),
            submitText = stringResource(id = CommonStrings.action_invite),
            onSubmitClick = { state.eventSink(MessagesEvent.InviteDialogDismissed(InviteDialogAction.Invite)) },
            onDismiss = { state.eventSink(MessagesEvent.InviteDialogDismissed(InviteDialogAction.Cancel)) }
        )
    }
}

@Composable
private fun MessagesViewContent(
    floatingNavigationBlocked: Boolean = false,
    state: MessagesState,
    onContentClick: (TimelineItem.Event) -> Unit,
    onUserDataClick: (MatrixUser) -> Unit,
    onLinkClick: (Link, Boolean) -> Unit,
    onReactionClick: (key: String, TimelineItem.Event) -> Unit,
    onReactionLongClick: (key: String, TimelineItem.Event) -> Unit,
    onMoreReactionsClick: (TimelineItem.Event) -> Unit,
    onReadReceiptClick: (TimelineItem.Event) -> Unit,
    onMessageLongClick: (TimelineItem.Event, IntRect) -> Unit,
    selectionMode: Boolean,
    selectedEventIds: Set<EventId>,
    onSelectionToggle: (TimelineItem.Event) -> Unit,
    onViewAllPinnedMessagesClick: () -> Unit,
    forceJumpToBottomVisibility: Boolean,
    onSwipeToReply: (TimelineItem.Event) -> Unit,
    modifier: Modifier = Modifier,
    knockRequestsBannerView: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
                .fillMaxSize(),
    ) {
        if (state.voiceMessageComposerState.showPermissionRationaleDialog) {
            VoiceMessagePermissionRationaleDialog(
                onContinue = {
                    state.voiceMessageComposerState.eventSink(VoiceMessageComposerEvent.AcceptPermissionRationale)
                },
                onDismiss = {
                    state.voiceMessageComposerState.eventSink(VoiceMessageComposerEvent.DismissPermissionsRationale)
                },
                appName = state.appName
            )
        }
        if (state.voiceMessageComposerState.showSendFailureDialog) {
            VoiceMessageSendingFailedDialog(
                onDismiss = { state.voiceMessageComposerState.eventSink(VoiceMessageComposerEvent.DismissSendFailureDialog) },
            )
        }
        if (state.voiceMessageComposerState.showRecordingPermissionLostDialog) {
            VoiceRecordingPermissionLostDialog(
                onDismiss = { state.voiceMessageComposerState.eventSink(VoiceMessageComposerEvent.DismissRecordingPermissionLostDialog) },
            )
        }

        Box {
            val scrollBehavior = PinnedMessagesBannerViewDefaults.rememberScrollBehavior(
                pinnedMessagesCount = (state.pinnedMessagesBannerState as? PinnedMessagesBannerState.Visible)?.pinnedMessagesCount() ?: 0,
            )
            val density = LocalDensity.current
            var overlayBannerHeightDp by remember { mutableStateOf(0.dp) }

            TimelineView(
                floatingNavigationBlocked = floatingNavigationBlocked,
                state = state.timelineState,
                viewportScopeId = state.roomId.value,
                timelineProtectionState = state.timelineProtectionState,
                onUserDataClick = onUserDataClick,
                onLinkClick = { link -> onLinkClick(link, false) },
                onContentClick = onContentClick,
                onMessageLongClick = onMessageLongClick,
                onSwipeToReply = onSwipeToReply,
                onReactionClick = onReactionClick,
                onReactionLongClick = onReactionLongClick,
                onMoreReactionsClick = onMoreReactionsClick,
                onReadReceiptClick = onReadReceiptClick,
                selectionMode = selectionMode,
                selectedEventIds = selectedEventIds,
                onSelectionToggle = onSelectionToggle,
                reEditableWithdrawnEventIds = state.reEditableWithdrawnEventIds,
                onReEditWithdrawn = { state.eventSink(MessagesEvent.ReEditWithdrawn(it)) },
                forceJumpToBottomVisibility = forceJumpToBottomVisibility,
                nestedScrollConnection = scrollBehavior.nestedScrollConnection,
                floatingDateTopOffset = overlayBannerHeightDp,
            )

            if (state.timelineState.timelineMode !is Timeline.Mode.Thread) {
                Column(
                    modifier = Modifier.onSizeChanged { overlayBannerHeightDp = with(density) { it.height.toDp() } }
                ) {
                    AnimatedVisibility(
                        visible = state.showHistoryRecoveryBanner,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        HistoryRecoveryBanner(
                            status = state.timelineState.historyRecoveryStatus,
                            onClick = {
                                state.eventSink(MessagesEvent.OpenHistoryRecovery(state.timelineState.historyRecoveryStatus))
                            },
                        )
                    }
                    AnimatedVisibility(
                        visible = state.pinnedMessagesBannerState is PinnedMessagesBannerState.Visible && scrollBehavior.isVisible,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        fun focusOnPinnedEvent(eventId: EventId) {
                            state.timelineState.eventSink(
                                TimelineEvent.FocusOnEvent(eventId = eventId, debounce = FOCUS_ON_PINNED_EVENT_DEBOUNCE_DURATION_IN_MILLIS.milliseconds)
                            )
                        }
                        PinnedMessagesBannerView(
                            state = state.pinnedMessagesBannerState,
                            onClick = ::focusOnPinnedEvent,
                            onViewAllClick = onViewAllPinnedMessagesClick,
                            onUnpinClick = if (state.userEventPermissions.canPinUnpin) {
                                { eventId -> state.eventSink(MessagesEvent.UnpinEvent(eventId)) }
                            } else {
                                null
                            },
                        )
                    }
                    if (state.showLiveLocationShareBanner) {
                        LiveLocationSharingBanner(
                            onClick = { state.eventSink(MessagesEvent.ShowLiveLocationShare) },
                            onStopClick = { state.eventSink(MessagesEvent.StopLiveLocationShare) },
                            pausedState = state.pausedLiveLocationShare,
                        )
                    }
                }
            }

            knockRequestsBannerView()
        }
    }
}

@Composable
private fun HistoryRecoveryBanner(status: HistoryRecoveryStatus, onClick: () -> Unit) {
    if (!status.shouldShowRoomRecoveryBanner()) return
    val text = when (status) {
        HistoryRecoveryStatus.IDLE -> return
        HistoryRecoveryStatus.WAITING_FOR_MATRIX_RECOVERY -> stringResource(R.string.screen_room_history_recovery_matrix_required)
        HistoryRecoveryStatus.WAITING_FOR_MATRIX_VERIFICATION -> stringResource(R.string.screen_room_history_recovery_matrix_verification_required)
        HistoryRecoveryStatus.WAITING_FOR_DEVICE_TRUST -> stringResource(R.string.screen_room_history_recovery_device_trust_required)
        HistoryRecoveryStatus.WAITING_FOR_NETWORK -> stringResource(R.string.screen_room_history_recovery_waiting_for_network)
        HistoryRecoveryStatus.AUTHORIZING_DEVICE -> stringResource(R.string.screen_room_history_recovery_authorizing)
        HistoryRecoveryStatus.WAITING_FOR_OLD_DEVICE -> stringResource(R.string.screen_room_history_recovery_waiting_for_old_device)
        HistoryRecoveryStatus.WAITING_FOR_ADMIN_APPROVAL -> stringResource(R.string.screen_room_history_recovery_waiting_for_admin)
        HistoryRecoveryStatus.RETRYING -> stringResource(R.string.screen_room_history_recovery_retrying)
        HistoryRecoveryStatus.REQUEST_EXPIRED -> stringResource(R.string.screen_room_history_recovery_request_expired)
        HistoryRecoveryStatus.KMS_UNAVAILABLE -> stringResource(R.string.screen_room_history_recovery_kms_unavailable)
        HistoryRecoveryStatus.STANDARD_AUTHORIZED -> stringResource(R.string.screen_room_history_recovery_partial)
        HistoryRecoveryStatus.IDENTITY_RESET_UNRECOVERABLE -> return
        HistoryRecoveryStatus.FAILED_CLOSED -> stringResource(R.string.screen_room_history_recovery_failed_closed)
    }
    val showProgress = status in setOf(
        HistoryRecoveryStatus.AUTHORIZING_DEVICE,
        HistoryRecoveryStatus.RETRYING,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElementTheme.colors.bgSubtleSecondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(16.dp),
                strokeWidth = 2.dp,
                color = ElementTheme.colors.iconPrimary,
            )
        }
        Text(
            modifier = Modifier.weight(1f),
            text = text,
            color = ElementTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Icon(
            imageVector = CompoundIcons.ChevronRight(),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = ElementTheme.colors.iconSecondary,
        )
    }
}

/**
 * Transient recovery work is deliberately silent inside a room. It may refresh missing protected projections in
 * the background, but must not resize the timeline or look like a broken loading loop. Only stable, actionable
 * states occupy room UI. Security remains fail-closed in the timeline projection layer.
 */
internal fun HistoryRecoveryStatus.shouldShowRoomRecoveryBanner(): Boolean = when (this) {
    HistoryRecoveryStatus.WAITING_FOR_MATRIX_RECOVERY,
    HistoryRecoveryStatus.WAITING_FOR_MATRIX_VERIFICATION,
    HistoryRecoveryStatus.WAITING_FOR_DEVICE_TRUST,
    HistoryRecoveryStatus.WAITING_FOR_OLD_DEVICE,
    HistoryRecoveryStatus.WAITING_FOR_ADMIN_APPROVAL,
    HistoryRecoveryStatus.REQUEST_EXPIRED,
    HistoryRecoveryStatus.KMS_UNAVAILABLE,
    HistoryRecoveryStatus.FAILED_CLOSED -> true
    HistoryRecoveryStatus.IDLE,
    HistoryRecoveryStatus.WAITING_FOR_NETWORK,
    HistoryRecoveryStatus.AUTHORIZING_DEVICE,
    HistoryRecoveryStatus.RETRYING,
    HistoryRecoveryStatus.STANDARD_AUTHORIZED,
    HistoryRecoveryStatus.IDENTITY_RESET_UNRECOVERABLE -> false
}

@Composable
private fun MultiSelectActionBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onForward: () -> Unit,
    onFavourite: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElementTheme.colors.bgCanvasDefault)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(CommonStrings.action_cancel)) }
            Text(text = stringResource(R.string.message_selected_count, selectedCount))
            Spacer(Modifier.width(48.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(enabled = selectedCount > 0, onClick = onForward) { Text(stringResource(CommonStrings.action_forward)) }
            TextButton(enabled = selectedCount > 0, onClick = onFavourite) { Text(stringResource(CommonStrings.common_favourite)) }
            TextButton(enabled = selectedCount > 0, onClick = onDelete) { Text(stringResource(CommonStrings.action_delete)) }
        }
    }
}

@Composable
private fun MessagesViewComposerBottomSheetContents(
    state: MessagesState,
    commonEditor: (@Composable (Modifier, () -> Unit) -> Unit)? = null,
    commonEditorId: String? = null,
    onRoomSuccessorClick: (RoomId) -> Unit,
    onLinkClick: (String, Boolean) -> Unit,
    onSendLocationClick: () -> Unit,
    onCreatePollClick: () -> Unit,
) {
    when {
        state.successorRoom != null -> {
            SuccessorRoomBanner(roomSuccessor = state.successorRoom, onRoomSuccessorClick = onRoomSuccessorClick)
        }
        state.userEventPermissions.canSendMessage -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Do not show the identity change if user is composing a Rich message or is seeing suggestion(s).
                if (state.composerState.suggestions.isEmpty() &&
                    state.composerState.textEditorState is TextEditorState.Markdown) {
                    IdentityChangeStateView(
                        state = state.identityChangeState,
                        onLinkClick = onLinkClick,
                    )
                }
                val verificationViolation = state.identityChangeState.roomMemberIdentityStateChanges.firstOrNull {
                    it.identityState == IdentityState.VerificationViolation
                }
                if (verificationViolation != null) {
                    DisabledComposerView(modifier = Modifier.fillMaxWidth())
                } else {
                    MessageComposerView(
                        commonEditor = commonEditor, commonEditorId = commonEditorId,
                        state = state.composerState,
                        voiceMessageState = state.voiceMessageComposerState,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AttachmentsPanel(
                        state = state.composerState,
                        onSendLocationClick = onSendLocationClick,
                        onCreatePollClick = onCreatePollClick,
                        enableTextFormatting = state.enableTextFormatting,
                    )
                }
            }
        }
        else -> {
            CantSendMessageBanner(waitingForFriendAcceptance = state.directRoomRelationState.isPendingRequest)
        }
    }
}

@Composable
private fun CantSendMessageBanner(waitingForFriendAcceptance: Boolean) {
    Row(
        modifier = Modifier
                .fillMaxWidth()
                .background(ElementTheme.colors.bgSubtleSecondary)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (waitingForFriendAcceptance) {
                stringResource(R.string.screen_room_waiting_for_friend_acceptance)
            } else {
                stringResource(id = R.string.screen_room_timeline_no_permission_to_post)
            },
            color = ElementTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun SuccessorRoomBanner(
    roomSuccessor: SuccessorRoom,
    onRoomSuccessorClick: (RoomId) -> Unit,
    modifier: Modifier = Modifier,
) {
    ComposerAlertMolecule(
        avatar = null,
        content = stringResource(R.string.screen_room_timeline_tombstoned_room_message).toAnnotatedString(),
        onSubmitClick = { onRoomSuccessorClick(roomSuccessor.roomId) },
        modifier = modifier,
        submitText = stringResource(R.string.screen_room_timeline_tombstoned_room_action)
    )
}

@PreviewsDayNight
@Composable
internal fun MessagesViewPreview(@PreviewParameter(MessagesStateProvider::class) state: MessagesState) = ElementPreview {
    MessagesView(
        state = state,
        onBackClick = {},
        onRoomDetailsClick = {},
        onEventContentClick = { _, _ -> false },
        onUserDataClick = {},
        onLinkClick = { _, _ -> },
        onSendLocationClick = {},
        onCreatePollClick = {},
        onJoinCallClick = {},
        onViewAllPinnedMessagesClick = { },
        forceJumpToBottomVisibility = true,
        knockRequestsBannerView = {},
        onThreadsListClick = {},
    )
}

@Preview
@Composable
internal fun MessagesViewA11yPreview() = ElementPreview {
    val content = aTimelineItemTextContent(
        body = "A message content"
    )
    MessagesView(
        state = aMessagesState(
            roomName = "A DM with a very looong name",
            dmUserVerificationState = IdentityState.VerificationViolation,
            timelineState = aTimelineState(
                timelineItems = persistentListOf(
                    // 1 items with isMine = false
                    aTimelineItemEvent(
                        isMine = false,
                        content = content,
                        groupPosition = TimelineItemGroupPosition.None,
                        sendState = LocalEventSendState.Failed.Unknown("Message failed to send"),
                    ),
                    // A state event on top of it
                    aTimelineItemEvent(
                        isMine = false,
                        content = aTimelineItemStateEventContent(),
                        groupPosition = TimelineItemGroupPosition.None
                    ),
                    // 1 item with isMine = true
                    aTimelineItemEvent(
                        isMine = true,
                        content = content,
                        groupPosition = TimelineItemGroupPosition.None
                    ),
                    // A grouped event on top of it
                    aGroupedEvents(),
                    // A day separator
                    aTimelineItemDaySeparator(),
                ),
                // Render a focused event for an event with sender information displayed
                focusedEventIndex = 2,
            )
        ),
        onBackClick = {},
        onRoomDetailsClick = {},
        onEventContentClick = { _, _ -> false },
        onUserDataClick = {},
        onLinkClick = { _, _ -> },
        onSendLocationClick = {},
        onCreatePollClick = {},
        onJoinCallClick = {},
        onViewAllPinnedMessagesClick = {},
        onThreadsListClick = {},
        forceJumpToBottomVisibility = true,
        knockRequestsBannerView = {},
    )
}
