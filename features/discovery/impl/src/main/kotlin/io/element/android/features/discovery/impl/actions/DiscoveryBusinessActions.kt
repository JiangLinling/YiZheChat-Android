/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.discovery.impl.network.DiscoveryCodeClient
import io.element.android.features.discovery.impl.network.DiscoveryTarget
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.*
import io.element.android.libraries.di.SessionScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

/** Uses the existing Vault journal, ACTION authority and operation lookup. Does not create another outbox or lifecycle. */
@Inject
@SingleIn(SessionScope::class)
internal class DiscoveryBusinessActions(
    private val access: DefaultDiscoveryAccess,
    private val gate: AndroidDiscoveryIdentityGate,
    private val authority: DiscoveryCodeClient,
    private val transport: PinnedDiscoveryTransport,
    private val clock: AndroidDiscoveryClock,
    private val store: SecureDiscoveryInteractionStore,
) {
    private val lock = Mutex()

    suspend fun pending(): DiscoveryBusinessCommand? = access.withSession {
        val identity = gate.current() ?: throw DiscoveryFailure(401)
        val command = store.pending(identity)
        check(command == null || command is DiscoveryBusinessCommand) { "Another Discovery operation requires confirmation." }
        command as? DiscoveryBusinessCommand
    }

    /**
     * The caller parses the entire strict business receipt and reconciles any current enrollment query BEFORE retiring the intent.
     * Unknown outcomes, parser failures and cancellation preserve exactly the original command for recovery.
     */
    suspend fun <T> execute(newCommand: DiscoveryBusinessCommand?, accepts: (DiscoveryBusinessCommand) -> Boolean = { true },
        confirmedEnrollment: (T) -> DiscoveryEnrollmentResultLocator? = { null },
        confirmedPerson: (T) -> DiscoveryPersonResultLocator? = { null },
        confirmedMall: (T) -> DiscoveryMallResultLocator? = { null },
        confirmedResult: (T) -> DiscoveryConfirmedResult? = { null },
        consume: suspend (DiscoveryBusinessCommand, JsonObject) -> T): T? {
        check(lock.tryLock()) { "A course operation is already running." }
        try {
            return access.withSession { session ->
                val identity = gate.current() ?: throw DiscoveryFailure(401)
                suspend fun current() {
                    if (gate.current() != identity) throw CancellationException("Discovery identity changed")
                }
                val pending = store.pending(identity)
                check(pending == null || newCommand == null) { "Confirm the original Discovery operation first." }
                val command = pending ?: newCommand ?: return@withSession null
                check(command is DiscoveryBusinessCommand) { "Another Discovery operation requires confirmation." }
                check(accepts(command)) { "The original operation belongs to another business page." }
                if (pending == null) {
                    if (command is DiscoveryPersonCommand) check(store.personResult(identity) == null) { "Confirm the saved profile first." }
                    if (command is DiscoveryMallCommand) check(store.mallResult(identity) == null) { "Confirm the previous mall result first." }
                    if (command is DiscoveryResultCommand) check(store.result(identity, command.resultKind) == null) { "Confirm the previous business result first." }
                    store.stage(identity, command)
                }
                current()
                val recovered = if (pending != null) recover(session, command) else null
                val receipt = recovered ?: send(identity, session, command)
                current()
                validateBinding(command, receipt)
                val result = consume(command, receipt)
                current()
                confirmedEnrollment(result)?.let { locator ->
                    require(command is DiscoveryCourseEnrollmentCommand)
                    store.rememberEnrollment(identity, command, locator)
                    current()
                }
                confirmedPerson(result)?.let { locator ->
                    require(command is DiscoveryPersonCommand)
                    store.rememberPerson(identity, command, locator)
                    current()
                }
                confirmedMall(result)?.let { locator ->
                    require(command is DiscoveryMallCommand)
                    store.rememberMall(identity, command, locator)
                    current()
                }
                confirmedResult(result)?.let { locator ->
                    require(command is DiscoveryResultCommand)
                    store.rememberResult(identity, command, locator)
                    current()
                }
                store.complete(identity, command.operationId)
                result
            }
        } finally { lock.unlock() }
    }

    suspend fun enrollmentResult(courseId: Long, sessionId: Long): DiscoveryEnrollmentResultLocator? = access.withSession {
        val identity = gate.current() ?: throw DiscoveryFailure(401)
        store.enrollmentResult(identity, courseId, sessionId)
    }

    /** Local acknowledgement by the current account; no network authority is minted or extended. */
    suspend fun dismissEnrollmentResult(courseId: Long, sessionId: Long, enrollmentId: Long) {
        val identity = gate.current() ?: throw DiscoveryFailure(401)
        store.dismissEnrollment(identity, courseId, sessionId, enrollmentId)
    }

    suspend fun personResult(): DiscoveryPersonResultLocator? = access.withSession {
        val identity = gate.current() ?: throw DiscoveryFailure(401)
        store.personResult(identity)
    }

    suspend fun pendingPerson(): DiscoveryPersonCommand? = access.withSession {
        val identity = gate.current() ?: throw DiscoveryFailure(401)
        store.pending(identity) as? DiscoveryPersonCommand
    }

    suspend fun dismissPersonResult(result: DiscoveryPersonResultLocator) {
        val identity = gate.current() ?: throw DiscoveryFailure(401)
        store.dismissPerson(identity, result)
        if (gate.current() != identity) throw CancellationException("Discovery identity changed")
    }

    suspend fun mallResult(): DiscoveryMallResultLocator? = access.withSession {
        val identity = gate.current() ?: throw DiscoveryFailure(401)
        store.mallResult(identity)
    }
    suspend fun pendingMall(): DiscoveryMallCommand? = access.withSession {
        val identity = gate.current() ?: throw DiscoveryFailure(401)
        store.pending(identity) as? DiscoveryMallCommand
    }
    suspend fun dismissMallResult(result: DiscoveryMallResultLocator) {
        val identity = gate.current() ?: throw DiscoveryFailure(401)
        store.dismissMall(identity, result)
        if (gate.current() != identity) throw CancellationException("Discovery identity changed")
    }

    private suspend fun recover(session: DiscoverySession, command: DiscoveryBusinessCommand): JsonObject? {
        val bytes = try { transport.operation(command.operationId, headers(session))
        } catch (failure: DiscoveryFailure) {
            if (failure.status == 409) return null
            throw failure
        }
        return try {
            val response = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            when (response["status"]) {
                JsonPrimitive("PENDING") -> { require(response.keys == setOf("status")); null }
                JsonPrimitive("COMPLETED") -> {
                    require(response.keys == setOf("status", "result"))
                    response.getValue("result").jsonObject
                }
                else -> throw DiscoveryFailure(503)
            }
        } finally { bytes.fill(0) }
    }

    private suspend fun send(identity: DiscoveryAccountScope, session: DiscoverySession, command: DiscoveryBusinessCommand): JsonObject {
        val body = command.bytes()
        try {
            require(body.size <= 16384)
            if (gate.current() != identity) throw CancellationException("Discovery identity changed")
            val code = authority.issueAction(identity, DiscoveryActionTarget(command.operationId, sha256(body), command.path))
            fun fresh() {
                if (clock.wallMillis() >= minOf(code.authorizationExpiresAtMillis, code.codeExpiresAtMillis, session.expiresAtMillis) ||
                    clock.elapsedMillis() - code.startedAtElapsedMillis !in 0 until 5000 ||
                    clock.elapsedMillis() >= session.expiresAtElapsedMillis) throw DiscoveryFailure(401)
            }
            if (gate.current() != identity) throw CancellationException("Discovery identity changed")
            fresh()
            val bytes = transport.post(DiscoveryTarget.GATEWAY, command.path, body, headers(session) + mapOf(
                "X-Discovery-Code" to code.secret.value,
                "X-Discovery-Verifier" to code.verifier.value,
                "X-Discovery-Exchange" to code.exchangeId,
            ))
            return try {
                fresh()
                Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            } finally { bytes.fill(0) }
        } finally { body.fill(0) }
    }

    suspend fun confirmedResult(kind: String): DiscoveryConfirmedResult? = access.withSession {
        store.result(gate.current() ?: throw DiscoveryFailure(401), kind)
    }

    suspend fun pendingResult(kind: String): DiscoveryResultCommand? = access.withSession {
        (store.pending(gate.current() ?: throw DiscoveryFailure(401)) as? DiscoveryResultCommand)?.takeIf { it.resultKind == kind }
    }

    suspend fun dismissResult(result: DiscoveryConfirmedResult) {
        val identity = gate.current() ?: throw DiscoveryFailure(401)
        store.dismissResult(identity, result)
        if (gate.current() != identity) throw CancellationException("Discovery identity changed")
    }

    private fun headers(session: DiscoverySession) = mapOf("Authorization" to "Bearer ${session.secret.value}")

    private fun validateBinding(command: DiscoveryBusinessCommand, receipt: JsonObject) {
        require(receipt["operation_id"] == JsonPrimitive(command.operationId))
        if (command is DiscoveryCourseCommand) {
            require(receipt["course_id"] == JsonPrimitive(command.courseId) && receipt["session_id"] == JsonPrimitive(command.sessionId))
        }
        require(receipt["outcome"] in setOf(JsonPrimitive("APPLIED"), JsonPrimitive("REJECTED")))
        // Exact APPLIED/REJECTED keys and nested schemas are checked by consume, before store.complete.
        if (receipt["outcome"] == JsonPrimitive("APPLIED")) when (command) {
            is DiscoveryCourseQuoteCommand -> require(receipt["options_revision"] == JsonPrimitive(command.optionsRevision))
            is DiscoveryCourseEnrollmentCommand -> {
                require(receipt["quote_id"] == JsonPrimitive(command.quoteId))
                require(receipt["selection_sha256"] == JsonPrimitive(command.selectionSha256))
            }
            is DiscoveryAddressCommand -> if (command.addressId != null) require(receipt["address_id"] == JsonPrimitive(command.addressId))
            is DiscoveryPersonCommand -> if (command.childId != null) require(receipt["child_user_id"] == JsonPrimitive(command.childId))
            is DiscoveryMallCommand -> if (command.action != DiscoveryMallAction.CREATE) {
                require(receipt["mall_order_id"] == command.body.getValue("mall_order_id"))
            }
            is DiscoveryResultCommand -> Unit // Exact target/receipt matching is owned by its typed consumer.
        }
    }
}
