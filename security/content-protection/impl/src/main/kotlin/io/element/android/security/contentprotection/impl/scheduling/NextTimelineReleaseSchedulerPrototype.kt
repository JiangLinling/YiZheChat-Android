/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl.scheduling

import java.util.ArrayDeque

/**
 * Local, deterministic scheduler for ordering timeline work before the existing KMS release batcher.
 *
 * The timeline adapter wires only foreground-visible and history-prefetch work into this scheduler. It does not persist
 * reliable work and is not a replacement for a lifecycle-owned durable queue.
 */
internal class NextTimelineReleaseSchedulerPrototype<T>(
    private val config: NextTimelineReleaseSchedulerConfig,
) {
    private val pendingByClass = NextTimelineReleaseClass.entries.associateWith { ArrayDeque<NextTimelineReleaseRequest<T>>() }
    private val inFlight = linkedSetOf<NextTimelineReleaseDispatchTicket<T>>()
    private val admittedKeys = mutableSetOf<NextTimelineReleaseDeduplicationKey>()
    private var pendingCount = 0
    private var laneIndex = 0
    private var remainingLaneWeight = config.weightFor(DISPATCH_ORDER[laneIndex])

    @Synchronized
    fun enqueue(request: NextTimelineReleaseRequest<T>): NextTimelineReleaseAdmission {
        if (request.deduplicationKey in admittedKeys) {
            return when (request.releaseClass) {
                NextTimelineReleaseClass.RELIABLE_SECURITY -> NextTimelineReleaseAdmission.RetryRequired(
                    requestId = request.requestId,
                    operationId = requireNotNull(request.reliableOperationId),
                )
                NextTimelineReleaseClass.FOREGROUND_VISIBLE,
                NextTimelineReleaseClass.HISTORY_PREFETCH -> NextTimelineReleaseAdmission.SkippedDuplicate(request.requestId)
            }
        }
        if (request.releaseClass == NextTimelineReleaseClass.HISTORY_PREFETCH &&
            nonForegroundPendingCount() >= config.maximumPending - config.foregroundReservedPending
        ) {
            return NextTimelineReleaseAdmission.SkippedBackpressure(request.requestId)
        }
        if (pendingCount >= config.maximumPending) {
            return when (request.releaseClass) {
                NextTimelineReleaseClass.RELIABLE_SECURITY -> NextTimelineReleaseAdmission.RetryRequired(
                    requestId = request.requestId,
                    operationId = requireNotNull(request.reliableOperationId),
                )
                NextTimelineReleaseClass.FOREGROUND_VISIBLE,
                NextTimelineReleaseClass.HISTORY_PREFETCH -> NextTimelineReleaseAdmission.SkippedBackpressure(request.requestId)
            }
        }
        admittedKeys += request.deduplicationKey
        pendingByClass.getValue(request.releaseClass).addLast(request)
        pendingCount += 1
        return NextTimelineReleaseAdmission.Enqueued
    }

    /**
     * Reclassifies only work that has not been dispatched. In-flight and reliable-security work keep
     * their original owner and authorization. History overflow is removed from the tail so the
     * configured foreground reserve remains available without making the queue unbounded.
     */
    @Synchronized
    fun reprioritizeViewport(
        scopeId: String,
        currentGeneration: Long,
        visibleRequestIds: List<String>,
    ): NextTimelineViewportReprioritization<T> {
        require(scopeId.isNotBlank()) { "scopeId must not be blank" }
        require(currentGeneration >= 0) { "currentGeneration must not be negative" }
        val visibleOrder = visibleRequestIds.distinct().withIndex().associate { it.value to it.index }
        val matching = mutableListOf<NextTimelineReleaseRequest<T>>()
        CANCELLABLE_CLASSES.forEach { releaseClass ->
            val queue = pendingByClass.getValue(releaseClass)
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val request = iterator.next()
                if (request.viewportIdentity?.scopeId == scopeId) {
                    iterator.remove()
                    matching += request
                }
            }
        }

        val updated = matching.map { request ->
            val nextClass = if (request.requestId in visibleOrder) {
                NextTimelineReleaseClass.FOREGROUND_VISIBLE
            } else {
                NextTimelineReleaseClass.HISTORY_PREFETCH
            }
            request.copy(
                releaseClass = nextClass,
                viewportIdentity = requireNotNull(request.viewportIdentity).copy(generation = currentGeneration),
            )
        }
        updated
            .filter { it.releaseClass == NextTimelineReleaseClass.FOREGROUND_VISIBLE }
            .sortedBy { visibleOrder.getValue(it.requestId) }
            .forEach(pendingByClass.getValue(NextTimelineReleaseClass.FOREGROUND_VISIBLE)::addLast)
        updated
            .filter { it.releaseClass == NextTimelineReleaseClass.HISTORY_PREFETCH }
            .forEach(pendingByClass.getValue(NextTimelineReleaseClass.HISTORY_PREFETCH)::addLast)

        val removedHistory = mutableListOf<NextTimelineReleaseRequest<T>>()
        val history = pendingByClass.getValue(NextTimelineReleaseClass.HISTORY_PREFETCH)
        while (nonForegroundPendingCount() > config.maximumPending - config.foregroundReservedPending && history.isNotEmpty()) {
            history.removeLast().also { removed ->
                pendingCount -= 1
                admittedKeys.remove(removed.deduplicationKey)
                removedHistory += removed
            }
        }
        return NextTimelineViewportReprioritization(
            updatedCount = updated.size,
            removedHistory = removedHistory,
        )
    }

    @Synchronized
    fun takeBatch(
        limit: Int = config.maximumBatchSize,
        allowedClasses: Set<NextTimelineReleaseClass> = NextTimelineReleaseClass.entries.toSet(),
    ): List<NextTimelineReleaseDispatchTicket<T>> {
        require(limit in 1..config.maximumBatchSize) {
            "limit must be between 1 and configured maximumBatchSize"
        }
        return buildList {
            repeat(limit) {
                val request = pollNext(allowedClasses) ?: return@buildList
                val ticket = NextTimelineReleaseDispatchTicket(
                    request = request,
                    onTerminal = ::removeCompletedTicket,
                    onResolved = ::removeResolvedTicket,
                )
                inFlight += ticket
                add(ticket)
            }
        }
    }

    /**
     * Cancels only viewport-owned work from older generations. Removed pending requests are returned so their owner can
     * finish any non-sensitive bookkeeping. Reliable work has no viewport generation and is never touched here.
     */
    @Synchronized
    fun advanceViewportGeneration(scopeId: String, currentGeneration: Long): NextTimelineGenerationCancellation<T> {
        require(scopeId.isNotBlank()) { "scopeId must not be blank" }
        require(currentGeneration >= 0) { "currentGeneration must not be negative" }

        val removedPending = buildList {
            CANCELLABLE_CLASSES.forEach { releaseClass ->
                val queue = pendingByClass.getValue(releaseClass)
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    val request = iterator.next()
                    val identity = requireNotNull(request.viewportIdentity)
                    if (identity.scopeId == scopeId && identity.generation != currentGeneration) {
                        iterator.remove()
                        pendingCount -= 1
                        admittedKeys.remove(request.deduplicationKey)
                        add(request)
                    }
                }
            }
        }
        val cancelledInFlight = inFlight.toList().filter { ticket ->
            ticket.cancelIfGenerationIsStale(scopeId, currentGeneration)
        }
        return NextTimelineGenerationCancellation(removedPending, cancelledInFlight)
    }

    @Synchronized
    fun cancelViewportRequests(scopeId: String, requestIds: Set<String>): NextTimelineGenerationCancellation<T> =
        cancelViewportWork { identity, request ->
            identity.scopeId == scopeId && request.requestId in requestIds
        }

    @Synchronized
    fun cancelViewportScope(scopeId: String): NextTimelineGenerationCancellation<T> =
        cancelViewportWork { identity, _ -> identity.scopeId == scopeId }

    @Synchronized
    fun pendingCount(): Int = pendingCount

    @Synchronized
    fun inFlightCount(): Int = inFlight.size

    @Synchronized
    fun pendingCount(releaseClass: NextTimelineReleaseClass): Int = pendingByClass.getValue(releaseClass).size

    @Synchronized
    private fun removeCompletedTicket(ticket: NextTimelineReleaseDispatchTicket<T>) {
        inFlight.remove(ticket)
    }

    @Synchronized
    private fun removeResolvedTicket(ticket: NextTimelineReleaseDispatchTicket<T>) {
        admittedKeys.remove(ticket.request.deduplicationKey)
    }

    private fun cancelViewportWork(
        predicate: (NextTimelineViewportIdentity, NextTimelineReleaseRequest<T>) -> Boolean,
    ): NextTimelineGenerationCancellation<T> {
        val removedPending = buildList {
            CANCELLABLE_CLASSES.forEach { releaseClass ->
                val queue = pendingByClass.getValue(releaseClass)
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    val request = iterator.next()
                    val identity = requireNotNull(request.viewportIdentity)
                    if (predicate(identity, request)) {
                        iterator.remove()
                        pendingCount -= 1
                        admittedKeys.remove(request.deduplicationKey)
                        add(request)
                    }
                }
            }
        }
        val cancelledInFlight = inFlight.toList().filter { ticket ->
            val identity = ticket.request.viewportIdentity ?: return@filter false
            predicate(identity, ticket.request) && ticket.cancel()
        }
        return NextTimelineGenerationCancellation(removedPending, cancelledInFlight)
    }

    private fun pollNext(allowedClasses: Set<NextTimelineReleaseClass>): NextTimelineReleaseRequest<T>? {
        repeat(DISPATCH_ORDER.size) {
            val releaseClass = DISPATCH_ORDER[laneIndex]
            val queue = pendingByClass.getValue(releaseClass)
            if (releaseClass in allowedClasses && queue.isNotEmpty()) {
                val request = queue.removeFirst()
                pendingCount -= 1
                remainingLaneWeight -= 1
                if (remainingLaneWeight == 0) advanceLane()
                return request
            }
            advanceLane()
        }
        return null
    }

    private fun advanceLane() {
        laneIndex = (laneIndex + 1) % DISPATCH_ORDER.size
        remainingLaneWeight = config.weightFor(DISPATCH_ORDER[laneIndex])
    }

    private fun nonForegroundPendingCount(): Int = pendingCount -
        pendingByClass.getValue(NextTimelineReleaseClass.FOREGROUND_VISIBLE).size

    private companion object {
        val DISPATCH_ORDER = listOf(
            NextTimelineReleaseClass.FOREGROUND_VISIBLE,
            NextTimelineReleaseClass.RELIABLE_SECURITY,
            NextTimelineReleaseClass.HISTORY_PREFETCH,
        )
        val CANCELLABLE_CLASSES = listOf(
            NextTimelineReleaseClass.FOREGROUND_VISIBLE,
            NextTimelineReleaseClass.HISTORY_PREFETCH,
        )
    }
}

internal data class NextTimelineReleaseSchedulerConfig(
    val maximumPending: Int,
    val maximumBatchSize: Int,
    val foregroundWeight: Int,
    val historyWeight: Int,
    val reliableWeight: Int,
    val foregroundReservedPending: Int = 0,
) {
    init {
        require(maximumPending > 0) { "maximumPending must be positive" }
        require(maximumBatchSize in 1..maximumPending) { "maximumBatchSize must be between 1 and maximumPending" }
        require(foregroundWeight > 0) { "foregroundWeight must be positive" }
        require(historyWeight > 0) { "historyWeight must be positive" }
        require(reliableWeight > 0) { "reliableWeight must be positive" }
        require(foregroundReservedPending in 0 until maximumPending) {
            "foregroundReservedPending must be between zero and maximumPending"
        }
    }

    fun weightFor(releaseClass: NextTimelineReleaseClass): Int = when (releaseClass) {
        NextTimelineReleaseClass.FOREGROUND_VISIBLE -> foregroundWeight
        NextTimelineReleaseClass.HISTORY_PREFETCH -> historyWeight
        NextTimelineReleaseClass.RELIABLE_SECURITY -> reliableWeight
    }
}

internal data class NextTimelineViewportReprioritization<T>(
    val updatedCount: Int,
    val removedHistory: List<NextTimelineReleaseRequest<T>>,
)

internal enum class NextTimelineReleaseClass {
    FOREGROUND_VISIBLE,
    HISTORY_PREFETCH,
    RELIABLE_SECURITY,
}

internal data class NextTimelineViewportIdentity(
    val scopeId: String,
    val roomId: String,
    val eventId: String,
    val contentVersion: String,
    val generation: Long,
) {
    init {
        require(scopeId.isNotBlank()) { "scopeId must not be blank" }
        require(roomId.isNotBlank()) { "roomId must not be blank" }
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(contentVersion.isNotBlank()) { "contentVersion must not be blank" }
        require(generation >= 0) { "generation must not be negative" }
    }
}

internal data class NextTimelineReleaseRequest<T>(
    val requestId: String,
    val deduplicationKey: NextTimelineReleaseDeduplicationKey,
    val releaseClass: NextTimelineReleaseClass,
    val payload: T,
    val viewportIdentity: NextTimelineViewportIdentity? = null,
    val reliableOperationId: String? = null,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        when (releaseClass) {
            NextTimelineReleaseClass.RELIABLE_SECURITY -> {
                require(viewportIdentity == null) { "reliable work must not be owned by a viewport" }
                require(!reliableOperationId.isNullOrBlank()) { "reliable work must retain its lifecycle operation id" }
            }
            NextTimelineReleaseClass.FOREGROUND_VISIBLE,
            NextTimelineReleaseClass.HISTORY_PREFETCH -> {
                require(viewportIdentity != null) { "viewport work must have a complete viewport identity" }
                require(reliableOperationId == null) { "viewport work must not masquerade as reliable lifecycle work" }
            }
        }
    }
}

internal data class NextTimelineReleaseDeduplicationKey(
    val requestIdentity: String,
    val contentIdentity: String,
    val authorizationIdentity: String,
) {
    init {
        require(requestIdentity.isNotBlank()) { "requestIdentity must not be blank" }
        require(contentIdentity.isNotBlank()) { "contentIdentity must not be blank" }
        require(authorizationIdentity.isNotBlank()) { "authorizationIdentity must not be blank" }
    }
}

internal sealed interface NextTimelineReleaseAdmission {
    data object Enqueued : NextTimelineReleaseAdmission

    /** The request was not accepted. Its caller remains its owner and may create a later viewport intent. */
    data class SkippedBackpressure(val requestId: String) : NextTimelineReleaseAdmission

    data class SkippedDuplicate(val requestId: String) : NextTimelineReleaseAdmission

    /** The request was not accepted or renumbered. Its lifecycle owner must retry the same operation durably. */
    data class RetryRequired(
        val requestId: String,
        val operationId: String,
    ) : NextTimelineReleaseAdmission
}

internal data class NextTimelineGenerationCancellation<T>(
    val removedPending: List<NextTimelineReleaseRequest<T>>,
    val cancelledInFlight: List<NextTimelineReleaseDispatchTicket<T>>,
) {
    val cancelledInFlightCount: Int get() = cancelledInFlight.size
}

internal class NextTimelineSensitiveResult<R : Any>(
    value: R,
    private val clear: (R) -> Unit,
) {
    private val lock = Any()
    private var ownedValue: R? = value
    private var transferred = false

    fun transferToOwner(): R = synchronized(lock) {
        check(!transferred && ownedValue != null) { "sensitive result ownership has already ended" }
        transferred = true
        ownedValue.also { ownedValue = null }!!
    }

    fun clearExactlyOnce(): Boolean {
        val valueToClear = synchronized(lock) {
            if (transferred) return false
            ownedValue.also { ownedValue = null }
        } ?: return false
        clear(valueToClear)
        return true
    }
}

internal sealed interface NextTimelineResultDisposition<out R> {
    /** Sensitive-result cleanup responsibility has transferred to the receiving owner with [value]. */
    data class Delivered<R>(val value: R) : NextTimelineResultDisposition<R>

    data object ClearedStale : NextTimelineResultDisposition<Nothing>
    data object ClearedDuplicate : NextTimelineResultDisposition<Nothing>
}

internal class NextTimelineReleaseDispatchTicket<T> internal constructor(
    val request: NextTimelineReleaseRequest<T>,
    private val onTerminal: (NextTimelineReleaseDispatchTicket<T>) -> Unit,
    private val onResolved: (NextTimelineReleaseDispatchTicket<T>) -> Unit,
) {
    private val lock = Any()
    private var state = State.DISPATCHED
    private var terminalReported = false
    private var resolutionReported = false

    /**
     * Accepts a result only for the exact scope/room/event/contentVersion/generation that was dispatched. A rejected or
     * duplicate result is cleared exactly once by [NextTimelineSensitiveResult].
     */
    fun <R : Any> complete(
        result: NextTimelineSensitiveResult<R>,
        currentViewportIdentity: NextTimelineViewportIdentity?,
    ): NextTimelineResultDisposition<R> {
        val action = synchronized(lock) {
            when (state) {
                State.DISPATCHED -> {
                    val isCurrent = request.releaseClass == NextTimelineReleaseClass.RELIABLE_SECURITY ||
                        request.viewportIdentity == currentViewportIdentity
                    state = if (isCurrent) State.DELIVERED else State.CLEARED
                    if (isCurrent) CompletionAction.DELIVER else CompletionAction.CLEAR_STALE
                }
                State.CANCELLED -> {
                    state = State.CLEARED
                    CompletionAction.CLEAR_STALE
                }
                State.DELIVERED,
                State.CLEARED -> CompletionAction.CLEAR_DUPLICATE
            }
        }

        return when (action) {
            CompletionAction.DELIVER -> {
                reportTerminalOnce()
                reportResolutionOnce()
                NextTimelineResultDisposition.Delivered(result.transferToOwner())
            }
            CompletionAction.CLEAR_STALE -> {
                reportTerminalOnce()
                reportResolutionOnce()
                result.clearExactlyOnce()
                NextTimelineResultDisposition.ClearedStale
            }
            CompletionAction.CLEAR_DUPLICATE -> {
                result.clearExactlyOnce()
                NextTimelineResultDisposition.ClearedDuplicate
            }
        }
    }

    internal fun cancelIfGenerationIsStale(scopeId: String, currentGeneration: Long): Boolean {
        val cancelled = synchronized(lock) {
            val identity = request.viewportIdentity
            if (state == State.DISPATCHED && identity != null && identity.scopeId == scopeId && identity.generation != currentGeneration) {
                state = State.CANCELLED
                true
            } else {
                false
            }
        }
        if (cancelled) reportTerminalOnce()
        return cancelled
    }

    internal fun cancel(): Boolean {
        val cancelled = synchronized(lock) {
            if (state == State.DISPATCHED) {
                state = State.CANCELLED
                true
            } else {
                false
            }
        }
        if (cancelled) reportTerminalOnce()
        return cancelled
    }

    /** Ends a dispatch that produced no sensitive result, for example a transport failure. */
    fun fail(): Boolean {
        val failed = synchronized(lock) {
            when (state) {
                State.DISPATCHED,
                State.CANCELLED -> {
                    state = State.CLEARED
                    true
                }
                State.DELIVERED,
                State.CLEARED -> false
            }
        }
        if (failed) {
            reportTerminalOnce()
            reportResolutionOnce()
        }
        return failed
    }

    private fun reportTerminalOnce() {
        val shouldReport = synchronized(lock) {
            if (terminalReported) false else {
                terminalReported = true
                true
            }
        }
        if (shouldReport) onTerminal(this)
    }

    private fun reportResolutionOnce() {
        val shouldReport = synchronized(lock) {
            if (resolutionReported) false else {
                resolutionReported = true
                true
            }
        }
        if (shouldReport) onResolved(this)
    }

    private enum class State {
        DISPATCHED,
        CANCELLED,
        DELIVERED,
        CLEARED,
    }

    private enum class CompletionAction {
        DELIVER,
        CLEAR_STALE,
        CLEAR_DUPLICATE,
    }
}
