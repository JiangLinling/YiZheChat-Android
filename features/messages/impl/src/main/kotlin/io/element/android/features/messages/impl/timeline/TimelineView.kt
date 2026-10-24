/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.platform.testTag as composeTestTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.crypto.sendfailure.resolve.ResolveVerifiedUserSendFailureView
import io.element.android.features.messages.impl.timeline.components.FloatingDateBadgeOverlay
import io.element.android.features.messages.impl.timeline.components.TimelineItemRow
import io.element.android.features.messages.impl.timeline.components.toText
import io.element.android.features.messages.impl.timeline.di.LocalTimelineItemPresenterFactories
import io.element.android.features.messages.impl.timeline.di.aFakeTimelineItemPresenterFactories
import io.element.android.features.messages.impl.timeline.focus.FocusRequestStateView
import io.element.android.features.messages.impl.timeline.model.NewEventState
import io.element.android.features.messages.impl.timeline.model.GroupedEventsKind
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContentProvider
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.model.event.canBeForwarded
import io.element.android.features.messages.impl.timeline.protection.TimelineProtectionState
import io.element.android.features.messages.impl.timeline.protection.aTimelineProtectionState
import io.element.android.libraries.androidutils.system.copyToClipboard
import io.element.android.libraries.designsystem.components.dialogs.AlertDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.messageFromMeBackground
import io.element.android.libraries.designsystem.theme.components.FloatingActionButton
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.messageFromMeBackground
import io.element.android.libraries.designsystem.utils.animateScrollToItemCenter
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.security.contentprotection.api.TrustedRedactionReason
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.testtags.testTag
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.a11y.isTalkbackActive
import io.element.android.wysiwyg.link.Link
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun TimelineView(
    state: TimelineState,
    viewportScopeId: String = "timeline",
    timelineProtectionState: TimelineProtectionState,
    onUserDataClick: (MatrixUser) -> Unit,
    onLinkClick: (Link) -> Unit,
    onContentClick: (TimelineItem.Event) -> Unit,
    onMessageLongClick: (TimelineItem.Event, IntRect) -> Unit,
    onSwipeToReply: (TimelineItem.Event) -> Unit,
    onReactionClick: (emoji: String, TimelineItem.Event) -> Unit,
    onReactionLongClick: (emoji: String, TimelineItem.Event) -> Unit,
    onMoreReactionsClick: (TimelineItem.Event) -> Unit,
    onReadReceiptClick: (TimelineItem.Event) -> Unit,
    selectionMode: Boolean = false,
    selectedEventIds: Set<EventId> = emptySet(),
    onSelectionToggle: (TimelineItem.Event) -> Unit = {},
    reEditableWithdrawnEventIds: Set<EventId> = emptySet(),
    onReEditWithdrawn: (EventId) -> Unit = {},
    modifier: Modifier = Modifier,
    lazyListState: LazyListState = rememberLazyListState(),
    onViewportChanged: ((List<UniqueId>) -> Unit)? = null,
    forceJumpToBottomVisibility: Boolean = false,
    nestedScrollConnection: NestedScrollConnection = rememberNestedScrollInteropConnection(),
    floatingDateTopOffset: Dp = 0.dp,
    floatingNavigationBlocked: Boolean = false,
) {
    val userVisible = rememberTimelineUserVisible()
    val emotionPlayback = remember(viewportScopeId) { io.element.android.features.messages.impl.emotions.TimelineEmotionState() }
    val composeCommitTracker = remember { arrayOfNulls<Any>(1) }
    val composeStartedAt = System.nanoTime()
    SideEffect {
        if (composeCommitTracker[0] !== state.timelineItems) {
            composeCommitTracker[0] = state.timelineItems
            Timber.tag("YiZheTimelinePerf").d(
                "stage=compose_commit item_count=%d duration_ms=%d",
                state.timelineItems.size,
                (System.nanoTime() - composeStartedAt) / 1_000_000L,
            )
        }
    }

    fun clearFocusRequestState() {
        state.eventSink(TimelineEvent.ClearFocusRequestState)
    }

    fun onScrollFinishAt(firstVisibleIndex: Int, isUserVisible: Boolean) {
        state.eventSink(TimelineEvent.OnScrollFinished(firstVisibleIndex, isUserVisible))
    }

    fun onFocusEventRender() {
        state.eventSink(TimelineEvent.OnFocusEventRender)
    }

    fun onJumpToLive() {
        state.eventSink(TimelineEvent.JumpToLive)
    }

    val context = LocalContext.current
    val toastMessage = stringResource(CommonStrings.common_copied_to_clipboard)
    val view = LocalView.current
    // Disable reverse layout when TalkBack is enabled to avoid incorrect ordering issues seen in the current Compose UI version
    val talkbackActive = isTalkbackActive()
    val useReverseLayout = !talkbackActive
    val programmaticScrollInProgress = remember { mutableStateOf(false) }
    val timeSeparatorLabels = remember(state.timelineItems) {
        buildTimelineTimeSeparatorLabels(state.timelineItems)
    }
    val renderedTimelineItemIds = remember(state.timelineItems) {
        renderedMediaPresentationIds(state.timelineItems)
    }
    val fastPathPendingMedia = remember(state.pendingMediaSendPresentations, renderedTimelineItemIds) {
        unrenderedPendingMediaSendPresentations(
            pendingMedia = state.pendingMediaSendPresentations,
            renderedTimelineItemIds = renderedTimelineItemIds,
        )
    }

    // The converted timeline row and the fast-path bubble switch in one committed composition. Acknowledging
    // afterwards only prunes the transient state; it cannot create a frame where neither representation exists.
    LaunchedEffect(state.pendingMediaSendPresentations, renderedTimelineItemIds) {
        state.pendingMediaSendPresentations
            .filter { it.displayId in renderedTimelineItemIds }
            .forEach {
                Timber.tag("YiZheMediaPresentation").d(
                    "YZMEDIA_ACK elapsed_ms=%d id=%s visible=%s",
                    android.os.SystemClock.elapsedRealtime(),
                    it.displayId.value,
                    lazyListState.layoutInfo.visibleItemsInfo.any { item -> item.key == it.displayId },
                )
                state.eventSink(TimelineEvent.AcknowledgePendingMediaPresentation(it.displayId))
            }
    }

    fun inReplyToClick(eventId: EventId) {
        state.eventSink(TimelineEvent.FocusOnEvent(eventId))
    }

    fun onLinkLongClick(link: Link) {
        view.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS
        )
        context.copyToClipboard(
            text = link.url,
            toastMessage = toastMessage,
        )
    }

    fun prefetchMoreItems() {
        val failed = state.timelineItems.any { item ->
            val loading = (item as? TimelineItem.Virtual)?.model as?
                io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemLoadingIndicatorModel
            loading != null && loading.direction == Timeline.PaginationDirection.BACKWARDS && loading.lastRequestFailed
        }
        if (!failed) state.eventSink(TimelineEvent.LoadMore(Timeline.PaginationDirection.BACKWARDS))
    }

    // Keep the timeline at full opacity. The surrounding navigation transition already covers room
    // entry; fading the whole LazyColumn made cached messages look as if they disappeared and were
    // decrypted again whenever the room screen was recreated.
    CompositionLocalProvider(LocalTimelineIsTalkbackActive provides talkbackActive,
        io.element.android.features.messages.impl.emotions.LocalEmotionRequest provides emotionPlayback.request) {
        Box(modifier) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(nestedScrollConnection)
                        .testTag(TestTags.timeline),
                    state = lazyListState,
                    reverseLayout = useReverseLayout,
                    contentPadding = PaddingValues(top = 64.dp, bottom = 8.dp),
                    // Stretch overscroll invalidates snapshot state and records a GraphicsLayer on
                    // every edge frame. The chat timeline already exposes pagination and scroll
                    // position explicitly, so the decorative stretch is unnecessary here.
                    overscrollEffect = null,
                ) {
                    items(
                        items = state.timelineItems,
                        contentType = { timelineItem -> timelineItem.contentType() },
                        key = { timelineItem -> timelineItem.identifier() },
                    ) { timelineItem ->
                        // Bounds are needed only when a long press completes. Snapshot state here used to
                        // invalidate every visible row whenever boundsInWindow changed during a scroll.
                        val itemBounds = remember(timelineItem.identifier()) { TimelineItemBoundsHolder() }
                        val eventId = (timelineItem as? TimelineItem.Event)?.eventId
                        val isDestroying = eventId?.let {
                            it in state.destroyingBurnAfterReadEventIds ||
                                it in state.destroyingMessageRemovalEventIds
                        } == true
                        val isRemovalPending = eventId?.let { it in state.pendingMessageRemovalEventIds } == true
                        val selectableEvent = (timelineItem as? TimelineItem.Event)?.takeIf { event ->
                            !isDestroying && !isRemovalPending && event.eventId != null &&
                                !event.content.isBurnAfterRead && event.content.canBeForwarded()
                        }
                        val withdrawnByMe = (timelineItem as? TimelineItem.Event)?.takeIf { event ->
                            event.isMine && event.content is TimelineItemRedactedContent &&
                                (event.trustedRedactionReason == TrustedRedactionReason.USER_WITHDRAWN ||
                                    event.eventId in reEditableWithdrawnEventIds)
                        }
                        Column {
                            timeSeparatorLabels[timelineItem.identifier().value]?.let { label ->
                                TimelineTimeSeparatorView(label)
                            }
                            // Keep the divider inside the message row: reverse-layout LazyColumn
                            // places separate item children in reverse order.
                            if (timelineItem.events().any { it.eventId == state.newMessageDividerEventId } && state.newMessageDividerEventId != null) {
                                Text(
                                    text = stringResource(R.string.screen_messages_new_message_divider),
                                    style = ElementTheme.typography.fontBodySmRegular,
                                    color = ElementTheme.colors.textSecondary,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                            MessageDestructionRow(
                                isDestroying = isDestroying,
                                isPending = isRemovalPending,
                            ) {
                                if (withdrawnByMe != null) {
                                    WithdrawnMessageRow(
                                        canReEdit = withdrawnByMe.eventId in reEditableWithdrawnEventIds,
                                        onReEdit = { withdrawnByMe.eventId?.let(onReEditWithdrawn) },
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier.onGloballyPositioned { coordinates ->
                                            val bounds = coordinates.boundsInWindow()
                                            if (!itemBounds.presentationLogged && timelineItem.identifier().value.startsWith("optimistic-media:")) {
                                                itemBounds.presentationLogged = true
                                                Timber.tag("YiZheMediaPresentation").d(
                                                    "YZMEDIA_LAYOUT elapsed_ms=%d id=%s top=%d bottom=%d viewport=%d",
                                                    android.os.SystemClock.elapsedRealtime(),
                                                    timelineItem.identifier().value,
                                                    bounds.top.roundToInt(),
                                                    bounds.bottom.roundToInt(),
                                                    view.height,
                                                )
                                            }
                                            itemBounds.value = IntRect(
                                                left = bounds.left.roundToInt(),
                                                top = bounds.top.roundToInt(),
                                                right = bounds.right.roundToInt(),
                                                bottom = bounds.bottom.roundToInt(),
                                            )
                                        },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (selectionMode && selectableEvent != null) {
                                            Checkbox(
                                                checked = selectableEvent.eventId in selectedEventIds,
                                                onCheckedChange = { onSelectionToggle(selectableEvent) },
                                            )
                                        }
                                        Box(modifier = Modifier.weight(1f)) {
                                            CompositionLocalProvider(io.element.android.features.messages.impl.emotions.LocalEmotionEventId provides timelineItem.identifier().value) {
                                            TimelineItemRow(
                                        timelineItem = timelineItem,
                                        timelineMode = state.timelineMode,
                                        timelineRoomInfo = state.timelineRoomInfo,
                                        timelineProtectionState = timelineProtectionState,
                                        renderReadReceipts = state.renderReadReceipts,
                                        isLastOutgoingMessage = state.isLastOutgoingMessage(timelineItem.identifier()),
                                        focusedEventId = state.focusedEventId,
                                        displayThreadSummaries = state.displayThreadSummaries,
                                        onUserDataClick = onUserDataClick,
                                        onLinkClick = onLinkClick,
                                        onLinkLongClick = ::onLinkLongClick,
                                        onContentClick = { event ->
                                            if (!isDestroying && !isRemovalPending) {
                                                if (selectionMode) selectableEvent?.let(onSelectionToggle) else if (!emotionPlayback.replay(event)) onContentClick(event)
                                            }
                                        },
                                        onLongClick = { event ->
                                            if (!isDestroying && !isRemovalPending) {
                                                if (selectionMode) selectableEvent?.let(onSelectionToggle) else onMessageLongClick(event, itemBounds.value)
                                            }
                                        },
                                        inReplyToClick = { targetId -> if (!isDestroying && !isRemovalPending) inReplyToClick(targetId) },
                                        onReactionClick = { emoji, event -> if (!isDestroying && !isRemovalPending) onReactionClick(emoji, event) },
                                        onReactionLongClick = { emoji, event -> if (!isDestroying && !isRemovalPending) onReactionLongClick(emoji, event) },
                                        onMoreReactionsClick = { event -> if (!isDestroying && !isRemovalPending) onMoreReactionsClick(event) },
                                        onReadReceiptClick = { event -> if (!isDestroying && !isRemovalPending) onReadReceiptClick(event) },
                                        onSwipeToReply = { event -> if (!isDestroying && !isRemovalPending && !selectionMode) onSwipeToReply(event) },
                                                eventSink = state.eventSink,
                                            )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                PendingMediaSendFastPath(
                    pendingMedia = fastPathPendingMedia,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                TimelineViewportAnchorKeeper(
                    scopeId = viewportScopeId,
                    timelineItems = state.timelineItems,
                    lazyListState = lazyListState,
                    isLive = state.isLive,
                    focusRequestState = state.focusRequestState,
                    programmaticScrollInProgress = programmaticScrollInProgress.value,
                )

                TimelineViewportReleaseHelper(
                    timelineItems = state.timelineItems,
                    lazyListState = lazyListState,
                    userVisible = userVisible,
                    onViewportChanged = onViewportChanged
                        ?: { visibleItemIds -> state.eventSink(TimelineEvent.ViewportChanged(visibleItemIds, userVisible)) },
                )

                if (!state.navigatingToFirstNewMessage) FocusRequestStateView(
                    focusRequestState = state.focusRequestState,
                    onClearFocusRequestState = ::clearFocusRequestState
                )

                TimelinePrefetchingHelper(
                    lazyListState = lazyListState,
                    programmaticScrollInProgress = programmaticScrollInProgress,
                    prefetch = ::prefetchMoreItems
                )

                TimelineScrollHelper(
                    scopeId = viewportScopeId,
                    state = state,
                    navigationBlocked = floatingNavigationBlocked || selectionMode || fastPathPendingMedia.isNotEmpty(),
                    hasAnyEvent = state.hasAnyEvent,
                    lazyListState = lazyListState,
                    forceJumpToBottomVisibility = forceJumpToBottomVisibility,
                    newEventState = state.newEventState,
                    isLive = state.isLive,
                    focusRequestState = state.focusRequestState,
                    terminalRemovalInProgress = state.destroyingBurnAfterReadEventIds.isNotEmpty() ||
                        state.destroyingMessageRemovalEventIds.isNotEmpty(),
                    programmaticScrollInProgress = programmaticScrollInProgress,
                    onScrollFinishAt = ::onScrollFinishAt,
                    onJumpToLive = ::onJumpToLive,
                    onFocusEventRender = ::onFocusEventRender,
                )

                NewMessageGeometryObserver(state, lazyListState)

                io.element.android.features.messages.impl.emotions.TimelineEmotions(
                    state = state, playback = emotionPlayback, scopeId = viewportScopeId,
                    liveEdge = lazyListState.firstVisibleItemIndex <= 1,
                    blocked = selectionMode || talkbackActive,
                    protection = timelineProtectionState,
                )
                if (useReverseLayout) {
                    FloatingDateBadgeOverlay(
                        lazyListState = lazyListState,
                        timelineItems = state.timelineItems,
                        isLive = state.isLive,
                        topOffset = floatingDateTopOffset,
                    )
                }
        }
    }

    ResolveVerifiedUserSendFailureView(state = state.resolveVerifiedUserSendFailureState)

    MessageShieldDialog(state)
}

@Composable
private fun PendingMediaSendFastPath(
    pendingMedia: List<PendingMediaSendPresentation>,
    modifier: Modifier = Modifier,
) {
    if (pendingMedia.isEmpty()) return
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        pendingMedia.takeLast(MAX_VISIBLE_FAST_PATH_MEDIA_SENDS).forEach { pending ->
            Row(
                modifier = Modifier
                    .composeTestTag("pending_media_send_${pending.displayId.value}")
                    .padding(top = 4.dp)
                    .background(
                        color = ElementTheme.colors.messageFromMeBackground,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(16.dp),
                    strokeWidth = 2.dp,
                    color = ElementTheme.colors.iconSecondary,
                )
                Text(
                    text = stringResource(R.string.screen_room_protected_attachment_decrypting),
                    color = ElementTheme.colors.textPrimary,
                    style = ElementTheme.typography.fontBodySmRegular,
                )
            }
        }
    }
}

private const val MAX_VISIBLE_FAST_PATH_MEDIA_SENDS = 3

internal fun renderedMediaPresentationIds(timelineItems: List<TimelineItem>): Set<UniqueId> = buildSet {
    timelineItems.forEach { item ->
        add(item.identifier())
        // Album cells retain each event's display identity after the row becomes a group.
        if (item is TimelineItem.GroupedEvents && item.kind == GroupedEventsKind.MEDIA_ALBUM) {
            item.events.forEach { add(it.displayId) }
        }
    }
}

internal fun unrenderedPendingMediaSendPresentations(
    pendingMedia: List<PendingMediaSendPresentation>,
    renderedTimelineItemIds: Set<UniqueId>,
): List<PendingMediaSendPresentation> = pendingMedia.filterNot { it.displayId in renderedTimelineItemIds }

/** Geometry cache deliberately outside Compose snapshot state so viewport motion cannot recompose the row. */
internal class TimelineItemBoundsHolder {
    var value: IntRect = IntRect.Zero
    var presentationLogged: Boolean = false
}

private data class TimelineViewportSnapshot(
    val source: List<TimelineItem>,
    val anchor: TimelineViewportAnchorCandidate?,
    val isAtLiveEdge: Boolean,
)

/**
 * Keeps a stable event at the same physical viewport offset across atomic timeline commits.
 * Only event identities and pixel offsets are retained; message content never crosses commits.
 */
@Composable
private fun TimelineViewportAnchorKeeper(
    scopeId: String,
    timelineItems: List<TimelineItem>,
    lazyListState: LazyListState,
    isLive: Boolean,
    focusRequestState: FocusRequestState,
    programmaticScrollInProgress: Boolean,
) {
    // Read these on every frame, not only after a list reference changes. A slow Reset may already
    // have changed LazyListState's numerical index by the time the new list is composed. The last
    // committed event identity is therefore the source of truth for continuity, never that
    // potentially reset index. Only identity and offset are retained; no message content is kept.
    val firstVisibleIndex = lazyListState.firstVisibleItemIndex
    val firstVisibleOffset = lazyListState.firstVisibleItemScrollOffset
    val userScrollInProgress = lazyListState.isScrollInProgress
    val currentAnchor = (timelineItems.getOrNull(firstVisibleIndex) as? TimelineItem.Event)?.let { item ->
        TimelineViewportAnchorCandidate(
            itemId = item.identifier().value,
            scrollOffset = firstVisibleOffset,
            distanceFromFirstVisibleItem = 0,
        )
    }
    val currentIsAtLiveEdge = isLive && firstVisibleIndex < 3
    val initialSnapshot = TimelineViewportSnapshot(
        source = timelineItems,
        anchor = currentAnchor,
        isAtLiveEdge = currentIsAtLiveEdge,
    )
    val previousSnapshotHolder = remember(scopeId) { arrayOf(initialSnapshot) }
    val previousSnapshot = previousSnapshotHolder[0]
    val timelineChanged = previousSnapshot.source !== timelineItems
    val keepLiveEdge = shouldKeepTimelineAtLiveEdge(
        timelineChanged = timelineChanged,
        wasAtLiveEdge = previousSnapshot.isAtLiveEdge,
        isLive = isLive,
        userScrollInProgress = userScrollInProgress,
        programmaticScrollInProgress = programmaticScrollInProgress,
        focusNavigationInProgress = focusRequestState !is FocusRequestState.None,
    )

    val anchorRequest = if (timelineChanged) {
        val currentIndices = timelineItems
            .mapIndexed { index, item -> item.identifier().value to index }
            .toMap()
        resolveTimelineViewportAnchor(
            candidates = listOfNotNull(previousSnapshot.anchor),
            currentItemIndices = currentIndices,
            isAtLiveEdge = previousSnapshot.isAtLiveEdge,
            userScrollInProgress = userScrollInProgress,
            // Structural commits must preserve the event under the user's finger. This is an
            // immediate identity correction, not an animated/programmatic navigation. Skipping it
            // loses the only pre-Reset anchor and lets a late pagination generation move the page.
            preserveCommitDuringUserScroll = true,
            programmaticScrollInProgress = programmaticScrollInProgress,
            focusNavigationInProgress = focusRequestState !is FocusRequestState.None,
        )
    } else {
        null
    }

    SideEffect {
        previousSnapshotHolder[0] = TimelineViewportSnapshot(
            source = timelineItems,
            anchor = anchorRequest?.let { request ->
                TimelineViewportAnchorCandidate(
                    itemId = request.itemId,
                    scrollOffset = request.scrollOffset,
                    distanceFromFirstVisibleItem = 0,
                )
            } ?: currentAnchor,
            isAtLiveEdge = when {
                keepLiveEdge -> true
                anchorRequest != null -> false
                else -> currentIsAtLiveEdge
            },
        )
        if (keepLiveEdge) {
            lazyListState.requestScrollToItem(0)
            Timber.tag("YiZheTimelineAnchor").d("scope=%s stage=follow_latest target_index=0", scopeId)
        } else if (anchorRequest != null) {
            lazyListState.requestScrollToItem(anchorRequest.targetIndex, anchorRequest.scrollOffset)
            Timber.tag("YiZheTimelineAnchor").d(
                "scope=%s anchor=%s target_index=%d offset_px=%d",
                scopeId,
                anchorRequest.itemId,
                anchorRequest.targetIndex,
                anchorRequest.scrollOffset,
            )
        }
    }
}

/**
 * A terminal, presentation-only transition. The presenter does not set [isDestroying] until the
 * encrypted local tombstone (and, when available, the remote burn session end) has completed.
 * Killing the process therefore discards only this animation state, never the destruction state.
 */
@Composable
internal fun MessageDestructionRow(
    isDestroying: Boolean,
    isPending: Boolean,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    var showPending by remember { mutableStateOf(false) }
    LaunchedEffect(isPending) {
        showPending = false
        if (isPending) {
            delay(220)
            showPending = true
        }
    }
    val progress by animateFloatAsState(
        targetValue = if (isDestroying) 1f else 0f,
        animationSpec = tween(durationMillis = 430, easing = LinearEasing),
        label = "messageDestruction",
    )
    LaunchedEffect(isDestroying) {
        if (isDestroying) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    AnimatedVisibility(
        visible = !isDestroying,
        exit = shrinkVertically(
            animationSpec = tween(durationMillis = 140, delayMillis = 300),
            shrinkTowards = Alignment.CenterVertically,
        ),
    ) {
        val settleProgress = (progress / 0.28f).coerceIn(0f, 1f)
        val fragmentProgress = ((progress - 0.22f) / 0.78f).coerceIn(0f, 1f)
        // Ordinary messages do not need an extra drawing layer or fragment renderer.
        // Keep the same content composition and terminal animation; only attach its effects
        // while the existing animation has a nonzero progress value.
        val destructionEffects = if (progress > 0f) {
            Modifier
                .graphicsLayer {
                    scaleX = 1f - settleProgress * 0.03f
                    scaleY = 1f - settleProgress * 0.03f
                    alpha = 1f - fragmentProgress * fragmentProgress * 0.88f
                }
                .blur((fragmentProgress * 1.4f).dp)
                .drawWithContent {
                    if (fragmentProgress <= 0f) {
                        drawContent()
                    } else {
                        val columns = 5
                        val rows = 3
                        val fragmentWidth = size.width / columns
                        val fragmentHeight = size.height / rows
                        repeat(rows) { row ->
                            repeat(columns) { column ->
                                val index = row * columns + column
                                val direction = if (index % 2 == 0) -1f else 1f
                                val horizontal = direction * (3.dp.toPx() + (index % 3) * 2.dp.toPx()) * fragmentProgress
                                val vertical = (
                                    7.dp.toPx() + row * 5.dp.toPx() + (index % 2) * 3.dp.toPx()
                                    ) * fragmentProgress * fragmentProgress
                                translate(left = horizontal, top = vertical) {
                                    clipRect(
                                        left = column * fragmentWidth,
                                        top = row * fragmentHeight,
                                        right = (column + 1) * fragmentWidth,
                                        bottom = (row + 1) * fragmentHeight,
                                    ) {
                                        this@drawWithContent.drawContent()
                                    }
                                }
                            }
                        }
                    }
                }
        } else {
            Modifier
        }
        Box(
            modifier = Modifier.fillMaxWidth().then(destructionEffects),
        ) {
            content()
            if (showPending && !isDestroying) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(18.dp),
                    color = ElementTheme.colors.iconAccentPrimary,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun WithdrawnMessageRow(
    canReEdit: Boolean,
    onReEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.message_withdrawn_by_you),
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textSecondary,
        )
        if (canReEdit) {
            Text(
                text = stringResource(R.string.message_reedit_withdrawn),
                modifier = Modifier
                    .clickable(onClick = onReEdit)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                style = ElementTheme.typography.fontBodySmMedium,
                color = ElementTheme.colors.textActionAccent,
            )
        }
    }
}

private const val TIMELINE_TIME_SEPARATOR_GAP_MILLIS = 5 * 60 * 1_000L

internal fun buildTimelineTimeSeparatorLabels(
    timelineItems: List<TimelineItem>,
    minimumGapMillis: Long = TIMELINE_TIME_SEPARATOR_GAP_MILLIS,
): Map<String, String> {
    val events = timelineItems
        .filterIsInstance<TimelineItem.Event>()
        .filter { it.sentTimeMillis > 0L && it.sentTime.isNotBlank() }
        .sortedWith(compareBy<TimelineItem.Event> { it.sentTimeMillis }.thenBy { it.identifier().value })
    if (events.isEmpty()) return emptyMap()

    return buildMap {
        var previous: TimelineItem.Event? = null
        events.forEach { event ->
            val dateChanged = previous?.sentDate != event.sentDate
            val hasEnoughGap = previous?.let { event.sentTimeMillis - it.sentTimeMillis >= minimumGapMillis } ?: true
            if (dateChanged || hasEnoughGap) {
                val label = if (previous == null || dateChanged) {
                    listOf(event.sentDate, event.sentTime).filter(String::isNotBlank).joinToString(" ")
                } else {
                    event.sentTime
                }
                put(event.identifier().value, label)
            }
            previous = event
        }
    }
}

@Composable
private fun TimelineTimeSeparatorView(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        io.element.android.libraries.designsystem.theme.components.Text(
            text = label,
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textSecondary,
        )
    }
}

private data class MeasuredTimelineViewport(val itemIds: List<UniqueId>)

/**
 * Reports only a viewport measured by the same Compose snapshot as the immutable item list used
 * for index lookup. A transient list/layout mismatch is ignored instead of attributing an index to
 * an item from another timeline revision. An empty measured viewport remains distinct from the
 * pre-measurement state.
 */
@Composable
private fun TimelineViewportReleaseHelper(
    timelineItems: List<TimelineItem>,
    lazyListState: LazyListState,
    userVisible: Boolean,
    onViewportChanged: (List<UniqueId>) -> Unit,
) {
    val latestTimelineItems by rememberUpdatedState(timelineItems)
    val latestOnViewportChanged by rememberUpdatedState(onViewportChanged)

    LaunchedEffect(lazyListState, userVisible) {
        snapshotFlow {
            val layoutInfo = lazyListState.layoutInfo
            val items = latestTimelineItems
            when {
                layoutInfo.viewportSize.height <= 0 -> null
                layoutInfo.totalItemsCount > 0 && layoutInfo.visibleItemsInfo.isEmpty() -> null
                else -> timelineViewportItemIds(
                    timelineItems = items,
                    visibleIndices = layoutInfo.visibleItemsInfo.map { it.index },
                )?.let(::MeasuredTimelineViewport)
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collectLatest { viewport -> latestOnViewportChanged(viewport.itemIds) }
    }
}

internal fun timelineViewportItemIds(
    timelineItems: List<TimelineItem>,
    visibleIndices: List<Int>,
): List<UniqueId>? {
    if (visibleIndices.any { it !in timelineItems.indices }) return null
    return visibleIndices
        .flatMap { index ->
            when (val item = timelineItems[index]) {
                is TimelineItem.Event -> listOf(item.id)
                is TimelineItem.GroupedEvents -> item.events.map { event -> event.id }
                is TimelineItem.Virtual -> emptyList()
            }
        }
        .distinct()
}

@Composable
private fun MessageShieldDialog(state: TimelineState) {
    val messageShield = state.messageShieldDialogData ?: return
    AlertDialog(
        content = messageShield.toText(),
        onDismiss = { state.eventSink.invoke(TimelineEvent.HideShieldDialog) },
    )
}

@Composable
private fun TimelinePrefetchingHelper(
    lazyListState: LazyListState,
    programmaticScrollInProgress: MutableState<Boolean>,
    prefetch: () -> Unit,
) {
    val latestPrefetch by rememberUpdatedState(prefetch)

    LaunchedEffect(Unit) {
        // We're using snapshot flows for these because using `LaunchedEffect` with `derivedState` doesn't seem to be responsive enough
        val firstVisibleItemIndexFlow = snapshotFlow { lazyListState.firstVisibleItemIndex }
        val layoutInfoFlow = snapshotFlow { lazyListState.layoutInfo }
        val scrollActivityFlow = snapshotFlow {
            lazyListState.isScrollInProgress to programmaticScrollInProgress.value
        }
            // This value changes too frequently, so we debounce it to avoid unnecessary prefetching. It's the equivalent of a conditional 'throttleLatest'
            .conflate()
            .transform { scrollActivity ->
                emit(scrollActivity)
                if (scrollActivity.first) delay(100.milliseconds)
            }

        val isCloseToStartOfLoadedTimelineFlow = combine(layoutInfoFlow, firstVisibleItemIndexFlow) { layoutInfo, firstVisibleItemIndex ->
            firstVisibleItemIndex + layoutInfo.visibleItemsInfo.size >= layoutInfo.totalItemsCount - 40
        }

        // If we have no timeline items, we need to back paginate to load some messages. This usually happens on all timelines except for live ones.
        // This automatic pagination was previously done by the SDK, and we received a `Reset` update, but now we need to do it ourselves.
        val isEmptyTimelineFlow = layoutInfoFlow.map { it.totalItemsCount == 0 }

        combine(
            isCloseToStartOfLoadedTimelineFlow.distinctUntilChanged(),
            scrollActivityFlow.distinctUntilChanged(),
            isEmptyTimelineFlow,
        ) { needsPrefetch, scrollActivity, isEmptyAndNeedsBackPagination ->
            shouldPrefetchTimeline(
                needsPrefetch = needsPrefetch,
                isScrolling = scrollActivity.first,
                isEmptyAndNeedsBackPagination = isEmptyAndNeedsBackPagination,
                isProgrammaticScroll = scrollActivity.second,
            )
        }
            .distinctUntilChanged()
            .collectLatest { needsPrefetch ->
                if (needsPrefetch) {
                    Timber.d("Prefetching pagination with ${lazyListState.layoutInfo.totalItemsCount} items")
                    latestPrefetch()
                }
            }
    }
}

internal fun shouldPrefetchTimeline(
    needsPrefetch: Boolean,
    isScrolling: Boolean,
    isEmptyAndNeedsBackPagination: Boolean,
    isProgrammaticScroll: Boolean,
): Boolean = isEmptyAndNeedsBackPagination || needsPrefetch && isScrolling && !isProgrammaticScroll

@Composable
private fun BoxScope.TimelineScrollHelper(
    scopeId: String,
    state: TimelineState,
    navigationBlocked: Boolean,
    hasAnyEvent: Boolean,
    lazyListState: LazyListState,
    newEventState: NewEventState,
    isLive: Boolean,
    forceJumpToBottomVisibility: Boolean,
    focusRequestState: FocusRequestState,
    terminalRemovalInProgress: Boolean,
    programmaticScrollInProgress: MutableState<Boolean>,
    onScrollFinishAt: (Int, Boolean) -> Unit,
    onJumpToLive: () -> Unit,
    onFocusEventRender: () -> Unit,
) {
    val userVisible = rememberTimelineUserVisible()
    var liveEntryPlaced by remember(scopeId) { mutableStateOf(false) }
    LaunchedEffect(isLive, hasAnyEvent, focusRequestState) {
        if (!liveEntryPlaced && isLive && hasAnyEvent) {
            liveEntryPlaced = true
            if (focusRequestState is FocusRequestState.None) lazyListState.requestScrollToItem(0)
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val firstNewInsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.roundToPx() }
    val isScrollFinished by remember { derivedStateOf { !lazyListState.isScrollInProgress } }
    val canAutoScroll by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex < 3 && isLive
        }
    }
    var jumpToLiveHandled by remember { mutableStateOf(true) }
    var terminalRemovalWasActive by remember { mutableStateOf(false) }
    var pinBottomThroughTerminalRemoval by remember { mutableStateOf(false) }
    val latestOnScrollFinishAt by rememberUpdatedState(onScrollFinishAt)
    val programmaticScrollMutex = remember { Mutex() }

    suspend fun runProgrammaticScroll(block: suspend () -> Unit) {
        programmaticScrollMutex.lock()
        try {
            programmaticScrollInProgress.value = true
            try {
                block()
            } finally {
                // Keep pagination gated until LazyListState has observed the end of this scroll.
                withContext(NonCancellable) {
                    try {
                        snapshotFlow { lazyListState.isScrollInProgress }.first { isScrolling -> !isScrolling }
                    } finally {
                        programmaticScrollInProgress.value = false
                    }
                }
            }
        } finally {
            programmaticScrollMutex.unlock()
        }
    }

    /**
     * @param force If true, notify the presenter when the latest item is already visible so read receipts are
     * still sent. Do not start a no-op animation in that case: it visibly shifts a newly sent bubble and can
     * make the pagination helper mistake the programmatic animation for a request to load history.
     */
    fun scrollToBottom(force: Boolean) {
        coroutineScope.launch {
            val isAlreadyAtBottom = lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0
            if (lazyListState.firstVisibleItemIndex > 10) {
                runProgrammaticScroll { lazyListState.scrollToItem(0) }
            } else if (!isAlreadyAtBottom) {
                runProgrammaticScroll { lazyListState.animateScrollToItem(0) }
            } else if (force) {
                latestOnScrollFinishAt(0, userVisible)
            }
        }
    }

    fun jumpToBottom() {
        if (isLive) {
            scrollToBottom(force = false)
        } else {
            jumpToLiveHandled = false
            onJumpToLive()
        }
    }

    LaunchedEffect(jumpToLiveHandled, isLive) {
        if (!jumpToLiveHandled && isLive) {
            runProgrammaticScroll { lazyListState.scrollToItem(0) }
            jumpToLiveHandled = true
        }
    }

    LaunchedEffect(terminalRemovalInProgress, isLive) {
        val removalStarted = terminalRemovalInProgress && !terminalRemovalWasActive
        if (removalStarted) {
            // Only preserve the live bottom edge when it was already visible. A user reading older
            // history must not be pulled away by a remote redaction or burn-after-read event.
            // Index zero may be a virtual typing/read-receipt row. Match the timeline's existing
            // near-bottom rule so a fully visible latest message at index one or two is still pinned.
            pinBottomThroughTerminalRemoval = isLive && lazyListState.firstVisibleItemIndex < 3
        }
        terminalRemovalWasActive = terminalRemovalInProgress

        if (!pinBottomThroughTerminalRemoval) return@LaunchedEffect
        if (!terminalRemovalInProgress) {
            // The retained item is released after its collapse. Correct the final measurement too,
            // before a later outgoing message can animate across the temporary empty viewport.
            lazyListState.requestScrollToItem(0)
            pinBottomThroughTerminalRemoval = false
            return@LaunchedEffect
        }

        // AnimatedVisibility changes the terminal row height during several layout frames. Keep the
        // reverse-layout viewport anchored to item zero for those frames; this preserves the
        // destruction animation without moving or re-inserting any other timeline item.
        while (true) {
            withFrameNanos { }
            lazyListState.requestScrollToItem(0)
        }
    }

    val latestTimelineState by rememberUpdatedState(state)
    val latestOnFocusEventRender by rememberUpdatedState(onFocusEventRender)
    LaunchedEffect(focusRequestState) {
        if (focusRequestState is FocusRequestState.Success && focusRequestState.isIndexed && !focusRequestState.rendered) {
            runProgrammaticScroll {
                if (state.navigatingToFirstNewMessage) {
                    // A distant target is loaded directly. Once measured, align its beginning near the physical top.
                    lazyListState.scrollToItem(focusRequestState.index)
                    withFrameNanos { }
                    val layout = lazyListState.layoutInfo
                    val row = layout.visibleItemsInfo.firstOrNull { it.index == focusRequestState.index }
                    if (row != null) {
                        val inset = layout.afterContentPadding + firstNewInsetPx
                        val offset = if (layout.reverseLayout) row.size - layout.viewportSize.height + inset else -inset
                        lazyListState.scrollToItem(focusRequestState.index, offset)
                    }
                } else lazyListState.animateScrollToItemCenter(focusRequestState.index)
            }
            withFrameNanos { }
            if (lazyListState.layoutInfo.visibleItemsInfo.none { it.index == focusRequestState.index }) return@LaunchedEffect
            if (state.navigatingToFirstNewMessage) {
                val currentItems = latestTimelineState.timelineItems
                val focusedRow = lazyListState.layoutInfo.visibleItemsInfo.any { measured ->
                    val item = currentItems.getOrNull(measured.index)
                    item != null && item.identifier() == measured.key && item.events().any { it.eventId == focusRequestState.eventId }
                }
                if (!focusedRow) return@LaunchedEffect
            }
            latestOnFocusEventRender()
        }
    }

    LaunchedEffect(canAutoScroll, newEventState) {
        val shouldScrollToBottom = isScrollFinished &&
            (canAutoScroll && newEventState == NewEventState.FromOther || newEventState == NewEventState.FromMe)
        if (shouldScrollToBottom) {
            scrollToBottom(force = true)
        }
    }

    LaunchedEffect(isScrollFinished, hasAnyEvent, userVisible, state.timelineItems) {
        if (isScrollFinished && hasAnyEvent) {
            // Notify the parent composable about the first visible item index when scrolling finishes
            latestOnScrollFinishAt(lazyListState.firstVisibleItemIndex, userVisible)
        }
    }

    NewMessageNavigationPill(
        scopeId = scopeId, state = state,
        // Use inverse of canAutoScroll otherwise we might briefly see the before the scroll animation is triggered
        showLatest = !canAutoScroll || forceJumpToBottomVisibility || !isLive,
        blocked = navigationBlocked,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 12.dp),
        onLatest = { jumpToBottom() },
    )
}

@Composable
private fun NewMessageGeometryObserver(state: TimelineState, list: LazyListState) {
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(list, state.newMessageNavigation.firstNewEventId) {
        snapshotFlow {
            val current = latestState
            val layout = list.layoutInfo
            // A pagination/echo revision may retain indices while changing row identity. Wait
            // for the measured keys to match this exact immutable timeline before consuming an anchor.
            if (layout.viewportSize.height <= 0 || layout.totalItemsCount != current.timelineItems.size ||
                layout.visibleItemsInfo.any { measured -> current.timelineItems.getOrNull(measured.index)?.identifier() != measured.key }) {
                return@snapshotFlow null
            }
            val index = newMessageItemIndex(current.timelineItems, current.newMessageNavigation.firstNewEventId)
            val measured = layout.visibleItemsInfo.firstOrNull { it.index == index }
            val above = firstNewMessageAboveViewport(index, layout.visibleItemsInfo.map { it.index }, measured?.offset,
                layout.viewportStartOffset, layout.reverseLayout)
            TimelineEvent.NewMessageHintGeometry(
                aboveViewport = above,
                targetVisible = measured != null && !above && !list.isScrollInProgress,
                atLiveEdge = current.isLive && list.firstVisibleItemIndex < 3,
            )
        }.filterNotNull().distinctUntilChanged().collectLatest { geometry ->
            delay(200L) // Wait for entry / media layout to settle; a keyboard alone cannot create a new batch.
            latestState.eventSink(geometry)
        }
    }
}

@Composable
private fun JumpToBottomButton(
    isVisible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        modifier = modifier,
        visible = isVisible,
        enter = scaleIn(animationSpec = tween(100)),
        exit = scaleOut(animationSpec = tween(100)),
    ) {
        FloatingActionButton(
            onClick = onClick,
            elevation = FloatingActionButtonDefaults.elevation(4.dp, 4.dp, 4.dp, 4.dp),
            shape = CircleShape,
            modifier = Modifier.size(36.dp),
            containerColor = ElementTheme.colors.bgSubtleSecondary,
            contentColor = ElementTheme.colors.iconSecondary,
        ) {
            Icon(
                modifier = Modifier
                    .size(24.dp)
                    .rotate(90f),
                imageVector = CompoundIcons.ArrowRight(),
                contentDescription = stringResource(id = CommonStrings.a11y_jump_to_bottom)
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun TimelineViewPreview(
    @PreviewParameter(TimelineItemEventContentProvider::class) content: TimelineItemEventContent
) = ElementPreview {
    val timelineItems = aTimelineItemList(content)
    val timelineEvents = timelineItems.filterIsInstance<TimelineItem.Event>()
    val lastEventIdFromMe = timelineEvents.firstOrNull { it.isMine }?.eventId
    val lastEventIdFromOther = timelineEvents.firstOrNull { !it.isMine }?.eventId
    CompositionLocalProvider(
        LocalTimelineItemPresenterFactories provides aFakeTimelineItemPresenterFactories(),
    ) {
        TimelineView(
            state = aTimelineState(
                timelineItems = timelineItems,
                timelineRoomInfo = aTimelineRoomInfo(
                    pinnedEventIds = listOfNotNull(lastEventIdFromMe, lastEventIdFromOther)
                ),
                focusedEventIndex = 0,
            ),
            timelineProtectionState = aTimelineProtectionState(),
            onUserDataClick = {},
            onLinkClick = {},
            onContentClick = {},
            onMessageLongClick = { _, _ -> },
            onSwipeToReply = {},
            onReactionClick = { _, _ -> },
            onReactionLongClick = { _, _ -> },
            onMoreReactionsClick = {},
            onReadReceiptClick = {},
            forceJumpToBottomVisibility = true,
        )
    }
}
