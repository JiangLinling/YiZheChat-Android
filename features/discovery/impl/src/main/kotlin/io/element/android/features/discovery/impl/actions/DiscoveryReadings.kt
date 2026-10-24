/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.discovery.impl.network.DiscoveryCodeClient
import io.element.android.features.discovery.impl.network.HttpDiscoveryInteractionClient
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.*
import io.element.android.libraries.di.SessionScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal class DiscoveryReadingCapacityException : IllegalStateException("Reading recovery is pending.")

internal data class DiscoveryReadingHandle(val scope: DiscoveryAccountScope, val visitId: String)

/** Reading facts use the same secure journal, ACTION protocol and receipt consumer as explicit interactions. */
@Inject
@SingleIn(SessionScope::class)
internal class DiscoveryReadings(
    private val access: DefaultDiscoveryAccess,
    private val gate: AndroidDiscoveryIdentityGate,
    private val authority: DiscoveryCodeClient,
    transport: PinnedDiscoveryTransport,
    clock: AndroidDiscoveryClock,
    private val store: SecureDiscoveryInteractionStore,
) {
    private val network = HttpDiscoveryInteractionClient(transport, clock)
    private val lifecycle = Mutex()
    private val networkWork = Mutex()
    private val live = mutableSetOf<DiscoveryReadingHandle>()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    suspend fun begin(articleId: Long, visitId: String = UUID.randomUUID().toString(), finishId: String = UUID.randomUUID().toString()): DiscoveryReadingHandle {
        var created: DiscoveryReadingHandle? = null
        try {
            return access.withSession {
                val identity = gate.current() ?: throw DiscoveryFailure(401)
                lifecycle.withLock {
                    val row = DiscoveryReadingVisit(visitId, finishId, articleId)
                    val handle = DiscoveryReadingHandle(identity, row.visitId)
                    if (live.add(handle)) created = handle
                    prepare(identity)
                    store.updateReadings(identity) { rows ->
                        val existing = rows.find { it.visitId == row.visitId }
                        if (existing != null) {
                            check(existing.articleId == articleId && existing.finishId == finishId && !existing.ended)
                            rows
                        } else {
                            if (rows.size >= 32) throw DiscoveryReadingCapacityException()
                            rows + row
                        }
                    }
                    current(identity)
                    handle
                }
            }
        } catch (failure: Exception) {
            // The session may expire after publication but before the caller receives its handle.
            created?.let { handle -> withContext(NonCancellable) { lifecycle.withLock { live.remove(handle) } } }
            throw failure
        }
    }

    suspend fun checkpoint(handle: DiscoveryReadingHandle, milliseconds: Long, ended: Boolean = false) {
        lifecycle.withLock {
            try {
                current(handle.scope)
                store.updateReadings(handle.scope) { rows -> rows.map { row ->
                    if (row.visitId != handle.visitId) row else {
                        check(!row.ended)
                        row.copy(milliseconds = maxOf(row.milliseconds, milliseconds.coerceIn(0, DiscoveryReadingVisit.MAX_MILLIS)), ended = ended)
                    }
                } }
            } finally {
                // A failed final write keeps the last durable checkpoint for later reconciliation.
                if (ended) live.remove(handle)
            }
        }
    }

    suspend fun synchronize(): Boolean {
        // Reading work never acquires the explicit interaction coordinator's mutex or its pending slot.
        if (!networkWork.tryLock()) return false
        try {
            return access.withSession { session ->
                val identity = gate.current() ?: throw DiscoveryFailure(401)
                lifecycle.withLock { prepare(identity) }
                val visits = store.readings(identity)
                for (snapshot in visits) {
                    current(identity)
                    var row = store.readings(identity).find { it.visitId == snapshot.visitId } ?: continue
                    if (!row.startApplied) {
                        val outcome = perform(identity, session, row.start())
                        current(identity)
                        store.updateReadings(identity) { rows ->
                            if (outcome == DiscoveryInteractionOutcome.REJECTED) rows.filterNot { it.visitId == row.visitId }
                            else rows.map { if (it.visitId == row.visitId) it.copy(startApplied = true) else it }
                        }
                        mutableRevision.value++
                        row = store.readings(identity).find { it.visitId == snapshot.visitId } ?: continue
                    }
                    if (row.ended) {
                        perform(identity, session, row.finish())
                        current(identity)
                        store.updateReadings(identity) { rows -> rows.filterNot { it.visitId == snapshot.visitId } }
                        mutableRevision.value++
                    }
                }
                store.readings(identity).none { !it.startApplied || it.ended }
            }
        } finally { networkWork.unlock() }
    }

    private suspend fun prepare(identity: DiscoveryAccountScope) {
        current(identity)
        store.updateReadings(identity) { rows -> rows.map { row ->
            if (!row.ended && DiscoveryReadingHandle(identity, row.visitId) !in live) row.copy(ended = true) else row
        } }
    }

    private suspend fun current(identity: DiscoveryAccountScope) {
        if (gate.current() != identity) throw CancellationException("Discovery identity changed")
    }

    private suspend fun perform(identity: DiscoveryAccountScope, session: DiscoverySession, command: DiscoveryArticleView): DiscoveryInteractionOutcome {
        current(identity)
        network.recovered(session, command)?.let { current(identity); return it }
        val body = command.bytes()
        return try {
            current(identity)
            val code = authority.issueAction(identity, DiscoveryActionTarget(command.operationId, sha256(body)))
            current(identity)
            network.send(session, code, command, body)
        } finally { body.fill(0) }
    }
}
