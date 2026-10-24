/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl.scheduling

import io.element.android.security.contentprotection.api.KmsTransportPriority
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Requests without an operation identity remain serial, but are not falsely treated as one FIFO operation. */
internal enum class LegacyKmsTransportOperation { CURRENT_MESSAGE, USER_MEDIA, FOREGROUND_BOOTSTRAP, BACKGROUND }

/** One bounded queue. Operation heads wait in the queue, never holding an active transport slot. */
internal class BoundedKmsTransportArbiter<T>(
    private val maximumPending: Int = 64,
    private val maximumActive: Int = 4,
    private val maximumBatch: Int = 32,
    private val foregroundReservedPending: Int = 4,
) {
    data class Entry<T>(val operationKey: Any, val priority: KmsTransportPriority, val value: T)
    class Batch<T> internal constructor(val entries: List<Entry<T>>) {
        val values: List<T> get() = entries.map(Entry<T>::value)
        internal val operationKey: Any get() = entries.first().operationKey
        internal val priority: KmsTransportPriority get() = entries.first().priority
    }

    private val lock = Any()
    private val changed = MutableStateFlow(0L)
    private val queued = mutableListOf<Entry<T>>()
    private val active = mutableMapOf<Any, Batch<T>>()
    private var consecutiveMessageBatches = 0

    init {
        require(maximumActive >= 2 && maximumPending > foregroundReservedPending && foregroundReservedPending > 0 && maximumBatch > 0)
    }

    fun tryEnqueue(entry: Entry<T>): Boolean = synchronized(lock) {
        val limit = if (entry.priority == KmsTransportPriority.CURRENT_MESSAGE) maximumPending else maximumPending - foregroundReservedPending
        if (queued.size >= limit) return false
        queued += entry
        changed.value += 1
        true
    }

    suspend fun enqueue(entry: Entry<T>) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val revision = changed.value
            if (tryEnqueue(entry)) return
            changed.first { it != revision }
        }
    }

    suspend fun nextBatch(windowMillis: Long): Batch<T> {
        while (true) {
            currentCoroutineContext().ensureActive()
            val revision = changed.value
            if (synchronized(lock) { queued.isEmpty() }) {
                changed.first { it != revision }
                continue
            }
            // No slot is held while collecting or waiting for an eligible operation head.
            delay(windowMillis)
            takeBatch()?.let { return it }
            changed.first { it != revision }
        }
    }

    fun takeBatch(): Batch<T>? = synchronized(lock) {
        if (active.size >= maximumActive) return null
        val backgroundActive = active.values.count { it.priority == KmsTransportPriority.BACKGROUND }
        val bootstrapActive = active.values.count { it.priority == KmsTransportPriority.FOREGROUND_BOOTSTRAP }
        val nonMessageActive = active.values.count { it.priority != KmsTransportPriority.CURRENT_MESSAGE }
        val seen = mutableSetOf<Any>()
        val eligible = queued.asSequence().filter { seen.add(it.operationKey) }.filter {
            it.operationKey !in active &&
                (it.operationKey !is LegacyKmsTransportOperation || active.keys.none { key -> key is LegacyKmsTransportOperation }) && when (it.priority) {
                KmsTransportPriority.CURRENT_MESSAGE -> true
                KmsTransportPriority.USER_MEDIA -> nonMessageActive < maximumActive - 1
                KmsTransportPriority.FOREGROUND_BOOTSTRAP -> bootstrapActive < 2 && nonMessageActive < maximumActive - 1
                KmsTransportPriority.BACKGROUND -> backgroundActive < 1 && nonMessageActive < maximumActive - 1
            }
        }.toList()
        // Bound background starvation without allowing background to spend the final foreground slot.
        val fairHead = if (consecutiveMessageBatches >= 8) eligible.firstOrNull { it.priority != KmsTransportPriority.CURRENT_MESSAGE } else null
        val head = fairHead ?: eligible.minByOrNull { it.priority.ordinal } ?: return null
        consecutiveMessageBatches = if (head.priority == KmsTransportPriority.CURRENT_MESSAGE) (consecutiveMessageBatches + 1).coerceAtMost(8) else 0
        // Never jump over a different-priority earlier entry of the same operation.
        val entries = queued.asSequence().filter { it.operationKey == head.operationKey }
            .takeWhile { it.priority == head.priority }.take(maximumBatch).toList()
        entries.forEach { queued.remove(it) }
        Batch(entries).also {
            active[head.operationKey] = it
            changed.value += 1
        }
    }

    fun complete(batch: Batch<T>) = synchronized(lock) {
        check(active[batch.operationKey] === batch) { "Only the active batch owns its transport slot." }
        active.remove(batch.operationKey)
        changed.value += 1
    }

    /** Returns only queued resources. Active HTTP continues owning its bytes until its own finally. */
    fun removeQueued(predicate: (T) -> Boolean): List<T> = synchronized(lock) {
        val removed = queued.filter { predicate(it.value) }
        queued.removeAll(removed.toSet())
        if (removed.isNotEmpty()) changed.value += 1
        removed.map(Entry<T>::value)
    }

    fun pendingCount(): Int = synchronized(lock) { queued.size }
    fun activeCount(): Int = synchronized(lock) { active.size }
}
