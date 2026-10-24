/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import android.content.SharedPreferences
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.discovery.impl.session.DiscoveryAccountScope
import io.element.android.features.discovery.impl.session.sha256
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.security.securestorage.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

internal interface DiscoveryInteractionStore {
    suspend fun pending(scope: DiscoveryAccountScope): DiscoveryExplicitCommand?
    suspend fun stage(scope: DiscoveryAccountScope, command: DiscoveryExplicitCommand)
    suspend fun complete(scope: DiscoveryAccountScope, operationId: String)
}

/** One outstanding explicit Discovery operation per login. No credentials or business fields in preferences. */
@Inject
@SingleIn(SessionScope::class)
internal class SecureDiscoveryInteractionStore(
    private val client: MatrixClient,
    private val files: SensitiveFileStore,
    private val preferences: SharedPreferences,
) : DiscoveryInteractionStore {
    private val vaultId = deriveAccountVaultId(client.sessionId.value, client.deviceId.value)
    private val purpose = SecureObjectPurpose.OFFLINE_OUTBOX

    override suspend fun pending(scope: DiscoveryAccountScope): DiscoveryExplicitCommand? = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(scope)
            cleanup(key)
            readRecord(key, scope)?.command
        }
    }

    override suspend fun stage(scope: DiscoveryAccountScope, command: DiscoveryExplicitCommand) = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(scope)
            cleanup(key)
            val record = readRecord(key, scope)
            val existing = record?.command
            if (existing != null) {
                check(existing == command) { "Discovery interaction already pending." }
                return@withLock
            }
            val bytes = DiscoveryInteractionRecord(command, record?.enrollments.orEmpty(), record?.person, record?.mall, record?.results.orEmpty()).bytes()
            try { publish(key, binding(scope), bytes) } finally { bytes.fill(0) }
        }
    }

    override suspend fun complete(scope: DiscoveryAccountScope, operationId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(scope)
            cleanup(key)
            val record = readRecord(key, scope) ?: return@withLock
            val current = record.command ?: return@withLock
            check(current.operationId == operationId) { "Discovery operation identity changed." }
            if (record.enrollments.isNotEmpty() || record.person != null || record.mall != null || record.results.isNotEmpty()) {
                val bytes = record.copy(command = null).bytes()
                try { publish(key, binding(scope), bytes) } finally { bytes.fill(0) }
                return@withLock
            }
            retire(key)
        }
    }

    /** Persist the minimal result before retiring its original intent, using the same atomic encrypted journal replacement. */
    suspend fun rememberEnrollment(scope: DiscoveryAccountScope, command: DiscoveryCourseEnrollmentCommand,
        result: DiscoveryEnrollmentResultLocator) = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(scope)
            cleanup(key)
            val record = requireNotNull(readRecord(key, scope))
            check(record.command == command)
            require(result.operationId == command.operationId && result.courseId == command.courseId && result.sessionId == command.sessionId)
            record.enrollments.firstOrNull { it.operationId == command.operationId }?.let { require(it == result) }
            val updated = record.copy(enrollments = record.enrollments.filterNot {
                it.courseId == result.courseId && it.sessionId == result.sessionId
            } + result)
            val bytes = updated.bytes()
            try { publish(key, binding(scope), bytes) } finally { bytes.fill(0) }
        }
    }

    suspend fun enrollmentResult(scope: DiscoveryAccountScope, courseId: Long, sessionId: Long): DiscoveryEnrollmentResultLocator? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val key = key(scope)
                cleanup(key)
                readRecord(key, scope)?.enrollments?.firstOrNull { it.courseId == courseId && it.sessionId == sessionId }
            }
        }

    /** Explicit user dismissal only; background/expiry/process death must leave the locator recoverable. */
    suspend fun dismissEnrollment(scope: DiscoveryAccountScope, courseId: Long, sessionId: Long, enrollmentId: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(scope)
            cleanup(key)
            val record = readRecord(key, scope) ?: return@withLock
            val result = record.enrollments.firstOrNull { it.courseId == courseId && it.sessionId == sessionId } ?: return@withLock
            require(enrollmentId in result.enrollmentIds)
            check(record.command?.operationId != result.operationId) { "The original enrollment intent is not retired." }
            val updated = record.copy(enrollments = record.enrollments - result)
            if (updated.command == null && updated.enrollments.isEmpty() && updated.person == null && updated.mall == null && updated.results.isEmpty()) {
                retire(key)
                return@withLock
            }
            val bytes = updated.bytes()
            try { publish(key, binding(scope), bytes) } finally { bytes.fill(0) }
        }
    }

    suspend fun rememberPerson(scope: DiscoveryAccountScope, command: DiscoveryPersonCommand, result: DiscoveryPersonResultLocator) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val key = key(scope)
                cleanup(key)
                val record = requireNotNull(readRecord(key, scope))
                check(record.command == command && command.operationId == result.operationId && command.matches(result.target))
                check(record.person == null || record.person == result) { "Confirm the previous saved profile first." }
                val bytes = record.copy(person = result).bytes()
                try { publish(key, binding(scope), bytes) } finally { bytes.fill(0) }
            }
        }

    suspend fun personResult(scope: DiscoveryAccountScope): DiscoveryPersonResultLocator? = withContext(Dispatchers.IO) {
        lock.withLock { val key = key(scope); cleanup(key); readRecord(key, scope)?.person }
    }

    suspend fun dismissPerson(scope: DiscoveryAccountScope, result: DiscoveryPersonResultLocator) = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(scope)
            cleanup(key)
            val record = requireNotNull(readRecord(key, scope)) { "Saved profile is not owned by this login." }
            check(record.person == result && record.command?.operationId != result.operationId)
            val updated = record.copy(person = null)
            if (updated.command == null && updated.enrollments.isEmpty() && updated.mall == null && updated.results.isEmpty()) retire(key) else {
                val bytes = updated.bytes()
                try { publish(key, binding(scope), bytes) } finally { bytes.fill(0) }
            }
        }
    }

    suspend fun rememberMall(scope: DiscoveryAccountScope, command: DiscoveryMallCommand, result: DiscoveryMallResultLocator) = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(scope); cleanup(key)
            val record = requireNotNull(readRecord(key, scope))
            check(record.command == command && result.matches(command))
            check(record.mall == null || record.mall == result) { "Confirm the previous mall result first." }
            val bytes = record.copy(mall = result).bytes()
            try { publish(key, binding(scope), bytes) } finally { bytes.fill(0) }
        }
    }
    suspend fun mallResult(scope: DiscoveryAccountScope): DiscoveryMallResultLocator? = withContext(Dispatchers.IO) {
        lock.withLock { val key = key(scope); cleanup(key); readRecord(key, scope)?.mall }
    }
    suspend fun dismissMall(scope: DiscoveryAccountScope, result: DiscoveryMallResultLocator) = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(scope); cleanup(key)
            val record = requireNotNull(readRecord(key, scope))
            check(record.mall == result && record.command?.operationId != result.operationId)
            val updated = record.copy(mall = null)
            if (updated.command == null && updated.enrollments.isEmpty() && updated.person == null && updated.results.isEmpty()) retire(key) else {
                val bytes = updated.bytes()
                try { publish(key, binding(scope), bytes) } finally { bytes.fill(0) }
            }
        }
    }

    suspend fun rememberResult(scope: DiscoveryAccountScope, command: DiscoveryResultCommand, result: DiscoveryConfirmedResult) = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(scope); cleanup(key)
            val record = requireNotNull(readRecord(key, scope))
            require(record.command == command && result.kind == command.resultKind && result.matches(command))
            val existing = record.results.singleOrNull { it.kind == result.kind }
            check(existing == null || existing == result)
            val bytes = record.copy(results = record.results.filterNot { it.kind == result.kind } + result).bytes()
            try { publish(key, binding(scope), bytes) } finally { bytes.fill(0) }
        }
    }

    suspend fun result(scope: DiscoveryAccountScope, kind: String): DiscoveryConfirmedResult? = withContext(Dispatchers.IO) {
        lock.withLock { val key = key(scope); cleanup(key); readRecord(key, scope)?.results?.singleOrNull { it.kind == kind } }
    }

    suspend fun dismissResult(scope: DiscoveryAccountScope, result: DiscoveryConfirmedResult) = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(scope); cleanup(key)
            val record = requireNotNull(readRecord(key, scope))
            require(record.results.singleOrNull { it.kind == result.kind } == result && record.command?.operationId != result.operationId)
            val updated = record.copy(results = record.results - result)
            if (updated.command == null && updated.enrollments.isEmpty() && updated.person == null && updated.mall == null && updated.results.isEmpty()) retire(key)
            else {
                val bytes = updated.bytes()
                try { publish(key, binding(scope), bytes) } finally { bytes.fill(0) }
            }
        }
    }

    suspend fun readings(scope: DiscoveryAccountScope): List<DiscoveryReadingVisit> = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(scope, reading = true)
            cleanup(key)
            readReadings(key, scope)
        }
    }

    suspend fun updateReadings(scope: DiscoveryAccountScope, change: (List<DiscoveryReadingVisit>) -> List<DiscoveryReadingVisit>) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val key = key(scope, reading = true)
                cleanup(key)
                val old = readReadings(key, scope)
                val next = change(old)
                require(next.size <= 32 && next.map { it.visitId }.toSet().size == next.size)
                require(next.flatMap { listOf(it.visitId, it.finishId) }.toSet().size == next.size * 2)
                old.forEach { before -> next.find { it.visitId == before.visitId }?.let { after ->
                    require(after.articleId == before.articleId && after.finishId == before.finishId)
                    require(after.milliseconds >= before.milliseconds && (!before.startApplied || after.startApplied))
                    require(!before.ended || after.ended && after.milliseconds == before.milliseconds)
                } }
                if (next == old) return@withLock
                if (next.isEmpty()) {
                    reference(key)?.let { previous ->
                        check(preferences.edit().remove(key).putString("$key.retired", encode(previous)).commit())
                        cleanup(key)
                    }
                } else {
                    val bytes = JsonArray(next.map { it.json() }).toString().encodeToByteArray()
                    try { publish(key, binding(scope, reading = true), bytes) } finally { bytes.fill(0) }
                }
            }
        }

    private suspend fun retire(key: String) {
        val reference = reference(key) ?: error("Discovery reference missing.")
        check(preferences.edit().remove(key).putString("$key.retired", encode(reference)).commit())
        try { cleanup(key) } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // The intent or acknowledged locator is durably retired. The existing journal retries
            // encrypted-object deletion before the next read/write; this is not cleanup acceptance.
        }
    }

    private suspend fun publish(key: String, associatedData: ByteArray, bytes: ByteArray) {
        require(bytes.size in 1..32768)
        val previous = reference(key)
        val objectId = SecureObjectId(objectPrefix(key) + UUID.randomUUID().toString())
        // Creation and replacement share the same crash journal; the old published object survives failed writes.
        check(preferences.edit().putString("$key.pending", objectId.value).commit())
        val reference = files.write(SecureObjectWriteRequest(objectId, vaultId, purpose, associatedData, bytes.size.toLong())) { it.write(bytes) }
        check(reference.objectId == objectId && reference.plaintextLength == bytes.size.toLong())
        decode(encode(reference), key)
        val editor = preferences.edit().putString(key, encode(reference)).remove("$key.pending")
        if (previous != null) editor.putString("$key.retired", encode(previous))
        check(editor.commit())
        cleanup(key)
    }

    private fun key(scope: DiscoveryAccountScope, reading: Boolean = false): String {
        require(scope.accountHash == sha256(client.sessionId.value) && scope.deviceHash == sha256(client.deviceId.value))
        require(scope.loginTimestamp > 0) { "A stable host login timestamp is required for intent recovery." }
        return (if (reading) "discovery_reading_v1_" else "discovery_interaction_v1_") + sha256(binding(scope, reading))
    }

    private fun binding(scope: DiscoveryAccountScope, reading: Boolean = false): ByteArray =
        "${if (reading) "discovery-reading-v1" else "discovery-interaction-v1"}\u0000${vaultId.value}\u0000${scope.loginTimestamp}".encodeToByteArray()

    private suspend fun readRecord(key: String, scope: DiscoveryAccountScope): DiscoveryInteractionRecord? =
        readValue(key, binding(scope), 32768) { DiscoveryInteractionRecord.parse(it.jsonObject) }

    private suspend fun readReadings(key: String, scope: DiscoveryAccountScope): List<DiscoveryReadingVisit> =
        readValue(key, binding(scope, reading = true), 32768) { value ->
            value.jsonArray.also { require(it.size <= 32) }.map { DiscoveryReadingVisit.parse(it.jsonObject) }.also { rows ->
                require(rows.flatMap { listOf(it.visitId, it.finishId) }.toSet().size == rows.size * 2)
            }
        }.orEmpty()

    private suspend fun <T> readValue(key: String, associatedData: ByteArray, limit: Int, parse: (JsonElement) -> T): T? {
        val reference = reference(key) ?: return null
        check(reference.associatedDataSha256 == sha256(associatedData)) { "Discovery intent binding changed." }
        return files.read(reference) { input ->
            val bytes = ByteArray(limit + 1)
            try {
                var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    check(count > 0)
                    size += count
                }
                check(size <= limit && size.toLong() == reference.plaintextLength)
                parse(Json.parseToJsonElement(bytes.decodeToString(0, size)))
            } finally { bytes.fill(0) }
        }
    }

    private fun reference(key: String): SecureObjectReference? = preferences.getString(key, null)?.let { decode(it, key) }

    private suspend fun cleanup(key: String) {
        preferences.getString("$key.pending", null)?.let { id ->
            validateObjectId(id, key)
            check(files.deleteObject(SecureObjectIdentity(SecureObjectId(id), vaultId, purpose)))
            check(preferences.edit().remove("$key.pending").commit())
        }
        reference("$key.retired")?.let { old ->
            check(files.delete(old))
            check(preferences.edit().remove("$key.retired").commit())
        }
    }

    private fun encode(ref: SecureObjectReference): String = buildJsonObject {
        put("object", ref.objectId.value); put("vault", ref.vaultId.value); put("purpose", ref.purpose.formatCode)
        put("key", ref.keyEnvelopeId.value); put("format", ref.formatVersion); put("plain", ref.plaintextLength)
        put("cipher", ref.ciphertextLength); put("binding", ref.associatedDataSha256)
    }.toString()

    private fun decode(value: String, key: String): SecureObjectReference {
        require(value.length <= 4096)
        val json = Json.parseToJsonElement(value).jsonObject
        require(json.keys == setOf("object", "vault", "purpose", "key", "format", "plain", "cipher", "binding"))
        fun text(key: String) = json.getValue(key).jsonPrimitive.content
        require(text("vault") == vaultId.value && json.getValue("purpose").jsonPrimitive.int == purpose.formatCode)
        validateObjectId(text("object"), key)
        require(text("key").matches(Regex("[a-f0-9]{32}")))
        require(text("binding") == key.substringBefore('.').takeLast(64))
        val length = json.getValue("plain").jsonPrimitive.long.also { require(it in 1..32768) }
        require(json.getValue("cipher").jsonPrimitive.long in length..131_072)
        return SecureObjectReference(SecureObjectId(text("object")), vaultId, purpose, KeyEnvelopeId(text("key")),
            json.getValue("format").jsonPrimitive.int, length, json.getValue("cipher").jsonPrimitive.long, text("binding"))
    }

    private fun objectPrefix(key: String) = "discovery-article-" + key.substringBefore('.').takeLast(64) + "-"
    private fun validateObjectId(id: String, key: String) {
        require(id.startsWith(objectPrefix(key)))
        val nonce = id.removePrefix(objectPrefix(key))
        require(UUID.fromString(nonce).toString() == nonce)
    }

    companion object {
        // This only serializes local journal writes; never held during a network request.
        private val lock = Mutex()
    }
}
