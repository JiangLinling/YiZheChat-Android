/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("ktlint:standard:filename")

package io.element.android.features.messages.impl.local

import android.content.SharedPreferences
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.messages.api.MessageReferenceState
import io.element.android.features.messages.api.MessageReferenceStore
import io.element.android.features.messages.api.MessageReference
import io.element.android.features.messages.api.RoomReadingNavigation
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultMessageReferenceStore(
    private val matrixClient: MatrixClient,
    private val sensitiveFileStore: SensitiveFileStore,
    private val sharedPreferences: SharedPreferences,
    private val favouriteSynchronizer: FavouriteReferenceSynchronizer,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
    private val coordinator: AppOperationalStateCoordinator,
) : MessageReferenceStore {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(MessageReferenceState())
    private var loaded = false
    private val remoteMutex = Mutex()
    private var syncedFavouriteKeys: Set<String> = emptySet()
    private var locallySuppressedFavouriteKeys: Set<String> = emptySet()

    init {
        sessionCoroutineScope.launch {
            coordinator.operations.map { operations ->
                operations.values.filter { it.lifecycleId == FullAppLifecycleId.LC_18 && it.status == LifecycleOperationStatus.SUCCEEDED }
                    .map { it.operationId }.toSet()
            }.distinctUntilChanged().collect { if (loaded) refreshFavourites() }
        }
    }

    override val state: StateFlow<MessageReferenceState> = mutableState.asStateFlow()

    override suspend fun load() {
        mutex.withLock { ensureLoaded() }
        // Timeline suppression is ready from local encrypted state; background sync never holds its mutex.
        sessionCoroutineScope.launch { refreshFavourites() }
    }

    override suspend fun saveLastViewedPosition(roomId: RoomId, eventId: EventId) {
        if (!roomId.value.startsWith("!") || !eventId.value.startsWith("$") || eventId.value.length < 2) return
        mutex.withLock {
            ensureLoaded()
            val previous = mutableState.value
            if (previous.lastViewedEventIds[roomId.value] == eventId.value || previous.isLocallyDeleted(roomId, eventId)) return
            persist(previous.copy(lastViewedEventIds = previous.lastViewedEventIds + (roomId.value to eventId.value)))
        }
    }

    override suspend fun saveReadingNavigation(roomId: RoomId, navigation: RoomReadingNavigation) {
        if (!roomId.value.startsWith("!")) return
        val ids = listOfNotNull(navigation.latestSeenEventId, navigation.firstNewEventId, navigation.batchEndEventId)
        if (ids.any { !it.startsWith("$") || it.length < 2 }) return
        mutex.withLock {
            ensureLoaded()
            val previous = mutableState.value
            if (previous.readingNavigation[roomId.value] == navigation) return
            persist(previous.copy(readingNavigation = previous.readingNavigation + (roomId.value to navigation)))
        }
    }

    private fun Map<String, RoomReadingNavigation>.withoutDeletedNavigation(keys: Set<String>) = mapValues { (roomId, position) ->
        fun removed(id: String?) = id != null && MessageReferenceState.key(RoomId(roomId), EventId(id)) in keys
        position.copy(
            latestSeenEventId = position.latestSeenEventId.takeUnless(::removed),
            firstNewEventId = position.firstNewEventId.takeUnless(::removed),
            batchEndEventId = position.batchEndEventId.takeUnless(::removed),
        )
    }

    override suspend fun toggleFavourite(roomId: RoomId, eventId: EventId): Boolean = remoteMutex.withLock {
        mutex.withLock { ensureLoaded() }
        val key = MessageReferenceState.key(roomId, eventId)
        val adding = key !in mutableState.value.favouriteKeys
        val remote = favouriteSynchronizer.update(if (adding) setOf(key) else emptySet(), if (adding) emptySet() else setOf(key))
        mutex.withLock { persist(
            mutableState.value.copy(
                favouriteKeys = if (adding) mutableState.value.favouriteKeys + key else mutableState.value.favouriteKeys - key,
            )
        ); if (remote != null) mergeRemote(remote) }
        adding
    }

    override suspend fun addFavourites(roomId: RoomId, eventIds: Collection<EventId>) {
        if (eventIds.isEmpty()) return
        remoteMutex.withLock {
            mutex.withLock { ensureLoaded() }
            val keys = eventIds.mapTo(mutableSetOf()) { MessageReferenceState.key(roomId, it) }
            val remote = favouriteSynchronizer.update(keys, emptySet())
            mutex.withLock { persist(mutableState.value.copy(favouriteKeys = mutableState.value.favouriteKeys + keys)); if (remote != null) mergeRemote(remote) }
        }
    }

    override suspend fun removeFavourite(roomId: RoomId, eventId: EventId) {
        remoteMutex.withLock {
            mutex.withLock { ensureLoaded() }
            val key = MessageReferenceState.key(roomId, eventId)
            val remote = favouriteSynchronizer.update(emptySet(), setOf(key))
            mutex.withLock { if (key in mutableState.value.favouriteKeys) {
                persist(mutableState.value.copy(favouriteKeys = mutableState.value.favouriteKeys - key))
            }; if (remote != null) mergeRemote(remote) }
        }
    }

    private suspend fun refreshFavourites() {
        if (!remoteMutex.tryLock()) return
        try {
            val remote = favouriteSynchronizer.refresh()
            mutex.withLock { ensureLoaded(); mergeRemote(remote) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep local refs; original message authorization remains authoritative. */ }
        finally { remoteMutex.unlock() }
    }

    private suspend fun mergeRemote(remote: Set<String>) {
        val state = mutableState.value
        val legacy = state.favouriteKeys - syncedFavouriteKeys
        val previous = syncedFavouriteKeys
        syncedFavouriteKeys = remote
        try { persist(state.copy(favouriteKeys = (legacy + remote).filterNotTo(linkedSetOf()) { it in state.locallyDeletedKeys || it in locallySuppressedFavouriteKeys })) }
        catch (failure: Throwable) { syncedFavouriteKeys = previous; throw failure }
    }

    override suspend fun deleteLocally(roomId: RoomId, eventIds: Collection<EventId>) {
        deleteLocally(eventIds.map { eventId -> MessageReference(roomId, eventId) })
    }

    override suspend fun deleteLocally(references: Collection<MessageReference>) {
        if (references.isEmpty()) return
        mutex.withLock {
            ensureLoaded()
            val keys = references.mapTo(mutableSetOf()) { reference ->
                MessageReferenceState.key(reference.roomId, reference.eventId)
            }
            val previous = mutableState.value
            val updated = previous.copy(
                locallyDeletedKeys = previous.locallyDeletedKeys + keys,
                // A locally deleted item must not remain discoverable through Favorites.
                favouriteKeys = previous.favouriteKeys - keys,
                lastViewedEventIds = previous.lastViewedEventIds.filterNot { (roomId, eventId) ->
                    MessageReferenceState.key(RoomId(roomId), EventId(eventId)) in keys
                },
                readingNavigation = previous.readingNavigation.withoutDeletedNavigation(keys),
            )
            // Remove the bubble from the open conversation immediately. Persistence still uses
            // the encrypted sensitive file store; if it fails, restore the previous UI state.
            mutableState.value = updated
            runCatching { persist(updated) }
                .onFailure { mutableState.value = previous }
                .getOrThrow()
        }
    }

    override suspend fun deleteUnavailableHistoryLocally(
        references: Collection<MessageReference>,
        cutoffTimestamp: Long,
    ) {
        require(cutoffTimestamp > 0L)
        mutex.withLock {
            ensureLoaded()
            val keys = references.mapTo(mutableSetOf()) { reference ->
                MessageReferenceState.key(reference.roomId, reference.eventId)
            }
            val previous = mutableState.value
            val previousCutoff = previous.unavailableHistoryCutoffTimestamp ?: Long.MIN_VALUE
            val updated = previous.copy(
                locallyDeletedKeys = previous.locallyDeletedKeys + keys,
                favouriteKeys = previous.favouriteKeys - keys,
                unavailableHistoryCutoffTimestamp = maxOf(previousCutoff, cutoffTimestamp),
                lastViewedEventIds = previous.lastViewedEventIds.filterNot { (roomId, eventId) ->
                    MessageReferenceState.key(RoomId(roomId), EventId(eventId)) in keys
                },
                readingNavigation = previous.readingNavigation.withoutDeletedNavigation(keys),
            )
            mutableState.value = updated
            runCatching { persist(updated) }
                .onFailure { mutableState.value = previous }
                .getOrThrow()
        }
    }

    override suspend fun deleteConversation(roomId: RoomId, cutoffTimestamp: Long) {
        mutex.withLock {
            ensureLoaded()
            val previous = mutableState.value
            val previousSuppressed = locallySuppressedFavouriteKeys
            locallySuppressedFavouriteKeys = locallySuppressedFavouriteKeys + previous.favouriteKeys.filter { it.startsWith("${roomId.value}\u0000") }
            val updated = previous.copy(
                conversationCutoffs = previous.conversationCutoffs + (roomId.value to cutoffTimestamp),
                lastViewedEventIds = previous.lastViewedEventIds - roomId.value,
                readingNavigation = previous.readingNavigation - roomId.value,
                favouriteKeys = previous.favouriteKeys.filterNotTo(linkedSetOf()) { it.startsWith("${roomId.value}\u0000") },
                locallyDeletedKeys = previous.locallyDeletedKeys.filterNotTo(linkedSetOf()) { it.startsWith("${roomId.value}\u0000") },
            )
            mutableState.value = updated
            runCatching { persist(updated) }
                .onFailure { mutableState.value = previous; locallySuppressedFavouriteKeys = previousSuppressed }
                .getOrThrow()
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutableState.value = readReference()?.let { reference ->
            runCatching {
                sensitiveFileStore.read(reference) { input -> decode(input.readBytes().decodeToString()) }
            }.getOrDefault(MessageReferenceState())
        } ?: MessageReferenceState()
        loaded = true
    }

    private suspend fun persist(updated: MessageReferenceState) {
        val oldReference = readReference()
        if (
            updated.favouriteKeys.isEmpty() &&
            updated.locallyDeletedKeys.isEmpty() &&
            updated.conversationCutoffs.isEmpty() &&
            updated.lastViewedEventIds.isEmpty() &&
            updated.readingNavigation.isEmpty() &&
            locallySuppressedFavouriteKeys.isEmpty() &&
            syncedFavouriteKeys.isEmpty() &&
            updated.unavailableHistoryCutoffTimestamp == null
        ) {
            check(sharedPreferences.edit().remove(referenceKey).commit())
            oldReference?.let { runCatching { sensitiveFileStore.delete(it) } }
            mutableState.value = updated
            return
        }
        val payload = encode(updated).encodeToByteArray()
        val reference = sensitiveFileStore.write(
            SecureObjectWriteRequest(
                objectId = SecureObjectId("message-reference-index-v1"),
                vaultId = vaultId,
                purpose = SecureObjectPurpose.MESSAGE_REFERENCE_INDEX,
                associatedData = associatedData,
                expectedPlaintextLength = payload.size.toLong(),
            )
        ) { output -> output.write(payload) }
        check(sharedPreferences.edit().putString(referenceKey, encodeReference(reference)).commit())
        mutableState.value = updated
        oldReference?.let { runCatching { sensitiveFileStore.delete(it) } }
    }

    private fun encode(state: MessageReferenceState): String = JSONObject()
        .put("version", 4)
        .put("favourites", JSONArray(state.favouriteKeys.sorted()))
        .put("syncedFavourites", JSONArray(syncedFavouriteKeys.sorted()))
        .put("locallySuppressedFavourites", JSONArray(locallySuppressedFavouriteKeys.sorted()))
        .put("locallyDeleted", JSONArray(state.locallyDeletedKeys.sorted()))
        .put("conversationCutoffs", JSONObject(state.conversationCutoffs))
        .put("lastViewedEventIds", JSONObject(state.lastViewedEventIds))
        .put("readingNavigation", JSONObject().apply {
            state.readingNavigation.forEach { (roomId, position) ->
                put(roomId, JSONObject()
                    .put("latest", position.latestSeenEventId)
                    .put("first", position.firstNewEventId)
                    .put("end", position.batchEndEventId)
                    .put("remaining", position.hintRemainingMillis.coerceIn(0L, 8_000L))
                    .put("activated", position.hintActivated))
            }
        })
        .put("unavailableHistoryCutoffTimestamp", state.unavailableHistoryCutoffTimestamp)
        .toString()

    private fun decode(payload: String): MessageReferenceState {
        val json = JSONObject(payload)
        fun strings(name: String): Set<String> = buildSet {
            val values = json.optJSONArray(name) ?: return@buildSet
            for (index in 0 until values.length()) add(values.getString(index))
        }
        syncedFavouriteKeys = strings("syncedFavourites")
        locallySuppressedFavouriteKeys = strings("locallySuppressedFavourites")
        return MessageReferenceState(
            favouriteKeys = strings("favourites"),
            locallyDeletedKeys = strings("locallyDeleted"),
            lastViewedEventIds = buildMap {
                val values = json.optJSONObject("lastViewedEventIds") ?: return@buildMap
                values.keys().forEach { roomId ->
                    val eventId = values.optString(roomId)
                    if (roomId.startsWith("!") && eventId.startsWith("$") && eventId.length > 1) put(roomId, eventId)
                }
            },
            conversationCutoffs = buildMap {
                // v1-v3 payloads remain readable; navigation defaults to an empty map.
                val values = json.optJSONObject("conversationCutoffs") ?: return@buildMap
                values.keys().forEach { roomId -> put(roomId, values.optLong(roomId, Long.MIN_VALUE)) }
                entries.removeAll { it.value == Long.MIN_VALUE }
            },
            unavailableHistoryCutoffTimestamp = json.optLong("unavailableHistoryCutoffTimestamp")
                .takeIf { json.has("unavailableHistoryCutoffTimestamp") && it > 0L },
            readingNavigation = buildMap {
                val values = json.optJSONObject("readingNavigation") ?: return@buildMap
                values.keys().forEach { roomId ->
                    val value = values.optJSONObject(roomId) ?: return@forEach
                    fun id(key: String) = value.optString(key).takeIf { it.startsWith("$") && it.length > 1 }
                    if (roomId.startsWith("!")) put(roomId, RoomReadingNavigation(
                        latestSeenEventId = id("latest"), firstNewEventId = id("first"), batchEndEventId = id("end"),
                        hintRemainingMillis = value.optLong("remaining", 8_000L).coerceIn(0L, 8_000L),
                        hintActivated = value.optBoolean("activated"),
                    ))
                }
            },
        )
    }

    private val vaultId: AccountVaultId
        get() = deriveAccountVaultId(matrixClient.sessionId.value, matrixClient.deviceId.value)
    private val associatedData: ByteArray
        get() = "yizhe-message-reference-index-v1\u0000${matrixClient.sessionId.value}\u0000${matrixClient.deviceId.value}".encodeToByteArray()
    private val referenceKey: String
        get() = "message_reference_index_ref_" + MessageDigest.getInstance("SHA-256")
            .digest(vaultId.value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun encodeReference(reference: SecureObjectReference): String = JSONObject().apply {
        put("objectId", reference.objectId.value)
        put("vaultId", reference.vaultId.value)
        put("purpose", reference.purpose.formatCode)
        put("keyEnvelopeId", reference.keyEnvelopeId.value)
        put("formatVersion", reference.formatVersion)
        put("plaintextLength", reference.plaintextLength)
        put("ciphertextLength", reference.ciphertextLength)
        put("associatedDataSha256", reference.associatedDataSha256)
    }.toString()

    private fun readReference(): SecureObjectReference? = sharedPreferences.getString(referenceKey, null)?.let { value ->
        runCatching {
            JSONObject(value).let { json ->
                SecureObjectReference(
                    objectId = SecureObjectId(json.getString("objectId")),
                    vaultId = AccountVaultId(json.getString("vaultId")),
                    purpose = requireNotNull(SecureObjectPurpose.fromFormatCode(json.getInt("purpose"))),
                    keyEnvelopeId = KeyEnvelopeId(json.getString("keyEnvelopeId")),
                    formatVersion = json.getInt("formatVersion"),
                    plaintextLength = json.getLong("plaintextLength"),
                    ciphertextLength = json.getLong("ciphertextLength"),
                    associatedDataSha256 = json.getString("associatedDataSha256"),
                )
            }
        }.getOrNull()
    }
}
