/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Base64

/** Exact current context selected by the coordinator before starting this durable transport job.
 * The digest binds account, boot, installed bytes, signed policy and current DAK; it is not proof
 * by itself. The transport requires independent revalidation before/after every network boundary.
 */
@Serializable
internal data class NextPolicyLifecycleScope(
    val jobId: String,
    val authorityDigestSha256: String,
    val installationId: String,
    val accountIdHash: String,
    val matrixDeviceIdHash: String,
    val deadlineEpochMillis: Long,
)

/** Separate immutable original LC01/LC09 intents. Never retires legacy operations or cleanup. */
internal class NextPolicyLifecycleJournal(private val file: NextPolicyJournalFile) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }

    fun prepare(scope: NextPolicyLifecycleScope): NextPolicyLifecycleIntent = file.locked {
        validateScope(scope)
        val state = read()
        state.intents.firstOrNull { it.scope.jobId == scope.jobId }?.let {
            check(it.scope == scope) { "A lifecycle intent cannot change scope or deadline." }
            return@locked it
        }
        check(state.intents.size < 256) { "Preserve pending lifecycle intents at capacity." }
        val intent = create(scope)
        val bytes = json.encodeToString(state.copy(intents = state.intents + intent)).encodeToByteArray()
        check(bytes.size <= MAX_FILE_BYTES)
        file.write(bytes)
        intent
    }

    private fun read(): State {
        val bytes = file.read() ?: return State()
        check(bytes.size in 1..MAX_FILE_BYTES)
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        val state = json.decodeFromString<State>(text)
        check(json.encodeToString(state) == text && state.schemaVersion == 1)
        check(state.intents.size <= 256 && state.intents.map { it.scope.jobId }.distinct().size == state.intents.size)
        state.intents.forEach { check(it == create(it.scope)) { "Lifecycle intent original bytes are corrupt." } }
        return state
    }

    private fun create(scope: NextPolicyLifecycleScope): NextPolicyLifecycleIntent {
        validateScope(scope)
        val identity = transportDigest((scope.jobId + "\n" + scope.authorityDigestSha256).encodeToByteArray())
        fun body(id: FullAppLifecycleId, trigger: String): String {
            val suffix = id.wireValue.lowercase()
            val request = LifecycleOperationRequest(
                operationId = "next-$suffix-$identity", lifecycleId = id, trigger = trigger,
                idempotencyKey = "next-$suffix-$identity", accountIdHash = scope.accountIdHash,
                matrixDeviceIdHash = scope.matrixDeviceIdHash, deadlineEpochMillis = scope.deadlineEpochMillis,
            )
            val wire = LifecycleOperationStartWire(
                operationId = request.operationId, lifecycleId = id.wireValue, trigger = trigger,
                idempotencyKey = request.idempotencyKey, accountIdHash = request.accountIdHash,
                matrixDeviceIdHash = request.matrixDeviceIdHash, deadlineEpochMillis = request.deadlineEpochMillis,
            )
            return Base64.getEncoder().encodeToString(json.encodeToString(wire).encodeToByteArray())
        }
        return NextPolicyLifecycleIntent(scope, body(FullAppLifecycleId.LC_01, "periodic_release_check"),
            body(FullAppLifecycleId.LC_09, "policy_changed"))
    }

    @Serializable private data class State(val schemaVersion: Int = 1, val intents: List<NextPolicyLifecycleIntent> = emptyList())
    private companion object { const val MAX_FILE_BYTES = 4 * 1024 * 1024 }
}

@Serializable
internal data class NextPolicyLifecycleIntent(
    val scope: NextPolicyLifecycleScope,
    val artifactStartBodyB64: String,
    val policyStartBodyB64: String,
) {
    fun originalStartBody(phase: NextPolicyRequestPhase): ByteArray = Base64.getDecoder().decode(
        if (phase == NextPolicyRequestPhase.ARTIFACT) artifactStartBodyB64 else policyStartBodyB64,
    )
}

/** Lifecycle binding plus original request transport, not a policy/application authority.
 * start sends the exact persisted creation body; post MUST use the explicit supplied header.
 * Neither HTTP success nor an earlier CONSUMED memo emits Android lifecycle success receipts.
 */
internal class NextPolicyLifecycleTransport(
    private val lifecycleJournal: NextPolicyLifecycleJournal,
    private val requests: NextPolicyRequestJournal,
    private val start: suspend (originalCreationBody: ByteArray) -> LifecycleOperation,
    private val post: suspend (path: String, originalBody: ByteArray, lifecycleOperationId: String) -> ByteArray,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }

    suspend fun send(
        scope: NextPolicyLifecycleScope,
        phase: NextPolicyRequestPhase,
        proposedOriginalBody: ByteArray,
        validateCurrentAuthority: suspend () -> Unit,
        validateOriginalBody: (ByteArray) -> Unit,
    ): ByteArray = mutex.withLock {
        val owned = proposedOriginalBody.copyOf()
        validateScope(scope)
        validateCurrentAuthority()
        validateOriginalBody(owned.copyOf())
        val path = requestPath(phase, owned)
        val intent = lifecycleJournal.prepare(scope)
        val original = requests.prepare(scope.jobId, scope.authorityDigestSha256, phase, owned, validateOriginalBody)
        check(requestPath(phase, original) == path)
        val creation = intent.originalStartBody(phase)
        val expected = json.decodeFromString<LifecycleOperationStartWire>(creation.decodeToString(throwOnInvalidSequence = true))
        validateCurrentAuthority()
        val operation = start(creation.copyOf())
        // Lifecycle snapshots expose the stored SHA-256 digest, never the original request key.
        check(operation.operationId == expected.operationId && operation.idempotencyKey == sha256Hex(expected.idempotencyKey))
        check(operation.lifecycleId.wireValue == expected.lifecycleId && operation.deadlineEpochMillis == expected.deadlineEpochMillis)
        check(operation.subjects.installationId == scope.installationId && operation.subjects.accountIdHash == scope.accountIdHash)
        check(operation.subjects.matrixDeviceIdHash == scope.matrixDeviceIdHash && operation.subjects.roomIdHash == null && operation.subjects.eventIdHash == null)
        check(operation.status !in setOf(LifecycleOperationStatus.BLOCKED, LifecycleOperationStatus.CANCELLED,
            LifecycleOperationStatus.FAILED_FINAL, LifecycleOperationStatus.TERMINATED))
        validateCurrentAuthority()
        validateOriginalBody(original.copyOf())
        val response = post(path, original.copyOf(), expected.operationId).copyOf()
        validateCurrentAuthority()
        validateOriginalBody(original.copyOf())
        // Caller verifies the signed response or authenticated ACK and persists its evidence.
        response
    }

    private fun requestPath(phase: NextPolicyRequestPhase, body: ByteArray): String = when (phase) {
        NextPolicyRequestPhase.ARTIFACT -> "/v2/app/artifact-acceptances"
        NextPolicyRequestPhase.CHALLENGE -> "/v2/policy-application-challenges"
        NextPolicyRequestPhase.RECEIPT -> {
            val envelope = Json.parseToJsonElement(body.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                ?: error("Receipt envelope is unavailable.")
            val payload = envelope["payload"] as? JsonObject ?: error("Receipt payload is unavailable.")
            val id = payload["challengeId"] as? JsonPrimitive ?: error("Receipt challenge is unavailable.")
            check(id.isString && id.content.matches(TRANSPORT_ID))
            "/v2/policy-application-challenges/${id.content}/receipts"
        }
    }
}

private fun validateScope(scope: NextPolicyLifecycleScope) {
    check(scope.jobId.matches(TRANSPORT_ID) && scope.authorityDigestSha256.matches(TRANSPORT_HASH))
    check(scope.installationId.matches(TRANSPORT_ID) && scope.installationId.length >= 16)
    check(scope.accountIdHash.matches(TRANSPORT_HASH) && scope.matrixDeviceIdHash.matches(TRANSPORT_HASH))
    check(scope.deadlineEpochMillis > 0)
}
private fun transportDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
private val TRANSPORT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
private val TRANSPORT_HASH = Regex("[0-9a-f]{64}")
