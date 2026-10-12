/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.core.coroutine.childScope
import io.element.android.libraries.matrix.api.core.UniqueId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.Timeline
import java.util.concurrent.atomic.AtomicLong

/**
 * This class is responsible for subscribing to a timeline and post the items/diffs to the timelineDiffProcessor.
 * It will also trigger a callback when a new synced event is received.
 */
internal class TimelineItemsSubscriber(
    timelineCoroutineScope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val timeline: Timeline,
    private val timelineDiffProcessor: MatrixTimelineDiffProcessor,
) {
    private var subscriptionCount = 0
    @Volatile
    private var activeCommitScope: TimelineCommitScope? = null
    private val mutex = Mutex()
    private val viewportGeneration = AtomicLong()

    private val coroutineScope = timelineCoroutineScope.childScope(dispatcher, "TimelineItemsSubscriber")

    /**
     * Add a subscription to the timeline and start posting items/diffs to the timelineDiffProcessor.
     * It will also trigger a callback when a new synced event is received.
     */
    suspend fun subscribeIfNeeded() = mutex.withLock {
        if (subscriptionCount == 0) {
            val commitScope = TimelineCommitScope(nextCommitScope.incrementAndGet())
            activeCommitScope = commitScope
            timelineDiffProcessor.activateCommitScope(commitScope)
            coroutineScope.launch {
                val diffQueue = Channel<List<org.matrix.rustcomponents.sdk.TimelineDiff>>(capacity = DIFF_QUEUE_CAPACITY)
                launch {
                    try {
                        timeline.timelineDiffFlow().collect { diffs ->
                            // Backpressure is intentional: diff streams are lossless and must never be conflated.
                            diffQueue.send(diffs)
                        }
                    } finally {
                        diffQueue.close()
                    }
                }
                launch {
                    val coordinator = TimelineStructuralTransactionCoordinator(commitScope)
                    for (diffs in diffQueue) {
                        val transaction = coordinator.accept(diffs) ?: continue
                        timelineDiffProcessor.commitDiffs(transaction)
                    }
                }
            }
        }
        subscriptionCount++
    }

    /**
     * Remove a subscription to the timeline and unsubscribe if needed.
     * The timeline will be unsubscribed when the last subscription is removed.
     * If the timelineCoroutineScope is cancelled, the timeline will be unsubscribed automatically.
     */
    suspend fun unsubscribeIfNeeded() = mutex.withLock {
        when (subscriptionCount) {
            0 -> return@withLock
            1 -> {
                val closingScope = activeCommitScope
                activeCommitScope = null
                closingScope?.let { timelineDiffProcessor.invalidateCommitScope(it) }
                coroutineScope.coroutineContext.cancelChildren()
            }
        }
        subscriptionCount--
    }

    /**
     * Captures the commit owner before crossing the asynchronous dispatcher boundary. A delayed
     * update from an unsubscribed viewport therefore still carries the old scope and is rejected by
     * the processor after a resubscribe.
     */
    fun updateViewport(visibleItemIds: List<UniqueId>) {
        val commitScope = activeCommitScope ?: return
        val generation = viewportGeneration.incrementAndGet()
        coroutineScope.launch {
            timelineDiffProcessor.updateViewport(commitScope, generation, visibleItemIds)
        }
    }

    private companion object {
        const val DIFF_QUEUE_CAPACITY = 32
        val nextCommitScope = AtomicLong()
    }
}
