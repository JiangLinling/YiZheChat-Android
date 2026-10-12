/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.factories

import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.messages.impl.timeline.diff.TimelineItemsCacheInvalidator
import io.element.android.features.messages.impl.timeline.factories.event.TimelineItemEventFactory
import io.element.android.features.messages.impl.timeline.factories.virtual.TimelineItemVirtualFactory
import io.element.android.features.messages.impl.timeline.groups.TimelineItemGrouper
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.isEdited
import io.element.android.features.messages.impl.timeline.model.event.isRedacted
import io.element.android.libraries.androidutils.diff.DiffCacheUpdater
import io.element.android.libraries.androidutils.diff.MutableListDiffCache
import io.element.android.libraries.androidutils.metadata.isInDebug
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

@AssistedInject
class TimelineItemsFactory(
    @Assisted private val config: TimelineItemsFactoryConfig,
    eventItemFactoryCreator: TimelineItemEventFactory.Creator,
    private val dispatchers: CoroutineDispatchers,
    private val virtualItemFactory: TimelineItemVirtualFactory,
    private val timelineItemGrouper: TimelineItemGrouper,
) {
    @AssistedFactory
    interface Creator {
        fun create(config: TimelineItemsFactoryConfig): TimelineItemsFactory
    }

    private val eventItemFactory = eventItemFactoryCreator.create(config)
    private val _timelineItems = MutableSharedFlow<ImmutableList<TimelineItem>>(replay = 1)
    private val lock = Mutex()
    private val diffCache = MutableListDiffCache<TimelineItem>()
    private var hasEmittedTimelineItems = false
    private var emissionGeneration = 0L
    private val reportedUiRedactions = mutableSetOf<String>()
    private var previousTimelineItems = emptyList<MatrixTimelineItem>()
    private var previousRoomMembers = emptyList<RoomMember>()
    private val diffCacheUpdater = DiffCacheUpdater<MatrixTimelineItem, TimelineItem>(
        diffCache = diffCache,
        detectMoves = false,
        cacheInvalidator = TimelineItemsCacheInvalidator()
    ) { old, new ->
        when {
            old is MatrixTimelineItem.Event && new is MatrixTimelineItem.Event ->
                old.displayUniqueId == new.displayUniqueId
            old is MatrixTimelineItem.Virtual && new is MatrixTimelineItem.Virtual ->
                old.uniqueId == new.uniqueId
            else -> false
        }
    }

    val timelineItems: Flow<ImmutableList<TimelineItem>> = _timelineItems.distinctUntilChanged()

    suspend fun replaceWith(
        timelineItems: List<MatrixTimelineItem>,
        roomMembers: List<RoomMember>,
    ) = withContext(dispatchers.computation) {
        lock.withLock {
            val timelineReferencesUnchanged = sameReferences(previousTimelineItems, timelineItems)
            val roomMembersChanged = previousRoomMembers != roomMembers
            val changedReadReceiptMemberIds = if (roomMembersChanged) {
                changedReadReceiptMemberIds(previousRoomMembers, roomMembers)
            } else {
                emptySet()
            }
            val readReceiptAffectedKeys = if (changedReadReceiptMemberIds.isEmpty()) {
                emptySet()
            } else {
                timelineItems.mapNotNull { item ->
                    val event = item as? MatrixTimelineItem.Event ?: return@mapNotNull null
                    itemKey(event)?.takeIf {
                        (config.computeReadReceipts && event.hasReceiptFrom(changedReadReceiptMemberIds)) ||
                            (event.event.senderProfile !is ProfileDetails.Ready && event.event.sender in changedReadReceiptMemberIds)
                    }
                }.toSet()
            }
            if (hasEmittedTimelineItems && timelineReferencesUnchanged && readReceiptAffectedKeys.isEmpty()) {
                // Room members are loaded independently from the timeline. Most member changes cannot affect
                // a message row at all; receipts and unresolved sender profiles use member data. Remember the
                // latest snapshot without rebuilding or republishing every visible message.
                previousRoomMembers = roomMembers.toList()
                return@withLock
            }
            val startedAt = System.nanoTime()
            val previousByKey = previousTimelineItems.mapNotNull { item ->
                itemKey(item)?.let { key -> key to item }
            }.toMap()
            val contentChangedKeys = timelineItems.mapNotNull { item ->
                itemKey(item)?.takeIf { key -> previousByKey[key] !== item || key in readReceiptAffectedKeys }
            }.toSet()
            val previousGroupingContexts = previousTimelineItems.eventGroupingContexts()
            val groupingChangedKeys = timelineItems.eventGroupingContexts().mapNotNull { (key, context) ->
                key.takeIf { previousGroupingContexts[key] != context }
            }.toSet()
            val changedKeys = contentChangedKeys + groupingChangedKeys
            var changedKeysForFullBuild = changedKeys
            var previousByKeyForFullBuild = previousByKey
            var retainedVisiblePresentations = emptyMap<String, TimelineItem>()
            if (previousTimelineItems.isEmpty() && timelineItems.size > INITIAL_VISIBLE_ITEM_COUNT) {
                val visibleItems = timelineItems.takeLast(INITIAL_VISIBLE_ITEM_COUNT)
                val visibleEventNeighbours = timelineItems.realEventNeighbours().takeLast(INITIAL_VISIBLE_ITEM_COUNT)
                val visibleKeys = visibleItems.mapNotNull(::itemKey).toSet()
                diffCacheUpdater.updateWith(visibleItems)
                buildAndEmitTimelineItemStates(visibleItems, roomMembers, visibleKeys, emptyMap(), visibleEventNeighbours)
                retainedVisiblePresentations = visibleItems.mapIndexedNotNull { index, item ->
                    val key = itemKey(item) ?: return@mapIndexedNotNull null
                    diffCache.get(index)?.let { presentation -> key to presentation }
                }.toMap()
                Timber.tag(PERFORMANCE_TAG).d(
                    "stage=timeline_item_build phase=visible item_count=%d changed_count=%d duration_ms=%d",
                    visibleItems.size,
                    visibleKeys.size,
                    (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND,
                )
                changedKeysForFullBuild = changedKeys - visibleKeys
                previousByKeyForFullBuild = visibleItems.mapNotNull { item ->
                    itemKey(item)?.let { key -> key to item }
                }.toMap()
            }
            diffCacheUpdater.updateWith(timelineItems)
            if (retainedVisiblePresentations.isNotEmpty()) {
                // Prepending the off-screen history invalidates cache entries around the insertion point.
                // The visible window was already built with neighbours from the complete timeline, so restoring
                // those exact presentations is both correct and prevents a second visible-row composition pass.
                timelineItems.forEachIndexed { index, item ->
                    retainedVisiblePresentations[itemKey(item)]?.let { presentation ->
                        diffCache[index] = presentation
                    }
                }
            }
            buildAndEmitTimelineItemStates(timelineItems, roomMembers, changedKeysForFullBuild, previousByKeyForFullBuild)
            hasEmittedTimelineItems = true
            previousTimelineItems = timelineItems.toList()
            previousRoomMembers = roomMembers.toList()
            Timber.tag(PERFORMANCE_TAG).d(
                "stage=timeline_item_build item_count=%d changed_count=%d duration_ms=%d",
                timelineItems.size,
                changedKeys.size,
                (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND,
            )
        }
    }

    private suspend fun buildAndEmitTimelineItemStates(
        timelineItems: List<MatrixTimelineItem>,
        roomMembers: List<RoomMember>,
        changedKeys: Set<String>,
        previousByKey: Map<String, MatrixTimelineItem>,
        eventNeighbours: List<TimelineEventNeighbours> = timelineItems.realEventNeighbours(),
    ) {
        val newTimelineItemStates = ArrayList<TimelineItem>()
        for (index in diffCache.indices().reversed()) {
            val cacheItem = diffCache.get(index)
            if (cacheItem == null) {
                buildAndCacheItem(timelineItems, index, roomMembers, eventNeighbours[index])?.also { timelineItemState ->
                    newTimelineItemStates.add(timelineItemState)
                }
            } else {
                val currentMatrixItem = timelineItems[index] as? MatrixTimelineItem.Event
                val currentKey = currentMatrixItem?.let(::itemKey)
                val updatedItem = if (cacheItem is TimelineItem.Event && currentMatrixItem != null && currentKey != null &&
                    (currentKey in changedKeys || previousByKey[currentKey] !== currentMatrixItem)
                ) {
                    eventItemFactory.update(
                        timelineItem = cacheItem,
                        receivedMatrixTimelineItem = currentMatrixItem,
                        roomMembers = roomMembers,
                        neighbours = eventNeighbours[index],
                    )
                } else {
                    cacheItem
                }
                // Unchanged rows on the next snapshot must reuse the latest state, not the initial local echo.
                diffCache[index] = updatedItem
                newTimelineItemStates.add(updatedItem)
            }
        }
        val projectionItems = coalesceDuplicateRemoteEventProjections(newTimelineItemStates)
        if (projectionItems.size != newTimelineItemStates.size) {
            Timber.tag(COMPOSE_KEY_DIAGNOSTIC_TAG).w(
                "coalesced_duplicate_event_projections candidate_count=%d retained_count=%d",
                newTimelineItemStates.size,
                projectionItems.size,
            )
        }
        val result = timelineItemGrouper.group(projectionItems).toImmutableList()
        emissionGeneration++
        if (isInDebug) {
            logDuplicateComposeKeys(result, emissionGeneration)
            result.filterIsInstance<TimelineItem.Event>()
                .filter { it.content.isRedacted() }
                .forEach { event ->
                    val identity = event.eventId?.value ?: event.transactionId?.value ?: event.id.value
                    if (reportedUiRedactions.add(identity)) {
                        Timber.tag(REDACTION_LATENCY_TAG).d(
                            "stage=T4 identity=%s source=ui_projection epoch_ms=%d monotonic_ns=%d generation=%d",
                            identity,
                            System.currentTimeMillis(),
                            System.nanoTime(),
                            emissionGeneration,
                        )
                    }
                }
        }
        this._timelineItems.emit(result)
    }

    private fun sameReferences(previous: List<MatrixTimelineItem>, current: List<MatrixTimelineItem>): Boolean =
        previous.size == current.size && previous.indices.all { index -> previous[index] === current[index] }

    private fun changedReadReceiptMemberIds(previous: List<RoomMember>, current: List<RoomMember>): Set<UserId> {
        val previousProfiles = previous.associate { member -> member.userId to member.readReceiptProfile() }
        val currentProfiles = current.associate { member -> member.userId to member.readReceiptProfile() }
        return (previousProfiles.keys + currentProfiles.keys).filterTo(mutableSetOf()) { userId ->
            previousProfiles[userId] != currentProfiles[userId]
        }
    }

    private fun RoomMember.readReceiptProfile(): ReadReceiptMemberProfile = ReadReceiptMemberProfile(
        displayName = displayName,
        avatarUrl = avatarUrl,
    )

    private fun MatrixTimelineItem.Event.hasReceiptFrom(userIds: Set<UserId>): Boolean =
        event.receipts.any { receipt -> receipt.userId in userIds }

    private fun itemKey(item: MatrixTimelineItem): String? = when (item) {
        is MatrixTimelineItem.Event -> "event:${item.displayUniqueId.value}"
        is MatrixTimelineItem.Virtual -> "virtual:${item.uniqueId.value}"
        MatrixTimelineItem.Other -> null
    }

    private fun List<MatrixTimelineItem>.eventGroupingContexts(): Map<String, EventGroupingContext> {
        val neighbours = realEventNeighbours()
        return mapIndexedNotNull { index, item ->
            val event = item as? MatrixTimelineItem.Event ?: return@mapIndexedNotNull null
            val key = itemKey(event) ?: return@mapIndexedNotNull null
            key to EventGroupingContext(
                previousKey = neighbours[index].previous?.let(::itemKey),
                nextKey = neighbours[index].next?.let(::itemKey),
            )
        }.toMap()
    }

    private companion object {
        const val PERFORMANCE_TAG = "YiZheTimelinePerf"
        const val REDACTION_LATENCY_TAG = "YiZheRedactionLatency"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val INITIAL_VISIBLE_ITEM_COUNT = 30
    }

    private suspend fun buildAndCacheItem(
        timelineItems: List<MatrixTimelineItem>,
        index: Int,
        roomMembers: List<RoomMember>,
        neighbours: TimelineEventNeighbours,
    ): TimelineItem? {
        val timelineItem =
            when (val currentTimelineItem = timelineItems[index]) {
                is MatrixTimelineItem.Event -> eventItemFactory.create(currentTimelineItem, neighbours, roomMembers)
                is MatrixTimelineItem.Virtual -> virtualItemFactory.create(currentTimelineItem)
                MatrixTimelineItem.Other -> null
            }
        diffCache[index] = timelineItem
        return timelineItem
    }
}

internal data class TimelineComposeKeyDiagnostic(
    val composeKey: String,
    val logicalMessageId: String,
    val localEchoId: String?,
    val transactionId: String?,
    val eventId: String?,
    val relationType: String,
    val optimistic: Boolean,
    val sendState: String?,
    val redacted: Boolean,
    val edited: Boolean,
    val timelineIndex: Int,
    val source: String?,
    val generation: Long,
)

internal fun duplicateComposeKeyDiagnostics(
    items: List<TimelineItem>,
    generation: Long,
): List<TimelineComposeKeyDiagnostic> = items
    .mapIndexed { index, item -> item.toComposeKeyDiagnostic(index, generation) }
    .groupBy(TimelineComposeKeyDiagnostic::composeKey)
    .values
    .filter { conflicts -> conflicts.size > 1 }
    .flatten()

/**
 * Keeps exactly one presentation for each authenticated event. During local-echo confirmation or redaction the SDK
 * can briefly project two versions of the same event. A terminal redacted projection must win over stale plaintext;
 * otherwise the newest projection in this snapshot wins. Local echoes have no event id and are intentionally kept.
 */
internal fun coalesceDuplicateRemoteEventProjections(items: List<TimelineItem>): List<TimelineItem> {
    val retainedIndexByEventId = items.mapIndexedNotNull { index, item ->
        val event = item as? TimelineItem.Event ?: return@mapIndexedNotNull null
        val eventId = event.eventId ?: return@mapIndexedNotNull null
        eventId to index
    }.groupBy({ it.first }, { it.second }).mapValues { (_, candidateIndices) ->
        candidateIndices.maxWithOrNull(
            compareBy<Int> { index ->
                val event = items[index] as TimelineItem.Event
                if (event.content.isRedacted()) 1 else 0
            }.thenBy { index -> index }
        ) ?: error("Authenticated event projection group cannot be empty")
    }
    if (retainedIndexByEventId.size == items.count { it is TimelineItem.Event && it.eventId != null }) {
        return items
    }
    return items.filterIndexed { index, item ->
        val event = item as? TimelineItem.Event ?: return@filterIndexed true
        val eventId = event.eventId ?: return@filterIndexed true
        retainedIndexByEventId[eventId] == index
    }
}

private fun logDuplicateComposeKeys(items: List<TimelineItem>, generation: Long) {
    duplicateComposeKeyDiagnostics(items, generation).forEach { conflict ->
        Timber.tag(COMPOSE_KEY_DIAGNOSTIC_TAG).e(
            "duplicate_compose_key composeKey=%s logicalMessageId=%s localEchoId=%s transactionId=%s eventId=%s " +
                "relationType=%s optimistic=%s sendState=%s redacted=%s edited=%s timelineIndex=%d source=%s generation=%d",
            conflict.composeKey,
            conflict.logicalMessageId,
            conflict.localEchoId,
            conflict.transactionId,
            conflict.eventId,
            conflict.relationType,
            conflict.optimistic,
            conflict.sendState,
            conflict.redacted,
            conflict.edited,
            conflict.timelineIndex,
            conflict.source,
            conflict.generation,
        )
    }
}

private fun TimelineItem.toComposeKeyDiagnostic(index: Int, generation: Long): TimelineComposeKeyDiagnostic = when (this) {
    is TimelineItem.Event -> {
        val composeKey = identifier().value
        val localEchoId = composeKey.takeIf { it.startsWith(OPTIMISTIC_KEY_PREFIX) }
            ?: transactionId?.value?.takeIf { it.startsWith(OPTIMISTIC_KEY_PREFIX) }
        TimelineComposeKeyDiagnostic(
            composeKey = composeKey,
            logicalMessageId = displayId.value,
            localEchoId = localEchoId,
            transactionId = transactionId?.value,
            eventId = eventId?.value,
            relationType = when {
                inReplyTo != null -> "reply"
                threadInfo != null -> "thread"
                else -> "none"
            },
            optimistic = eventId == null && localEchoId != null,
            sendState = localSendState?.javaClass?.simpleName,
            redacted = content.isRedacted(),
            edited = content.isEdited(),
            timelineIndex = index,
            source = origin?.name,
            generation = generation,
        )
    }
    is TimelineItem.Virtual -> TimelineComposeKeyDiagnostic(
        composeKey = identifier().value,
        logicalMessageId = identifier().value,
        localEchoId = null,
        transactionId = null,
        eventId = null,
        relationType = "virtual",
        optimistic = false,
        sendState = null,
        redacted = false,
        edited = false,
        timelineIndex = index,
        source = "virtual",
        generation = generation,
    )
    is TimelineItem.GroupedEvents -> TimelineComposeKeyDiagnostic(
        composeKey = identifier().value,
        logicalMessageId = identifier().value,
        localEchoId = null,
        transactionId = null,
        eventId = null,
        relationType = "grouped",
        optimistic = false,
        sendState = null,
        redacted = false,
        edited = false,
        timelineIndex = index,
        source = "grouped",
        generation = generation,
    )
}

private const val COMPOSE_KEY_DIAGNOSTIC_TAG = "YiZheTimelineIdentity"
private const val OPTIMISTIC_KEY_PREFIX = "optimistic:"

data class TimelineEventNeighbours(
    val previous: MatrixTimelineItem.Event?,
    val next: MatrixTimelineItem.Event?,
)

private data class EventGroupingContext(
    val previousKey: String?,
    val nextKey: String?,
)

private data class ReadReceiptMemberProfile(
    val displayName: String?,
    val avatarUrl: String?,
)

/**
 * Computes event neighbours in canonical timeline order. Presentation-only items such as typing,
 * read markers and pagination indicators must not change message grouping.
 */
internal fun List<MatrixTimelineItem>.realEventNeighbours(): List<TimelineEventNeighbours> {
    val previous = arrayOfNulls<MatrixTimelineItem.Event>(size)
    var previousEvent: MatrixTimelineItem.Event? = null
    indices.forEach { index ->
        previous[index] = previousEvent
        (this[index] as? MatrixTimelineItem.Event)?.let { previousEvent = it }
    }

    val next = arrayOfNulls<MatrixTimelineItem.Event>(size)
    var nextEvent: MatrixTimelineItem.Event? = null
    indices.reversed().forEach { index ->
        next[index] = nextEvent
        (this[index] as? MatrixTimelineItem.Event)?.let { nextEvent = it }
    }

    return indices.map { index -> TimelineEventNeighbours(previous[index], next[index]) }
}
