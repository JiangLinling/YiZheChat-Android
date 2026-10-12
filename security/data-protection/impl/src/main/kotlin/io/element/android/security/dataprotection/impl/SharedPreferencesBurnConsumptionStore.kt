/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.dataprotection.api.BurnConsumptionKey
import io.element.android.security.dataprotection.api.BurnConsumptionState
import io.element.android.security.dataprotection.api.BurnConsumptionStore
import io.element.android.security.dataprotection.api.PendingBurnConsumption
import io.element.android.security.dataprotection.api.canTransitionTo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SharedPreferencesBurnConsumptionStore private constructor(
    private val records: BurnConsumptionRecordStore,
) : BurnConsumptionStore {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        SharedPreferencesBurnConsumptionRecordStore(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
    )

    internal constructor(records: MutableMap<String, String>) : this(InMemoryBurnConsumptionRecordStore(records))

    private val mutex = Mutex()

    override suspend fun get(key: BurnConsumptionKey): PendingBurnConsumption? = mutex.withLock {
        records.get(preferenceKey(key))?.let(::decode)
    }

    override suspend fun pending(): List<PendingBurnConsumption> = mutex.withLock {
        records.entries()
            .asSequence()
            .filter { (key, _) -> key.startsWith(RECORD_PREFIX) }
            .map { (_, value) -> decode(value) }
            .filter { it.state !in TERMINAL_STATES }
            .toList()
    }

    override suspend fun persist(record: PendingBurnConsumption): PendingBurnConsumption = mutex.withLock {
        val key = preferenceKey(record.key)
        val current = records.get(key)?.let(::decode)
        if (current != null) {
            check(current.key == record.key)
            check(current.rootOperationId == null || record.rootOperationId == current.rootOperationId)
            check(current.viewOperationId == record.viewOperationId)
            check(current.sessionId == record.sessionId)
            check(current.installationId == record.installationId)
            check(current.policySnapshotSha256 == null || record.policySnapshotSha256 == current.policySnapshotSha256)
            check(current.operationDeadlineEpochMillis == record.operationDeadlineEpochMillis)
            check(current.state.canTransitionTo(record.state)) {
                "Burn consumption checkpoint cannot move backwards."
            }
            check(
                current.viewExpiresAtEpochMillis == null ||
                    record.viewExpiresAtEpochMillis == current.viewExpiresAtEpochMillis
            ) { "Burn consumption view deadline cannot change." }
        } else {
            check(record.state == BurnConsumptionState.OPENING) {
                "A burn consumption checkpoint must start before the open side effect."
            }
        }
        check(records.put(key, encode(record))) {
            "Unable to persist burn consumption checkpoint."
        }
        record
    }

    override suspend fun removeConfirmed(key: BurnConsumptionKey): Boolean = mutex.withLock {
        val preferenceKey = preferenceKey(key)
        val current = records.get(preferenceKey)?.let(::decode) ?: return@withLock true
        check(current.state == BurnConsumptionState.TERMINAL_CONFIRMED) {
            "Unconfirmed burn consumption state cannot be removed."
        }
        records.remove(preferenceKey)
    }

    private fun encode(record: PendingBurnConsumption): String = buildJsonObject {
        put("account_id_hash", record.key.accountIdHash)
        put("room_id_hash", record.key.roomIdHash)
        put("event_id_hash", record.key.eventIdHash)
        put("content_id", record.key.contentId)
        put("root_operation_id", record.rootOperationId?.let(::JsonPrimitive) ?: JsonNull)
        put("view_operation_id", record.viewOperationId)
        put("session_id", record.sessionId)
        put("installation_id", record.installationId)
        put("policy_snapshot_sha256", record.policySnapshotSha256?.let(::JsonPrimitive) ?: JsonNull)
        put("operation_deadline", record.operationDeadlineEpochMillis)
        put("state", record.state.name)
        put("view_expires_at", record.viewExpiresAtEpochMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("end_reason", record.endReason?.let(::JsonPrimitive) ?: JsonNull)
        put("last_attempt_at", record.lastAttemptAtEpochMillis)
        put("failure_code", record.failureCode?.let(::JsonPrimitive) ?: JsonNull)
    }.toString()

    private fun decode(encoded: String): PendingBurnConsumption {
        val value = Json.parseToJsonElement(encoded).jsonObject
        fun requiredString(name: String): String = requireNotNull(value[name]).jsonPrimitive.content
        fun optionalString(name: String): String? = value[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        fun optionalLong(name: String): Long? = value[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull
        return PendingBurnConsumption(
            key = BurnConsumptionKey(
                accountIdHash = requiredString("account_id_hash"),
                roomIdHash = requiredString("room_id_hash"),
                eventIdHash = requiredString("event_id_hash"),
                contentId = requiredString("content_id"),
            ),
            rootOperationId = optionalString("root_operation_id"),
            viewOperationId = requiredString("view_operation_id"),
            sessionId = requiredString("session_id"),
            installationId = requiredString("installation_id"),
            policySnapshotSha256 = optionalString("policy_snapshot_sha256"),
            operationDeadlineEpochMillis = requireNotNull(value["operation_deadline"]).jsonPrimitive.long,
            state = BurnConsumptionState.valueOf(requiredString("state")),
            viewExpiresAtEpochMillis = optionalLong("view_expires_at"),
            endReason = optionalString("end_reason"),
            lastAttemptAtEpochMillis = requireNotNull(value["last_attempt_at"]).jsonPrimitive.long,
            failureCode = optionalString("failure_code"),
        )
    }

    private fun preferenceKey(key: BurnConsumptionKey): String {
        val identity = listOf(key.accountIdHash, key.roomIdHash, key.eventIdHash, key.contentId).joinToString("\u0000")
        return RECORD_PREFIX + MessageDigest.getInstance("SHA-256")
            .digest(identity.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val PREFERENCES_NAME = "burn_consumption_checkpoints_v1"
        const val RECORD_PREFIX = "record_"
        val TERMINAL_STATES = setOf(BurnConsumptionState.TERMINAL_CONFIRMED, BurnConsumptionState.FAILED)
    }
}

internal interface BurnConsumptionRecordStore {
    fun get(key: String): String?
    fun entries(): Map<String, String>
    fun put(key: String, value: String): Boolean
    fun remove(key: String): Boolean
}

private class SharedPreferencesBurnConsumptionRecordStore(
    private val preferences: android.content.SharedPreferences,
) : BurnConsumptionRecordStore {
    override fun get(key: String): String? = preferences.getString(key, null)

    override fun entries(): Map<String, String> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap()

    override fun put(key: String, value: String): Boolean = preferences.edit().putString(key, value).commit()

    override fun remove(key: String): Boolean = preferences.edit().remove(key).commit()
}

private class InMemoryBurnConsumptionRecordStore(
    private val records: MutableMap<String, String>,
) : BurnConsumptionRecordStore {
    override fun get(key: String): String? = records[key]
    override fun entries(): Map<String, String> = records.toMap()
    override fun put(key: String, value: String): Boolean {
        records[key] = value
        return true
    }
    override fun remove(key: String): Boolean = records.remove(key) != null
}
