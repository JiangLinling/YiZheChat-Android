/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import androidx.compose.ui.util.fastForEach
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.EventReaction
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.FormattedBody
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.InReplyTo
import io.element.android.libraries.matrix.api.timeline.item.event.LiveLocationContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageFormat
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedOperationContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedRelayEntry
import io.element.android.libraries.matrix.api.timeline.item.event.ReactionSender
import io.element.android.libraries.matrix.api.timeline.item.event.RedactedContent
import io.element.android.libraries.matrix.api.timeline.item.event.RoomMembershipContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TimelineItemEventOrigin
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.isAuthorizedForUserDisplay
import io.element.android.libraries.matrix.api.timeline.item.event.withMediaEventOrigin
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefresh
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentInvalidation
import io.element.android.security.contentprotection.api.TimelineContentReleaseClass
import io.element.android.security.contentprotection.api.TimelineContentReleaseIntent
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import timber.log.Timber
import io.element.android.security.dataprotection.api.LifecycleStageTiming

internal class MatrixTimelineDiffProcessor(
    private val timelineItems: MutableSharedFlow<List<MatrixTimelineItem>>,
    private val membershipChangeEventReceivedFlow: MutableSharedFlow<Unit>,
    private val syncedEventReceivedFlow: MutableSharedFlow<Unit>,
    private val timelineItemMapper: MatrixTimelineItemMapper,
    protectedContentAdapter: MatrixProtectedContentAdapter? = null,
    coroutineScope: CoroutineScope? = null,
    decryptDispatcher: CoroutineDispatcher? = null,
    private val mediaRoomId: String? = null,
) {
    private val mutex = Mutex()
    private var rawItems = emptyList<MatrixTimelineItem>()
    private var displayItems = emptyList<MatrixTimelineItem>()
    private var safeShellItems = emptyList<MatrixTimelineItem>()
    private var hasPublishedItems = timelineItems.replayCache.isNotEmpty()
    private var rawItemSlotsByKey = emptyMap<String, IndexedValue<MatrixTimelineItem>>()
    private var safeShellItemsByKey = emptyMap<String, MatrixTimelineItem>()
    private var rawStateGeneration = 0L
    private var activeCommitScope: ActiveCommitScope? = null
    private var hasActivatedCommitScope = false
    private val tombstonedEventIds = mutableSetOf<String>()
    private val tombstonedContentIds = mutableSetOf<ContentId>()
    private val decryptAdapter = protectedContentAdapter?.takeUnless { it === DisabledMatrixProtectedContentAdapter }
    private val backgroundScope = coroutineScope
    private val backgroundDispatcher = decryptDispatcher
    private val pendingDecrypts = PendingDecryptQueue(
        maximumSize = MAX_PENDING_DECRYPTS,
        historyFairnessVisibleBurst = HISTORY_FAIRNESS_VISIBLE_BURST,
    )
    private val visibleDecryptSignals = Channel<Unit>(capacity = VISIBLE_ONLY_DECRYPT_WORKER_COUNT - 1)
    private val reservedVisibleDecryptSignals = Channel<Unit>(capacity = 1)
    private val historyDecryptSignals = Channel<Unit>(capacity = HISTORY_DECRYPT_WORKER_COUNT)
    private val decryptedItems = Channel<DecryptedItem>(
        capacity = DECRYPT_QUEUE_CAPACITY,
        onUndeliveredElement = { update -> update.pendingWork?.let(pendingDecrypts::completePublication) },
    )
    private val pendingDecryptingShells = mutableMapOf<String, Long>()
    private val revealedDecryptingShells = mutableSetOf<String>()
    // Encrypted source metadata only. Plaintext stays in the existing authorization-scoped cache.
    private val pendingOriginalSendSources = mutableMapOf<String, MatrixTimelineItem>()
    private val preparedAttachmentBindings = java.util.concurrent.ConcurrentHashMap<String, ProtectedAttachmentTransactionBinding>()
    private var decryptingShellGeneration = 0L
    private var latestAuthorizationRefreshRevision = 0L
    private var latestHardInvalidationRevision = 0L
    private var latestHistoryRecoveryGeneration = 0L
    private var latestContentInvalidationSequence = 0L
    private var initialRenderCandidates: Map<String, MatrixTimelineItem>? = null
    private var initialRenderActivated = false
    private var initialRenderRetired = false
    private var latestViewportGeneration = 0L
    private var hasMeasuredViewport = false
    private var visibleViewportItemKeys = emptyList<String>()
    private var initialStructuralProjectionBarrier: InitialStructuralProjectionBarrier? = null
    private val transientRetryLock = Any()
    private var transientRetryAttempt = 0
    private var transientRetryJob: Job? = null
    private val activeDecryptJobs = mutableMapOf<Job, PendingDecryptWork>()

    private data class ActiveCommitScope(
        val scope: TimelineCommitScope,
        val releaseScopeId: String,
        var latestSequence: Long = 0L,
        var latestStructuralEpoch: Long = 0L,
    )

    private data class InitialStructuralProjectionBarrier(
        val scope: TimelineCommitScope,
        val pendingVisibleKeys: MutableSet<String>,
    )

    init {
        if (decryptAdapter != null && coroutineScope != null && decryptDispatcher != null) {
            val refreshRegistration = decryptAdapter.registerAuthorizationRefreshListener { refresh ->
                coroutineScope.launch(decryptDispatcher) {
                    retryProtectedAuthorization(refresh)
                }
            }
            val contentInvalidationRegistration = decryptAdapter.registerContentInvalidationListener { invalidation ->
                coroutineScope.launch(decryptDispatcher) {
                    commitContentInvalidation(invalidation)
                }
            }
            coroutineScope.coroutineContext[Job]?.invokeOnCompletion {
                decryptedItems.cancel()
                preparedAttachmentBindings.clear()
                refreshRegistration.close()
                contentInvalidationRegistration.close()
                // Children have finished: cover process/room closure even when a cancelled flow
                // could not acquire the unsubscribe mutex. No durable operation is cancelled here.
                activeCommitScope?.let { owner ->
                    pendingDecrypts.cancelScope(owner.releaseScopeId)
                    decryptAdapter.closeTimelineReleaseScope(owner.releaseScopeId)
                }
                activeCommitScope = null
            }
            repeat(VISIBLE_ONLY_DECRYPT_WORKER_COUNT) { worker ->
                coroutineScope.launch(decryptDispatcher) {
                    val signals = if (worker == 0) reservedVisibleDecryptSignals else visibleDecryptSignals
                    for (ignored in signals) {
                        val work = if (worker == 0) pendingDecrypts.pollReservedVisible() else pendingDecrypts.pollForeground()
                        work?.let { decryptPendingWork(it) }
                    }
                }
            }
            repeat(HISTORY_DECRYPT_WORKER_COUNT) {
                coroutineScope.launch(decryptDispatcher) {
                    for (ignored in historyDecryptSignals) {
                        // This lane helps fill the initial viewport too, but is the only lane allowed
                        // to continue through off-screen history after visible-first work is drained.
                        pendingDecrypts.pollVisibleOrHistory()?.let { work -> decryptPendingWork(work) }
                    }
                }
            }
            coroutineScope.launch(decryptDispatcher) {
                collectDecryptedItemBatches(
                    input = decryptedItems,
                    releaseBatch = { batch -> batch.forEach { it.pendingWork?.let(pendingDecrypts::completePublication) } },
                    applyBatch = ::applyDecryptedItems,
                )
            }
        }
    }

    /** Starts a new serialized commit scope. Transactions from every older subscription become stale. */
    suspend fun activateCommitScope(scope: TimelineCommitScope) = mutex.withLock {
        backgroundScope?.coroutineContext?.get(Job)?.ensureActive()
        val previous = activeCommitScope
        previous?.takeIf { it.scope != scope }?.let {
            pendingOriginalSendSources.clear()
            pendingDecrypts.cancelScope(it.releaseScopeId)
            cancelDecryptScope(it.releaseScopeId)
            decryptAdapter?.closeTimelineReleaseScope(it.releaseScopeId)
        }
        val releaseScopeId = if (previous?.scope != scope) {
            initialStructuralProjectionBarrier = null
            latestViewportGeneration = 0L
            initialRenderCandidates = null
            initialRenderActivated = false
            initialRenderRetired = false
            hasMeasuredViewport = false
            visibleViewportItemKeys = emptyList()
            decryptAdapter?.openTimelineReleaseScope(scope.value.toString()) ?: scope.value.toString()
        } else {
            requireNotNull(previous).releaseScopeId
        }
        activeCommitScope = ActiveCommitScope(scope, releaseScopeId)
        hasActivatedCommitScope = true
    }

    /** Invalidates only the matching scope, so a late unsubscribe cannot close a newer subscription. */
    suspend fun invalidateCommitScope(scope: TimelineCommitScope) = mutex.withLock {
        activeCommitScope?.takeIf { it.scope == scope }?.let { activeScope ->
            pendingOriginalSendSources.clear()
            initialRenderCandidates = null
            initialRenderRetired = true
            activeCommitScope = null
            initialStructuralProjectionBarrier = null
            hasMeasuredViewport = false
            visibleViewportItemKeys = emptyList()
            pendingDecrypts.cancelScope(activeScope.releaseScopeId)
            cancelDecryptScope(activeScope.releaseScopeId)
            decryptAdapter?.closeTimelineReleaseScope(activeScope.releaseScopeId)
        }
    }

    /**
     * Applies one measured Compose viewport to the exact Matrix subscription that produced it.
     * Raw-state generation remains independent and continues to guard immutable source commits.
     */
    suspend fun updateViewport(
        scope: TimelineCommitScope,
        generation: Long,
        visibleItemIds: List<UniqueId>,
        allowInitialRender: Boolean = false,
    ): Boolean {
        var updated = false
        var updatedScopeId: String? = null
        var updatedVisibleKeys = emptyList<String>()
        var updatedBootstrapKeys = emptyList<String>()
        mutex.withLock {
            val activeScope = activeCommitScope ?: return@withLock
            if (activeScope.scope != scope || generation <= latestViewportGeneration) return@withLock
            val visibleKeys = visibleItemIds
                .asSequence()
                .map { itemId -> "event:${itemId.value}" }
                .distinct()
                .toList()
            val previouslyVisibleKeys = if (hasMeasuredViewport) visibleViewportItemKeys.toSet() else emptySet()
            latestViewportGeneration = generation
            hasMeasuredViewport = true
            visibleViewportItemKeys = visibleKeys
            val window = timelineDecryptWindow(rawItems, visibleKeys)
            decryptAdapter?.protectRecentTimelineCache(window)
            val windowKeys = window.mapNotNullTo(mutableSetOf(), ::itemKey)
            pendingDecrypts.retainHistoryWindow(windowKeys)
            val shellCandidates = window.mapNotNull { item ->
                val key = itemKey(item) ?: return@mapNotNull null
                val current = rawItemSlotsByKey[key] ?: return@mapNotNull null
                val safeShell = safeShellItemsByKey[key] ?: return@mapNotNull null
                current.value.takeIf {
                    current.index in displayItems.indices &&
                        (displayItems[current.index] == safeShell ||
                            (key !in previouslyVisibleKeys &&
                                displayItems[current.index].isTemporarilyUnavailableProtectedProjection()))
                }
            }
            // Hidden ordinary text cannot participate in the measured viewport. When the
            // actual newest event is still on screen, keep that nearby text ahead of older
            // speculative history instead of appending it behind the existing backlog.
            // It remains HISTORY_PREFETCH, with the same owner, generation and raw identity.
            // Reading older messages does not activate this scheduling hint.
            val tailIsOnScreen = rawItems.lastOrNull { it is MatrixTimelineItem.Event }?.let(::itemKey) in visibleKeys
            val preferredHiddenHistory = if (tailIsOnScreen) {
                val tailKeys = rawItems.takeLast(VISIBLE_FIRST_ITEM_COUNT).mapNotNullTo(mutableSetOf(), ::itemKey)
                shellCandidates.filter { item ->
                    val key = itemKey(item) ?: return@filter false
                    val content = (safeShellItemsByKey[key] as? MatrixTimelineItem.Event)?.event?.content as? MessageContent
                    key !in visibleKeys && key in tailKeys && content?.protectedMessageStatus == ProtectedMessageStatus.DECRYPTING
                }
            } else {
                emptyList()
            }
            // Capture one finite raw-identity set after a real foreground measurement.
            // An initial hidden/unmeasured page is unarmed, not a retired foreground entry.
            val anchor = rawItems.indexOfLast { it is MatrixTimelineItem.Event && itemKey(it) in visibleKeys }
            val anchorIsDisplayable = anchor >= 0 && displayItems.size == rawItems.size &&
                (displayItems[anchor] as? MatrixTimelineItem.Event)?.event?.content?.isAuthorizedForUserDisplay(true) == true
            if (!initialRenderRetired && allowInitialRender && anchorIsDisplayable && initialRenderCandidates == null) {
                initialRenderCandidates = rawItems.takeLast(VISIBLE_FIRST_ITEM_COUNT).mapNotNull { item ->
                    val key = itemKey(item) ?: return@mapNotNull null
                    item.takeIf { isEmptyOrdinaryPendingShell(safeShellItemsByKey[key]) &&
                        decryptAdapter?.isInitialRenderText(it) == true }?.let { key to it }
                }.toMap()
            }
            val candidates = initialRenderCandidates.orEmpty()
            val renderedTailOnScreen = anchorIsDisplayable && rawItems.drop(anchor + 1).all { item ->
                item is MatrixTimelineItem.Virtual || candidates[itemKey(item)] === item &&
                    isEmptyOrdinaryPendingShell(displayItems[rawItemSlotsByKey[itemKey(item)]?.index ?: return@all false])
            }
            val eligible = allowInitialRender && renderedTailOnScreen && !initialRenderRetired
            if (initialRenderActivated && !eligible) initialRenderRetired = true
            val bootstrapKeys = if (!eligible || initialRenderRetired) emptyList() else shellCandidates.mapNotNull { item ->
                val key = itemKey(item) ?: return@mapNotNull null
                key.takeIf { it !in visibleKeys && candidates[it] === item && isEmptyOrdinaryPendingShell(safeShellItemsByKey[it]) }
            }.take(VISIBLE_FIRST_ITEM_COUNT)
            if (bootstrapKeys.isNotEmpty()) initialRenderActivated = true
            else if (initialRenderActivated) initialRenderRetired = true
            updatedBootstrapKeys = bootstrapKeys
            pendingDecrypts.reprioritize(
                visibleKeys = visibleKeys,
                shellCandidates = shellCandidates.filter { itemKey(it) in visibleKeys },
                rawStateGeneration = rawStateGeneration,
                timelineScopeId = activeScope.releaseScopeId,
                releaseGeneration = generation,
                preferredHistoryKeys = preferredHiddenHistory.mapNotNull(::itemKey),
                bootstrapHistoryKeys = bootstrapKeys,
            )
            pendingDecrypts.offer(
                visible = emptyList(),
                history = shellCandidates.filterNot { itemKey(it) in visibleKeys },
                rawStateGeneration = rawStateGeneration,
                timelineScopeId = activeScope.releaseScopeId,
                releaseGeneration = generation,
            )
            updatedScopeId = activeScope.releaseScopeId
            updatedVisibleKeys = visibleKeys
            updated = true
        }
        if (updated) {
            decryptAdapter?.updateTimelineReleaseViewport(
                scopeId = checkNotNull(updatedScopeId),
                generation = generation,
                visibleRequestIds = updatedVisibleKeys,
                bootstrapRequestIds = updatedBootstrapKeys,
            )
            signalPendingDecrypts()
        }
        return updated
    }

    private suspend fun decryptPendingWork(work: PendingDecryptWork) {
        val parent = currentCoroutineContext()[Job]
        val job = mutex.withLock {
            if (work.timelineScopeId != UNSCOPED_TIMELINE_RELEASE_SCOPE &&
                activeCommitScope?.releaseScopeId != work.timelineScopeId
            ) null else Job(parent).also { activeDecryptJobs[it] = work }
        }
        if (job == null) {
            pendingDecrypts.complete(work, retry = false)
            signalPendingDecrypts()
            return
        }
        try {
            withContext(job) { executePendingDecryptWork(work) }
        } catch (_: CancellationException) {
            // Retire this consumer immediately, keeping the permanent lane available for the next scope.
            currentCoroutineContext().ensureActive()
        } finally {
            withContext(NonCancellable) { mutex.withLock { activeDecryptJobs.remove(job) } }
            job.complete()
        }
    }

    /** Caller holds the commit mutex, fencing admission against an exact owner exit. */
    private fun cancelDecryptScope(scopeId: String) {
        activeDecryptJobs.filterValues { it.timelineScopeId == scopeId }.keys.forEach { it.cancel() }
    }

    private suspend fun executePendingDecryptWork(work: PendingDecryptWork) {
        val adapter = decryptAdapter ?: return
        val workStartedAt = System.nanoTime()
        Timber.tag(PERFORMANCE_TAG).d(
            "stage=decrypt_work_start key=%s priority=%s recent=%s projection_recent=%s scope=%s",
            work.key, work.priority, work.recentHistory, work.recentProjection, work.timelineScopeId,
        )
        var retryAfterRevisionChange = false
        var resultAwaitingPublication = false
        val revisionAtStart = adapter.authorizationRevision()
        try {
            val eventId = (work.item as? MatrixTimelineItem.Event)?.let { item ->
                item.eventId?.value ?: item.transactionId?.value ?: item.uniqueId.value
            } ?: work.key
            LifecycleStageTiming.point("timeline_read_start", eventId, "${work.timelineScopeId}#${work.priority}")
            val decryptedItem = if (work.timelineScopeId == UNSCOPED_TIMELINE_RELEASE_SCOPE) {
                // Direct processor callers predate timeline subscriptions and have no release-scope
                // owner. Keep that test/tooling path on the unscoped adapter API; production
                // subscribers always activate an exact owner scope before committing diffs.
                adapter.decryptItem(work.item)
            } else {
                adapter.decryptItem(
                    work.item,
                    TimelineContentReleaseIntent(
                        scopeId = work.timelineScopeId,
                        requestId = work.key,
                        eventId = eventId,
                        generation = work.releaseGeneration,
                        releaseClass = when (work.priority) {
                            DecryptPriority.VISIBLE -> if (work.releaseGeneration == 0L) TimelineContentReleaseClass.HISTORY_PREFETCH
                                else TimelineContentReleaseClass.FOREGROUND_VISIBLE
                            DecryptPriority.HISTORY -> if (work.bootstrapHistory) TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP
                                else TimelineContentReleaseClass.HISTORY_PREFETCH
                        },
                    )
                )
            }
            if (revisionAtStart == adapter.authorizationRevision()) {
                LifecycleStageTiming.point("timeline_read_complete", eventId, "${work.timelineScopeId}#${work.priority}")
                pendingDecrypts.awaitPublication(work)
                decryptedItems.send(
                    DecryptedItem(
                        pendingWork = work,
                        rawItem = work.item,
                        decryptedItem = decryptedItem,
                        authorizationRevision = revisionAtStart,
                        priority = work.priority,
                        rawStateGeneration = work.rawStateGeneration,
                        timelineScopeId = work.timelineScopeId,
                        recentHistory = work.recentHistory,
                        recentProjection = work.recentProjection,
                    )
                )
                resultAwaitingPublication = true
            } else {
                retryAfterRevisionChange = true
            }
        } catch (_: CancellationException) {
            // A newer timeline diff can cancel only the superseded KMS release request while this
            // long-lived worker is still healthy. Treat that as completion of this one work item;
            // otherwise rapid live-location updates can retire every fixed worker and leave later
            // messages queued until the room recreates the processor. A real scope/job cancellation
            // still propagates and terminates the worker with the owning timeline lifecycle.
            currentCoroutineContext().ensureActive()
        } catch (failure: Exception) {
            Timber.tag(PERFORMANCE_TAG).w("timeline decryption failed: %s", failure.javaClass.simpleName)
            if (revisionAtStart == adapter.authorizationRevision()) {
                val failClosedItem = mutex.withLock {
                    safeShellItemsByKey[work.key] ?: work.item
                }
                pendingDecrypts.awaitPublication(work)
                decryptedItems.send(
                    DecryptedItem(
                        pendingWork = work,
                        rawItem = work.item,
                        decryptedItem = failClosedItem,
                        authorizationRevision = revisionAtStart,
                        priority = work.priority,
                        rawStateGeneration = work.rawStateGeneration,
                        timelineScopeId = work.timelineScopeId,
                        recentHistory = work.recentHistory,
                        recentProjection = work.recentProjection,
                    )
                )
                resultAwaitingPublication = true
            } else {
                retryAfterRevisionChange = true
            }
        } finally {
            Timber.tag(PERFORMANCE_TAG).d(
                "stage=decrypt_work_finish key=%s priority=%s recent=%s projection_recent=%s duration_ms=%d",
                work.key, work.priority, work.recentHistory, work.recentProjection, (System.nanoTime() - workStartedAt) / NANOS_PER_MILLISECOND,
            )
            // Complete and conditionally retry atomically. A newer Set/Reset may already have
            // queued a different immutable raw item for this key; an older worker must never
            // overwrite that replacement while unwinding.
            if (!resultAwaitingPublication) pendingDecrypts.completePublication(work)
            pendingDecrypts.complete(work, retry = retryAfterRevisionChange)
            signalPendingDecrypts()
        }
    }

    private fun signalPendingDecrypts() {
        if (pendingDecrypts.hasReservedVisibleWork()) reservedVisibleDecryptSignals.trySend(Unit)
        if (pendingDecrypts.hasForegroundWork()) {
            repeat(VISIBLE_ONLY_DECRYPT_WORKER_COUNT - 1) { visibleDecryptSignals.trySend(Unit) }
        }
        if (pendingDecrypts.hasQueuedWork()) {
            repeat(HISTORY_DECRYPT_WORKER_COUNT) { historyDecryptSignals.trySend(Unit) }
        }
    }

    private fun classifyForCurrentViewport(
        items: List<MatrixTimelineItem>,
        visibleKeys: List<String>? = visibleViewportItemKeys.takeIf { hasMeasuredViewport },
    ): Pair<List<MatrixTimelineItem>, List<MatrixTimelineItem>> {
        val windowKeys = timelineDecryptWindow(rawItems, visibleKeys).mapNotNullTo(mutableSetOf(), ::itemKey)
        if (visibleKeys == null) {
            val startupVisibleKeys = rawItems.takeLast(VISIBLE_FIRST_ITEM_COUNT).mapNotNullTo(mutableSetOf(), ::itemKey)
            return items.asReversed().filter { itemKey(it) in startupVisibleKeys } to
                items.asReversed().filter { itemKey(it) in windowKeys && itemKey(it) !in startupVisibleKeys }
        }
        val remainingByKey = LinkedHashMap<String, MatrixTimelineItem>()
        items.forEach { item -> itemKey(item)?.let { key -> remainingByKey[key] = item } }
        val visible = visibleKeys.mapNotNull(remainingByKey::remove)
        val history = items.asReversed().filter { item -> itemKey(item) in remainingByKey && itemKey(item) in windowKeys }
        return visible to history
    }

    /**
     * Reprojects the immutable raw timeline for one explicit authorization transition.
     *
     * Hard boundaries always publish every safe shell before retrying. A selective cache-expiry
     * refresh may atomically retain only projections that still have an exact, current-authority
     * process-memory cache hit. A cache miss, burn-after-read item, revision race, unknown mode or
     * unseen hard generation remains fail-closed.
     */
    private suspend fun retryProtectedAuthorization(refresh: MatrixAuthorizationRefresh) {
        Timber.tag(PERFORMANCE_TAG).d("stage=timeline_authorization_refresh mode=%s revision=%d", refresh.mode, refresh.revision)
        resetTransientRetries("AUTHORIZATION_REFRESH")
        val retrySnapshot = mutex.withLock {
            val adapter = decryptAdapter ?: return
            if (refresh.mode == MatrixAuthorizationRefreshMode.HISTORY_RECOVERY_DELTA) {
                val recoveryGeneration = refresh.recoveryGeneration ?: return
                if (refresh.accountScopeId != adapter.accountScopeId() ||
                    adapter.roomScopeId() !in refresh.roomScopeIds ||
                    recoveryGeneration <= latestHistoryRecoveryGeneration
                ) {
                    return
                }
                latestHistoryRecoveryGeneration = recoveryGeneration
            }
            if (refresh.revision <= latestAuthorizationRefreshRevision) return
            val hasUnseenHardInvalidation =
                refresh.latestHardInvalidationRevision > latestHardInvalidationRevision
            if (hasUnseenHardInvalidation) {
                // Cache clearing retires the KMS scope owner. A retained room subscription must
                // acquire a fresh owner; the old owner and its queued/in-flight work stay invalid.
                activeCommitScope?.let { previous ->
                    pendingDecrypts.cancelScope(previous.releaseScopeId)
                    cancelDecryptScope(previous.releaseScopeId)
                    adapter.closeTimelineReleaseScope(previous.releaseScopeId)
                    val renewedScopeId = adapter.openTimelineReleaseScope(previous.scope.value.toString())
                    activeCommitScope = previous.copy(releaseScopeId = renewedScopeId)
                    if (hasMeasuredViewport) {
                        adapter.updateTimelineReleaseViewport(renewedScopeId, latestViewportGeneration, visibleViewportItemKeys)
                    }
                    adapter.updateRoomSummaryCarrier(renewedScopeId, rawItems.filterIsInstance<MatrixTimelineItem.Event>().lastOrNull())
                }
            }
            if (rawItems.isEmpty()) {
                latestAuthorizationRefreshRevision = refresh.revision
                latestHardInvalidationRevision = maxOf(latestHardInvalidationRevision, refresh.latestHardInvalidationRevision)
                return
            }
            safeShellItems = adapter.timelineItemsShell(rawItems)
            safeShellItemsByKey = safeShellItems.associateByItemKey()
            pendingOriginalSendSources.clear()
            val currentAuthorizationRevision = adapter.authorizationRevision()
            val mayRecoverCurrentCache =
                (!hasActivatedCommitScope || activeCommitScope != null) &&
                refresh.mode in setOf(
                    MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY,
                    MatrixAuthorizationRefreshMode.PREVIEW_ACCESS_REOPENED,
                    MatrixAuthorizationRefreshMode.HISTORY_RECOVERY_DELTA,
                ) &&
                    !hasUnseenHardInvalidation &&
                    refresh.revision == currentAuthorizationRevision
            if (!mayRecoverCurrentCache) {
                // A hard authorization transition must never wait behind a presentation barrier.
                initialStructuralProjectionBarrier = null
            }
            val cacheBackedKeys = mutableSetOf<String>()
            val candidateDisplayItems = safeShellItems.toMutableList()
            if (mayRecoverCurrentCache) {
                rawItems.forEach { rawItem ->
                    val key = itemKey(rawItem) ?: return@forEach
                    val current = rawItemSlotsByKey[key] ?: return@forEach
                    val safeShell = safeShellItemsByKey[key] ?: return@forEach
                    if (candidateDisplayItems.getOrNull(current.index) != safeShell) return@forEach
                    val cachedProjection = adapter.cachedProjectionForCurrentAuthorization(rawItem)
                        ?: return@forEach
                    if (adapter.authorizationRevision() == refresh.revision &&
                        current.value == rawItem &&
                        current.index in candidateDisplayItems.indices
                    ) {
                        candidateDisplayItems[current.index] = cachedProjection
                        cacheBackedKeys += key
                    }
                }
            }
            val recoveredCurrentCache = mayRecoverCurrentCache &&
                adapter.authorizationRevision() == refresh.revision
            displayItems = if (recoveredCurrentCache) candidateDisplayItems else safeShellItems
            pendingDecryptingShells.clear()
            revealedDecryptingShells.clear()
            val projectionCommitted = if (recoveredCurrentCache) {
                publishDisplayItems(expectedAuthorizationRevision = refresh.revision)
            } else {
                publishDisplayItems()
                true
            }
            if (!projectionCommitted) {
                displayItems = safeShellItems
                cacheBackedKeys.clear()
                publishDisplayItems()
            }
            latestAuthorizationRefreshRevision = refresh.revision
            latestHardInvalidationRevision = maxOf(
                latestHardInvalidationRevision,
                refresh.latestHardInvalidationRevision,
            )
            // A closed subscription still clears its projection at an authorization boundary,
            // but must not compete with the visible room by starting unowned background reads.
            if (hasActivatedCommitScope && activeCommitScope == null) return
            RawDecryptSnapshot(
                items = rawItems.filterNot { item -> itemKey(item) in cacheBackedKeys },
                generation = rawStateGeneration,
                timelineScopeId = activeCommitScope?.releaseScopeId ?: UNSCOPED_TIMELINE_RELEASE_SCOPE,
                releaseGeneration = if (hasMeasuredViewport) latestViewportGeneration else 0L,
                visibleItemKeys = visibleViewportItemKeys.takeIf { hasMeasuredViewport },
            )
        }
        if (retrySnapshot.items.isEmpty()) return
        val (visibleItems, historyItems) = classifyForCurrentViewport(
            retrySnapshot.items,
            retrySnapshot.visibleItemKeys,
        )
        pendingDecrypts.offer(
            visible = visibleItems,
            history = historyItems,
            force = true,
            rawStateGeneration = retrySnapshot.generation,
            timelineScopeId = retrySnapshot.timelineScopeId,
            releaseGeneration = retrySnapshot.releaseGeneration,
        )
        signalPendingDecrypts()
    }

    /**
     * Commits one already-persisted content tombstone without changing the global authorization
     * generation. Only matching encrypted carriers become terminal; unrelated cleartext and
     * in-flight work remain stable. Matching in-flight decrypts are invalidated before publish.
     */
    private suspend fun commitContentInvalidation(invalidation: MatrixContentInvalidation) = mutex.withLock {
        val adapter = decryptAdapter ?: return@withLock
        if (invalidation.sequence <= latestContentInvalidationSequence) return@withLock
        latestContentInvalidationSequence = invalidation.sequence
        // Keep the content tombstone even when its Matrix carrier has not reached this processor
        // yet. A later realtime, pagination or structural-reset diff must still become terminal
        // before it can enter the raw/display projection.
        tombstonedContentIds.add(invalidation.contentId)
        if (rawItems.isEmpty()) return@withLock

        val targetIndexes = rawItems.indices.filter { index ->
            adapter.containsContent(rawItems[index], invalidation.contentId)
        }
        if (targetIndexes.isEmpty()) return@withLock

        val nextRawItems = rawItems.toMutableList()
        val nextDisplayItems = displayItems.toMutableList()
        val nextSafeShellItems = safeShellItems.toMutableList()
        val invalidatedKeys = mutableSetOf<String>()
        targetIndexes.forEach { index ->
            val rawItem = rawItems[index]
            val eventItem = rawItem as? MatrixTimelineItem.Event ?: return@forEach
            eventItem.eventId?.value?.let(tombstonedEventIds::add)
            val terminalItem = eventItem.copy(event = eventItem.event.copy(content = RedactedContent))
            nextRawItems[index] = terminalItem
            if (index in nextDisplayItems.indices) nextDisplayItems[index] = terminalItem
            if (index in nextSafeShellItems.indices) nextSafeShellItems[index] = terminalItem
            itemKey(rawItem)?.let(invalidatedKeys::add)
        }
        if (invalidatedKeys.isEmpty()) return@withLock

        rawItems = nextRawItems
        displayItems = nextDisplayItems
        safeShellItems = nextSafeShellItems
        rawItemSlotsByKey = rawItems.indexByItemKey()
        safeShellItemsByKey = safeShellItems.associateByItemKey()
        rawStateGeneration++
        pendingDecrypts.invalidate(invalidatedKeys, rawStateGeneration)
        activeCommitScope?.let { scope ->
            adapter.cancelTimelineReleaseRequests(scope.releaseScopeId, invalidatedKeys)
        }
        pendingDecryptingShells.keys.removeAll(invalidatedKeys)
        revealedDecryptingShells.removeAll(invalidatedKeys)
        // A terminal transition always outranks a presentation barrier.
        initialStructuralProjectionBarrier = null
        publishDisplayItems()
    }

    /** Commits one scoped transaction through the same mutex as raw/display mutation and publication. */
    suspend fun commitDiffs(transaction: TimelineDiffTransaction): Boolean =
        withContext(NonCancellable) {
            postDiffsInternal(transaction.diffs, transaction)
        }

    suspend fun postDiffs(diffs: List<TimelineDiff>) {
        postDiffsInternal(diffs, transaction = null)
    }

    /** Registered by the sending producer before the SDK can publish its local echo. */
    suspend fun registerPreparedAttachment(
        transportName: String,
        captionCarrier: String?,
        bindTransaction: suspend (String) -> Unit,
    ) = mutex.withLock {
        check(preparedAttachmentBindings.size < 64) { "Pending attachment transaction bindings are full." }
        check(preparedAttachmentBindings.putIfAbsent(transportName,
            ProtectedAttachmentTransactionBinding(transportName, captionCarrier, bindTransaction)) == null)
    }

    fun completePreparedAttachment(transportName: String) {
        preparedAttachmentBindings.remove(transportName)
    }

    private suspend fun postDiffsInternal(
        diffs: List<TimelineDiff>,
        transaction: TimelineDiffTransaction?,
    ): Boolean {
        val startedAt = System.nanoTime()
        var changedItems = emptyList<MatrixTimelineItem>()
        var changedItemsGeneration = 0L
        var changedItemsScopeId = UNSCOPED_TIMELINE_RELEASE_SCOPE
        var changedItemsReleaseGeneration = 0L
        var changedVisibleItemKeys: List<String>? = null
        var recentTailKeys = emptySet<String>()
        var removedItemKeys = emptySet<String>()
        var delayedShells = emptyMap<String, Long>()
        var cachedProjectionItemKeys = emptySet<String>()
        var commitAccepted = transaction == null
        mutex.withLock {
            if (transaction != null && !canCommit(transaction)) return@withLock
            val barrierWasActive = initialStructuralProjectionBarrier != null
            val tombstonesBeforeCommit = tombstonedEventIds.toSet()
            val expectedAuthorizationRevision = transaction?.let { decryptAdapter?.authorizationRevision() }
            var previousDisplayItems = displayItems
            var previousRawItems = rawItems
            var seededItems = emptyList<MatrixTimelineItem>()
            if (rawItems.isEmpty() && timelineItems.replayCache.isNotEmpty()) {
                val replayedItems = timelineItems.first()
                replayedItems.redactedEventIdentities().forEach { identity ->
                    if (tombstonedEventIds.add(identity)) {
                        logRedactionLifecycle("T6", identity, "replay_cache_tombstone")
                    }
                }
                rawItems = replayedItems.map { item -> item.applyTerminalPriority() }
                displayItems = rawItems
                seededItems = rawItems
                previousDisplayItems = displayItems
                previousRawItems = rawItems
            }
            val result = processDiffs(diffs)
            val changed = result.changedItems()
            val latestChangedItems = (seededItems + changed).keepLatestByItemKey()
            val changedTombstoneIds = latestChangedItems.redactedEventIdentities()
            val nextRawItems = result.items()
            // Bind before shells, cache projections or decrypt workers can inspect this local echo.
            // The adapter rechecks the encrypted journal's original operation and all three identities.
            latestChangedItems.filterIsInstance<MatrixTimelineItem.Event>().forEach { item ->
                val name = ((item.event.content as? MessageContent)?.type as?
                    io.element.android.libraries.matrix.api.timeline.item.event.MessageTypeWithAttachment)?.filename
                val binding = name?.let(preparedAttachmentBindings::get)
                if (binding != null) {
                    try {
                        if (binding.bind(item)) preparedAttachmentBindings.remove(name, binding)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Do not kill the lossless diff subscription on a journal/identity failure.
                        // No successful binding is recorded; normal authorization still fails closed.
                        Timber.w("YZCP_ATTACHMENT_TRANSACTION_BINDING_PENDING")
                    }
                }
            }
            val previousRawSlots = rawItemSlotsByKey
            // Classify every raw item before publishing, including items restored from the replay cache.
            // This is intentionally independent from the derived-work queue: no timing or diff shape may
            // allow an encrypted transport carrier to reach a text bubble or accessibility semantics.
            val nextSafeShellItems = decryptAdapter?.timelineItemsShell(nextRawItems) ?: nextRawItems
            val changedDecryptingKeys = if (decryptAdapter != null && backgroundScope != null && backgroundDispatcher != null) {
                latestChangedItems.mapNotNull { item ->
                    itemKey(item)?.takeIf { decryptAdapter.hasTransientDecryptingShell(item) }
                }.toSet()
            } else {
                emptySet()
            }
            rawItems = nextRawItems
            activeCommitScope?.let { scope ->
                decryptAdapter?.updateRoomSummaryCarrier(scope.releaseScopeId, rawItems.filterIsInstance<MatrixTimelineItem.Event>().lastOrNull())
            }
            rawStateGeneration++
            safeShellItems = nextSafeShellItems
            rawItemSlotsByKey = rawItems.indexByItemKey()
            val decryptWindow = timelineDecryptWindow(rawItems, visibleViewportItemKeys.takeIf { hasMeasuredViewport })
            decryptAdapter?.protectRecentTimelineCache(decryptWindow)
            pendingDecrypts.retainHistoryWindow(decryptWindow.mapNotNullTo(mutableSetOf(), ::itemKey))
            if (!hasMeasuredViewport) {
                pendingDecrypts.reprioritize(
                    visibleKeys = rawItems.takeLast(VISIBLE_FIRST_ITEM_COUNT).asReversed().mapNotNull(::itemKey),
                    shellCandidates = emptyList(),
                    rawStateGeneration = rawStateGeneration,
                    timelineScopeId = activeCommitScope?.releaseScopeId ?: UNSCOPED_TIMELINE_RELEASE_SCOPE,
                    releaseGeneration = 0L,
                )
            }
            if (diffs.none { it == TimelineDiff.Clear || it is TimelineDiff.Reset }) {
                // A decrypting text shell is deliberately absent from user-facing projections,
                // so a newly arrived event cannot yet appear in the measured Compose viewport.
                // Expedite new incremental arrivals and real own-send identity/state transitions
                // without claiming they are visible. A confirmed local echo keeps its SDK unique
                // key, so testing only for new SYNC keys leaves its sent/failed state behind history.
                // Their release class remains HISTORY_PREFETCH with all authorization intact.
                val tailKeys = rawItems.takeLast(VISIBLE_FIRST_ITEM_COUNT).mapNotNullTo(mutableSetOf(), ::itemKey)
                recentTailKeys = latestChangedItems.filterIsInstance<MatrixTimelineItem.Event>()
                    .mapNotNull { item ->
                        val key = itemKey(item) ?: return@mapNotNull null
                        key.takeIf { it in tailKeys && item.isRecentTimelineWork(previousRawSlots[key]?.value) }
                    }.toSet()
            }
            safeShellItemsByKey = safeShellItems.associateByItemKey()
            displayItems = result.displayItems(nextSafeShellItems)
                .retainPreviousProjectionWhileDecrypting(
                    previousDisplayItems = previousDisplayItems,
                    previousRawItems = previousRawItems,
                    currentRawItems = nextRawItems,
                    decryptingKeys = changedDecryptingKeys,
                    requireSameSource = diffs.any { it == TimelineDiff.Clear },
                )
            pendingOriginalSendSources.keys.retainAll(rawItemSlotsByKey.keys)
            changedDecryptingKeys.forEach { key ->
                val source = pendingOriginalSendSources[key] ?: previousRawSlots[key]?.value ?: return@forEach
                val current = rawItemSlotsByKey[key]?.value ?: return@forEach
                if (decryptAdapter?.pendingOriginalSendProjection(source, current) != null) {
                    pendingOriginalSendSources[key] = source
                } else {
                    pendingOriginalSendSources.remove(key)
                }
            }
            if (decryptAdapter != null) {
                val cachedKeys = mutableSetOf<String>()
                val nextDisplayItems = displayItems.toMutableList()
                // A structural Reset reclassifies the complete Rust snapshot as changed. Limiting
                // current-authority cache recovery to the newest viewport made older authorized
                // projections fall back to shells for one asynchronous decrypt batch, collapsing
                // the list around recall/destruction rows. For Reset only, inspect every shell
                // slot in the replacement snapshot. The adapter still returns a projection only
                // for the exact encrypted carrier under the current room/session/policy/revision;
                // redaction, burn-after-read, expiry, lock and revocation remain cache misses.
                val cacheRecoveryCandidates = if (diffs.any { it is TimelineDiff.Reset }) {
                    rawItems
                } else {
                    rawItems.takeLast(VISIBLE_FIRST_ITEM_COUNT)
                }
                cacheRecoveryCandidates.forEach { rawItem ->
                    val key = itemKey(rawItem) ?: return@forEach
                    val current = rawItemSlotsByKey[key] ?: return@forEach
                    val safeShell = safeShellItemsByKey[key] ?: return@forEach
                    // A room re-entry can seed this processor from the replayed display projection
                    // immediately before Rust publishes the authoritative encrypted Reset. That
                    // replay item is not valid raw-source evidence, so the strict source comparison
                    // above must continue to reject it. Recover only a shell slot through the
                    // adapter's current-authorization cache, whose key binds the exact encrypted
                    // carrier and whose generation is invalidated on lock, revocation or policy
                    // change. Never replace a retained projection or a terminal security result.
                    if (nextDisplayItems.getOrNull(current.index) != safeShell) return@forEach
                    val cachedProjection = decryptAdapter.cachedProjectionForCurrentAuthorization(rawItem)
                        ?: return@forEach
                    if (current.value == rawItem && current.index in nextDisplayItems.indices) {
                        nextDisplayItems[current.index] = cachedProjection
                        cachedKeys += key
                    }
                }
                if (cachedKeys.isNotEmpty()) {
                    displayItems = nextDisplayItems
                    cachedProjectionItemKeys = cachedKeys
                }
            }
            val newlyTerminalEventIds = tombstonedEventIds - tombstonesBeforeCommit
            if (barrierWasActive && newlyTerminalEventIds.isNotEmpty()) {
                // A terminal transition takes precedence over the initial presentation barrier.
                initialStructuralProjectionBarrier = null
            }
            val visibleRawKeys = rawItems.takeLast(VISIBLE_FIRST_ITEM_COUNT).mapNotNullTo(mutableSetOf(), ::itemKey)
            val hasReadableCachedViewport = cachedProjectionItemKeys.any { key ->
                key in visibleRawKeys && rawItemSlotsByKey[key]?.index?.let { displayItems[it].hasAuthorizedOrdinaryMessage() } == true
            }
            val pendingVisibleKeys = changedDecryptingKeys
                .intersect(visibleRawKeys)
                .minus(cachedProjectionItemKeys)
            val startsInitialStructuralProjection = transaction != null &&
                transaction.sequence == 1L &&
                transaction.diffs.any { it == TimelineDiff.Clear } &&
                transaction.diffs.any { it is TimelineDiff.Reset } &&
                timelineItems.replayCache.lastOrNull().orEmpty().isEmpty() &&
                !hasReadableCachedViewport &&
                pendingVisibleKeys.isNotEmpty()
            if (startsInitialStructuralProjection) {
                initialStructuralProjectionBarrier = InitialStructuralProjectionBarrier(
                    scope = transaction.scope,
                    pendingVisibleKeys = pendingVisibleKeys.toMutableSet(),
                )
            } else {
                initialStructuralProjectionBarrier?.let { barrier ->
                    barrier.pendingVisibleKeys.retainAll(visibleRawKeys)
                    barrier.pendingVisibleKeys += pendingVisibleKeys
                    if (barrier.pendingVisibleKeys.isEmpty()) {
                        initialStructuralProjectionBarrier = null
                    }
                }
            }
            changedTombstoneIds.forEach { identity ->
                logRedactionLifecycle("T3", identity, "domain_tombstone")
            }
            val rawKeys = rawItems.mapNotNull(::itemKey).toSet()
            pendingDecryptingShells.keys.retainAll(rawKeys)
            revealedDecryptingShells.retainAll(rawKeys)
            val nextDelayedShells = mutableMapOf<String, Long>()
            latestChangedItems.forEach { item ->
                val key = itemKey(item) ?: return@forEach
                if (key in changedDecryptingKeys) {
                    val generation = ++decryptingShellGeneration
                    pendingDecryptingShells[key] = generation
                    if (DECRYPTING_SHELL_GRACE_MILLIS == 0L) {
                        revealedDecryptingShells.add(key)
                    } else {
                        revealedDecryptingShells.remove(key)
                        nextDelayedShells[key] = generation
                    }
                } else {
                    pendingDecryptingShells.remove(key)
                    revealedDecryptingShells.remove(key)
                }
            }
            val projectionCommitted = if (expectedAuthorizationRevision == null) {
                publishDisplayItems()
                true
            } else {
                publishDisplayItems(expectedAuthorizationRevision = expectedAuthorizationRevision)
            }
            if (!projectionCommitted) {
                // Authorization changed while the structural candidate was assembled. The raw
                // transaction remains authoritative, but only its fail-closed shells may commit.
                initialStructuralProjectionBarrier = null
                displayItems = safeShellItems
                pendingDecryptingShells.clear()
                revealedDecryptingShells.clear()
                publishDisplayItems()
            }
            transaction?.let(::markCommitted)
            commitAccepted = true
            if (changedTombstoneIds.isNotEmpty()) {
                decryptAdapter?.clearDerivedPlaintextForRedaction(changedTombstoneIds)
                changedTombstoneIds.forEach { identity ->
                    logRedactionLifecycle("T5", identity, "derived_plaintext_cleared")
                }
            }
            changedItems = latestChangedItems
            changedItemsGeneration = rawStateGeneration
            changedItemsScopeId = activeCommitScope?.releaseScopeId ?: UNSCOPED_TIMELINE_RELEASE_SCOPE
            changedItemsReleaseGeneration = if (hasMeasuredViewport) latestViewportGeneration else 0L
            changedVisibleItemKeys = visibleViewportItemKeys.takeIf { hasMeasuredViewport }
            removedItemKeys = previousRawSlots.keys - rawKeys
            delayedShells = nextDelayedShells
            if (result.hasNewEventsFromSync) {
                syncedEventReceivedFlow.emit(Unit)
            }
            if (result.hasMembershipChangeEventFromSync) {
                membershipChangeEventReceivedFlow.emit(Unit)
            }
        }
        if (!commitAccepted) {
            Timber.tag(PERFORMANCE_TAG).d(
                "stage=timeline_commit_drop scope=%s sequence=%d structural_epoch=%d",
                transaction?.scope?.value,
                transaction?.sequence,
                transaction?.structuralEpoch,
            )
            return false
        }
        if (decryptAdapter != null) {
            val changedItemKeys = changedItems.mapNotNullTo(mutableSetOf(), ::itemKey)
            decryptAdapter.cancelTimelineReleaseRequests(changedItemsScopeId, removedItemKeys + changedItemKeys)
            pendingDecrypts.invalidate(removedItemKeys, changedItemsGeneration)
            val uncachedChangedItems = changedItems.filterNot { item -> itemKey(item) in cachedProjectionItemKeys }
            // Before Compose has measured a viewport, retain a finite startup fallback so the
            // first screen can become usable and produce the authoritative visible snapshot.
            val (visibleItems, historyItems) = classifyForCurrentViewport(uncachedChangedItems, changedVisibleItemKeys)
            // New arrivals retain their existing HISTORY_PREFETCH authorization even while the
            // user reads older messages. Only the nearby backlog is speculative prefetch.
            val recentItems = uncachedChangedItems.filter { itemKey(it) in recentTailKeys && it !in visibleItems }
            pendingDecrypts.offer(
                visible = visibleItems,
                history = historyItems.filterNot { itemKey(it) in recentTailKeys },
                recentHistory = recentItems,
                recentProjectionKeys = recentTailKeys,
                rawStateGeneration = changedItemsGeneration,
                timelineScopeId = changedItemsScopeId,
                releaseGeneration = changedItemsReleaseGeneration,
            )
            signalPendingDecrypts()
        }
        if (delayedShells.isNotEmpty()) {
            backgroundScope?.launch(checkNotNull(backgroundDispatcher)) {
                delay(DECRYPTING_SHELL_GRACE_MILLIS)
                revealDecryptingShells(delayedShells)
            }
        }
        Timber.tag(PERFORMANCE_TAG).d(
            "stage=timeline_diff_receive diff_count=%d changed_count=%d recent_arrival_count=%d decrypt_queue_depth=%d decrypt_in_flight=%d duration_ms=%d",
            diffs.size,
            changedItems.size,
            recentTailKeys.size,
            pendingDecrypts.pendingSize(),
            pendingDecrypts.inFlightSize(),
            (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND,
        )
        return true
    }

    private fun canCommit(transaction: TimelineDiffTransaction): Boolean {
        val active = activeCommitScope ?: return false
        if (active.scope != transaction.scope) return false
        if (transaction.sequence != active.latestSequence + 1L) return false
        if (transaction.structuralEpoch < active.latestStructuralEpoch) return false
        if (transaction.diffs.any { it == TimelineDiff.Clear || it is TimelineDiff.Reset } &&
            transaction.structuralEpoch <= active.latestStructuralEpoch
        ) {
            return false
        }
        return true
    }

    private fun markCommitted(transaction: TimelineDiffTransaction) {
        val active = checkNotNull(activeCommitScope)
        check(active.scope == transaction.scope)
        active.latestSequence = transaction.sequence
        active.latestStructuralEpoch = maxOf(active.latestStructuralEpoch, transaction.structuralEpoch)
    }

    /** Returns the immutable, pre-decryption Matrix event used to derive a visible projection. */
    suspend fun rawItem(eventOrTransactionId: EventOrTransactionId): MatrixTimelineItem? = mutex.withLock {
        rawItems.firstOrNull { item ->
            val event = (item as? MatrixTimelineItem.Event)?.event ?: return@firstOrNull false
            EventOrTransactionId.from(event.eventId, event.transactionId) == eventOrTransactionId
        }
    }

    /** Exact encrypted producer name, owned by this room and sender; never match plaintext labels. */
    suspend fun rawMediaItems(transportName: String): List<MatrixTimelineItem.Event> = mutex.withLock {
        rawItems.filterIsInstance<MatrixTimelineItem.Event>().filter { item ->
            val type = (item.event.content as? MessageContent)?.type
            val name = when (type) {
                is ImageMessageType -> type.filename
                is VideoMessageType -> type.filename
                is AudioMessageType -> type.filename
                is VoiceMessageType -> type.filename
                is FileMessageType -> type.filename
                else -> null
            }
            item.event.isOwn && (name == transportName || item.protectedMediaTransportName == transportName)
        }
    }

    /**
     * Publishes one projection that was authorized by an explicit user action, such as opening a
     * burn-after-read message. The immutable raw source and authorization revision are rechecked at
     * every commit boundary so a concurrent replacement or revocation can never expose stale text.
     */
    suspend fun publishAuthorizedProjection(
        rawItem: MatrixTimelineItem,
        authorizedItem: MatrixTimelineItem,
    ): Boolean = mutex.withLock {
        val adapter = decryptAdapter ?: return@withLock false
        val key = itemKey(rawItem) ?: return@withLock false
        if (itemKey(authorizedItem) != key) return@withLock false
        val eventId = (rawItem as? MatrixTimelineItem.Event)?.eventId?.value
        if (eventId != null && eventId in tombstonedEventIds) return@withLock false

        val authorizationRevision = adapter.authorizationRevision()
        val current = rawItemSlotsByKey[key] ?: return@withLock false
        if (current.value != rawItem || current.index !in displayItems.indices) return@withLock false

        val nextDisplayItems = displayItems.toMutableList().also { items ->
            items[current.index] = authorizedItem
        }
        if (adapter.authorizationRevision() != authorizationRevision ||
            rawItemSlotsByKey[key] != current
        ) {
            return@withLock false
        }

        displayItems = nextDisplayItems
        pendingDecryptingShells.remove(key)
        revealedDecryptingShells.remove(key)
        if (publishDisplayItems(expectedAuthorizationRevision = authorizationRevision)) {
            true
        } else {
            // A revocation raced the final emission. Replace every derived projection with its
            // fail-closed shell before returning failure to the caller.
            displayItems = safeShellItems
            pendingDecryptingShells.clear()
            revealedDecryptingShells.clear()
            initialStructuralProjectionBarrier = null
            publishDisplayItems()
            false
        }
    }

    /**
     * Commits one locally terminal event to the visible projection before any remote redaction
     * round-trip completes. The immutable raw carrier remains available to the destruction path,
     * while stale decrypt work for this key is invalidated and unrelated projections stay intact.
     */
    suspend fun clearAuthorizedProjection(rawItem: MatrixTimelineItem): Boolean = mutex.withLock {
        val key = itemKey(rawItem) ?: return@withLock false
        val current = rawItemSlotsByKey[key] ?: return@withLock false
        if (current.value != rawItem || current.index !in displayItems.indices) return@withLock false

        val eventId = (rawItem as? MatrixTimelineItem.Event)?.eventId?.value
        if (eventId != null) tombstonedEventIds.add(eventId)
        val terminalProjection = if (eventId != null) {
            rawItem.applyTombstonePriority()
        } else {
            safeShellItemsByKey[key] ?: rawItem
        }
        rawStateGeneration++
        pendingDecrypts.invalidate(setOf(key), rawStateGeneration)
        displayItems = displayItems.toMutableList().also { items ->
            items[current.index] = terminalProjection
        }
        pendingDecryptingShells.remove(key)
        revealedDecryptingShells.remove(key)
        initialStructuralProjectionBarrier = null
        publishDisplayItems()
        true
    }

    /** Includes every protected operation share that belongs to the selected aggregate. */
    suspend fun rawItemsForDestruction(eventOrTransactionId: EventOrTransactionId): List<MatrixTimelineItem> = mutex.withLock {
        val targetEventId = eventOrTransactionId.eventId?.value
        val targetIndex = rawItems.indexOfFirst { raw ->
            val event = (raw as? MatrixTimelineItem.Event)?.event ?: return@indexOfFirst false
            EventOrTransactionId.from(event.eventId, event.transactionId) == eventOrTransactionId
        }
        val targetSender = (rawItems.getOrNull(targetIndex) as? MatrixTimelineItem.Event)?.event?.sender
            ?: return@withLock emptyList()
        val targetContent = (displayItems.getOrNull(targetIndex) as? MatrixTimelineItem.Event)?.event?.content
        val operationTargets = buildSet {
            targetEventId?.let(::add)
            (targetContent as? LiveLocationContent)?.protectedAggregateId?.let(::add)
        }
        rawItems.mapIndexedNotNull { index, raw ->
            val event = (raw as? MatrixTimelineItem.Event)?.event ?: return@mapIndexedNotNull null
            val isTarget = EventOrTransactionId.from(event.eventId, event.transactionId) == eventOrTransactionId
            val operation = (displayItems.getOrNull(index) as? MatrixTimelineItem.Event)?.event?.content as? ProtectedOperationContent
            raw.takeIf { isTarget || (event.sender == targetSender && operation?.targetEventId in operationTargets) }
        }
    }

    private fun processDiffs(diffs: List<TimelineDiff>): DiffingResult {
        val result = DiffingResult(rawItems, displayItems)
        diffs.forEach { diff ->
            result.applyDiff(diff)
        }
        return result
    }

    private fun DiffingResult.applyDiff(diff: TimelineDiff) {
        when (diff) {
            is TimelineDiff.Append -> {
                diff.values.fastForEach { item ->
                    add(item.map())
                }
            }
            is TimelineDiff.PushBack -> {
                val item = diff.value.map()
                add(item)
            }
            is TimelineDiff.PushFront -> {
                val item = diff.value.map()
                add(0, item)
            }
            is TimelineDiff.Set -> {
                val item = diff.value.map().preserveDisplayIdentityFrom(items().getOrNull(diff.index.toInt()))
                set(diff.index.toInt(), item)
            }
            is TimelineDiff.Insert -> {
                val item = diff.value.map()
                add(diff.index.toInt(), item)
            }
            is TimelineDiff.Remove -> {
                removeAt(diff.index.toInt())
            }
            is TimelineDiff.Reset -> {
                // A Clear followed by Reset is one structural replacement. Preserve the stable
                // display identity from the pre-Clear snapshot so Compose does not recreate every
                // row merely because Rust allocated fresh transient ids during the rebuild.
                val previousItemsByEventId = buildMap {
                    putAll(resultInitialEventItemsByEventId)
                    items().filterIsInstance<MatrixTimelineItem.Event>().forEach { item ->
                        item.eventId?.let { eventId -> put(eventId, item) }
                    }
                }
                clear()
                diff.values.fastForEach { item ->
                    val mapped = item.map()
                    val previous = (mapped as? MatrixTimelineItem.Event)?.eventId?.let(previousItemsByEventId::get)
                    add(mapped.preserveDisplayIdentityFrom(previous))
                }
            }
            TimelineDiff.PopFront -> {
                removeFirst()
            }
            TimelineDiff.PopBack -> {
                removeLast()
            }
            TimelineDiff.Clear -> {
                clear()
            }
            is TimelineDiff.Truncate -> {
                truncate(diff.length.toInt())
            }
        }
    }

    private fun TimelineItem.map(): MatrixTimelineItem {
        return timelineItemMapper.map(this).applyTerminalPriority()
    }

    /**
     * SDK Set/Reset updates can allocate a new transient display id for the same confirmed event. Preserve identity
     * only across an actual replacement of that event; unrelated list entries must keep their independent identities.
     */
    private fun MatrixTimelineItem.preserveDisplayIdentityFrom(previous: MatrixTimelineItem?): MatrixTimelineItem {
        val eventItem = this as? MatrixTimelineItem.Event ?: return this
        val previousEvent = previous as? MatrixTimelineItem.Event ?: return this
        if (eventItem.eventId == null || eventItem.eventId != previousEvent.eventId) return this
        return if (eventItem.displayUniqueId == previousEvent.displayUniqueId) {
            eventItem
        } else {
            eventItem.copy(displayUniqueId = previousEvent.displayUniqueId)
        }
    }

    private fun List<MatrixTimelineItem>.retainPreviousProjectionWhileDecrypting(
        previousDisplayItems: List<MatrixTimelineItem>,
        previousRawItems: List<MatrixTimelineItem>,
        currentRawItems: List<MatrixTimelineItem>,
        decryptingKeys: Set<String>,
        requireSameSource: Boolean,
    ): List<MatrixTimelineItem> {
        if (decryptingKeys.isEmpty() || previousDisplayItems.isEmpty()) return this
        val previousByIdentity = previousDisplayItems.mapNotNull { item ->
            projectionIdentity(item)?.let { identity -> identity to item }
        }.toMap()
        val previousRawByIdentity = previousRawItems.mapNotNull { item ->
            projectionIdentity(item)?.let { identity -> identity to item }
        }.toMap()
        val currentRawByIdentity = currentRawItems.mapNotNull { item ->
            projectionIdentity(item)?.let { identity -> identity to item }
        }.toMap()
        return map { item ->
            val key = itemKey(item)
            if (key in decryptingKeys) {
                val identity = projectionIdentity(item)
                val previousRaw = identity?.let(previousRawByIdentity::get)
                val currentRaw = identity?.let(currentRawByIdentity::get)
                if (!requireSameSource ||
                    previousRaw != null && currentRaw != null && previousRaw.hasSameProjectionSourceAs(currentRaw)
                ) {
                    identity?.let(previousByIdentity::get) ?: item
                } else {
                    item
                }
            } else {
                item
            }
        }
    }

    /**
     * Structural rebuild reuse is permitted only for the exact immutable source event. Display
     * identifiers are intentionally excluded: Rust may allocate a new transient id during a
     * Clear/Reset, while any event/content/policy change must force a new projection.
     */
    private fun MatrixTimelineItem.hasSameProjectionSourceAs(other: MatrixTimelineItem): Boolean = when {
        this is MatrixTimelineItem.Event && other is MatrixTimelineItem.Event ->
            event.eventId == other.event.eventId &&
                event.transactionId == other.event.transactionId &&
                event.sender == other.event.sender &&
                event.timestamp == other.event.timestamp &&
                event.content == other.event.content
        this is MatrixTimelineItem.Virtual && other is MatrixTimelineItem.Virtual -> virtual == other.virtual
        else -> this == other
    }

    private fun MatrixTimelineItem.applyTombstonePriority(): MatrixTimelineItem {
        val eventItem = this as? MatrixTimelineItem.Event ?: return this
        val eventId = eventItem.eventId?.value ?: return this
        if (eventItem.event.content is RedactedContent) {
            if (tombstonedEventIds.add(eventId)) {
                logRedactionLifecycle("T2", eventId, "matrix_diff_redaction")
            }
            return this
        }
        if (eventId !in tombstonedEventIds) return this
        Timber.tag(REDACTION_LATENCY_TAG).w(
            "stale_generation_blocked identity=%s epoch_ms=%d monotonic_ns=%d",
            eventId,
            System.currentTimeMillis(),
            System.nanoTime(),
        )
        return eventItem.copy(event = eventItem.event.copy(content = RedactedContent))
    }

    /**
     * Content invalidation can race ahead of realtime delivery, pagination or a Reset. Persisting
     * the content id in this room processor makes that terminal state dominate every later carrier
     * without advancing the global authorization revision or touching unrelated projections.
     */
    private fun MatrixTimelineItem.applyTerminalPriority(): MatrixTimelineItem {
        val eventPriorityItem = applyTombstonePriority()
        val eventItem = eventPriorityItem as? MatrixTimelineItem.Event ?: return eventPriorityItem
        if (eventItem.event.content is RedactedContent) return eventPriorityItem
        val adapter = decryptAdapter ?: return eventPriorityItem
        val isContentTombstoned = tombstonedContentIds.any { contentId ->
            adapter.containsContent(eventItem, contentId)
        }
        if (!isContentTombstoned) return eventPriorityItem

        eventItem.eventId?.value?.let { eventId ->
            if (tombstonedEventIds.add(eventId)) {
                logRedactionLifecycle("T2", eventId, "content_tombstone_before_diff")
            }
        }
        return eventItem.copy(event = eventItem.event.copy(content = RedactedContent))
    }

    private suspend fun applyDecryptedItems(items: List<DecryptedItem>) {
        val startedAt = System.nanoTime()
        var appliedCount = 0
        var hasTemporarilyUnavailableItems = false
        var retryItems = emptyList<IndexedValue<MatrixTimelineItem>>()
        var retryVisibleItems = emptyList<MatrixTimelineItem>()
        var retryHistoryItems = emptyList<MatrixTimelineItem>()
        var retryRecentHistoryItems = emptyList<MatrixTimelineItem>()
        var retryRecentProjectionKeys = emptySet<String>()
        var retryGeneration = 0L
        var retryReleaseGeneration = 0L
        var retryTimelineScopeId = UNSCOPED_TIMELINE_RELEASE_SCOPE
        mutex.withLock {
            val adapter = decryptAdapter ?: return@withLock
            val authorizationRevisionAtValidation = adapter.authorizationRevision()
            val nextDisplayItems = displayItems.toMutableList()
            val candidatesByKey = LinkedHashMap<String, DecryptedItem>()
            val retriesByKey = LinkedHashMap<String, IndexedValue<MatrixTimelineItem>>()
            val recentRetryKeys = mutableSetOf<String>()

            fun retryCurrentRaw(key: String, recent: Boolean = false) {
                rawItemSlotsByKey[key]?.let { current ->
                    retriesByKey[key] = current
                    if (recent && current.index >= rawItems.size - VISIBLE_FIRST_ITEM_COUNT) recentRetryKeys += key
                }
            }

            items.forEach { update ->
                val key = itemKey(update.rawItem) ?: return@forEach
                val current = rawItemSlotsByKey[key] ?: return@forEach
                val activeTimelineScopeId = activeCommitScope?.releaseScopeId ?: UNSCOPED_TIMELINE_RELEASE_SCOPE
                if (update.authorizationRevision != authorizationRevisionAtValidation ||
                    update.timelineScopeId != activeTimelineScopeId ||
                    !pendingDecrypts.isCurrent(key, update.rawStateGeneration, update.timelineScopeId) ||
                    current.value != update.rawItem ||
                    current.index !in nextDisplayItems.indices
                ) {
                    retryCurrentRaw(key, recent = (update.recentHistory || update.recentProjection) &&
                        update.authorizationRevision == authorizationRevisionAtValidation && update.timelineScopeId == activeTimelineScopeId)
                    return@forEach
                }
                // Keep only the newest buffered result for a key. The immutable raw equality is
                // checked again at the commit boundary below.
                candidatesByKey[key] = update
            }

            val authorizationRevisionAtCommit = adapter.authorizationRevision()
            val authorizationChanged = authorizationRevisionAtCommit != authorizationRevisionAtValidation
            val appliedKeys = mutableSetOf<String>()
            if (!authorizationChanged) {
                candidatesByKey.forEach { (key, update) ->
                    val current = rawItemSlotsByKey[key]
                    // A Set/Reset may replace an event while the previous projection is buffered.
                    // Revalidate the current immutable source at the final batch commit boundary.
                    if (current == null ||
                        current.value != update.rawItem ||
                        current.index !in nextDisplayItems.indices ||
                        update.authorizationRevision != authorizationRevisionAtCommit
                    ) {
                        retryCurrentRaw(key, recent = update.recentHistory || update.recentProjection)
                        return@forEach
                    }
                    nextDisplayItems[current.index] = update.decryptedItem
                    appliedKeys += key
                }
            } else {
                // A revision transition invalidates every previously derived projection, not just
                // this batch. Publish only safe shells and retry from the latest immutable raw state.
                rawItemSlotsByKey.forEach { (key, current) -> retriesByKey[key] = current }
                recentRetryKeys.clear()
            }

            if (authorizationChanged) {
                initialStructuralProjectionBarrier = null
                displayItems = safeShellItems
                pendingDecryptingShells.clear()
                revealedDecryptingShells.clear()
                publishDisplayItems()
            } else if (appliedKeys.isNotEmpty()) {
                displayItems = nextDisplayItems
                appliedKeys.forEach { key ->
                    pendingDecryptingShells.remove(key)
                    revealedDecryptingShells.remove(key)
                }
                initialStructuralProjectionBarrier?.let { barrier ->
                    val initialViewportKeys = barrier.pendingVisibleKeys.toSet()
                    barrier.pendingVisibleKeys.removeAll(appliedKeys)
                    val hasAuthenticatedFirstContent = appliedKeys.any { key ->
                        candidatesByKey[key]?.let { update ->
                            (key in initialViewportKeys || update.recentHistory || update.recentProjection) &&
                                update.decryptedItem.hasAuthorizedOrdinaryMessage()
                        } == true
                    }
                    // One current-authority message is enough to leave the empty loading screen.
                    // Other items remain safe shells; a slow older message must not hide the
                    // already authorized viewport. Scope, source and revision were checked above.
                    if (barrier.pendingVisibleKeys.isEmpty() || hasAuthenticatedFirstContent) {
                        initialStructuralProjectionBarrier = null
                    }
                }
                if (publishDisplayItems(expectedAuthorizationRevision = authorizationRevisionAtCommit)) {
                    appliedKeys.forEach { key ->
                        val event = candidatesByKey[key]?.rawItem as? MatrixTimelineItem.Event
                        LifecycleStageTiming.point("timeline_projection_applied", event?.eventId?.value.orEmpty(),
                            candidatesByKey[key]?.timelineScopeId.orEmpty())
                    }
                    appliedCount = appliedKeys.size
                    appliedKeys.forEach(retriesByKey::remove)
                } else {
                    // The revision changed while the final projection was being assembled or emitted.
                    // Immediately replace any possibly stale projection with shells and retry all raw.
                    displayItems = safeShellItems
                    pendingDecryptingShells.clear()
                    revealedDecryptingShells.clear()
                    initialStructuralProjectionBarrier = null
                    rawItemSlotsByKey.forEach { (key, current) -> retriesByKey[key] = current }
                    recentRetryKeys.clear()
                    publishDisplayItems()
                }
            }

            retryItems = retriesByKey.values.sortedByDescending { it.index }
            val retryClassification = classifyForCurrentViewport(retryItems.map { it.value })
            retryVisibleItems = retryClassification.first
            retryHistoryItems = retryClassification.second.filterNot { itemKey(it) in recentRetryKeys }
            retryRecentHistoryItems = retryClassification.second.filter { itemKey(it) in recentRetryKeys }
            retryRecentProjectionKeys = recentRetryKeys.toSet()
            retryGeneration = rawStateGeneration
            retryReleaseGeneration = if (hasMeasuredViewport) latestViewportGeneration else 0L
            retryTimelineScopeId = activeCommitScope?.releaseScopeId ?: UNSCOPED_TIMELINE_RELEASE_SCOPE
            hasTemporarilyUnavailableItems = displayItems.any(MatrixTimelineItem::isTemporarilyUnavailableProtectedProjection)
        }
        if (retryItems.isNotEmpty()) {
            pendingDecrypts.offer(
                visible = retryVisibleItems,
                history = retryHistoryItems,
                recentHistory = retryRecentHistoryItems,
                recentProjectionKeys = retryRecentProjectionKeys,
                force = true,
                rawStateGeneration = retryGeneration,
                timelineScopeId = retryTimelineScopeId,
                releaseGeneration = retryReleaseGeneration,
            )
            signalPendingDecrypts()
        }
        updateTransientRetrySchedule(hasTemporarilyUnavailableItems)
        Timber.tag(PERFORMANCE_TAG).d(
            "stage=timeline_decrypt_publish batch_size=%d applied_count=%d visible_count=%d history_count=%d duration_ms=%d",
            items.size,
            appliedCount,
            items.count { it.priority == DecryptPriority.VISIBLE },
            items.count { it.priority == DecryptPriority.HISTORY },
            (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND,
        )
    }

    /**
     * Retries only projections that failed with a temporary security-service state. Every attempt
     * re-enters its original lifecycle gate and KMS validation; no authorization result or plaintext is
     * reused. The bounded backoff prevents a KMS/HA outage from turning room history into a request
     * storm, while trust/Vault/foreground recovery can still trigger an immediate revision refresh.
     */
    private fun updateTransientRetrySchedule(hasTemporarilyUnavailableItems: Boolean) {
        if (!hasTemporarilyUnavailableItems) {
            resetTransientRetries("NO_TRANSIENT_ITEMS")
            return
        }
        val scope = backgroundScope ?: return
        val dispatcher = backgroundDispatcher ?: return
        synchronized(transientRetryLock) {
            if (transientRetryJob?.isActive == true || transientRetryAttempt >= TRANSIENT_RETRY_DELAYS_MILLIS.size) return
            val delayMillis = TRANSIENT_RETRY_DELAYS_MILLIS[transientRetryAttempt++]
            Timber.tag(PERFORMANCE_TAG).d("stage=timeline_retry_schedule attempt=%d delay_ms=%d", transientRetryAttempt, delayMillis)
            transientRetryJob = scope.launch(dispatcher) {
                delay(delayMillis)
                val retrySnapshot = mutex.withLock {
                    val candidates = displayItems.mapIndexedNotNull { index, displayItem ->
                        rawItems.getOrNull(index)
                            ?.takeIf { displayItem.isTemporarilyUnavailableProtectedProjection() }
                            ?.let { IndexedValue(index, it) }
                    }
                    val (visibleItems, historyItems) = classifyForCurrentViewport(candidates.map { it.value })
                    TransientRetrySnapshot(
                        visible = visibleItems,
                        history = historyItems,
                        generation = rawStateGeneration,
                        timelineScopeId = activeCommitScope?.releaseScopeId ?: UNSCOPED_TIMELINE_RELEASE_SCOPE,
                        releaseGeneration = if (hasMeasuredViewport) latestViewportGeneration else 0L,
                    )
                }
                synchronized(transientRetryLock) { transientRetryJob = null }
                if (retrySnapshot.visible.isEmpty() && retrySnapshot.history.isEmpty()) {
                    resetTransientRetries("EMPTY_RETRY_SNAPSHOT")
                    return@launch
                }
                pendingDecrypts.offer(
                    visible = retrySnapshot.visible,
                    history = retrySnapshot.history,
                    force = true,
                    rawStateGeneration = retrySnapshot.generation,
                    timelineScopeId = retrySnapshot.timelineScopeId,
                    releaseGeneration = retrySnapshot.releaseGeneration,
                )
                signalPendingDecrypts()
            }
        }
    }

    private fun resetTransientRetries(reason: String) = synchronized(transientRetryLock) {
        if (transientRetryAttempt > 0) {
            Timber.tag(PERFORMANCE_TAG).d("stage=timeline_retry_reset reason=%s previous_attempt=%d", reason, transientRetryAttempt)
        }
        transientRetryJob?.cancel()
        transientRetryJob = null
        transientRetryAttempt = 0
    }

    private suspend fun revealDecryptingShells(shells: Map<String, Long>) {
        mutex.withLock {
            val keysToReveal = shells.mapNotNullTo(mutableSetOf()) { (key, generation) ->
                key.takeIf { pendingDecryptingShells[key] == generation }
            }
            if (revealedDecryptingShells.addAll(keysToReveal)) {
                publishDisplayItems()
            }
        }
    }

    /**
     * Holds a previous safe rendering, or omits a new event, during the short decrypt grace window.
     * The raw protected carrier is never emitted. Slow items become visible only as a fail-closed shell.
     */
    private suspend fun publishDisplayItems(expectedAuthorizationRevision: Long? = null): Boolean {
        if (expectedAuthorizationRevision != null && decryptAdapter?.authorizationRevision() != expectedAuthorizationRevision) {
            return false
        }
        initialStructuralProjectionBarrier?.let { barrier ->
            if (activeCommitScope?.scope != barrier.scope) {
                initialStructuralProjectionBarrier = null
                return@let
            }
            // The first scoped Clear+Reset has no trustworthy prior projection. Keep the UI in its
            // empty secure-loading state until the first authorized viewport message, or until
            // every pending item has an explicit fail-closed result. No plaintext is retained or
            // published by this barrier, and hard/terminal transitions clear it synchronously.
            Timber.tag(PERFORMANCE_TAG).d("stage=initial_projection_wait pending_count=%d", barrier.pendingVisibleKeys.size)
            return true
        }
        val currentlyPublished = timelineItems.replayCache.lastOrNull().orEmpty()
        val needsPreviousSafeProjection = pendingDecryptingShells.keys.any { key -> key !in revealedDecryptingShells }
        val publishedByProjectionIdentity = if (needsPreviousSafeProjection) {
            currentlyPublished.mapNotNull { item ->
                projectionIdentity(item)?.let { identity -> identity to item }
            }.toMap()
        } else {
            emptyMap()
        }
        val revision = if (pendingOriginalSendSources.isNotEmpty()) decryptAdapter?.authorizationRevision() else null
        val originalSendProjections = mutableMapOf<String, MatrixTimelineItem>()
        pendingOriginalSendSources.keys.toList().forEach { key ->
            val source = pendingOriginalSendSources[key] ?: return@forEach
            val current = rawItemSlotsByKey[key]?.value
            val original = if (key in pendingDecryptingShells && current != null) {
                decryptAdapter?.pendingOriginalSendProjection(source, current)
            } else null
            if (original != null) originalSendProjections[key] = original else pendingOriginalSendSources.remove(key)
        }
        if (revision != null && revision != decryptAdapter?.authorizationRevision()) return false
        val projectedItems = displayItems.mapNotNull { item ->
            val key = itemKey(item) ?: return@mapNotNull item
            originalSendProjections[key]?.let { return@mapNotNull it }
            val rawItem = rawItemSlotsByKey[key]?.value
            val safeShell = safeShellItemsByKey[key]
            val safeItem = if (item == rawItem && safeShell != null && safeShell != rawItem) safeShell else item
            if (key !in pendingDecryptingShells || key in revealedDecryptingShells) {
                safeItem
            } else {
                projectionIdentity(item)
                    ?.let(publishedByProjectionIdentity::get)
                    ?.takeUnless { published -> published == rawItem }
            }
        }
        val reducedItems = projectedItems
            .reduceProtectedOperations()
            .resolveReplyPreviewsFromAuthorizedProjection()
            .map { item ->
                if (mediaRoomId != null && item is MatrixTimelineItem.Event) {
                    val boundContent = item.event.content.withMediaEventOrigin(mediaRoomId, item.event.eventId?.value)
                    if (boundContent === item.event.content) item else item.copy(event = item.event.copy(content = boundContent))
                } else item
            }
        if (!hasPublishedItems || reducedItems != currentlyPublished) {
            timelineItems.emit(reducedItems)
            hasPublishedItems = true
        }
        return expectedAuthorizationRevision == null || decryptAdapter?.authorizationRevision() == expectedAuthorizationRevision
    }

    /** Applies authenticated operation carriers in event order and removes them from presentation. */
    private fun List<MatrixTimelineItem>.reduceProtectedOperations(): List<MatrixTimelineItem> {
        val reduced = toMutableList()
        val eventIndex = mutableMapOf<String, Int>()
        // operationId is supplied by the actor and is only authoritative inside one root aggregate.
        // Scoping replay suppression prevents an operation on another root or from another actor
        // from consuming the identifier before the intended operation is reduced.
        val seenOperations = mutableSetOf<Triple<String, String, String>>()
        val confirmedOperationIdentities = filterIsInstance<MatrixTimelineItem.Event>().mapNotNull { item ->
            val operation = item.event.content as? ProtectedOperationContent ?: return@mapNotNull null
            if (item.eventId?.value?.let(ProtectedEventOwnershipResolver::isCanonicalEvent) != true) return@mapNotNull null
            Triple(operation.operationId,operation.targetEventId,item.event.sender.value)
        }.toSet()
        val relayStates = mutableMapOf<String, ProtectedRelayReducer.State>()
        val operationIndices = mutableSetOf<Int>()
        forEachIndexed { index, item ->
            val eventItem = item as? MatrixTimelineItem.Event ?: return@forEachIndexed
            eventItem.eventId?.value?.let { eventId ->
                eventIndex[eventId] = index
                val message = eventItem.event.content as? MessageContent
                val lines = message?.body?.lines().orEmpty()
                val firstLine = lines.firstOrNull()?.trim().orEmpty()
                if (message?.isContentProtectionAuthorized == true && firstLine.startsWith(RELAY_TITLE_PREFIX)) {
                    val initialEntries = message.protectedRelayEntries ?: lines.mapNotNull { line ->
                            RELAY_ENTRY_PATTERN.matchEntire(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
                        }.mapIndexed { entryIndex, text ->
                            // Only the initial item has the root event's proven author.
                            // Legacy flattened entries have no author proof and cannot be edited.
                            ProtectedRelayEntry(
                                if (entryIndex == 0) message.protectedRelayInitialEntryId ?: "root:$eventId:0" else "legacy:$eventId:$entryIndex",
                                eventItem.event.sender.takeIf { entryIndex == 0 }, text,
                            )
                        }.toPersistentList()
                    relayStates.putIfAbsent(eventId, ProtectedRelayReducer.State(initialEntries,
                        lines.any { it.trim() == RELAY_CLOSED_MARKER }))
                    reduced[index] = eventItem.copy(event = eventItem.event.copy(content = message.copy(
                        protectedRelayEntries = initialEntries.toPersistentList(),
                    )))
                }
            }
            (eventItem.event.content as? LiveLocationContent)?.protectedAggregateId?.let { eventIndex[it] = index }
            val operation = eventItem.event.content as? ProtectedOperationContent ?: return@forEachIndexed
            operationIndices += index
            if (operation.protocolVersion != PROTECTED_OPERATION_PROTOCOL_VERSION) return@forEachIndexed
            val targetIndex = eventIndex[operation.targetEventId] ?: return@forEachIndexed
            val target = reduced[targetIndex] as? MatrixTimelineItem.Event ?: return@forEachIndexed
            val actor = eventItem.event.sender
            val operationIdentity = Triple(operation.operationId, operation.targetEventId, actor.value)
            // The same SDK send can temporarily have both local and canonical rows.
            // Canonical authority evidence wins over the speculative local projection.
            if (eventItem.eventId?.value?.let(ProtectedEventOwnershipResolver::isCanonicalEvent) != true &&
                operationIdentity in confirmedOperationIdentities) return@forEachIndexed
            val acceptOperation = { seenOperations.add(operationIdentity) }
            val targetOwner = target.event.sender
            when (operation.operation) {
                ProtectedOperationContent.Operation.REACTION_SET -> {
                    val emoji = operation.emoji?.takeIf { it.isNotBlank() && it.length <= MAX_REACTION_LENGTH } ?: return@forEachIndexed
                    val enabled = operation.enabled ?: return@forEachIndexed
                    if (!acceptOperation()) return@forEachIndexed
                    val reactions = target.event.reactions.associateBy(EventReaction::key).toMutableMap()
                    val existing = reactions[emoji]?.senders.orEmpty().filterNot { it.senderId == actor }.toMutableList()
                    if (enabled) existing += ReactionSender(actor, eventItem.event.timestamp)
                    if (existing.isEmpty()) reactions.remove(emoji) else reactions[emoji] = EventReaction(emoji, existing.toPersistentList())
                    reduced[targetIndex] = target.copy(event = target.event.copy(reactions = reactions.values.sortedBy(EventReaction::key).toPersistentList()))
                }
                ProtectedOperationContent.Operation.POLL_EDIT -> {
                    if (actor != targetOwner) return@forEachIndexed
                    val poll = target.event.content as? PollContent ?: return@forEachIndexed
                    if (poll.endTime != null) return@forEachIndexed
                    val answers = operation.pollAnswers
                    val maximum = operation.maxSelections ?: return@forEachIndexed
                    if (answers.size < 2 || answers.map { it.id }.toSet().size != answers.size || maximum !in 1uL..answers.size.toULong()) {
                        return@forEachIndexed
                    }
                    val question = operation.question?.takeIf(String::isNotBlank) ?: return@forEachIndexed
                    val kind = operation.pollKind ?: return@forEachIndexed
                    if (!acceptOperation()) return@forEachIndexed
                    reduced[targetIndex] = target.copy(event = target.event.copy(content = poll.copy(
                        question = question,
                        kind = kind,
                        maxSelections = maximum,
                        answers = answers.toPersistentList(),
                        votes = poll.votes.filterKeys { key -> answers.any { it.id == key } }.toPersistentMap(),
                        isEdited = true,
                    )))
                }
                ProtectedOperationContent.Operation.POLL_RESPONSE -> {
                    val poll = target.event.content as? PollContent ?: return@forEachIndexed
                    if (poll.endTime != null) return@forEachIndexed
                    val validAnswers = poll.answers.mapTo(mutableSetOf()) { it.id }
                    val selected = operation.selectedAnswerIds.distinct()
                    if (selected.size > poll.maxSelections.toInt() || selected.any { it !in validAnswers }) return@forEachIndexed
                    if (!acceptOperation()) return@forEachIndexed
                    val votes = poll.votes.mapValues { (_, voters) -> voters.filterNot { it == actor }.toPersistentList() }.toMutableMap()
                    selected.forEach { answerId -> votes[answerId] = (votes[answerId].orEmpty() + actor).distinct().toPersistentList() }
                    reduced[targetIndex] = target.copy(event = target.event.copy(content = poll.copy(votes = votes.toPersistentMap())))
                }
                ProtectedOperationContent.Operation.POLL_END -> {
                    if (actor != targetOwner) return@forEachIndexed
                    val poll = target.event.content as? PollContent ?: return@forEachIndexed
                    if (poll.endTime != null) return@forEachIndexed
                    val finalVotes = operation.pollVotes
                        .filterKeys { answerId -> poll.answers.any { it.id == answerId } }
                        .mapValues { (_, voters) -> voters.distinct().toPersistentList() }
                        .toPersistentMap()
                    if (!acceptOperation()) return@forEachIndexed
                    reduced[targetIndex] = target.copy(event = target.event.copy(content = poll.copy(
                        votes = finalVotes.takeUnless { it.isEmpty() } ?: poll.votes,
                        endTime = eventItem.event.timestamp.toULong(),
                    )))
                }
                ProtectedOperationContent.Operation.CAPTION_SET -> {
                    if (actor != targetOwner) return@forEachIndexed
                    val message = target.event.content as? MessageContent ?: return@forEachIndexed
                    val formatted = operation.formattedCaption?.let { FormattedBody(MessageFormat.HTML, it) }
                    val type = when (val attachment = message.type) {
                        is ImageMessageType -> attachment.copy(caption = operation.caption, formattedCaption = formatted)
                        is AudioMessageType -> attachment.copy(caption = operation.caption, formattedCaption = formatted)
                        is VoiceMessageType -> attachment.copy(caption = operation.caption, formattedCaption = formatted)
                        is VideoMessageType -> attachment.copy(caption = operation.caption, formattedCaption = formatted)
                        is FileMessageType -> attachment.copy(caption = operation.caption, formattedCaption = formatted)
                        else -> return@forEachIndexed
                    }
                    if (!acceptOperation()) return@forEachIndexed
                    reduced[targetIndex] = target.copy(event = target.event.copy(content = message.copy(type = type, isEdited = true)))
                }
                ProtectedOperationContent.Operation.LIVE_LOCATION_UPDATE -> {
                    if (actor != targetOwner) return@forEachIndexed
                    val live = target.event.content as? LiveLocationContent ?: return@forEachIndexed
                    if (!live.isLive) return@forEachIndexed
                    val timestamp = operation.timestamp ?: return@forEachIndexed
                    val geoUri = operation.geoUri ?: return@forEachIndexed
                    val next = io.element.android.libraries.matrix.api.room.location.LiveLocationInfo(operation.description, geoUri, timestamp)
                    if (!acceptOperation()) return@forEachIndexed
                    reduced[targetIndex] =
                        target.copy(event = target.event.copy(content = live.copy(locations = (live.locations + next).takeLast(MAX_LIVE_LOCATIONS))))
                }
                ProtectedOperationContent.Operation.LIVE_LOCATION_END -> {
                    if (actor != targetOwner) return@forEachIndexed
                    val live = target.event.content as? LiveLocationContent ?: return@forEachIndexed
                    if (!live.isLive) return@forEachIndexed
                    if (!acceptOperation()) return@forEachIndexed
                    reduced[targetIndex] = target.copy(event = target.event.copy(content = live.copy(isLive = false)))
                }
                ProtectedOperationContent.Operation.RELAY_APPEND,
                ProtectedOperationContent.Operation.RELAY_EDIT,
                ProtectedOperationContent.Operation.RELAY_CLOSE -> {
                    val message = target.event.content as? MessageContent ?: return@forEachIndexed
                    val firstLine = message.body.lineSequence().firstOrNull()?.trim() ?: return@forEachIndexed
                    if (!firstLine.startsWith(RELAY_TITLE_PREFIX)) return@forEachIndexed
                    val title = firstLine.removePrefix(RELAY_TITLE_PREFIX).takeIf(String::isNotBlank) ?: return@forEachIndexed
                    if (operation.aggregateId != operation.targetEventId) return@forEachIndexed
                    val state = relayStates[operation.targetEventId] ?: return@forEachIndexed
                    val isConfirmed = eventItem.eventId?.value?.let(ProtectedEventOwnershipResolver::isCanonicalEvent) == true
                    val updated = when (operation.operation) {
                        ProtectedOperationContent.Operation.RELAY_APPEND -> ProtectedRelayReducer.append(
                            state, operation.relayEntryId ?: "operation:${operation.operationId}", actor,
                            operation.relayEntry ?: return@forEachIndexed, isConfirmed,
                        )
                        ProtectedOperationContent.Operation.RELAY_EDIT -> ProtectedRelayReducer.edit(
                            state, operation.relayEntryId ?: return@forEachIndexed, actor,
                            operation.relayExpectedRevision ?: return@forEachIndexed,
                            operation.relayEntry ?: return@forEachIndexed, isConfirmed,
                        )
                        else -> ProtectedRelayReducer.close(state, actor, targetOwner)
                    }
                    if (updated == state || !acceptOperation()) return@forEachIndexed
                    relayStates[operation.targetEventId] = updated
                    val body = buildString {
                        append(RELAY_TITLE_PREFIX).append(title)
                        append("\n回复本消息即可参与；发起人回复“结束接龙”可结束")
                        updated.entries.forEachIndexed { entryIndex, entry -> append('\n').append(entryIndex + 1).append(". ").append(entry.text) }
                        if (updated.closed) append('\n').append(RELAY_CLOSED_MARKER)
                    }
                    reduced[targetIndex] =
                        target.copy(event = target.event.copy(content = message.copy(body = body, type = TextMessageType(body, null),
                            isEdited = true, protectedRelayEntries = updated.entries.toPersistentList())))
                }
                ProtectedOperationContent.Operation.CALL_STATE -> {
                    val call = target.event.content as? CallNotifyContent ?: return@forEachIndexed
                    val nextState = operation.callLifecycleState ?: return@forEachIndexed
                    if (!call.lifecycleState.canTransitionTo(nextState)) return@forEachIndexed
                    if (nextState == CallLifecycleState.CANCELLED && actor != targetOwner) return@forEachIndexed
                    if (nextState in setOf(CallLifecycleState.DECLINED, CallLifecycleState.MISSED) && actor == targetOwner) {
                        return@forEachIndexed
                    }
                    val duration = operation.durationMillis
                        ?.takeIf { nextState == CallLifecycleState.ENDED && it >= 0L }
                    if (!acceptOperation()) return@forEachIndexed
                    reduced[targetIndex] = target.copy(
                        event = target.event.copy(
                            content = call.copy(
                                lifecycleState = nextState,
                                durationMillis = duration,
                                lifecycleActor = actor,
                            )
                        )
                    )
                }
                ProtectedOperationContent.Operation.HIDDEN -> Unit
            }
        }
        return reduced.filterIndexed { index, _ -> index !in operationIndices }
    }

    /**
     * A protected relation carrier is not a user-facing quote preview. Resolve it only from the
     * currently authorized target projection. If that projection disappeared or was revoked, the
     * reply becomes a neutral unavailable shell instead of retaining stale quoted cleartext.
     */
    private fun List<MatrixTimelineItem>.resolveReplyPreviewsFromAuthorizedProjection(): List<MatrixTimelineItem> {
        val eventsById = mapNotNull { item ->
            val event = item as? MatrixTimelineItem.Event ?: return@mapNotNull null
            event.eventId?.let { it to event }
        }.toMap()

        return map { item ->
            val eventItem = item as? MatrixTimelineItem.Event ?: return@map item
            val message = eventItem.event.content as? MessageContent ?: return@map item
            val reply = message.inReplyTo ?: return@map item
            val replyEventId = reply.eventId()
            val readyReply = reply as? InReplyTo.Ready
            val embeddedProtectedMessage = (readyReply?.content as? MessageContent)?.isContentProtectionAuthorized == true
            val requiresCurrentProjection = message.isContentProtectionAuthorized ||
                readyReply?.content is ProtectedOperationContent || embeddedProtectedMessage
            val target = eventsById[replyEventId]
            val targetContent = target?.event?.content
            val resolvedReply = when {
                targetContent != null &&
                    targetContent !is ProtectedOperationContent &&
                    targetContent.isAuthorizedForUserDisplay() -> InReplyTo.Ready(
                        eventId = replyEventId,
                        content = targetContent,
                        senderId = target.event.sender,
                        senderProfile = target.event.senderProfile,
                    )
                target != null && requiresCurrentProjection -> InReplyTo.Ready(
                    eventId = replyEventId,
                    content = RedactedContent,
                    senderId = target.event.sender,
                    senderProfile = target.event.senderProfile,
                )
                requiresCurrentProjection -> InReplyTo.NotLoaded(replyEventId)
                else -> reply
            }
            if (resolvedReply == reply) {
                item
            } else {
                eventItem.copy(event = eventItem.event.copy(content = message.copy(inReplyTo = resolvedReply)))
            }
        }
    }

    private fun InReplyTo.eventId() = when (this) {
        is InReplyTo.Error -> eventId
        is InReplyTo.NotLoaded -> eventId
        is InReplyTo.Pending -> eventId
        is InReplyTo.Ready -> eventId
    }

    private fun CallLifecycleState?.canTransitionTo(next: CallLifecycleState): Boolean = when (this) {
        null -> true
        CallLifecycleState.CONNECTED -> next == CallLifecycleState.ENDED
        CallLifecycleState.DECLINED,
        CallLifecycleState.CANCELLED,
        CallLifecycleState.MISSED,
        CallLifecycleState.ENDED -> false
    }

    private fun List<MatrixTimelineItem>.redactedEventIdentities(): Set<String> = mapNotNullTo(mutableSetOf()) { item ->
        val event = item as? MatrixTimelineItem.Event ?: return@mapNotNullTo null
        event.eventId?.value?.takeIf { event.event.content is RedactedContent }
    }

    private fun logRedactionLifecycle(stage: String, identity: String, source: String) {
        Timber.tag(REDACTION_LATENCY_TAG).d(
            "stage=%s identity=%s source=%s epoch_ms=%d monotonic_ns=%d generation=%d",
            stage,
            identity,
            source,
            System.currentTimeMillis(),
            System.nanoTime(),
            rawStateGeneration,
        )
    }

    internal companion object {
        const val VISIBLE_FIRST_ITEM_COUNT = 30
        const val NEARBY_HISTORY_ITEM_COUNT = 60

        // Four workers fill the initial viewport; only one may continue through off-screen history.
        // The queue still retains history and the timeline scope cancels all workers on close.
        const val VISIBLE_ONLY_DECRYPT_WORKER_COUNT = 3
        const val HISTORY_DECRYPT_WORKER_COUNT = 1
        const val DECRYPT_WORKER_COUNT = VISIBLE_ONLY_DECRYPT_WORKER_COUNT + HISTORY_DECRYPT_WORKER_COUNT
        const val HISTORY_FAIRNESS_VISIBLE_BURST = VISIBLE_FIRST_ITEM_COUNT
        const val DECRYPT_QUEUE_CAPACITY = 64
        const val MAX_PENDING_DECRYPTS = 4_096
        const val EXPECTED_DECRYPT_BATCH_SIZE = 32

        // Four KMS workers complete with enough jitter that a frame-sized window still produced
        // almost one full timeline/Compose publication per item on a 120 Hz device. Keep visible
        // results responsive while allowing one worker wave to coalesce into a single projection.
        const val VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS = 200L
        const val HISTORY_DECRYPT_BATCH_WINDOW_MILLIS = 500L
        const val DECRYPTING_SHELL_GRACE_MILLIS = 0L
        val TRANSIENT_RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val PERFORMANCE_TAG = "YiZheTimelinePerf"
        const val REDACTION_LATENCY_TAG = "YiZheRedactionLatency"
        const val PROTECTED_OPERATION_PROTOCOL_VERSION = 1
        const val UNSCOPED_TIMELINE_RELEASE_SCOPE = "unscoped"
        const val MAX_REACTION_LENGTH = 32
        const val MAX_LIVE_LOCATIONS = 256
        const val MAX_RELAY_ENTRY_LENGTH = 2_000
        const val RELAY_TITLE_PREFIX = "接龙："
        const val RELAY_CLOSED_MARKER = "（已结束）"
        val RELAY_ENTRY_PATTERN = Regex("""^\s*\d+\.\s+(.+?)\s*$""")

        fun itemKey(item: MatrixTimelineItem): String? = when (item) {
            is MatrixTimelineItem.Event -> "event:${item.uniqueId.value}"
            is MatrixTimelineItem.Virtual -> "virtual:${item.uniqueId.value}"
            MatrixTimelineItem.Other -> null
        }
    }
}

internal enum class DecryptPriority {
    VISIBLE,
    HISTORY,
}

private fun isEmptyOrdinaryPendingShell(item: MatrixTimelineItem?): Boolean {
    val message = ((item as? MatrixTimelineItem.Event)?.event?.content as? MessageContent) ?: return false
    val text = message.type as? TextMessageType ?: return false
    return message.protectedMessageStatus == ProtectedMessageStatus.DECRYPTING && !message.isBurnAfterRead &&
        message.body.isEmpty() && text.body.isEmpty() && text.formatted == null
}

/** A scheduling window only: raw history remains intact and scrolling schedules deferred items. */
internal fun timelineDecryptWindow(items: List<MatrixTimelineItem>, visibleKeys: List<String>?): List<MatrixTimelineItem> {
    if (visibleKeys.isNullOrEmpty()) {
        return items.takeLast(MatrixTimelineDiffProcessor.VISIBLE_FIRST_ITEM_COUNT + MatrixTimelineDiffProcessor.NEARBY_HISTORY_ITEM_COUNT)
            .asReversed()
    }


    val visibleKeySet = visibleKeys.toSet()
    val visibleIndices = items.indices.filter { MatrixTimelineDiffProcessor.itemKey(items[it]) in visibleKeySet }
    if (visibleIndices.isEmpty()) return emptyList()
    val first = visibleIndices.first()
    val last = visibleIndices.last()
    val padding = MatrixTimelineDiffProcessor.NEARBY_HISTORY_ITEM_COUNT / 2
    val nearby = items.subList((first - padding).coerceAtLeast(0), (last + padding + 1).coerceAtMost(items.size))
        .asReversed()
    return nearby.filter { MatrixTimelineDiffProcessor.itemKey(it) in visibleKeySet } +
        nearby.filterNot { MatrixTimelineDiffProcessor.itemKey(it) in visibleKeySet }
            .take(MatrixTimelineDiffProcessor.NEARBY_HISTORY_ITEM_COUNT)
}

/** Scheduling hint only. Callers additionally require an incremental, current-scope tail slot. */
internal fun MatrixTimelineItem.Event.isRecentTimelineWork(previousItem: MatrixTimelineItem?): Boolean {
    val previous = (previousItem as? MatrixTimelineItem.Event)?.event
    if (previous == null) {
        return event.origin == TimelineItemEventOrigin.SYNC || event.isOwn && event.transactionId != null
    }
    return event.isOwn && previous.isOwn &&
        (event.eventId != previous.eventId || event.localSendState != previous.localSendState)
}

internal data class DecryptedItem(
    val rawItem: MatrixTimelineItem,
    val decryptedItem: MatrixTimelineItem,
    val authorizationRevision: Long,
    val priority: DecryptPriority,
    val rawStateGeneration: Long = 0L,
    val timelineScopeId: String = MatrixTimelineDiffProcessor.UNSCOPED_TIMELINE_RELEASE_SCOPE,
    val recentHistory: Boolean = false,
    // UI publication hint only; does not select a worker or change the release class.
    val recentProjection: Boolean = false,
    val pendingWork: PendingDecryptWork? = null,
)

private data class RawDecryptSnapshot(
    val items: List<MatrixTimelineItem>,
    val generation: Long,
    val timelineScopeId: String,
    val releaseGeneration: Long,
    val visibleItemKeys: List<String>?,
)

private data class TransientRetrySnapshot(
    val visible: List<MatrixTimelineItem>,
    val history: List<MatrixTimelineItem>,
    val generation: Long,
    val timelineScopeId: String,
    val releaseGeneration: Long,
)

private fun MatrixTimelineItem.isTemporarilyUnavailableProtectedProjection(): Boolean {
    val status = when (val content = (this as? MatrixTimelineItem.Event)?.event?.content) {
        is MessageContent -> content.protectedMessageStatus
        is ProtectedOperationContent -> content.protectedMessageStatus.takeIf { content.operation == ProtectedOperationContent.Operation.HIDDEN }
        else -> null
    }
    return status in setOf(ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE, ProtectedMessageStatus.SEND_BINDING_PENDING)
}

private fun MatrixTimelineItem.hasAuthorizedOrdinaryMessage(): Boolean =
    ((this as? MatrixTimelineItem.Event)?.event?.content as? MessageContent)?.let {
        it.isContentProtectionAuthorized && !it.isBurnAfterRead && it.protectedMessageStatus == null
    } == true

/**
 * Coalesces derived decrypt projections without delaying a recent arrival/own confirmation or
 * a visible result behind an off-screen history window. Authorization refreshes do not use this path: they still publish safe shells
 * synchronously, and the projection path rechecks the revision before any buffered result can be
 * projected.
 */
internal suspend fun collectDecryptedItemBatches(
    input: ReceiveChannel<DecryptedItem>,
    releaseBatch: (List<DecryptedItem>) -> Unit = {},
    applyBatch: suspend (List<DecryptedItem>) -> Unit,
) {
    while (true) {
        val first = input.receiveCatching().getOrNull() ?: return
        val batch = ArrayList<DecryptedItem>(MatrixTimelineDiffProcessor.EXPECTED_DECRYPT_BATCH_SIZE)
        batch += first
        try {
            val historyOnlyWindow = first.priority == DecryptPriority.HISTORY
            val windowMillis = if (historyOnlyWindow) {
                MatrixTimelineDiffProcessor.HISTORY_DECRYPT_BATCH_WINDOW_MILLIS
            } else {
                MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS
            }
            if (first.recentHistory || first.recentProjection) {
                // Already-authorized recent work keeps its original release class. Only the UI
                // coalescing delay is removed, with ready projections drained within the same bound.
                while (batch.size < MatrixTimelineDiffProcessor.DECRYPT_QUEUE_CAPACITY) {
                    batch += input.tryReceive().getOrNull() ?: break
                }
            } else {
                withTimeoutOrNull(windowMillis) {
                    while (batch.size < MatrixTimelineDiffProcessor.DECRYPT_QUEUE_CAPACITY) {
                        val next = input.receiveCatching().getOrNull() ?: return@withTimeoutOrNull
                        batch += next
                        if (next.recentHistory || next.recentProjection || historyOnlyWindow && next.priority == DecryptPriority.VISIBLE) {
                            // A new message/own confirmation must not wait for either window.
                            return@withTimeoutOrNull
                        }
                    }
                }
            }
            applyBatch(batch)
        } finally {
            releaseBatch(batch)
        }
    }
}

private fun List<MatrixTimelineItem>.associateByItemKey(): Map<String, MatrixTimelineItem> =
    mapNotNull { item ->
        MatrixTimelineDiffProcessor.itemKey(item)?.let { key -> key to item }
    }.toMap()

private fun projectionIdentity(item: MatrixTimelineItem): String? = when (item) {
    is MatrixTimelineItem.Event -> item.eventId?.let { "event-id:${it.value}" }
        ?: item.transactionId?.let { "transaction-id:${it.value}" }
        ?: "unique-id:${item.uniqueId.value}"
    is MatrixTimelineItem.Virtual -> "virtual:${item.uniqueId.value}"
    MatrixTimelineItem.Other -> null
}

private fun List<MatrixTimelineItem>.indexByItemKey(): Map<String, IndexedValue<MatrixTimelineItem>> =
    mapIndexedNotNull { index, item ->
        MatrixTimelineDiffProcessor.itemKey(item)?.let { key -> key to IndexedValue(index, item) }
    }.toMap()

private fun List<MatrixTimelineItem>.keepLatestByItemKey(): List<MatrixTimelineItem> {
    val seenKeys = mutableSetOf<String>()
    return asReversed().filter { item ->
        MatrixTimelineDiffProcessor.itemKey(item)?.let(seenKeys::add) ?: true
    }.asReversed()
}

/** Bounded, coalescing derived-work queue. Rust diffs themselves are never dropped or conflated. */
internal class PendingDecryptQueue(
    private val maximumSize: Int,
    private val historyFairnessVisibleBurst: Int,
) {
    private val visible = LinkedHashMap<String, PendingDecryptEntry>()
    private val history = LinkedHashMap<String, PendingDecryptEntry>()
    private val recentHistory = LinkedHashMap<String, PendingDecryptEntry>()
    private val inFlight = LinkedHashMap<String, PendingDecryptWork>()
    // Raw source identities only, bounded by the existing result channel and publication batch.
    // These are not worker reservations: a changed source or forced authorization retry can start.
    private val awaitingPublication = mutableMapOf<String, PendingDecryptWork>()
    private val latestRawStateGenerationByKey = mutableMapOf<String, Long>()
    private val latestTimelineScopeIdByKey = mutableMapOf<String, String>()
    private var visiblePollsSinceHistory = 0
    private var recentPollsSinceBacklog = 0
    private var historyWindowKeys: Set<String>? = null
    // A current-viewport scheduling hint only. Entries remain in their original history FIFO;
    // actual incremental recent arrivals retain their separate existing queue and semantics.
    private var bootstrapHistoryKeys = emptyList<String>()
    private var preferredHistoryKeys = emptyList<String>()
    private var preferredHistoryScopeId: String? = null
    private var preferredHistoryGeneration = -1L

    fun retainHistoryWindow(keys: Set<String>) = synchronized(this) {
        historyWindowKeys = keys
        history.keys.removeAll { it !in keys }
        preferredHistoryKeys = preferredHistoryKeys.filter { it in keys }
        bootstrapHistoryKeys = bootstrapHistoryKeys.filter { it in keys }
    }

    fun offer(
        visible: List<MatrixTimelineItem>,
        history: List<MatrixTimelineItem>,
        force: Boolean = false,
        rawStateGeneration: Long = 0L,
        timelineScopeId: String = MatrixTimelineDiffProcessor.UNSCOPED_TIMELINE_RELEASE_SCOPE,
        releaseGeneration: Long = 0L,
        recentHistory: List<MatrixTimelineItem> = emptyList(),
        recentProjectionKeys: Set<String> = emptySet(),
    ) = synchronized(this) {
        require(timelineScopeId.isNotBlank()) { "timelineScopeId must not be blank" }
        history.forEach { item ->
            offerItem(item, DecryptPriority.HISTORY, force, rawStateGeneration, timelineScopeId, releaseGeneration)
        }
        recentHistory.forEach { item ->
            offerItem(item, DecryptPriority.HISTORY, force, rawStateGeneration, timelineScopeId, releaseGeneration, preferRecentHistory = true)
        }
        // Rust can restore its initial snapshot as multiple incremental batches, not just Reset.
        // A truly new later batch must precede that pending restore work too. Reorder scheduling
        // only; Matrix event order, scope/generation guards and HISTORY_PREFETCH remain unchanged.
        val preferredRecent = LinkedHashMap<String, PendingDecryptEntry>()
        recentHistory.forEach { item ->
            val key = MatrixTimelineDiffProcessor.itemKey(item) ?: return@forEach
            val entry = this.recentHistory[key] ?: return@forEach
            if (entry.rawStateGeneration == rawStateGeneration && entry.timelineScopeId == timelineScopeId) {
                this.recentHistory.remove(key)
                preferredRecent[key] = entry
            }
        }
        if (preferredRecent.isNotEmpty()) {
            preferredRecent.putAll(this.recentHistory)
            this.recentHistory.clear()
            this.recentHistory.putAll(preferredRecent)
        }
        visible.forEach { item ->
            offerItem(item, DecryptPriority.VISIBLE, force, rawStateGeneration, timelineScopeId, releaseGeneration,
                preferRecentProjection = MatrixTimelineDiffProcessor.itemKey(item) in recentProjectionKeys)
        }
        trimToMaximumSize()
    }

    /** Reorders only timeline-derived pending work. In-flight work keeps its original authorization. */
    fun reprioritize(
        visibleKeys: List<String>,
        shellCandidates: List<MatrixTimelineItem>,
        rawStateGeneration: Long,
        timelineScopeId: String,
        releaseGeneration: Long,
        preferredHistoryKeys: List<String> = emptyList(),
        bootstrapHistoryKeys: List<String> = emptyList(),
    ) = synchronized(this) {
        val visibleKeySet = visibleKeys.toSet()
        val visibleIterator = visible.entries.iterator()
        while (visibleIterator.hasNext()) {
            val entry = visibleIterator.next()
            if (entry.key !in visibleKeySet) {
                visibleIterator.remove()
                history[entry.key] = entry.value.copy(releaseGeneration = releaseGeneration)
            }
        }
        history.replaceAll { _, entry -> entry.copy(releaseGeneration = releaseGeneration) }
        historyWindowKeys?.let { keys -> history.keys.removeAll { it !in keys } }
        recentHistory.replaceAll { _, entry -> entry.copy(releaseGeneration = releaseGeneration) }

        val reorderedVisible = LinkedHashMap<String, PendingDecryptEntry>()
        visibleKeys.forEach { key ->
            val entry = visible.remove(key) ?: recentHistory.remove(key) ?: history.remove(key) ?: return@forEach
            reorderedVisible[key] = entry.copy(releaseGeneration = releaseGeneration)
        }
        visible.clear()
        visible.putAll(reorderedVisible)

        shellCandidates.forEach { item ->
            offerItem(
                item = item,
                priority = DecryptPriority.VISIBLE,
                force = false,
                rawStateGeneration = rawStateGeneration,
                timelineScopeId = timelineScopeId,
                releaseGeneration = releaseGeneration,
            )
        }
        if (preferredHistoryScopeId != timelineScopeId || releaseGeneration >= preferredHistoryGeneration) {
            this.bootstrapHistoryKeys = bootstrapHistoryKeys.distinct().take(MatrixTimelineDiffProcessor.VISIBLE_FIRST_ITEM_COUNT)
            this.preferredHistoryKeys = preferredHistoryKeys.distinct().take(MatrixTimelineDiffProcessor.VISIBLE_FIRST_ITEM_COUNT)
            preferredHistoryScopeId = timelineScopeId
            preferredHistoryGeneration = releaseGeneration
        }
        trimToMaximumSize()
    }

    /**
     * Records removals at the same ordered raw-state boundary as replacement offers. A delayed
     * authorization snapshot or worker retry from an older generation cannot resurrect the item.
     */
    fun invalidate(keys: Set<String>, rawStateGeneration: Long) = synchronized(this) {
        val retiredPreferredKeys = mutableSetOf<String>()
        keys.forEach { key ->
            val latestGeneration = latestRawStateGenerationByKey[key]
            if (latestGeneration != null && rawStateGeneration < latestGeneration) return@forEach
            awaitingPublication.remove(key)
            latestRawStateGenerationByKey[key] = rawStateGeneration
            visible.remove(key)
            history.remove(key)
            recentHistory.remove(key)
            retiredPreferredKeys += key
        }
        preferredHistoryKeys = preferredHistoryKeys.filterNot { it in retiredPreferredKeys }
        bootstrapHistoryKeys = bootstrapHistoryKeys.filterNot { it in retiredPreferredKeys }
    }

    fun isCurrent(key: String, rawStateGeneration: Long, timelineScopeId: String): Boolean = synchronized(this) {
        latestRawStateGenerationByKey[key] == rawStateGeneration &&
            latestTimelineScopeIdByKey[key] == timelineScopeId
    }

    fun cancelScope(timelineScopeId: String) = synchronized(this) {
        awaitingPublication.entries.removeAll { it.value.timelineScopeId == timelineScopeId }
        visible.entries.removeAll { it.value.timelineScopeId == timelineScopeId }
        history.entries.removeAll { it.value.timelineScopeId == timelineScopeId }
        recentHistory.entries.removeAll { it.value.timelineScopeId == timelineScopeId }
        latestTimelineScopeIdByKey.entries.removeAll { it.value == timelineScopeId }
        if (preferredHistoryScopeId == timelineScopeId) {
            preferredHistoryKeys = emptyList()
            bootstrapHistoryKeys = emptyList()
            preferredHistoryScopeId = null
            preferredHistoryGeneration = -1L
        }
    }

    fun pollVisible(): PendingDecryptWork? = synchronized(this) {
        val work = removeFirstAvailable(visible, DecryptPriority.VISIBLE) ?: return@synchronized null
        recordVisiblePoll()
        inFlight[work.key] = work
        work
    }

    /** Owned generation-zero fallback has no measured visibility and cannot occupy this reserved worker. */
    fun pollReservedVisible(): PendingDecryptWork? = synchronized(this) {
        removeFirstAvailable(visible, DecryptPriority.VISIBLE, measuredOnly = true)?.also { work ->
            recordVisiblePoll()
            inFlight[work.key] = work
        }
    }

    /** Two existing foreground workers borrow bootstrap work after a real measurement. */
    fun pollForeground(): PendingDecryptWork? = synchronized(this) {
        val work = removeFirstAvailable(visible, DecryptPriority.VISIBLE, measuredOnly = true)?.also { recordVisiblePoll() }
            ?: if (recentPollsSinceBacklog < historyFairnessVisibleBurst) {
                firstBootstrapKey()?.let { key ->
                    checkNotNull(history.remove(key)).toPendingWork(key, DecryptPriority.HISTORY).copy(bootstrapHistory = true)
                }?.also { recentPollsSinceBacklog++ }
            } else null
        work?.also { inFlight[it.key] = it }
    }

    private fun firstBootstrapKey(): String? = bootstrapHistoryKeys.firstOrNull { key ->
        val entry = history[key]
        entry != null && key !in inFlight && entry.timelineScopeId == preferredHistoryScopeId &&
            entry.releaseGeneration == preferredHistoryGeneration
    }

    fun hasForegroundWork(): Boolean = synchronized(this) {
        hasReservedVisibleWork() || recentPollsSinceBacklog < historyFairnessVisibleBurst && firstBootstrapKey() != null
    }

    fun pollVisibleOrHistory(): PendingDecryptWork? = synchronized(this) {
        val forceHistory = (history.isNotEmpty() || recentHistory.isNotEmpty()) && visiblePollsSinceHistory >= historyFairnessVisibleBurst
        val work = if (forceHistory) {
            removeNextHistory(forceBacklog = true)?.also { visiblePollsSinceHistory = 0 }
        } else {
            removeFirstAvailable(visible, DecryptPriority.VISIBLE)?.also { recordVisiblePoll() }
                ?: removeNextHistory()?.also { visiblePollsSinceHistory = 0 }
        } ?: return@synchronized null
        inFlight[work.key] = work
        work
    }

    fun awaitPublication(work: PendingDecryptWork) = synchronized(this) {
        awaitingPublication[work.key] = work
    }

    fun completePublication(work: PendingDecryptWork) = synchronized(this) {
        if (awaitingPublication[work.key] === work) awaitingPublication.remove(work.key)
    }

    fun complete(work: PendingDecryptWork, retry: Boolean = false) = synchronized(this) {
        if (inFlight[work.key] != work) return@synchronized
        inFlight.remove(work.key)
        if (!retry) return@synchronized

        val latestGeneration = latestRawStateGenerationByKey[work.key]
        if (latestGeneration != null && work.rawStateGeneration < latestGeneration) return@synchronized
        if (latestTimelineScopeIdByKey[work.key] != work.timelineScopeId) return@synchronized
        // A refresh or a later diff may already have queued this key. Preserve that immutable raw
        // value and its existing priority rather than overwriting it with the completing worker.
        if (work.key in visible || work.key in history || work.key in recentHistory) return@synchronized
        val entry = PendingDecryptEntry(
            work.item,
            work.rawStateGeneration,
            work.timelineScopeId,
            work.releaseGeneration,
            work.recentProjection,
        )
        when (work.priority) {
            DecryptPriority.VISIBLE -> visible[work.key] = entry
            DecryptPriority.HISTORY -> if (work.recentHistory) {
                recentHistory[work.key] = entry
            } else if (historyWindowKeys == null || work.key in historyWindowKeys.orEmpty()) {
                history[work.key] = entry
            }
        }
        trimToMaximumSize()
    }

    fun hasQueuedWork(): Boolean = synchronized(this) { visible.isNotEmpty() || history.isNotEmpty() || recentHistory.isNotEmpty() }

    fun hasVisibleWork(): Boolean = synchronized(this) { visible.isNotEmpty() }

    fun hasReservedVisibleWork(): Boolean = synchronized(this) {
        visible.any { (key, entry) -> key !in inFlight &&
            (entry.releaseGeneration > 0L || entry.timelineScopeId == MatrixTimelineDiffProcessor.UNSCOPED_TIMELINE_RELEASE_SCOPE) }
    }

    /** Work waiting to start. This is the quantity bounded by [maximumSize]. */
    fun pendingSize(): Int = synchronized(this) { visible.size + history.size + recentHistory.size }

    /** Work already owned by decrypt workers; it is tracked separately from the pending bound. */
    fun inFlightSize(): Int = synchronized(this) { inFlight.size }

    private fun recordVisiblePoll() {
        visiblePollsSinceHistory = minOf(visiblePollsSinceHistory + 1, historyFairnessVisibleBurst)
    }

    private fun trimToMaximumSize() {
        var overflow = visible.size + history.size + recentHistory.size - maximumSize
        overflow -= removeLowestPriorityTail(history, overflow)
        overflow -= removeLowestPriorityTail(recentHistory, overflow)
        removeLowestPriorityTail(visible, overflow)
    }

    /** Scheduling only: both recent arrivals and backlog keep HISTORY_PREFETCH authorization. */
    private fun removeNextHistory(forceBacklog: Boolean = false): PendingDecryptWork? {
        if (forceBacklog || recentPollsSinceBacklog >= historyFairnessVisibleBurst) {
            removeFirstAvailable(history, DecryptPriority.HISTORY)?.let { work ->
                recentPollsSinceBacklog = 0
                return work
            }
        }
        return removeFirstAvailable(recentHistory, DecryptPriority.HISTORY, recentHistory = true)?.also {
            recentPollsSinceBacklog = minOf(recentPollsSinceBacklog + 1, historyFairnessVisibleBurst)
        } ?: removePreferredHistory()?.also {
            recentPollsSinceBacklog = minOf(recentPollsSinceBacklog + 1, historyFairnessVisibleBurst)
        } ?: removeFirstAvailable(history, DecryptPriority.HISTORY)?.also { recentPollsSinceBacklog = 0 }
    }

    private fun removePreferredHistory(): PendingDecryptWork? {
        val key = preferredHistoryKeys.firstOrNull { key ->
            val entry = history[key]
            entry != null && key !in inFlight && entry.timelineScopeId == preferredHistoryScopeId &&
                entry.releaseGeneration == preferredHistoryGeneration
        } ?: return null
        return checkNotNull(history.remove(key)).toPendingWork(key, DecryptPriority.HISTORY)
    }

    /**
     * The maps are insertion ordered from highest to lowest decrypt priority. Remove a suffix in one
     * pass so a large reset does not turn capacity trimming into quadratic work on the diff thread.
     */
    private fun removeLowestPriorityTail(
        items: LinkedHashMap<String, PendingDecryptEntry>,
        requestedCount: Int,
    ): Int {
        val removeCount = requestedCount.coerceIn(0, items.size)
        if (removeCount == 0) return 0
        val iterator = items.entries.iterator()
        repeat(items.size - removeCount) { iterator.next() }
        repeat(removeCount) {
            iterator.next()
            iterator.remove()
        }
        return removeCount
    }

    private fun removeFirstAvailable(
        items: LinkedHashMap<String, PendingDecryptEntry>,
        priority: DecryptPriority,
        recentHistory: Boolean = false,
        measuredOnly: Boolean = false,
    ): PendingDecryptWork? {
        val iterator = items.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in inFlight) continue
            if (measuredOnly && entry.value.timelineScopeId != MatrixTimelineDiffProcessor.UNSCOPED_TIMELINE_RELEASE_SCOPE &&
                entry.value.releaseGeneration == 0L
            ) continue
            iterator.remove()
            return entry.value.toPendingWork(entry.key, priority, recentHistory)
        }
        return null
    }

    private fun PendingDecryptEntry.toPendingWork(key: String, priority: DecryptPriority, recentHistory: Boolean = false): PendingDecryptWork {
        LifecycleStageTiming.observed("timeline_queue_dispatch", (item as? MatrixTimelineItem.Event)?.eventId?.value.orEmpty(),
            "${timelineScopeId}#${priority}#${rawStateGeneration}")
        return PendingDecryptWork(
            key = key,
            item = item,
            priority = priority,
            rawStateGeneration = rawStateGeneration,
            timelineScopeId = timelineScopeId,
            releaseGeneration = releaseGeneration,
            recentHistory = recentHistory,
            recentProjection = recentProjection,
        )
    }

    private fun offerItem(
        item: MatrixTimelineItem,
        priority: DecryptPriority,
        force: Boolean,
        rawStateGeneration: Long,
        timelineScopeId: String,
        releaseGeneration: Long,
        preferRecentHistory: Boolean = false,
        preferRecentProjection: Boolean = false,
    ) {
        val key = MatrixTimelineDiffProcessor.itemKey(item) ?: return
        if (priority == DecryptPriority.HISTORY && !preferRecentHistory &&
            historyWindowKeys != null && key !in historyWindowKeys.orEmpty() && key !in visible
        ) return
        val latestGeneration = latestRawStateGenerationByKey[key]
        if (latestGeneration != null && rawStateGeneration < latestGeneration) return

        val wasVisible = key in visible
        val wasRecentProjection = sequenceOf(visible[key], history[key], recentHistory[key])
            .any { it?.timelineScopeId == timelineScopeId && it.recentProjection } ||
            inFlight[key]?.let { it.timelineScopeId == timelineScopeId && it.recentProjection } == true
        // A metadata replacement can arrive after a recent work item left the pending bucket
        // but before its projection commits. Preserve only its current-scope scheduling hint;
        // the new immutable raw item must still run the full HISTORY authorization path.
        val wasRecent = recentHistory[key]?.timelineScopeId == timelineScopeId ||
            inFlight[key]?.let { it.timelineScopeId == timelineScopeId && it.recentHistory } == true
        if (latestGeneration == null || rawStateGeneration > latestGeneration) {
            latestRawStateGenerationByKey[key] = rawStateGeneration
            visible.remove(key)
            history.remove(key)
            recentHistory.remove(key)
        }
        latestTimelineScopeIdByKey[key] = timelineScopeId

        val bufferedWork = awaitingPublication[key]
        if (!force && bufferedWork?.item == item &&
            bufferedWork.rawStateGeneration == rawStateGeneration && bufferedWork.timelineScopeId == timelineScopeId
        ) return

        val inFlightWork = inFlight[key]
        if (!force &&
            inFlightWork?.item == item &&
            inFlightWork.rawStateGeneration == rawStateGeneration &&
            inFlightWork.timelineScopeId == timelineScopeId
        ) {
            return
        }

        val entry = PendingDecryptEntry(item, rawStateGeneration, timelineScopeId, releaseGeneration,
            recentProjection = preferRecentProjection || preferRecentHistory || wasRecentProjection)
        LifecycleStageTiming.observed("timeline_queue_offer", (item as? MatrixTimelineItem.Event)?.eventId?.value.orEmpty(),
            "${timelineScopeId}#${if (priority == DecryptPriority.VISIBLE || wasVisible) DecryptPriority.VISIBLE else DecryptPriority.HISTORY}#${rawStateGeneration}")
        when (priority) {
            DecryptPriority.VISIBLE -> {
                history.remove(key)
                recentHistory.remove(key)
                visible[key] = entry
            }
            DecryptPriority.HISTORY -> {
                if (wasVisible || key in visible) {
                    // Preserve an already queued visible slot when a replacement moves just outside
                    // the heuristic viewport, but always replace its immutable raw value.
                    visible[key] = entry
                } else if (preferRecentHistory || wasRecent) {
                    history.remove(key)
                    recentHistory[key] = entry
                } else {
                    history[key] = entry
                }
            }
        }
    }
}

private data class PendingDecryptEntry(
    val item: MatrixTimelineItem,
    val rawStateGeneration: Long,
    val timelineScopeId: String,
    val releaseGeneration: Long,
    val recentProjection: Boolean = false,
)

internal data class PendingDecryptWork(
    val key: String,
    val item: MatrixTimelineItem,
    val priority: DecryptPriority,
    val rawStateGeneration: Long = 0L,
    val timelineScopeId: String = MatrixTimelineDiffProcessor.UNSCOPED_TIMELINE_RELEASE_SCOPE,
    val releaseGeneration: Long = 0L,
    val recentHistory: Boolean = false,
    val recentProjection: Boolean = false,
    val bootstrapHistory: Boolean = false,
)

private class DiffingResult(
    initialItems: List<MatrixTimelineItem>,
    private val previousDisplayItems: List<MatrixTimelineItem>,
) {
    private val items = initialItems.toMutableList()
    val resultInitialEventItemsByEventId = initialItems
        .filterIsInstance<MatrixTimelineItem.Event>()
        .mapNotNull { item -> item.eventId?.let { eventId -> eventId to item } }
        .toMap()
    private val changed = LinkedHashMap<String, MatrixTimelineItem>()
    var hasNewEventsFromSync: Boolean = false
        private set
    var hasMembershipChangeEventFromSync: Boolean = false
        private set

    fun items(): List<MatrixTimelineItem> = items.toList()

    fun changedItems(): List<MatrixTimelineItem> = changed.values.toList()

    fun displayItems(safeShellItems: List<MatrixTimelineItem>): List<MatrixTimelineItem> {
        val previousByKey = previousDisplayItems.mapNotNull { item ->
            MatrixTimelineDiffProcessor.itemKey(item)?.let { key -> key to item }
        }.toMap()
        val safeShellsByKey = safeShellItems.mapNotNull { item ->
            MatrixTimelineDiffProcessor.itemKey(item)?.let { key -> key to item }
        }.toMap()
        return items.map { item ->
            val key = MatrixTimelineDiffProcessor.itemKey(item)
            if (key == null) {
                item
            } else {
                val safeFallback = safeShellsByKey[key] ?: item
                if (key in changed) {
                    safeFallback
                } else {
                    previousByKey[key]
                        ?.takeUnless { previous -> previous == item && safeFallback != item }
                        ?: safeFallback
                }
            }
        }
    }

    fun add(item: MatrixTimelineItem) {
        processItem(item)
        items.add(item)
        markChanged(item)
    }

    fun add(index: Int, item: MatrixTimelineItem) {
        processItem(item)
        items.add(index, item)
        markChanged(item)
    }

    fun set(index: Int, item: MatrixTimelineItem) {
        processItem(item)
        items[index] = item
        markChanged(item)
    }

    fun removeAt(index: Int) {
        items.removeAt(index)
    }

    fun removeFirst() {
        items.removeFirstOrNull()
    }

    fun removeLast() {
        items.removeLastOrNull()
    }

    fun truncate(length: Int) {
        items.subList(length, items.size).clear()
    }

    fun clear() {
        items.clear()
        changed.clear()
    }

    private fun processItem(item: MatrixTimelineItem) {
        if (skipProcessing()) return
        when (item) {
            is MatrixTimelineItem.Event -> {
                if (item.event.origin == TimelineItemEventOrigin.SYNC) {
                    hasNewEventsFromSync = true
                    when (item.event.content) {
                        is RoomMembershipContent -> hasMembershipChangeEventFromSync = true
                        else -> Unit
                    }
                }
            }
            else -> Unit
        }
    }

    private fun markChanged(item: MatrixTimelineItem) {
        MatrixTimelineDiffProcessor.itemKey(item)?.let { key -> changed[key] = item }
    }

    private fun skipProcessing(): Boolean {
        return hasNewEventsFromSync && hasMembershipChangeEventFromSync
    }
}
