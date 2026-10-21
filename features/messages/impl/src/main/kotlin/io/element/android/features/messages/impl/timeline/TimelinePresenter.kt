/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.location.api.live.ActiveLiveLocationShareManager
import io.element.android.features.messages.api.MessageReferenceStore
import io.element.android.features.messages.impl.MessagesNavigator
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.features.messages.impl.UserEventPermissions
import io.element.android.features.messages.impl.crypto.sendfailure.resolve.ResolveVerifiedUserSendFailureEvent
import io.element.android.features.messages.impl.crypto.sendfailure.resolve.ResolveVerifiedUserSendFailureState
import io.element.android.features.messages.impl.timeline.components.MessageShieldData
import io.element.android.features.messages.impl.timeline.factories.TimelineItemsFactory
import io.element.android.features.messages.impl.timeline.factories.TimelineItemsFactoryConfig
import io.element.android.features.messages.impl.timeline.model.NewEventState
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemTypingNotificationModel
import io.element.android.features.messages.impl.typing.TypingNotificationState
import io.element.android.features.messages.impl.userEventPermissions
import io.element.android.features.messages.impl.voicemessages.timeline.RedactedVoiceMessageManager
import io.element.android.features.poll.api.actions.EndPollAction
import io.element.android.features.poll.api.actions.SendPollResponseAction
import io.element.android.features.roomcall.api.RoomCallState
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.asEventId
import io.element.android.libraries.matrix.api.encryption.HiddenMessageRecoveryInventory
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.powerlevels.permissionsAsState
import io.element.android.libraries.matrix.api.room.roomMembers
import io.element.android.libraries.matrix.api.timeline.ReceiptType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.TimelineItemEventOrigin
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicyRevisionProvider
import io.element.android.security.contentprotection.api.RedactionReasonResolver
import io.element.android.security.contentprotection.api.TrustedRedactionReason
import io.element.android.services.analytics.api.AnalyticsLongRunningTransaction.DisplayFirstTimelineItems
import io.element.android.services.analytics.api.AnalyticsLongRunningTransaction.NotificationToMessage
import io.element.android.services.analytics.api.AnalyticsLongRunningTransaction.OpenRoom
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.analytics.api.finishLongRunningTransaction
import io.element.android.services.analyticsproviders.api.AnalyticsUserData
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest

const val FOCUS_ON_PINNED_EVENT_DEBOUNCE_DURATION_IN_MILLIS = 200L

@AssistedInject
class TimelinePresenter(
    timelineItemsFactoryCreator: TimelineItemsFactory.Creator,
    private val room: JoinedRoom,
    private val dispatchers: CoroutineDispatchers,
    @SessionCoroutineScope
    private val sessionCoroutineScope: CoroutineScope,
    @Assisted private val navigator: MessagesNavigator,
    private val redactedVoiceMessageManager: RedactedVoiceMessageManager,
    private val sendPollResponseAction: SendPollResponseAction,
    private val endPollAction: EndPollAction,
    private val sessionPreferencesStore: SessionPreferencesStore,
    @Assisted private val timelineController: TimelineController,
    private val timelineItemIndexer: TimelineItemIndexer = TimelineItemIndexer(),
    private val resolveVerifiedUserSendFailurePresenter: Presenter<ResolveVerifiedUserSendFailureState>,
    private val typingNotificationPresenter: Presenter<TypingNotificationState>,
    private val roomCallStatePresenter: Presenter<RoomCallState>,
    private val featureFlagService: FeatureFlagService,
    private val analyticsService: AnalyticsService,
    private val liveLocationShareManager: ActiveLiveLocationShareManager,
    private val dataProtectionPolicyProvider: DataProtectionPolicyProvider,
    private val dataProtectionPolicyRevisionProvider: DataProtectionPolicyRevisionProvider,
    private val messageReferenceStore: MessageReferenceStore,
    private val redactionReasonResolver: RedactionReasonResolver,
    private val hiddenMessageRecoveryInventory: HiddenMessageRecoveryInventory,
    private val snackbarDispatcher: SnackbarDispatcher,
) : Presenter<TimelineState> {
    private val tag = "TimelinePresenter"

    @AssistedFactory
    interface Factory {
        fun create(
            timelineController: TimelineController,
            navigator: MessagesNavigator
        ): TimelinePresenter
    }

    private val timelineItemsFactory: TimelineItemsFactory = timelineItemsFactoryCreator.create(
        config = TimelineItemsFactoryConfig(
            computeReadReceipts = true,
            computeReactions = true,
        )
    )
    private var timelineItems by mutableStateOf<ImmutableList<TimelineItem>>(persistentListOf())
    private var latestVisibleTimelineItems: ImmutableList<TimelineItem> = persistentListOf()
    private var consumedBurnAfterReadEventIds by mutableStateOf(persistentSetOf<EventId>())
    private var retainedBurnAfterReadItems by mutableStateOf<Map<EventId, RetainedTimelineItem>>(emptyMap())
    private var destroyingBurnAfterReadEventIds by mutableStateOf(persistentSetOf<EventId>())
    private var retainedMessageRemovalItems by mutableStateOf<Map<EventId, RetainedTimelineItem>>(emptyMap())
    private var pendingMessageRemovalEventIds by mutableStateOf(persistentSetOf<EventId>())
    private var destroyingMessageRemovalEventIds by mutableStateOf(persistentSetOf<EventId>())
    private var terminalRedactedEventIds by mutableStateOf(persistentSetOf<EventId>())
    private var latestClassifiableTimelineItems: ImmutableList<TimelineItem> = persistentListOf()
    private var trustedRedactionReasons by mutableStateOf<Map<EventId, TrustedRedactionReason>>(emptyMap())
    private val pendingRedactionReasonIds = mutableSetOf<EventId>()
    private val completedRedactionReasonIds = mutableSetOf<EventId>()
    private var removalPresentationGeneration = 0L

    private val focusRequestState: MutableState<FocusRequestState> = mutableStateOf(FocusRequestState.None)

    @Composable
    override fun present(): TimelineState {
        DisposableEffect(room.roomId) {
            val generation = ++removalPresentationGeneration
            onDispose {
                if (removalPresentationGeneration == generation) {
                    removalPresentationGeneration++
                    clearRemovalPresentationState()
                }
            }
        }
        LaunchedEffect(Unit) {
            val parent = analyticsService.getLongRunningTransaction(OpenRoom)
            analyticsService.startLongRunningTransaction(DisplayFirstTimelineItems, parent)
        }

        val localScope = rememberCoroutineScope()

        val timelineMode = remember { timelineController.mainTimelineMode() }

        val lastReadReceiptId = rememberSaveable { mutableStateOf<EventId?>(null) }
        val previousViewedEventId = remember(room.roomId) { mutableStateOf<EventId?>(null) }
        val previousPositionLoaded = remember(room.roomId) { mutableStateOf(false) }
        val lastVisibleEventId = remember(room.roomId) { mutableStateOf<EventId?>(null) }
        var readingNavigation by remember(room.roomId) { mutableStateOf(io.element.android.features.messages.api.RoomReadingNavigation()) }
        var entryNavigationCaptured by remember(room.roomId) { mutableStateOf(false) }
        var navigatingToFirstNewMessage by remember(room.roomId) { mutableStateOf(false) }
        var entryCandidateIds by remember(room.roomId) { mutableStateOf(emptyList<EventId>()) }
        var newMessageDividerEventId by remember(room.roomId) { mutableStateOf<EventId?>(null) }
        val newMessageFocusJob = remember(room.roomId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        var pendingReadIndex by remember(room.roomId) { mutableStateOf<Int?>(null) }
        var readViewportActive by remember(room.roomId) { mutableStateOf(false) }
        LaunchedEffect(room.roomId) {
            previousViewedEventId.value = if (timelineMode is Timeline.Mode.Live) timelineController.lastViewedPosition() else null
            if (timelineMode is Timeline.Mode.Live) readingNavigation = timelineController.readingNavigation()
            previousPositionLoaded.value = true
        }
        suspend fun captureEntryNavigation(items: List<TimelineItem>): Boolean {
            if (entryNavigationCaptured) return true
            if (!previousPositionLoaded.value) {
                readingNavigation = timelineController.readingNavigation()
            }
            if (items.none { it.events().any { event -> event.eventId != null } }) {
                return false
            }
            if (timelineMode is Timeline.Mode.Live) {
                // Do not paginate the entire history merely to find an unloaded unread marker.
                // Capture identities already known; unknown boundaries remain unknown at the live edge.
                readingNavigation = captureNewMessageNavigation(items, readingNavigation)
                newMessageDividerEventId = readingNavigation.firstNewEventId?.let(::EventId)
                val firstIndex = newMessageItemIndex(items, readingNavigation.firstNewEventId)
                if (firstIndex >= 0) {
                    entryCandidateIds = items.take(firstIndex + 1).flatMap { it.events() }
                        .filter { it.isIncomingMessage() }.mapNotNull { it.eventId }.asReversed()
                }
                runCatching { timelineController.saveReadingNavigation(readingNavigation) }
            }
            entryNavigationCaptured = true
            return true
        }
        LaunchedEffect(previousPositionLoaded.value, timelineItems) {
            if (previousPositionLoaded.value) captureEntryNavigation(timelineItems)
        }
        LaunchedEffect(timelineItems, entryNavigationCaptured) {
            if (!entryNavigationCaptured || navigatingToFirstNewMessage) return@LaunchedEffect
            val first = readingNavigation.firstNewEventId?.let(::EventId) ?: return@LaunchedEffect
            if (first !in entryCandidateIds) return@LaunchedEffect
            val available = timelineItems.flatMap { it.events() }.any { it.eventId == first && it.isIncomingMessage() }
            if (!available) {
                val next = nextAvailableNewMessage(timelineItems, first, entryCandidateIds)
                readingNavigation = readingNavigation.copy(firstNewEventId = next?.value,
                    hintRemainingMillis = if (next == null) 0L else readingNavigation.hintRemainingMillis)
                newMessageDividerEventId = next
                runCatching { timelineController.saveReadingNavigation(readingNavigation) }
            }
        }
        LaunchedEffect(previousPositionLoaded.value, lastVisibleEventId.value) {
            if (timelineMode is Timeline.Mode.Live && previousPositionLoaded.value) {
                lastVisibleEventId.value?.let { eventId ->
                    delay(300L)
                    sessionCoroutineScope.launch { runCatching { timelineController.saveLastViewedPosition(eventId) } }
                }
            }
        }
        DisposableEffect(room.roomId) {
            onDispose {
                if (timelineMode is Timeline.Mode.Live && previousPositionLoaded.value) {
                    lastVisibleEventId.value?.let { eventId ->
                        sessionCoroutineScope.launch { runCatching { timelineController.saveLastViewedPosition(eventId) } }
                    }
                }
            }
        }

        val roomInfo by room.roomInfoFlow.collectAsState()
        val pendingMediaSendPresentations by timelineController.pendingMediaSendPresentations().collectAsState()

        val prevMostRecentItemId = rememberSaveable { mutableStateOf<UniqueId?>(null) }

        val newEventState = remember { mutableStateOf(NewEventState.None) }
        val messageShieldDialogData: MutableState<MessageShieldData?> = remember { mutableStateOf(null) }
        val activeBurnAfterReadEventId = remember { mutableStateOf<EventId?>(null) }
        val openingBurnAfterReadEventId = remember { mutableStateOf<EventId?>(null) }
        val burnAfterReadOpenRejectedEventId = remember { mutableStateOf<EventId?>(null) }
        val activeBurnAfterReadPreview = remember { mutableStateOf<TimelineItem.Event?>(null) }

        val resolveVerifiedUserSendFailureState = resolveVerifiedUserSendFailurePresenter.present()
        val prefersReadReceipts by remember {
            sessionPreferencesStore.isRenderReadReceiptsEnabled()
        }.collectAsState(initial = true)
        val policyRevision by dataProtectionPolicyRevisionProvider.policyRevision.collectAsState()
        val managedReadReceiptsEnabled = remember(roomInfo.isDm, policyRevision) {
            roomInfo.isDm || dataProtectionPolicyProvider.current().policy.allowGroupReadReceipts
        }
        val renderReadReceipts = prefersReadReceipts && managedReadReceiptsEnabled
        LaunchedEffect(entryNavigationCaptured, pendingReadIndex, readViewportActive) {
            val index = pendingReadIndex ?: return@LaunchedEffect
            if (!entryNavigationCaptured || !readViewportActive) return@LaunchedEffect
            pendingReadIndex = null
            val sendPublicReadReceipts = sessionPreferencesStore.isSendPublicReadReceiptsEnabled().first()
            sendReadReceiptIfNeeded(index, timelineItems, lastReadReceiptId,
                roomReadReceiptType(roomInfo.isDm, managedReadReceiptsEnabled, sendPublicReadReceipts),
                canRead = { readViewportActive })
        }
        val isLive by remember {
            timelineController.isLive()
        }.collectAsState(initial = true)
        val recoveryStatus by hiddenMessageRecoveryInventory.recoveryStatus.collectAsState()
        val candidateCountByRoom by hiddenMessageRecoveryInventory.candidateCountByRoom.collectAsState()
        val hiddenHistoryCandidateCount = candidateCountByRoom[room.roomId] ?: 0
        val roomRecoveryStatus = recoveryStatus.takeIf { hiddenHistoryCandidateCount > 0 }
            ?: HistoryRecoveryStatus.IDLE

        // Loading/syncing rows is not viewing them. Only a foreground measured viewport
        // may advance receipts, including the initial DM or group entry.

        val displayThreadSummaries by produceState(false) {
            value = featureFlagService.isFeatureEnabled(FeatureFlags.Threads)
        }

        fun handleEvent(event: TimelineEvent) {
            when (event) {
                is TimelineEvent.LoadMore -> {
                    if (event.direction == Timeline.PaginationDirection.FORWARDS && timelineMode is Timeline.Mode.Thread) {
                        // Do not paginate forwards in thread mode, as it's not supported
                        return
                    }
                    localScope.launch {
                        timelineController.paginate(direction = event.direction)
                    }
                }
                is TimelineEvent.OnScrollFinished -> {
                    Timber.tag("YiZheReadViewport").d("scroll visible=%s live=%s index=%d", event.isUserVisible, isLive, event.firstIndex)
                    readViewportActive = event.isUserVisible
                    if (!event.isUserVisible) { pendingReadIndex = null; return }
                    if (isLive) {
                        if (event.firstIndex == 0) {
                            newEventState.value = NewEventState.None
                        }
                        Timber.tag(tag).d("## sendReadReceiptIfNeeded firstVisibleIndex: ${event.firstIndex}")
                        run {
                            localScope.launch {
                                // Freeze the authoritative boundary in this operation, before the SDK read
                                // mutation. Do not wait for an unrelated Compose frame to flush the receipt.
                                val items = if (entryNavigationCaptured) timelineItems else
                                    timelineItemsFactory.timelineItems.first { rows ->
                                        rows.any { row -> row.events().any { it.eventId != null } }
                                    }
                                if (!captureEntryNavigation(items)) {
                                    pendingReadIndex = event.firstIndex
                                    return@launch
                                }
                                val sendPublicReadReceipts = sessionPreferencesStore.isSendPublicReadReceiptsEnabled().first()
                                sendReadReceiptIfNeeded(event.firstIndex, items, lastReadReceiptId,
                                    roomReadReceiptType(roomInfo.isDm, managedReadReceiptsEnabled, sendPublicReadReceipts),
                                    canRead = { readViewportActive })
                            }
                        }
                    } else {
                        newEventState.value = NewEventState.None
                    }
                }
                is TimelineEvent.ViewportChanged -> {
                    Timber.tag("YiZheReadViewport").d("viewport visible=%s live=%s count=%d", event.isUserVisible, isLive, event.visibleItemIds.size)
                    timelineController.updateViewport(event.visibleItemIds)
                    readViewportActive = event.isUserVisible
                    if (!event.isUserVisible) { pendingReadIndex = null; return }
                    // Use rows from an actual measured viewport. Background sync alone must
                    // never advance the user's local reading position to unseen messages.
                    val visibleIds = event.visibleItemIds.toSet()
                    if (isLive && visibleIds.isNotEmpty()) {
                        // A measured visible latest event is authoritative even when no scroll
                        // gesture occurs (initial entry, keyboard change, or a short group).
                        val items = timelineItems
                        val latest = items.flatMap { it.events() }.firstOrNull { it.eventId != null }
                        val firstVisible = items.indexOfFirst { row -> row.events().any { it.id in visibleIds } }
                        if (firstVisible >= 0) localScope.launch {
                            if (captureEntryNavigation(items)) {
                                val public = sessionPreferencesStore.isSendPublicReadReceiptsEnabled().first()
                                sendReadReceiptIfNeeded(if (latest?.id in visibleIds) 0 else firstVisible,
                                    items, lastReadReceiptId, roomReadReceiptType(roomInfo.isDm, managedReadReceiptsEnabled, public),
                                    canRead = { readViewportActive })
                            }
                        }
                    }
                    lastVisibleEventId.value = timelineItems.asSequence().flatMap { item ->
                        when (item) {
                            is TimelineItem.Event -> sequenceOf(item)
                            is TimelineItem.GroupedEvents -> item.events.asSequence()
                            else -> emptySequence()
                        }
                    // Timeline rows run newest to oldest (the list uses reverseLayout).
                    // Anchor to the oldest visible message, at the top of the viewport.
                    }.lastOrNull { it.id in visibleIds }?.eventId
                    if (entryNavigationCaptured && isLive) {
                        val newest = timelineItems.flatMap { it.events() }.firstOrNull { it.eventId != null }
                        if (newest?.id in visibleIds && newest?.eventId != null && readingNavigation.latestSeenEventId != newest.eventId.value) {
                            readingNavigation = readingNavigation.copy(latestSeenEventId = newest.eventId.value)
                            val snapshot = readingNavigation
                            sessionCoroutineScope.launch { runCatching { timelineController.saveReadingNavigation(snapshot) } }
                        }
                    }
                }
                is TimelineEvent.NewMessageHintElapsed -> {
                    readingNavigation = readingNavigation.consumeHintTime(event.elapsedMillis)
                    val snapshot = readingNavigation
                    sessionCoroutineScope.launch { runCatching { timelineController.saveReadingNavigation(snapshot) } }
                }
                is TimelineEvent.NewMessageHintGeometry -> {
                    if (entryNavigationCaptured && readingNavigation.firstNewEventId != null && !navigatingToFirstNewMessage) {
                        val updated = when {
                            event.targetVisible -> readingNavigation.copy(firstNewEventId = null, hintRemainingMillis = 0L)
                            event.atLiveEdge && event.aboveViewport -> readingNavigation.copy(hintActivated = true)
                            else -> readingNavigation
                        }
                        if (updated != readingNavigation) {
                            readingNavigation = updated
                            sessionCoroutineScope.launch { runCatching { timelineController.saveReadingNavigation(updated) } }
                        }
                    }
                }
                TimelineEvent.ViewFirstNewMessage -> {
                    val target = readingNavigation.firstNewEventId?.let(::EventId)
                    if (target != null && !navigatingToFirstNewMessage) {
                        navigatingToFirstNewMessage = true
                        newMessageFocusJob.value = localScope.launch {
                            var current = requireNotNull(target)
                            while (true) {
                                focusRequestState.value = FocusRequestState.Requested(current, kotlin.time.Duration.ZERO)
                                focusOnEvent(current, focusRequestState)
                                val failure = focusRequestState.value as? FocusRequestState.Failure ?: break
                                // Retry another captured new event only for a genuinely missing target, never for a network failure.
                                if (failure.throwable !is io.element.android.libraries.matrix.api.room.errors.FocusEventException.EventNotFound) break
                                val next = nextAvailableNewMessage(timelineItems, current, entryCandidateIds)
                                if (next == null) {
                                    readingNavigation = readingNavigation.copy(firstNewEventId = null, hintRemainingMillis = 0L)
                                    break
                                }
                                current = next
                                newMessageDividerEventId = next
                                readingNavigation = readingNavigation.copy(firstNewEventId = next.value)
                            }
                            if (focusRequestState.value is FocusRequestState.Failure) {
                                navigatingToFirstNewMessage = false
                                snackbarDispatcher.post(SnackbarMessage(R.string.screen_messages_new_message_location_failed))
                                focusRequestState.value = FocusRequestState.None
                            }
                            runCatching { timelineController.saveReadingNavigation(readingNavigation) }
                        }
                    }
                }
                is TimelineEvent.SelectPollAnswer -> sessionCoroutineScope.launch {
                    timelineController.invokeOnCurrentTimeline {
                        sendPollResponseAction.execute(
                            timeline = this,
                            pollStartId = event.pollStartId,
                            answerId = event.answerId
                        ).also(event.onComplete)
                    }
                }
                is TimelineEvent.EndPoll -> sessionCoroutineScope.launch {
                    timelineController.invokeOnCurrentTimeline {
                        endPollAction.execute(
                            timeline = this,
                            pollStartId = event.pollStartId,
                        ).also(event.onComplete)
                    }
                }
                is TimelineEvent.EditPoll -> {
                    navigator.navigateToEditPoll(event.pollStartId)
                }
                is TimelineEvent.StopLiveLocationShare -> sessionCoroutineScope.launch {
                    liveLocationShareManager.stopShare(room.roomId, event.protectedAggregateId)
                }
                is TimelineEvent.FocusOnEvent -> sessionCoroutineScope.launch {
                    newMessageFocusJob.value?.cancel()
                    navigatingToFirstNewMessage = false
                    focusRequestState.value = FocusRequestState.Requested(event.eventId, event.debounce)
                    delay(event.debounce)
                    Timber.tag(tag).d("Started focus on ${event.eventId}")
                    focusOnEvent(event.eventId, focusRequestState)
                }.start()
                is TimelineEvent.OnFocusEventRender -> {
                    // If there was a pending 'notification tap opens timeline' transaction, finish it now we're focused in the required event
                    analyticsService.finishLongRunningTransaction(NotificationToMessage)

                    focusRequestState.value = focusRequestState.value.onFocusEventRender()
                    if (navigatingToFirstNewMessage) {
                        navigatingToFirstNewMessage = false
                        readingNavigation = readingNavigation.copy(firstNewEventId = null, hintRemainingMillis = 0L)
                        val snapshot = readingNavigation
                        sessionCoroutineScope.launch { runCatching { timelineController.saveReadingNavigation(snapshot) } }
                    }
                }
                is TimelineEvent.ClearFocusRequestState -> {
                    newMessageFocusJob.value?.cancel()
                    navigatingToFirstNewMessage = false
                    focusRequestState.value = FocusRequestState.None
                }
                is TimelineEvent.JumpToLive -> {
                    newMessageFocusJob.value?.cancel()
                    navigatingToFirstNewMessage = false
                    focusRequestState.value = FocusRequestState.None
                    timelineController.focusOnLive()
                }
                is TimelineEvent.AcknowledgePendingMediaPresentation -> {
                    timelineController.acknowledgePendingMediaPresentation(event.displayId)
                }
                TimelineEvent.HideShieldDialog -> messageShieldDialogData.value = null
                is TimelineEvent.ShowShieldDialog -> messageShieldDialogData.value = event.messageShieldData
                is TimelineEvent.ComputeVerifiedUserSendFailure -> {
                    resolveVerifiedUserSendFailureState.eventSink(ResolveVerifiedUserSendFailureEvent.ComputeForMessage(event.event))
                }
                is TimelineEvent.RetrySend -> localScope.launch {
                    timelineController.retryFailedEvent(event.event).onFailure {
                        snackbarDispatcher.post(SnackbarMessage(R.string.error_message_retry_failed))
                    }
                }
                is TimelineEvent.NavigateToPredecessorOrSuccessorRoom -> {
                    // Navigate to the predecessor or successor room
                    val serverNames = calculateServerNamesForRoom(room)
                    navigator.navigateToRoom(event.roomId, null, serverNames)
                }
                is TimelineEvent.OpenThread -> {
                    navigator.navigateToThread(
                        threadRootId = event.threadRootEventId,
                        focusedEventId = event.focusedEvent,
                    )
                }
                is TimelineEvent.OpenBurnAfterRead -> {
                    if (openingBurnAfterReadEventId.value != null || activeBurnAfterReadEventId.value != null) return
                    val candidate = latestVisibleTimelineItems
                        .filterIsInstance<TimelineItem.Event>()
                        .firstOrNull { it.eventId == event.eventId }
                    if (candidate?.isMine == true || (candidate != null && !candidate.content.isBurnAfterRead)) return
                    retainBurnAfterReadItem(event.eventId)
                    burnAfterReadOpenRejectedEventId.value = null
                    openingBurnAfterReadEventId.value = event.eventId
                    activeBurnAfterReadPreview.value = null
                    sessionCoroutineScope.launch {
                        var result: Result<Unit> = Result.failure(IllegalStateException("No active timeline"))
                        timelineController.invokeOnCurrentTimeline {
                            result = openBurnAfterRead(event.eventId)
                        }
                        result.fold(
                            onSuccess = {
                                activeBurnAfterReadEventId.value = event.eventId
                                openingBurnAfterReadEventId.value = null
                                burnAfterReadOpenRejectedEventId.value = null
                            },
                            onFailure = {
                                openingBurnAfterReadEventId.value = null
                                activeBurnAfterReadPreview.value = null
                                burnAfterReadOpenRejectedEventId.value = event.eventId
                                releaseRetainedBurnAfterReadItem(event.eventId)
                                Timber.e(it, "Failed to open burn-after-read message")
                            },
                        )
                    }
                }
                TimelineEvent.DismissBurnAfterReadOpenFailure -> burnAfterReadOpenRejectedEventId.value = null
                TimelineEvent.CloseBurnAfterRead -> {
                    val eventId = activeBurnAfterReadEventId.value ?: return
                    activeBurnAfterReadEventId.value = null
                    activeBurnAfterReadPreview.value = null
                    consumeBurnAfterReadEvent(eventId, Timeline.BurnAfterReadEndReason.VIEW_CLOSED)
                }
                is TimelineEvent.EndBurnAfterRead -> {
                    val eventId = activeBurnAfterReadEventId.value ?: return
                    activeBurnAfterReadEventId.value = null
                    activeBurnAfterReadPreview.value = null
                    consumeBurnAfterReadEvent(eventId, event.reason)
                }
                is TimelineEvent.PrepareMessageRemoval -> retainMessageRemovalItem(event.eventId)
                is TimelineEvent.CommitMessageRemoval -> commitMessageRemoval(event.eventId)
                is TimelineEvent.CancelMessageRemoval -> cancelMessageRemoval(event.eventId)
            }
        }

        LaunchedEffect(Unit) {
            timelineItemsFactory.timelineItems
                .onEach { newTimelineItems ->
                    val previewEventId = openingBurnAfterReadEventId.value ?: activeBurnAfterReadEventId.value
                    if (previewEventId != null) {
                        newTimelineItems.asSequence()
                            .filterIsInstance<TimelineItem.Event>()
                            .firstOrNull { item ->
                                item.eventId == previewEventId && !item.content.isBurnAfterReadUnopened
                            }
                            ?.let { activeBurnAfterReadPreview.value = it }
                    }
                    val terminalSafeTimelineItems = newTimelineItems.withTerminalRedactionPriority()
                    latestClassifiableTimelineItems = terminalSafeTimelineItems
                    resolveTrustedRedactionReasons(terminalSafeTimelineItems, removalPresentationGeneration)
                    val visibleTimelineItems = terminalSafeTimelineItems
                        .withTrustedRedactionReasons()
                        .filterNot { item ->
                            item is TimelineItem.Event && item.eventId in consumedBurnAfterReadEventIds
                        }
                        .toImmutableList()
                    latestVisibleTimelineItems = visibleTimelineItems
                    interruptRemovalPresentationForSecurityShells(visibleTimelineItems)
                    // Pending signed-reason resolution intentionally suppresses redacted rows from the UI.
                    // Terminal transitions must still observe the authenticated pre-suppression projection so
                    // the previous bubble can animate once and the terminal event can never be mistaken for a
                    // transiently missing row.
                    detectRemoteBurnAfterReadDestruction(terminalSafeTimelineItems)
                    detectRemoteMessageRemoval(terminalSafeTimelineItems)
                    rebuildPresentedTimelineItems()

                    analyticsService.run {
                        finishLongRunningTransaction(DisplayFirstTimelineItems)
                        finishLongRunningTransaction(OpenRoom)
                    }
                }
                .launchIn(this)

            combine(timelineController.timelineItems(), room.membersStateFlow, room.roomInfoFlow) { items, membersState, currentRoomInfo ->
                val parent = analyticsService.getLongRunningTransaction(DisplayFirstTimelineItems)
                val transaction = parent?.startChild("timelineItemsFactory.replaceWith", "Processing timeline items")
                transaction?.putExtraData(AnalyticsUserData.TIMELINE_ITEM_COUNT, items.count().toString())
                timelineItemsFactory.replaceWith(
                    timelineItems = items.withoutDirectCallSignals(currentRoomInfo.isDm),
                    roomMembers = membersState.roomMembers().orEmpty()
                )
                transaction?.finish()
                items
            }
                .onEach(redactedVoiceMessageManager::onEachMatrixTimelineItem)
                .flowOn(dispatchers.computation)
                .launchIn(this)
        }

        LaunchedEffect(timelineItems.size, timelineItems.firstOrNull { it is TimelineItem.Event }?.identifier()) {
            computeNewItemState(timelineItems, prevMostRecentItemId, newEventState)
        }

        LaunchedEffect(timelineItems.size, focusRequestState.value) {
            val currentFocusRequestState = focusRequestState.value
            if (currentFocusRequestState is FocusRequestState.Success && !currentFocusRequestState.rendered) {
                val eventId = currentFocusRequestState.eventId
                if (timelineItemIndexer.isKnown(eventId)) {
                    val index = timelineItemIndexer.indexOf(eventId)
                    focusRequestState.value = FocusRequestState.Success(eventId = eventId, index = index)
                } else {
                    Timber.w("Unknown timeline item for focused item, can't render focus")
                }
            }
        }

        val typingNotificationState = typingNotificationPresenter.present()
        val roomCallState = roomCallStatePresenter.present()
        val userEventPermissions by room.permissionsAsState(UserEventPermissions.DEFAULT) { perms ->
            perms.userEventPermissions()
        }
        val timelineRoomInfo by remember(typingNotificationState, roomCallState, roomInfo) {
            derivedStateOf {
                TimelineRoomInfo(
                    name = roomInfo.name,
                    isDm = roomInfo.isDm,
                    userHasPermissionToSendMessage = userEventPermissions.canSendMessage,
                    userHasPermissionToSendReaction = userEventPermissions.canSendReaction,
                    roomCallState = roomCallState,
                    pinnedEventIds = roomInfo.pinnedEventIds,
                    typingNotificationState = typingNotificationState,
                    predecessorRoom = room.predecessorRoom(),
                )
            }
        }

        LaunchedEffect(focusRequestState.value) {
            Timber.tag(tag).d("Timeline: $timelineMode | focus state: ${focusRequestState.value}")
        }

        val latestHandleEvent by rememberUpdatedState(::handleEvent)
        val stableEventSink = remember(room.roomId) { { event: TimelineEvent -> latestHandleEvent(event) } }
        return TimelineState(
            timelineItems = timelineItems,
            pendingMediaSendPresentations = pendingMediaSendPresentations.toImmutableList(),
            timelineMode = timelineMode,
            timelineRoomInfo = timelineRoomInfo,
            renderReadReceipts = renderReadReceipts,
            newEventState = newEventState.value,
            isLive = isLive,
            focusRequestState = focusRequestState.value,
            messageShieldDialogData = messageShieldDialogData.value,
            activeBurnAfterReadEventId = activeBurnAfterReadEventId.value,
            openingBurnAfterReadEventId = openingBurnAfterReadEventId.value,
            burnAfterReadOpenRejectedEventId = burnAfterReadOpenRejectedEventId.value,
            activeBurnAfterReadPreview = activeBurnAfterReadPreview.value,
            retainedBurnAfterReadEventIds = retainedBurnAfterReadItems.keys.toImmutableSet(),
            destroyingBurnAfterReadEventIds = destroyingBurnAfterReadEventIds,
            retainedMessageRemovalEventIds = retainedMessageRemovalItems.keys.toImmutableSet(),
            pendingMessageRemovalEventIds = pendingMessageRemovalEventIds,
            destroyingMessageRemovalEventIds = destroyingMessageRemovalEventIds,
            resolveVerifiedUserSendFailureState = resolveVerifiedUserSendFailureState,
            displayThreadSummaries = displayThreadSummaries,
            historyRecoveryStatus = roomRecoveryStatus,
            hiddenHistoryCandidateCount = hiddenHistoryCandidateCount,
            previousViewedEventId = previousViewedEventId.value.takeIf { it != getLastEventIdBeforeOrAt(0, timelineItems) },
            newMessageNavigation = readingNavigation,
            navigatingToFirstNewMessage = navigatingToFirstNewMessage,
            newMessageDividerEventId = newMessageDividerEventId,
            eventSink = stableEventSink,
        )
    }

    private fun hideConsumedBurnAfterReadEvent(eventId: EventId) {
        consumedBurnAfterReadEventIds = consumedBurnAfterReadEventIds.add(eventId)
        retainedBurnAfterReadItems = retainedBurnAfterReadItems - eventId
        destroyingBurnAfterReadEventIds = destroyingBurnAfterReadEventIds.remove(eventId)
        latestVisibleTimelineItems = latestVisibleTimelineItems
            .filterNot { item -> item is TimelineItem.Event && item.eventId == eventId }
            .toImmutableList()
        timelineItems = timelineItems
            .filterNot { item -> item is TimelineItem.Event && item.eventId == eventId }
            .toImmutableList()
    }

    private fun consumeBurnAfterReadEvent(eventId: EventId, reason: Timeline.BurnAfterReadEndReason) {
        retainBurnAfterReadItem(eventId)
        val presentationGeneration = removalPresentationGeneration
        sessionCoroutineScope.launch {
            // Persist the local terminal state before starting any visual transition. If Android kills the
            // process during the animation, the encrypted reference index still prevents resurrection.
            val localTerminalState = runCatching {
                messageReferenceStore.deleteLocally(room.roomId, listOf(eventId))
            }.onFailure { Timber.e(it, "Failed to persist consumed burn-after-read event reference") }
            var remoteTerminalState: Result<Unit>? = null
            timelineController.invokeOnCurrentTimeline {
                remoteTerminalState = endBurnAfterRead(eventId, reason)
            }
            remoteTerminalState?.onFailure { Timber.w(it, "Failed to confirm terminal burn-after-read state") }

            if (localTerminalState.isFailure && remoteTerminalState?.isSuccess != true) {
                withContext(dispatchers.main) { releaseRetainedBurnAfterReadItem(eventId) }
                return@launch
            }
            withContext(dispatchers.main) {
                consumedBurnAfterReadEventIds = consumedBurnAfterReadEventIds.add(eventId)
                if (presentationGeneration != removalPresentationGeneration) {
                    rebuildPresentedTimelineItems()
                    return@withContext
                }
                destroyingBurnAfterReadEventIds = destroyingBurnAfterReadEventIds.add(eventId)
                rebuildPresentedTimelineItems()
            }
            if (presentationGeneration != removalPresentationGeneration) return@launch
            delay(BURN_AFTER_READ_DESTRUCTION_MILLIS)
            withContext(dispatchers.main) {
                if (presentationGeneration == removalPresentationGeneration) hideConsumedBurnAfterReadEvent(eventId)
            }
        }
    }

    private fun retainBurnAfterReadItem(eventId: EventId) {
        if (eventId in retainedBurnAfterReadItems) return
        val index = timelineItems.indexOfFirst { item -> item is TimelineItem.Event && item.eventId == eventId }
        val item = timelineItems.getOrNull(index) as? TimelineItem.Event ?: return
        if (!item.content.isBurnAfterRead) return
        retainedBurnAfterReadItems = retainedBurnAfterReadItems + (eventId to timelineItems.retainedItemAt(index, item))
    }

    private fun releaseRetainedBurnAfterReadItem(eventId: EventId) {
        retainedBurnAfterReadItems = retainedBurnAfterReadItems - eventId
        destroyingBurnAfterReadEventIds = destroyingBurnAfterReadEventIds.remove(eventId)
        rebuildPresentedTimelineItems()
    }

    private fun detectRemoteBurnAfterReadDestruction(newItems: ImmutableList<TimelineItem>) {
        val terminalEventIds = newItems.asSequence()
            .filterIsInstance<TimelineItem.Event>()
            .filter { it.content is TimelineItemRedactedContent || it.content.isTerminalProtectedContentShell }
            .mapNotNull { it.eventId }
            .toSet()
        if (terminalEventIds.isEmpty()) return
        val newlyDestroyed = timelineItems.asSequence()
            .filterIsInstance<TimelineItem.Event>()
            .filter { it.content.isBurnAfterRead }
            .mapNotNull { item -> item.eventId?.takeIf { it in terminalEventIds && it !in retainedBurnAfterReadItems } }
            .toList()
        newlyDestroyed.forEach { eventId ->
            retainBurnAfterReadItem(eventId)
            val presentationGeneration = removalPresentationGeneration
            sessionCoroutineScope.launch {
                // The redacted Matrix event proves remote destruction. Persist the local terminal reference
                // before animating so later pagination or process recreation cannot reinsert the shell.
                runCatching { messageReferenceStore.deleteLocally(room.roomId, listOf(eventId)) }
                    .onFailure { Timber.e(it, "Failed to persist remotely destroyed burn-after-read event") }
                withContext(dispatchers.main) {
                    consumedBurnAfterReadEventIds = consumedBurnAfterReadEventIds.add(eventId)
                    if (presentationGeneration != removalPresentationGeneration) {
                        rebuildPresentedTimelineItems()
                        return@withContext
                    }
                    destroyingBurnAfterReadEventIds = destroyingBurnAfterReadEventIds.add(eventId)
                    rebuildPresentedTimelineItems()
                }
                if (presentationGeneration != removalPresentationGeneration) return@launch
                delay(BURN_AFTER_READ_DESTRUCTION_MILLIS)
                withContext(dispatchers.main) {
                    if (presentationGeneration == removalPresentationGeneration) hideConsumedBurnAfterReadEvent(eventId)
                }
            }
        }
    }

    private fun retainMessageRemovalItem(eventId: EventId) {
        if (eventId in retainedMessageRemovalItems) return
        val index = timelineItems.indexOfFirst { item -> item is TimelineItem.Event && item.eventId == eventId }
        val item = timelineItems.getOrNull(index) as? TimelineItem.Event ?: return
        if (item.content.isBurnAfterRead || item.content is TimelineItemRedactedContent) return
        retainedMessageRemovalItems = retainedMessageRemovalItems +
            (eventId to timelineItems.retainedItemAt(index, item))
        pendingMessageRemovalEventIds = pendingMessageRemovalEventIds.add(eventId)
    }

    private fun commitMessageRemoval(eventId: EventId) {
        if (eventId !in retainedMessageRemovalItems || eventId in destroyingMessageRemovalEventIds) return
        pendingMessageRemovalEventIds = pendingMessageRemovalEventIds.remove(eventId)
        destroyingMessageRemovalEventIds = destroyingMessageRemovalEventIds.add(eventId)
        val presentationGeneration = removalPresentationGeneration
        sessionCoroutineScope.launch {
            delay(MESSAGE_REMOVAL_DESTRUCTION_MILLIS)
            withContext(dispatchers.main) {
                if (presentationGeneration == removalPresentationGeneration) releaseRetainedMessageRemovalItem(eventId)
            }
        }
    }

    private fun cancelMessageRemoval(eventId: EventId) {
        // A remote redaction can win the race while the initiating request reports a transport failure.
        // Once destruction has begun, the authoritative terminal outcome must not be cancelled.
        if (eventId in destroyingMessageRemovalEventIds) return
        releaseRetainedMessageRemovalItem(eventId)
    }

    private fun releaseRetainedMessageRemovalItem(eventId: EventId) {
        retainedMessageRemovalItems = retainedMessageRemovalItems - eventId
        pendingMessageRemovalEventIds = pendingMessageRemovalEventIds.remove(eventId)
        destroyingMessageRemovalEventIds = destroyingMessageRemovalEventIds.remove(eventId)
        rebuildPresentedTimelineItems()
    }

    private fun detectRemoteMessageRemoval(newItems: ImmutableList<TimelineItem>) {
        val redactedEventIds = newItems.asSequence()
            .filterIsInstance<TimelineItem.Event>()
            .filter { it.content is TimelineItemRedactedContent }
            .mapNotNull { it.eventId }
            .toSet()
        if (redactedEventIds.isEmpty()) return
        timelineItems.asSequence()
            .filterIsInstance<TimelineItem.Event>()
            .filterNot { it.content.isBurnAfterRead || it.content is TimelineItemRedactedContent }
            .mapNotNull { item -> item.eventId?.takeIf { it in redactedEventIds } }
            .filterNot { it in retainedMessageRemovalItems }
            .toList()
            .forEach { eventId ->
                retainMessageRemovalItem(eventId)
                commitMessageRemoval(eventId)
            }
    }

    /**
     * A Matrix redaction is an irreversible terminal state. A later SDK refresh can transiently
     * replay an older projection together with a new append; that stale projection must never
     * replace a redacted row in the active room presentation. This barrier stores only event ids
     * and rewrites stale UI content to the terminal shell, so it cannot retain or restore plaintext.
     */
    private fun ImmutableList<TimelineItem>.withTerminalRedactionPriority(): ImmutableList<TimelineItem> {
        val observedTerminalIds = asSequence()
            .filterIsInstance<TimelineItem.Event>()
            .filter { it.content is TimelineItemRedactedContent }
            .mapNotNull { it.eventId }
            .toSet()
        if (observedTerminalIds.isNotEmpty()) {
            terminalRedactedEventIds = terminalRedactedEventIds.addAll(observedTerminalIds)
        }
        if (terminalRedactedEventIds.isEmpty()) return this

        return map { item ->
            if (item is TimelineItem.Event &&
                item.eventId in terminalRedactedEventIds &&
                item.content !is TimelineItemRedactedContent
            ) {
                item.copy(content = TimelineItemRedactedContent)
            } else {
                item
            }
        }.toImmutableList()
    }

    private fun rebuildPresentedTimelineItems() {
        val presentedTimelineItems = latestVisibleTimelineItems
            .filterNot { item -> item is TimelineItem.Event && item.eventId in consumedBurnAfterReadEventIds }
            .withRetainedBurnAfterReadItems(retainedBurnAfterReadItems)
            .withRetainedMessageRemovalItems(retainedMessageRemovalItems)
        timelineItems = presentedTimelineItems
        sessionCoroutineScope.launch {
            timelineItemIndexer.process(presentedTimelineItems)
        }
    }

    private fun ImmutableList<TimelineItem>.withTrustedRedactionReasons(): ImmutableList<TimelineItem> =
        mapNotNull { item ->
            if (item !is TimelineItem.Event || item.content !is TimelineItemRedactedContent || item.eventId == null) {
                return@mapNotNull item
            }
            when (val reason = trustedRedactionReasons[item.eventId]) {
                TrustedRedactionReason.RETENTION_EXPIRED,
                TrustedRedactionReason.BURN_AFTER_READ_COMPLETED -> null
                null -> item.takeUnless { item.eventId in pendingRedactionReasonIds }
                else -> item.copy(trustedRedactionReason = reason)
            }
        }.toImmutableList()

    private fun resolveTrustedRedactionReasons(items: ImmutableList<TimelineItem>, generation: Long) {
        val candidates = items.asSequence()
            .filterIsInstance<TimelineItem.Event>()
            .filter { it.content is TimelineItemRedactedContent }
            .mapNotNull { it.eventId }
            .filterNot { it in pendingRedactionReasonIds || it in completedRedactionReasonIds }
            .distinct()
            .toList()
        if (candidates.isEmpty()) return
        pendingRedactionReasonIds += candidates
        sessionCoroutineScope.launch {
            val hashesToIds = candidates.associateBy { eventId ->
                MessageDigest.getInstance("SHA-256").digest(eventId.value.encodeToByteArray()).toHex()
            }
            var resolved: Map<String, TrustedRedactionReason>? = null
            for (attempt in 0 until REDACTION_REASON_MAX_ATTEMPTS) {
                resolved = runCatching {
                    hashesToIds.keys.chunked(REDACTION_REASON_BATCH_SIZE).flatMap { batch ->
                        withContext(dispatchers.io) { redactionReasonResolver.resolve(batch.toSet()) }.entries
                    }.associate { it.toPair() }
                }.getOrNull()
                if (resolved != null || attempt == REDACTION_REASON_MAX_ATTEMPTS - 1) break
                delay(REDACTION_REASON_RETRY_DELAYS_MILLIS[attempt])
            }
            withContext(dispatchers.main) {
                pendingRedactionReasonIds -= candidates.toSet()
                if (generation != removalPresentationGeneration) return@withContext
                completedRedactionReasonIds += candidates
                val verifiedByEvent = resolved.orEmpty().mapNotNull { (hash, reason) ->
                    hashesToIds[hash]?.let { it to reason }
                }.toMap()
                trustedRedactionReasons = trustedRedactionReasons + verifiedByEvent
                latestVisibleTimelineItems = latestClassifiableTimelineItems
                    .withTrustedRedactionReasons()
                    .filterNot { item ->
                        item is TimelineItem.Event && item.eventId in consumedBurnAfterReadEventIds
                    }
                    .toImmutableList()
                rebuildPresentedTimelineItems()
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun interruptRemovalPresentationForSecurityShells(newItems: ImmutableList<TimelineItem>) {
        val securityShellEventIds = newItems.asSequence()
            .filterIsInstance<TimelineItem.Event>()
            .filter { it.content.isProtectedContentShell && !it.content.isTerminalProtectedContentShell }
            .mapNotNull { it.eventId }
            .toSet()
        if (securityShellEventIds.isEmpty()) return
        retainedBurnAfterReadItems = retainedBurnAfterReadItems - securityShellEventIds
        destroyingBurnAfterReadEventIds = destroyingBurnAfterReadEventIds.removeAll(securityShellEventIds)
        retainedMessageRemovalItems = retainedMessageRemovalItems - securityShellEventIds
        pendingMessageRemovalEventIds = pendingMessageRemovalEventIds.removeAll(securityShellEventIds)
        destroyingMessageRemovalEventIds = destroyingMessageRemovalEventIds.removeAll(securityShellEventIds)
    }

    private fun clearRemovalPresentationState() {
        retainedBurnAfterReadItems = emptyMap()
        destroyingBurnAfterReadEventIds = persistentSetOf()
        retainedMessageRemovalItems = emptyMap()
        pendingMessageRemovalEventIds = persistentSetOf()
        destroyingMessageRemovalEventIds = persistentSetOf()
        latestClassifiableTimelineItems = persistentListOf()
        trustedRedactionReasons = emptyMap()
        pendingRedactionReasonIds.clear()
        completedRedactionReasonIds.clear()
        rebuildPresentedTimelineItems()
    }

    private suspend fun focusOnEvent(
        eventId: EventId,
        focusRequestState: MutableState<FocusRequestState>,
    ) {
        if (timelineItemIndexer.isKnown(eventId)) {
            val index = timelineItemIndexer.indexOf(eventId)
            focusRequestState.value = FocusRequestState.Success(eventId = eventId, index = index)
            return
        }

        Timber.tag(tag).d("Event $eventId not found in the loaded timeline, loading a focused timeline")
        focusRequestState.value = FocusRequestState.Loading(eventId = eventId)

        val threadId = room.threadRootIdForEvent(eventId).getOrElse {
            focusRequestState.value = FocusRequestState.Failure(it)
            return
        }

        if (timelineController.mainTimelineMode() is Timeline.Mode.Thread && threadId == null) {
            // We are in a thread timeline, and the event isn't part of a thread, we need to navigate back to the room
            focusRequestState.value = FocusRequestState.None
            navigator.navigateToRoom(room.roomId, eventId, calculateServerNamesForRoom(room))
        } else {
            Timber.tag(tag).d("Focusing on event $eventId - thread $threadId")
            timelineController.focusOnEvent(eventId, threadId)
                .onSuccess { result ->
                    when (result) {
                        is EventFocusResult.FocusedOnLive -> {
                            focusRequestState.value = FocusRequestState.Success(eventId = eventId)
                        }
                        is EventFocusResult.IsInThread -> {
                            val currentThreadId = (timelineController.mainTimelineMode() as? Timeline.Mode.Thread)?.threadRootId
                            if (currentThreadId == result.threadId) {
                                // It's the same thread, we just focus on the event
                                focusRequestState.value = FocusRequestState.Success(eventId = eventId)
                            } else {
                                focusRequestState.value = FocusRequestState.Success(eventId = result.threadId.asEventId())
                                // It's part of a thread we're not in, let's open it in another timeline
                                navigator.navigateToThread(result.threadId, eventId)
                            }
                        }
                    }
                }
                .onFailure {
                    focusRequestState.value = FocusRequestState.Failure(it)
                }
        }
    }

    /**
     * This method compute the hasNewItem state passed as a [MutableState] each time the timeline items size changes.
     * Basically, if we got new timeline event from sync or local, either from us or another user, we update the state so we tell we have new items.
     * The state never goes back to None from this method, but need to be reset from somewhere else.
     */
    private fun computeNewItemState(
        timelineItems: ImmutableList<TimelineItem>,
        prevMostRecentItemId: MutableState<UniqueId?>,
        newEventState: MutableState<NewEventState>
    ) {
        // FromMe is prioritized over FromOther, so skip if we already have a FromMe
        if (newEventState.value == NewEventState.FromMe) {
            return
        }
        val newMostRecentItem = timelineItems.firstOrNull {
            // Ignore typing item
            (it as? TimelineItem.Virtual)?.model !is TimelineItemTypingNotificationModel
        }
        val prevMostRecentItemIdValue = prevMostRecentItemId.value
        val newMostRecentItemId = newMostRecentItem?.identifier()
        val hasNewEvent = prevMostRecentItemIdValue != null &&
            newMostRecentItem is TimelineItem.Event &&
            newMostRecentItem.origin != TimelineItemEventOrigin.PAGINATION &&
            newMostRecentItemId != prevMostRecentItemIdValue

        if (hasNewEvent) {
            // Scroll to bottom if the new event is from me, even if sent from another device
            val fromMe = newMostRecentItem.isMine
            newEventState.value = if (fromMe) {
                NewEventState.FromMe
            } else {
                NewEventState.FromOther
            }
        }
        prevMostRecentItemId.value = newMostRecentItemId
    }

    private fun CoroutineScope.sendReadReceiptIfNeeded(
        firstVisibleIndex: Int,
        timelineItems: ImmutableList<TimelineItem>,
        lastReadReceiptId: MutableState<EventId?>,
        readReceiptType: ReceiptType,
        canRead: () -> Boolean,
    ) = launch(dispatchers.computation) {
        if (!canRead()) return@launch
        // If we are at the bottom of timeline, we mark the room as read.
        if (firstVisibleIndex == 0) {
            timelineController.invokeOnCurrentTimeline {
                val latestId = timelineItems.flatMap { it.events() }.firstOrNull { it.eventId != null }?.eventId
                if (latestId != null && latestId == lastReadReceiptId.value) return@invokeOnCurrentTimeline
                val result = markAsRead(receiptType = readReceiptType)
                Timber.tag("YiZheReadViewport").d("mark_read success=%s private=%s", result.isSuccess, readReceiptType == ReceiptType.READ_PRIVATE)
                result.onSuccess {
                    lastReadReceiptId.value = latestId
                    if (room.roomInfoFlow.value.isMarkedUnread) room.setUnreadFlag(false)
                }
            }
        } else {
            // Get last valid EventId seen by the user, as the first index might refer to a Virtual item
            val eventId = getLastEventIdBeforeOrAt(firstVisibleIndex, timelineItems)
            if (eventId != null && eventId != lastReadReceiptId.value) {
                lastReadReceiptId.value = eventId
                timelineController.invokeOnCurrentTimeline {
                    sendReadReceipt(eventId = eventId, receiptType = readReceiptType)
                }
            }
        }
    }

    private fun getLastEventIdBeforeOrAt(index: Int, items: ImmutableList<TimelineItem>): EventId? {
        for (i in index until items.count()) {
            val item = items[i]
            item.events().firstOrNull { it.eventId != null }?.eventId?.let { return it }
        }
        return null
    }
}

private const val REDACTION_REASON_BATCH_SIZE = 256
private const val REDACTION_REASON_MAX_ATTEMPTS = 3
private val REDACTION_REASON_RETRY_DELAYS_MILLIS = longArrayOf(5_000L, 30_000L)

private data class RetainedTimelineItem(
    val item: TimelineItem.Event,
    val originalIndex: Int,
    val itemBefore: UniqueId?,
    val itemAfter: UniqueId?,
)

private fun List<TimelineItem>.retainedItemAt(index: Int, item: TimelineItem.Event): RetainedTimelineItem =
    RetainedTimelineItem(
        item = item,
        originalIndex = index,
        itemBefore = getOrNull(index - 1)?.identifier(),
        itemAfter = getOrNull(index + 1)?.identifier(),
    )

private fun List<TimelineItem>.withRetainedBurnAfterReadItems(
    retained: Map<EventId, RetainedTimelineItem>,
): ImmutableList<TimelineItem> = withRetainedItems(retained)

private fun List<TimelineItem>.withRetainedMessageRemovalItems(
    retained: Map<EventId, RetainedTimelineItem>,
): ImmutableList<TimelineItem> = withRetainedItems(retained)

private fun List<TimelineItem>.withRetainedItems(
    retained: Map<EventId, RetainedTimelineItem>,
): ImmutableList<TimelineItem> {
    if (retained.isEmpty()) return toImmutableList()
    val retainedIds = retained.keys
    val merged = filterNot { item -> item is TimelineItem.Event && item.eventId in retainedIds }.toMutableList()
    retained.values.sortedBy { it.originalIndex }.forEach { snapshot ->
        val beforeIndex = snapshot.itemBefore?.let { id -> merged.indexOfFirst { it.identifier() == id } } ?: -1
        val afterIndex = snapshot.itemAfter?.let { id -> merged.indexOfFirst { it.identifier() == id } } ?: -1
        val insertionIndex = when {
            beforeIndex >= 0 -> beforeIndex + 1
            afterIndex >= 0 -> afterIndex
            else -> snapshot.originalIndex.coerceIn(0, merged.size)
        }
        merged.add(insertionIndex.coerceIn(0, merged.size), snapshot.item)
    }
    return merged.toImmutableList()
}

private const val BURN_AFTER_READ_DESTRUCTION_MILLIS = 460L
internal const val MESSAGE_REMOVAL_DESTRUCTION_MILLIS = 460L

private fun FocusRequestState.onFocusEventRender(): FocusRequestState {
    return when (this) {
        is FocusRequestState.Success -> copy(rendered = true)
        else -> this
    }
}

// Workaround for not having the server names available, get possible server names from the user ids of the room members
private fun calculateServerNamesForRoom(room: JoinedRoom): List<String> {
    // If we have no room members, return right ahead
    val serverNames = room.membersStateFlow.value.roomMembers() ?: return emptyList()

    // Otherwise get the three most common server names from the user ids of the room members
    return serverNames
        .mapNotNull { it.userId.domainName }
        .groupingBy { it }
        .eachCount()
        .let { map ->
            map.keys.sortedByDescending { map[it] }
        }
        .take(3)
}
