/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperation
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Projection only: the coordinator retains its independent monotonic authority guard. */
internal class LifecycleOperationProjections {
    private val lock = Any()
    private var entries = persistentMapOf<String, LifecycleOperation>()
    private val mutableState = MutableStateFlow<Map<String, LifecycleOperation>>(entries)
    private val observers = mutableMapOf<String, MutableSet<SendChannel<LifecycleOperation?>>>()
    private val latest = mutableMapOf<FullAppLifecycleId, LifecycleOperation>()
    private val lifecycleEntries = mutableMapOf<FullAppLifecycleId, PersistentMap<String, LifecycleOperation>>()
    private val lifecycleObservers = mutableMapOf<FullAppLifecycleId, MutableSet<SendChannel<Map<String, LifecycleOperation>>>>()
    val state: StateFlow<Map<String, LifecycleOperation>> = mutableState.asStateFlow()
    val observerCount: Int get() = synchronized(lock) { observers.values.sumOf { it.size } }

    fun observe(id: String): Flow<LifecycleOperation?> = callbackFlow {
        synchronized(lock) {
            observers.getOrPut(id) { mutableSetOf() }.add(channel)
            trySend(entries[id])
        }
        awaitClose {
            synchronized(lock) {
                observers[id]?.let { listeners ->
                    listeners.remove(channel)
                    if (listeners.isEmpty()) observers.remove(id)
                }
            }
        }
    }.buffer(Channel.CONFLATED).distinctUntilChanged()

    fun observeLifecycle(id: FullAppLifecycleId): Flow<Map<String, LifecycleOperation>> = callbackFlow {
        synchronized(lock) {
            lifecycleObservers.getOrPut(id) { mutableSetOf() }.add(channel)
            trySend(lifecycleEntries[id].orEmpty())
        }
        awaitClose {
            synchronized(lock) {
                lifecycleObservers[id]?.let { listeners ->
                    listeners.remove(channel)
                    if (listeners.isEmpty()) lifecycleObservers.remove(id)
                }
            }
        }
    }.buffer(Channel.CONFLATED).distinctUntilChanged()

    fun put(operation: LifecycleOperation) = synchronized(lock) {
        if (entries[operation.operationId] == operation) return@synchronized
        check(entries[operation.operationId]?.lifecycleId?.let { it == operation.lifecycleId } != false) {
            "An original operation cannot change lifecycle."
        }
        entries = entries.put(operation.operationId, operation)
        val lifecycle = (lifecycleEntries[operation.lifecycleId] ?: persistentMapOf()).put(operation.operationId, operation)
        lifecycleEntries[operation.lifecycleId] = lifecycle
        val previous = latest[operation.lifecycleId]
        if (previous?.operationId == operation.operationId && operation.updatedAtEpochMillis < previous.updatedAtEpochMillis) {
            latest[operation.lifecycleId] = lifecycle.values.maxBy { it.updatedAtEpochMillis }
        } else if (previous == null || previous.operationId == operation.operationId ||
            operation.updatedAtEpochMillis > previous.updatedAtEpochMillis) {
            latest[operation.lifecycleId] = operation
        }
        // Wake the one pending bubble before notifying consumers of the full diagnostic projection.
        observers[operation.operationId]?.forEach { it.trySend(operation) }
        lifecycleObservers[operation.lifecycleId]?.forEach { it.trySend(lifecycle) }
        mutableState.value = entries
    }

    fun replace(operations: Map<String, LifecycleOperation>) = synchronized(lock) {
        val changed = observers.keys.filter { entries[it] != operations[it] }
        entries = operations.toPersistentMap()
        latest.clear()
        lifecycleEntries.clear()
        entries.values.forEach { operation ->
            lifecycleEntries[operation.lifecycleId] = (lifecycleEntries[operation.lifecycleId] ?: persistentMapOf())
                .put(operation.operationId, operation)
            val previous = latest[operation.lifecycleId]
            if (previous == null || operation.updatedAtEpochMillis > previous.updatedAtEpochMillis) {
                latest[operation.lifecycleId] = operation
            }
        }
        changed.forEach { id -> observers[id]?.forEach { it.trySend(entries[id]) } }
        lifecycleObservers.forEach { (id, listeners) -> listeners.forEach { it.trySend(lifecycleEntries[id].orEmpty()) } }
        mutableState.value = entries
    }

    fun latestByLifecycle(): Map<FullAppLifecycleId, LifecycleOperation> = synchronized(lock) { latest.toMap() }
}
