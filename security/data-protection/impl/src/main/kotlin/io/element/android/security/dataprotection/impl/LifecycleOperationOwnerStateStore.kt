/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import android.content.Context
import android.util.AtomicFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerDescriptor
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerPhase
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerRole
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerTransitionReceipt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

internal data class StoredLifecycleOperationOwnerState(
    val descriptor: LifecycleOperationOwnerDescriptor,
    val processInstanceIdSha256: String,
    val currentCapabilityIdSha256: String?,
    val storeRevision: Long,
    val transitions: List<LifecycleOperationOwnerTransitionReceipt>,
    val failureCode: String? = null,
    /** Metadata only; shares the original operation's atomic revision and recovery directory. */
    val mediaClosure: JsonObject? = null,
)

internal interface LifecycleOperationOwnerStateStore {
    fun read(operationId: String): StoredLifecycleOperationOwnerState?
    fun compareAndSet(
        operationId: String,
        expectedRevision: Long?,
        next: StoredLifecycleOperationOwnerState,
    ): Boolean
    fun scanMediaOwners(afterKey: String?, limit: Int): LifecycleMediaOwnerScan = error("Media owner discovery is unavailable.")
}

internal data class LifecycleMediaOwnerScan(
    val records: List<StoredLifecycleOperationOwnerState>,
    val nextAfterKey: String?,
    val hasMore: Boolean,
    val unreadableRecords: Int,
)

/** Used only by legacy unit-test constructors; it is never contributed to production DI. */
internal object FailClosedLifecycleOperationOwnerStateStore : LifecycleOperationOwnerStateStore {
    override fun read(operationId: String): StoredLifecycleOperationOwnerState? = error("Owner state store is not configured.")
    override fun compareAndSet(
        operationId: String,
        expectedRevision: Long?,
        next: StoredLifecycleOperationOwnerState,
    ): Boolean = error("Owner state store is not configured.")
}

@SingleIn(AppScope::class)
internal class AtomicFileLifecycleOperationOwnerStateStore internal constructor(
    private val backend: LifecycleOperationOwnerRecordBackend,
) : LifecycleOperationOwnerStateStore {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        AtomicFileOwnerRecordBackend(
            File(context.noBackupFilesDir, DIRECTORY_NAME),
        ),
    )

    internal constructor(
        directory: File,
        faultInjector: LifecycleOperationOwnerAtomicFileFaultInjector? = null,
    ) : this(AtomicFileOwnerRecordBackend(directory, faultInjector))

    override fun read(operationId: String): StoredLifecycleOperationOwnerState? = synchronized(STORE_LOCK) {
        backend.get(recordKey(operationId))?.let(::decode)
    }

    override fun compareAndSet(
        operationId: String,
        expectedRevision: Long?,
        next: StoredLifecycleOperationOwnerState,
    ): Boolean = synchronized(STORE_LOCK) {
        require(next.descriptor.operationId == operationId)
        val key = recordKey(operationId)
        val current = backend.get(key)?.let(::decode)
        if (current?.storeRevision != expectedRevision) return@synchronized false
        require(next.storeRevision == (expectedRevision ?: 0L) + 1L)
        require(next.transitions.size <= MAX_TRANSITIONS_PER_OPERATION)
        val encoded = encode(next)
        require(encoded.encodeToByteArray().size <= MAX_RECORD_BYTES)
        check(backend.put(key, encoded)) { "Unable to persist lifecycle owner state." }
        true
    }

    override fun scanMediaOwners(afterKey: String?, limit: Int): LifecycleMediaOwnerScan = synchronized(STORE_LOCK) {
        require(limit in 1..128)
        val keys = backend.keys().distinct().sorted().filter { afterKey == null || it > afterKey }
        val selected = keys.take(limit)
        val records = mutableListOf<StoredLifecycleOperationOwnerState>()
        var unreadable = 0
        selected.forEach { key ->
            try {
                val record = backend.get(key)?.let(::decode)
                if (record != null) {
                    check(recordKey(record.descriptor.operationId) == key)
                    if (record.mediaClosure != null) records.add(record)
                }
            } catch (_: Exception) {
                // Preserve unreadable evidence and advance the scan; one bad
                // owner must not starve independent retained cleanup facts.
                unreadable++
            }
        }
        LifecycleMediaOwnerScan(records, selected.lastOrNull(), keys.size > selected.size, unreadable)
    }

    private fun encode(state: StoredLifecycleOperationOwnerState): String = buildJsonObject {
        // Older binaries reject media owners instead of silently dropping the
        // resource outbox while rewriting a schema-1 capability transition.
        put("schema", if (state.mediaClosure == null) RECORD_SCHEMA_VERSION else MEDIA_RECORD_SCHEMA_VERSION)
        put("descriptor", encodeDescriptor(state.descriptor))
        put("process", state.processInstanceIdSha256)
        put("capability", state.currentCapabilityIdSha256?.let(::JsonPrimitive) ?: JsonNull)
        put("revision", state.storeRevision)
        put("failure", state.failureCode?.let(::JsonPrimitive) ?: JsonNull)
        put("media_closure", state.mediaClosure ?: JsonNull)
        put("transitions", buildJsonArray { state.transitions.forEach { add(encodeReceipt(it)) } })
    }.toString()

    private fun decode(encoded: String): StoredLifecycleOperationOwnerState {
        require(encoded.encodeToByteArray().size <= MAX_RECORD_BYTES)
        val value = Json.parseToJsonElement(encoded).jsonObject
        val schema = value.requiredLong("schema")
        require(schema == RECORD_SCHEMA_VERSION.toLong() || schema == MEDIA_RECORD_SCHEMA_VERSION.toLong())
        val media = value["media_closure"]?.takeUnless { it is JsonNull }?.jsonObject
        require((schema == MEDIA_RECORD_SCHEMA_VERSION.toLong()) == (media != null))
        return StoredLifecycleOperationOwnerState(
            descriptor = decodeDescriptor(value.requiredObject("descriptor")),
            processInstanceIdSha256 = value.requiredString("process").requireSha256(),
            currentCapabilityIdSha256 = value.optionalString("capability")?.requireSha256(),
            storeRevision = value.requiredLong("revision"),
            transitions = value.requiredArray("transitions").map { decodeReceipt(it.jsonObject) },
            failureCode = value.optionalString("failure"),
            mediaClosure = media,
        ).also {
            require(it.storeRevision > 0)
            require(it.transitions.size <= MAX_TRANSITIONS_PER_OPERATION)
        }
    }

    private fun encodeDescriptor(value: LifecycleOperationOwnerDescriptor): JsonObject = buildJsonObject {
        put("operation", value.operationId)
        put("lifecycle", value.lifecycleId.name)
        put("role", value.role?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("phase", value.phase.name)
        put("generation", value.generation)
        put("operation_updated_at", value.authoritativeOperationUpdatedAtEpochMillis)
    }

    private fun decodeDescriptor(value: JsonObject) = LifecycleOperationOwnerDescriptor(
        operationId = value.requiredString("operation"),
        lifecycleId = FullAppLifecycleId.valueOf(value.requiredString("lifecycle")),
        role = value.optionalString("role")?.let(LifecycleOperationOwnerRole::valueOf),
        phase = LifecycleOperationOwnerPhase.valueOf(value.requiredString("phase")),
        generation = value.requiredLong("generation"),
        authoritativeOperationUpdatedAtEpochMillis = value.requiredLong("operation_updated_at"),
    )

    private fun encodeReceipt(value: LifecycleOperationOwnerTransitionReceipt): JsonObject = buildJsonObject {
        put("schema", value.schemaVersion)
        put("operation", value.operationId)
        put("command", value.commandIdSha256)
        put("fingerprint", value.requestFingerprintSha256)
        put("process", value.processInstanceIdSha256)
        put("from_capability", value.fromCapabilityIdSha256?.let(::JsonPrimitive) ?: JsonNull)
        put("to_capability", value.toCapabilityIdSha256?.let(::JsonPrimitive) ?: JsonNull)
        put("from", encodeDescriptor(value.from))
        put("to", encodeDescriptor(value.to))
        put("revision", value.storeRevision)
    }

    private fun decodeReceipt(value: JsonObject) = LifecycleOperationOwnerTransitionReceipt(
        schemaVersion = value.requiredLong("schema").toInt(),
        operationId = value.requiredString("operation"),
        commandIdSha256 = value.requiredString("command"),
        requestFingerprintSha256 = value.requiredString("fingerprint"),
        processInstanceIdSha256 = value.requiredString("process"),
        fromCapabilityIdSha256 = value.optionalString("from_capability"),
        toCapabilityIdSha256 = value.optionalString("to_capability"),
        from = decodeDescriptor(value.requiredObject("from")),
        to = decodeDescriptor(value.requiredObject("to")),
        storeRevision = value.requiredLong("revision"),
    )

    private fun recordKey(operationId: String): String = RECORD_PREFIX + sha256Hex(operationId)

    private companion object {
        const val DIRECTORY_NAME = "lifecycle_operation_owners_v1"
        const val RECORD_PREFIX = "owner_"
        const val RECORD_SCHEMA_VERSION = 1
        const val MEDIA_RECORD_SCHEMA_VERSION = 2
        const val MAX_TRANSITIONS_PER_OPERATION = 512
        const val MAX_RECORD_BYTES = 512 * 1024
        val STORE_LOCK = Any()
    }
}

internal interface LifecycleOperationOwnerRecordBackend {
    fun get(key: String): String?
    fun put(key: String, value: String): Boolean
    fun keys(): List<String> = error("Owner record enumeration is unavailable.")
}

internal interface LifecycleOperationOwnerAtomicFileFaultInjector {
    fun beforeFinishWrite()
    fun afterFinishWrite()
}

private class AtomicFileOwnerRecordBackend(
    private val directory: File,
    private val faultInjector: LifecycleOperationOwnerAtomicFileFaultInjector? = null,
) : LifecycleOperationOwnerRecordBackend {
    init {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create lifecycle owner state directory." }
    }

    override fun get(key: String): String? = try {
        AtomicFile(recordFile(key)).readFully().decodeToString()
    } catch (_: FileNotFoundException) {
        null
    }

    override fun put(key: String, value: String): Boolean {
        val atomicFile = AtomicFile(recordFile(key))
        var output: FileOutputStream? = null
        try {
            val startedOutput = atomicFile.startWrite()
            output = startedOutput
            startedOutput.write(value.encodeToByteArray())
            startedOutput.fd.sync()
            faultInjector?.beforeFinishWrite()
            atomicFile.finishWrite(startedOutput)
            output = null
            faultInjector?.afterFinishWrite()
            return true
        } catch (failure: Exception) {
            output?.let(atomicFile::failWrite)
            throw failure
        }
    }

    override fun keys(): List<String> = checkNotNull(directory.listFiles()) { "Unable to enumerate lifecycle owners." }
        .map { it.name.removeSuffix(".bak").removeSuffix(".new") }
        .filter { it.matches(Regex("owner_[0-9a-f]{64}\\.json")) }
        .map { it.removeSuffix(".json") }

    private fun recordFile(key: String): File = File(directory, "$key.json")
}

internal interface ProcessInstanceIdProvider {
    val rawId: ByteArray
    val sha256: String
}

@Inject
@SingleIn(AppScope::class)
internal class SecureRandomProcessInstanceIdProvider : ProcessInstanceIdProvider {
    override val rawId: ByteArray = ByteArray(32).also(SECURE_RANDOM::nextBytes)
    override val sha256: String = sha256Hex(rawId)
}

internal interface LifecycleOperationOwnerNonceSource {
    fun next(): ByteArray
}

@Inject
@SingleIn(AppScope::class)
internal class SecureRandomLifecycleOperationOwnerNonceSource : LifecycleOperationOwnerNonceSource {
    override fun next(): ByteArray = ByteArray(32).also(SECURE_RANDOM::nextBytes)
}

internal fun sha256Hex(value: String): String = sha256Hex(value.encodeToByteArray())

internal fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun String.requireSha256(): String = also { require(SHA_256.matches(it)) }
private fun JsonObject.requiredString(name: String): String = requireNotNull(this[name]).jsonPrimitive.content
private fun JsonObject.optionalString(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
private fun JsonObject.requiredLong(name: String): Long = requireNotNull(this[name]).jsonPrimitive.long
private fun JsonObject.requiredObject(name: String): JsonObject = requireNotNull(this[name]).jsonObject
private fun JsonObject.requiredArray(name: String): JsonArray = requireNotNull(this[name]).jsonArray

private val SHA_256 = Regex("[0-9a-f]{64}")
private val SECURE_RANDOM = SecureRandom()
