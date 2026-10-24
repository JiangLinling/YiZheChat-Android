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
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/** Guest-only intent recovery; host login and lifecycle remain observers/dependencies, never mutation targets. */
@Inject
@SingleIn(SessionScope::class)
internal class DiscoveryInteractions(
    private val access: DefaultDiscoveryAccess,
    private val gate: AndroidDiscoveryIdentityGate,
    private val authority: DiscoveryCodeClient,
    transport: PinnedDiscoveryTransport,
    clock: AndroidDiscoveryClock,
    private val store: SecureDiscoveryInteractionStore,
) {
    private val network = HttpDiscoveryInteractionClient(transport, clock)
    private val lock = Mutex()

    suspend fun pending(): Boolean = access.withSession {
        store.pending(gate.current() ?: throw DiscoveryFailure(401)) != null
    }

    suspend fun interact(targetId: Long, kind: DiscoveryInteractionKind, enabled: Boolean) =
        execute(DiscoveryArticleInteraction(UUID.randomUUID().toString(), targetId, kind, enabled))

    suspend fun pendingComment(articleId: Long): DiscoveryArticleCommentCommand? = access.withSession {
        (store.pending(gate.current() ?: throw DiscoveryFailure(401)) as? DiscoveryArticleCommentCommand)?.takeIf { it.articleId == articleId }
    }

    suspend fun comment(articleId: Long, content: String, replyToCommentId: Long?) =
        execute(DiscoveryArticleCommentCommand(UUID.randomUUID().toString(), articleId, content, replyToCommentId))

    suspend fun recover() = execute(null)

    private suspend fun execute(newCommand: DiscoveryExplicitCommand?): DiscoveryInteractionOutcome {
        // No local network queue and no double tap creating a second business intent.
        check(lock.tryLock()) { "Discovery interaction is already running." }
        try {
            return access.withSession { session ->
                val identity = gate.current() ?: throw DiscoveryFailure(401)
                suspend fun current() {
                    if (gate.current() != identity) throw CancellationException("Discovery identity changed")
                }
                val pending = store.pending(identity)
                check(pending == null || newCommand == null) { "Confirm the pending interaction first." }
                val command = pending ?: newCommand ?: return@withSession DiscoveryInteractionOutcome.NOTHING_PENDING
                // A course operation must be recovered by its owner; article screens cannot consume its receipt.
                check(command !is DiscoveryBusinessCommand) { "A business operation requires confirmation." }
                if (pending == null) store.stage(identity, command)
                current()
                val recovered = if (pending != null) network.recovered(session, command) else null
                val outcome = recovered ?: run {
                    val body = command.bytes()
                    try {
                        current()
                        val code = authority.issueAction(identity, DiscoveryActionTarget(command.operationId, sha256(body)))
                        current()
                        network.send(session, code, command, body)
                    } finally { body.fill(0) }
                }
                current()
                store.complete(identity, command.operationId)
                outcome
            }
        } finally { lock.unlock() }
    }
}
