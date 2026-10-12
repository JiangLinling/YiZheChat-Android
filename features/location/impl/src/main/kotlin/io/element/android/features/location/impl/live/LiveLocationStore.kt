/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl.live

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import timber.log.Timber
import kotlin.time.Instant

private const val LIVE_LOCATION_EXPIRY_VALUE_SEPARATOR = "="
private const val PENDING_STOP_VALUE_SEPARATOR = "\u001F"
private const val LIFECYCLE_OPERATION_VALUE_SEPARATOR = "\u001F"

data class PendingLiveLocationStop(
    val roomId: RoomId,
    val protectedAggregateId: String?,
    val operationId: String? = null,
    val deadlineEpochMillis: Long? = null,
    val resourcesReserved: Boolean = false,
    val endAccepted: Boolean = false,
    val runtimeReleasedAtEpochMillis: Long? = null,
    val closureQueued: Boolean = false,
)

@Inject
@SingleIn(SessionScope::class)
class LiveLocationStore(
    preferenceDataStoreFactory: PreferenceDataStoreFactory,
    sessionId: SessionId,
) {
    private val store = preferenceDataStoreFactory.create("location_${sessionId.value.hash().take(16)}")
    private val acceptedLiveLocationDisclaimerKey = booleanPreferencesKey("live_location_disclaimer_accepted")
    private val liveLocationExpiriesKey = stringSetPreferencesKey("live_location_expiries")
    private val liveLocationBeaconIdsKey = stringSetPreferencesKey("live_location_beacon_ids")
    private val pendingLiveLocationStopsKey = stringSetPreferencesKey("pending_live_location_stops")
    private val lifecycleOperationIdsKey = stringSetPreferencesKey("live_location_lifecycle_operation_ids")

    suspend fun hasAcceptedLiveLocationDisclaimer(): Boolean = runCatchingExceptions {
        store.data.first()[acceptedLiveLocationDisclaimerKey] ?: false
    }.getOrDefault(false)

    suspend fun setAcceptedLiveLocationDisclaimer(): Result<Unit> = runCatchingExceptions {
        store.edit { prefs ->
            prefs[acceptedLiveLocationDisclaimerKey] = true
        }
    }

    suspend fun getLiveLocationExpiries(): Map<RoomId, Instant> = runCatchingExceptions {
        val serialized = store.data.first()[liveLocationExpiriesKey].orEmpty()
        decodeLiveLocationExpiries(serialized)
    }.onFailure { error ->
        Timber.e(error, "Failed to decode live location expiry payload")
    }.getOrDefault(emptyMap())

    suspend fun setLiveLocationExpiry(roomId: RoomId, expiresAt: Instant): Result<Unit> = runCatchingExceptions {
        store.edit { prefs ->
            val current = decodeLiveLocationExpiries(prefs[liveLocationExpiriesKey].orEmpty())
            prefs[liveLocationExpiriesKey] = encodeLiveLocationExpiries(current + (roomId to expiresAt))
        }
    }

    suspend fun removeLiveLocationExpiry(roomId: RoomId): Result<Unit> = runCatchingExceptions {
        store.edit { prefs ->
            val current = decodeLiveLocationExpiries(prefs[liveLocationExpiriesKey].orEmpty())
            val updated = current - roomId
            if (updated.isEmpty()) {
                prefs.remove(liveLocationExpiriesKey)
            } else {
                prefs[liveLocationExpiriesKey] = encodeLiveLocationExpiries(updated)
            }
        }
    }

    suspend fun getPendingLiveLocationStops(): List<PendingLiveLocationStop> = runCatchingExceptions {
        store.data.first()[pendingLiveLocationStopsKey].orEmpty().mapNotNull(::decodePendingStop)
    }.onFailure { error ->
        Timber.e(error, "Failed to decode pending live location stops")
    }.getOrThrow()

    suspend fun setPendingLiveLocationStop(roomId: RoomId, protectedAggregateId: String?,
        operationId: String? = null, deadlineEpochMillis: Long? = null,
        resourcesReserved: Boolean = false, endAccepted: Boolean = false,
        runtimeReleasedAtEpochMillis: Long? = null, closureQueued: Boolean = false): Result<Unit> = runCatchingExceptions {
        store.edit { prefs ->
            val current = prefs[pendingLiveLocationStopsKey].orEmpty()
                .mapNotNull(::decodePendingStop)
                .associateBy(PendingLiveLocationStop::roomId)
            val updated = current + (roomId to PendingLiveLocationStop(roomId, protectedAggregateId, operationId, deadlineEpochMillis, resourcesReserved, endAccepted, runtimeReleasedAtEpochMillis, closureQueued))
            prefs[pendingLiveLocationStopsKey] = updated.values.map(::encodePendingStop).toSet()
        }
    }

    suspend fun getLiveLocationBeaconIds(): Map<RoomId, String> = runCatchingExceptions {
        store.data.first()[liveLocationBeaconIdsKey].orEmpty().mapNotNull(::decodeLifecycleOperation).toMap()
    }.getOrDefault(emptyMap())

    suspend fun setLiveLocationBeaconId(roomId: RoomId, beaconId: String): Result<Unit> = runCatchingExceptions {
        require(beaconId.isNotBlank())
        store.edit { prefs ->
            val current = prefs[liveLocationBeaconIdsKey].orEmpty().mapNotNull(::decodeLifecycleOperation).toMap()
            prefs[liveLocationBeaconIdsKey] = (current + (roomId to beaconId)).map(::encodeLifecycleOperation).toSet()
        }
    }

    suspend fun removeLiveLocationBeaconId(roomId: RoomId): Result<Unit> = runCatchingExceptions {
        store.edit { prefs ->
            val updated = prefs[liveLocationBeaconIdsKey].orEmpty().mapNotNull(::decodeLifecycleOperation)
                .filterNot { it.first == roomId }.map(::encodeLifecycleOperation).toSet()
            if (updated.isEmpty()) prefs.remove(liveLocationBeaconIdsKey) else prefs[liveLocationBeaconIdsKey] = updated
        }
    }

    /** Removes the recovery deadline and its share identity in one DataStore transaction. */
    suspend fun removeLiveLocationShare(roomId: RoomId): Result<Unit> = runCatchingExceptions {
        store.edit { prefs ->
            val expiries = decodeLiveLocationExpiries(prefs[liveLocationExpiriesKey].orEmpty()) - roomId
            if (expiries.isEmpty()) prefs.remove(liveLocationExpiriesKey) else prefs[liveLocationExpiriesKey] = encodeLiveLocationExpiries(expiries)
            val beaconIds = prefs[liveLocationBeaconIdsKey].orEmpty().mapNotNull(::decodeLifecycleOperation)
                .filterNot { it.first == roomId }.map(::encodeLifecycleOperation).toSet()
            if (beaconIds.isEmpty()) prefs.remove(liveLocationBeaconIdsKey) else prefs[liveLocationBeaconIdsKey] = beaconIds
        }
    }

    suspend fun removePendingLiveLocationStop(roomId: RoomId): Result<Unit> = runCatchingExceptions {
        store.edit { prefs ->
            val updated = prefs[pendingLiveLocationStopsKey].orEmpty()
                .mapNotNull(::decodePendingStop)
                .filterNot { it.roomId == roomId }
                .map(::encodePendingStop)
                .toSet()
            if (updated.isEmpty()) {
                prefs.remove(pendingLiveLocationStopsKey)
            } else {
                prefs[pendingLiveLocationStopsKey] = updated
            }
        }
    }

    suspend fun getLifecycleOperationIds(): Map<RoomId, String> = runCatchingExceptions {
        store.data.first()[lifecycleOperationIdsKey].orEmpty().mapNotNull(::decodeLifecycleOperation)
            .associate { it }
    }.onFailure { error ->
        Timber.e(error, "Failed to decode live location lifecycle operations")
    }.getOrDefault(emptyMap())

    suspend fun setLifecycleOperationId(roomId: RoomId, operationId: String): Result<Unit> = runCatchingExceptions {
        require(operationId.isNotBlank())
        store.edit { prefs ->
            val current = prefs[lifecycleOperationIdsKey].orEmpty().mapNotNull(::decodeLifecycleOperation).toMap()
            prefs[lifecycleOperationIdsKey] = (current + (roomId to operationId)).map(::encodeLifecycleOperation).toSet()
        }
    }

    suspend fun removeLifecycleOperationId(roomId: RoomId): Result<Unit> = runCatchingExceptions {
        store.edit { prefs ->
            val updated = prefs[lifecycleOperationIdsKey].orEmpty()
                .mapNotNull(::decodeLifecycleOperation)
                .filterNot { it.first == roomId }
                .map(::encodeLifecycleOperation)
                .toSet()
            if (updated.isEmpty()) {
                prefs.remove(lifecycleOperationIdsKey)
            } else {
                prefs[lifecycleOperationIdsKey] = updated
            }
        }
    }

    private fun decodeLiveLocationExpiries(serialized: Set<String>): Map<RoomId, Instant> {
        return runCatchingExceptions {
            serialized
                .map { it.split(LIVE_LOCATION_EXPIRY_VALUE_SEPARATOR) }
                .associate { values ->
                    val roomId = RoomId(values[0])
                    val expiresAtMillis = values[1].toLong()
                    roomId to Instant.fromEpochMilliseconds(expiresAtMillis)
                }
        }.getOrDefault(emptyMap())
    }

    private fun encodeLiveLocationExpiries(expiries: Map<RoomId, Instant>): Set<String> {
        return expiries.entries.map { (roomId, expiresAt) ->
            "${roomId.value}$LIVE_LOCATION_EXPIRY_VALUE_SEPARATOR${expiresAt.toEpochMilliseconds()}"
        }.toSet()
    }

    private fun decodePendingStop(serialized: String): PendingLiveLocationStop? = runCatchingExceptions {
        val values = serialized.split(PENDING_STOP_VALUE_SEPARATOR, limit = 8)
        require(values.size in setOf(2, 4, 8) && values.first().isNotBlank())
        val roomId = RoomId(values.first())
        val aggregateId = values.getOrNull(1)?.takeIf(String::isNotEmpty)
        PendingLiveLocationStop(roomId, aggregateId, values.getOrNull(2)?.takeIf(String::isNotEmpty), values.getOrNull(3)?.toLongOrNull(),
            values.getOrNull(4) == "true", values.getOrNull(5) == "true", values.getOrNull(6)?.toLongOrNull(), values.getOrNull(7) == "true")
    }.getOrNull()

    private fun encodePendingStop(stop: PendingLiveLocationStop): String = buildString {
        append(stop.roomId.value)
        append(PENDING_STOP_VALUE_SEPARATOR)
        append(stop.protectedAggregateId.orEmpty())
        if (stop.operationId != null) {
            append(PENDING_STOP_VALUE_SEPARATOR); append(stop.operationId)
            append(PENDING_STOP_VALUE_SEPARATOR); append(requireNotNull(stop.deadlineEpochMillis))
            append(PENDING_STOP_VALUE_SEPARATOR); append(stop.resourcesReserved)
            append(PENDING_STOP_VALUE_SEPARATOR); append(stop.endAccepted)
            append(PENDING_STOP_VALUE_SEPARATOR); append(stop.runtimeReleasedAtEpochMillis ?: "")
            append(PENDING_STOP_VALUE_SEPARATOR); append(stop.closureQueued)
        }
    }

    private fun decodeLifecycleOperation(serialized: String): Pair<RoomId, String>? = runCatchingExceptions {
        val values = serialized.split(LIFECYCLE_OPERATION_VALUE_SEPARATOR, limit = 2)
        require(values.size == 2 && values.all(String::isNotBlank))
        RoomId(values[0]) to values[1]
    }.getOrNull()

    private fun encodeLifecycleOperation(entry: Map.Entry<RoomId, String>): String = buildString {
        append(entry.key.value)
        append(LIFECYCLE_OPERATION_VALUE_SEPARATOR)
        append(entry.value)
    }

    private fun encodeLifecycleOperation(entry: Pair<RoomId, String>): String = buildString {
        append(entry.first.value)
        append(LIFECYCLE_OPERATION_VALUE_SEPARATOR)
        append(entry.second)
    }

    suspend fun clear() {
        store.edit { prefs -> prefs.clear() }
    }
}
