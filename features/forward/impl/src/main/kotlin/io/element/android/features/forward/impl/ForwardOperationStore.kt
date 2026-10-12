/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.forward.impl

import android.content.SharedPreferences
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PersistedForwardOperation(
    val scopeKey: String,
    val generation: String,
    val operationNonce: String,
    val expiresAtEpochMs: Long,
    val pairStates: Map<ForwardPair, ForwardPairState>,
    val revision: Long = 0,
    val pairClaimTokens: Map<ForwardPair, String> = emptyMap(),
)

data class ForwardPairClaim(val operation: PersistedForwardOperation, val token: String)

interface ForwardOperationStore {
    suspend fun load(scopeKey: String, nowEpochMs: Long): PersistedForwardOperation?
    suspend fun save(operation: PersistedForwardOperation, expectedRevision: Long): PersistedForwardOperation
    suspend fun clear(scopeKey: String, generation: String)
    suspend fun create(operation: PersistedForwardOperation): PersistedForwardOperation = error("Atomic create is not implemented.")
    suspend fun claimPair(scopeKey: String, generation: String, pair: ForwardPair): ForwardPairClaim? = error("Atomic pair claim is not implemented.")
    suspend fun finishPair(
        scopeKey: String,
        generation: String,
        pair: ForwardPair,
        claimToken: String,
        result: ForwardPairState,
    ): PersistedForwardOperation = error("Token-bound pair completion is not implemented.")
}

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultForwardOperationStore(
    private val matrixClient: MatrixClient,
    private val sensitiveFileStore: SensitiveFileStore,
    private val sharedPreferences: SharedPreferences,
) : ForwardOperationStore {
    private val mutex get() = vaultMutexes.getOrPut(vaultId.value) { Mutex() }

    override suspend fun load(scopeKey: String, nowEpochMs: Long): PersistedForwardOperation? = mutex.withLock {
        cleanupRetired()
        val (reference, current) = readCurrentStrict()
        val restored = current.mapValues { (_, operation) -> operation.restoreSafe(nowEpochMs) }
        if (restored != current) persistLocked(reference, restored)
        restored[scopeKey]
    }

    override suspend fun save(operation: PersistedForwardOperation, expectedRevision: Long): PersistedForwardOperation = mutex.withLock {
        cleanupRetired()
        val (reference, current) = readCurrentStrict()
        val previous = current[operation.scopeKey] ?: error("Forward operation is missing.")
        check(previous.generation == operation.generation && previous.revision == expectedRevision) { "Forward operation revision changed." }
        val saved = operation.copy(revision = expectedRevision + 1)
        persistLocked(reference, current + (operation.scopeKey to saved))
        saved
    }

    override suspend fun clear(scopeKey: String, generation: String) = mutex.withLock {
        cleanupRetired()
        val (reference, current) = readCurrentStrict()
        if (current[scopeKey]?.generation == generation) persistLocked(reference, current - scopeKey)
    }

    override suspend fun create(operation: PersistedForwardOperation): PersistedForwardOperation = mutex.withLock {
        cleanupRetired()
        val (reference, current) = readCurrentStrict()
        check(current[operation.scopeKey] == null) { "A forward operation already owns this scope." }
        val created = operation.copy(revision = 1)
        persistLocked(reference, current + (operation.scopeKey to created))
        created
    }

    override suspend fun claimPair(scopeKey: String, generation: String, pair: ForwardPair): ForwardPairClaim? = mutex.withLock {
        cleanupRetired()
        val (reference, current) = readCurrentStrict()
        val operation = current[scopeKey] ?: error("Forward operation is missing.")
        check(operation.generation == generation) { "A newer forward operation owns this scope." }
        if (operation.pairStates[pair] !is ForwardPairState.Pending && operation.pairStates[pair] !is ForwardPairState.Failed) return@withLock null
        val token = UUID.randomUUID().toString()
        val claimed = operation.copy(
            pairStates = operation.pairStates + (pair to ForwardPairState.Sending),
            pairClaimTokens = operation.pairClaimTokens + (pair to token),
            revision = operation.revision + 1,
        )
        persistLocked(reference, current + (scopeKey to claimed))
        activeClaimTokens.add(token)
        ForwardPairClaim(claimed, token)
    }

    override suspend fun finishPair(
        scopeKey: String,
        generation: String,
        pair: ForwardPair,
        claimToken: String,
        result: ForwardPairState,
    ): PersistedForwardOperation = mutex.withLock {
        require(result is ForwardPairState.Success || result is ForwardPairState.Failed || result is ForwardPairState.Ambiguous)
        cleanupRetired()
        val (reference, current) = readCurrentStrict()
        val operation = current[scopeKey] ?: error("Forward operation is missing.")
        check(operation.generation == generation) { "A newer forward operation owns this scope." }
        check(operation.pairStates[pair] is ForwardPairState.Sending && operation.pairClaimTokens[pair] == claimToken) {
            "Forward pair is not owned by this sender."
        }
        val completed = operation.copy(
            pairStates = operation.pairStates + (pair to result),
            pairClaimTokens = operation.pairClaimTokens - pair,
            revision = operation.revision + 1,
        )
        persistLocked(reference, current + (scopeKey to completed))
        activeClaimTokens.remove(claimToken)
        completed
    }

    private fun PersistedForwardOperation.restoreSafe(nowEpochMs: Long): PersistedForwardOperation {
        val restoredStates = pairStates.mapValues { (pair, state) -> when {
            state is ForwardPairState.Sending && pairClaimTokens[pair] !in activeClaimTokens -> ForwardPairState.Ambiguous("Interrupted after send may have started")
            expiresAtEpochMs <= nowEpochMs && state !is ForwardPairState.Success && state !is ForwardPairState.Ambiguous ->
                ForwardPairState.Ambiguous("Forward recovery window expired")
            else -> state
        } }
        val restoredClaims = pairClaimTokens.filterValues(activeClaimTokens::contains)
        return if (restoredStates == pairStates && restoredClaims == pairClaimTokens) this else copy(
            pairStates = restoredStates,
            pairClaimTokens = restoredClaims,
            revision = revision + 1,
        )
    }

    private suspend fun readCurrentStrict(): Pair<SecureObjectReference?, Map<String, PersistedForwardOperation>> {
        val reference = sharedPreferences.getString(referenceKey, null)?.let(::decodeReferenceStrict) ?: return null to emptyMap()
        check(reference.vaultId == vaultId) { "Forward operation reference belongs to another vault." }
        check(reference.purpose == SecureObjectPurpose.OFFLINE_OUTBOX) { "Forward operation reference purpose is invalid." }
        val state = sensitiveFileStore.read(reference) { input ->
            val bytes = input.readBytes()
            try {
                decode(bytes.decodeToString())
            } finally {
                bytes.fill(0)
            }
        }
        check(reference.associatedDataSha256 == sha256Bytes(associatedData(state))) { "Forward operation associated data does not match its scope." }
        return reference to state
    }

    private suspend fun persistLocked(oldReference: SecureObjectReference?, next: Map<String, PersistedForwardOperation>) {
        if (next.isEmpty()) {
            val retired = retirementReferences() + listOfNotNull(oldReference)
            check(sharedPreferences.edit().remove(referenceKey).putString(retirementKey, encodeReferences(retired)).commit())
            cleanupRetired()
            return
        }
        val bytes = encode(next).encodeToByteArray()
        val newReference = try {
            sensitiveFileStore.write(SecureObjectWriteRequest(
                SecureObjectId("forward-operation-${UUID.randomUUID()}"), vaultId, SecureObjectPurpose.OFFLINE_OUTBOX,
                associatedData(next), bytes.size.toLong(),
            )) { it.write(bytes) }
        } finally {
            bytes.fill(0)
        }
        val retired = retirementReferences() + listOfNotNull(oldReference)
        val committed = runCatching {
            check(sharedPreferences.edit()
                .putString(referenceKey, encodeReference(newReference))
                .putString(retirementKey, encodeReferences(retired))
                .commit())
        }
        if (committed.isFailure) {
            val failure = committed.exceptionOrNull()!!
            runCatching { check(sensitiveFileStore.delete(newReference)) { "New forward object cleanup was refused." } }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        cleanupRetired()
    }

    private suspend fun cleanupRetired() {
        val pending = retirementReferences()
        if (pending.isEmpty()) return
        val remaining = pending.toMutableList()
        for (reference in pending) {
            check(reference.vaultId == vaultId && reference.purpose == SecureObjectPurpose.OFFLINE_OUTBOX) { "Invalid retirement reference." }
            check(sensitiveFileStore.delete(reference)) { "Forward operation retirement was refused." }
            remaining.remove(reference)
            check(sharedPreferences.edit().putString(retirementKey, encodeReferences(remaining)).commit())
        }
    }

    private fun encode(values: Map<String, PersistedForwardOperation>) = JSONArray().apply {
        values.values.forEach { value -> put(JSONObject().apply {
            put("scope", value.scopeKey); put("generation", value.generation); put("nonce", value.operationNonce); put("expires", value.expiresAtEpochMs); put("revision", value.revision)
            put("pairs", JSONArray().apply { value.pairStates.forEach { (pair, state) -> put(JSONObject().apply {
                put("event", pair.eventId.value); put("room", pair.roomId.value); put("state", state.code())
                value.pairClaimTokens[pair]?.let { put("claim", it) }
                when (state) { is ForwardPairState.Failed -> put("reason", state.reason); is ForwardPairState.Ambiguous -> put("reason", state.reason); else -> Unit }
            }) } })
        }) }
    }.toString()

    private fun decode(payload: String): Map<String, PersistedForwardOperation> = buildMap {
        val array = JSONArray(payload)
        for (index in 0 until array.length()) array.getJSONObject(index).let { json ->
            val claims = mutableMapOf<ForwardPair, String>()
            val pairs = buildMap { json.getJSONArray("pairs").let { values -> for (pairIndex in 0 until values.length()) values.getJSONObject(pairIndex).let { pair ->
                val key = ForwardPair(EventId(pair.getString("event")), RoomId(pair.getString("room")))
                put(key, pairState(pair.getString("state"), pair.optString("reason").takeIf(String::isNotEmpty)))
                pair.optString("claim").takeIf(String::isNotEmpty)?.let { claims[key] = it }
            } } }
            val value = PersistedForwardOperation(json.getString("scope"), json.getString("generation"), json.getString("nonce"), json.getLong("expires"), pairs, json.optLong("revision", 0), claims)
            check(put(value.scopeKey, value) == null) { "Duplicate forward operation scope." }
        }
    }

    private fun associatedData(values: Map<String, PersistedForwardOperation>): ByteArray {
        val bindings = values.values.flatMap { it.pairStates.keys.map { pair -> "${pair.eventId.value}\u0000${pair.roomId.value}" } }.sorted().joinToString("\u0001")
        return "yizhe-forward-operation-v1\u0000${matrixClient.sessionId.value}\u0000${matrixClient.deviceId.value}\u0000${sha256(bindings)}".encodeToByteArray()
    }

    private val vaultId get() = deriveAccountVaultId(matrixClient.sessionId.value, matrixClient.deviceId.value)
    private val referenceKey get() = "forward_operation_ref_" + sha256(vaultId.value)
    private val retirementKey get() = "forward_operation_retired_" + sha256(vaultId.value)
    private fun retirementReferences(): List<SecureObjectReference> = sharedPreferences.getString(retirementKey, null)?.let { encoded ->
        JSONArray(encoded).let { array -> List(array.length()) { decodeReferenceStrict(array.getString(it)) } }
    }.orEmpty()
    private fun encodeReferences(values: List<SecureObjectReference>) = JSONArray(values.map(::encodeReference)).toString()
    private fun encodeReference(ref: SecureObjectReference) = JSONObject().apply {
        put("objectId", ref.objectId.value); put("vaultId", ref.vaultId.value); put("purpose", ref.purpose.formatCode); put("keyEnvelopeId", ref.keyEnvelopeId.value)
        put("formatVersion", ref.formatVersion); put("plaintextLength", ref.plaintextLength); put("ciphertextLength", ref.ciphertextLength); put("associatedDataSha256", ref.associatedDataSha256)
    }.toString()
    private fun decodeReferenceStrict(encoded: String) = JSONObject(encoded).let { json -> SecureObjectReference(
        SecureObjectId(json.getString("objectId")), AccountVaultId(json.getString("vaultId")), requireNotNull(SecureObjectPurpose.fromFormatCode(json.getInt("purpose"))),
        KeyEnvelopeId(json.getString("keyEnvelopeId")), json.getInt("formatVersion"), json.getLong("plaintextLength"), json.getLong("ciphertextLength"), json.getString("associatedDataSha256")
    ) }

    companion object {
        private val vaultMutexes = ConcurrentHashMap<String, Mutex>()
        private val activeClaimTokens = ConcurrentHashMap.newKeySet<String>()
    }
}

private fun ForwardPairState.code() = when (this) {
    ForwardPairState.Pending -> "pending"; ForwardPairState.Sending -> "sending"; ForwardPairState.Success -> "success"
    is ForwardPairState.Failed -> "failed"; is ForwardPairState.Ambiguous -> "ambiguous"
}
private fun pairState(code: String, reason: String?) = when (code) {
    "pending" -> ForwardPairState.Pending; "sending" -> ForwardPairState.Sending; "success" -> ForwardPairState.Success
    "failed" -> ForwardPairState.Failed(reason); "ambiguous" -> ForwardPairState.Ambiguous(reason); else -> error("Unknown forward pair state.")
}
private fun sha256(value: String) = sha256Bytes(value.encodeToByteArray())
private fun sha256Bytes(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }
