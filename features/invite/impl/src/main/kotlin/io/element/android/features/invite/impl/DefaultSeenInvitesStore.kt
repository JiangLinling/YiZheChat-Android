/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.invite.impl

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.element.android.features.invite.api.InviteNotification
import io.element.android.features.invite.api.SeenInvitesStore
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.sessionstorage.api.observer.SessionListener
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectIdentity
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.deriveAccountVaultId
import org.json.JSONObject
import java.security.MessageDigest

private val notificationHistoryKey = stringSetPreferencesKey("notificationHistoryV1")
private val historyReferenceKey = stringPreferencesKey("notificationHistoryReferenceV2")
private val retiredReferencesKey = stringSetPreferencesKey("notificationRetiredReferencesV2")

private val seenInvitesKey = stringSetPreferencesKey("seenInvites")

class DefaultSeenInvitesStore(
    context: Context,
    sessionId: SessionId,
    private val sessionCoroutineScope: CoroutineScope,
    sessionObserver: SessionObserver,
    private val sensitiveFileStore: SensitiveFileStore,
    deviceId: String,
) : SeenInvitesStore {
    private val mutex = Mutex()
    private val vaultId = deriveAccountVaultId(sessionId.value, deviceId)
    private val objectId = SecureObjectId("invite-notification-history-v2")
    private val purpose = SecureObjectPurpose.CONTACT_PROFILE
    private val associatedData = "invite-notification-history-v2\u0000${sessionId.value}\u0000$deviceId".encodeToByteArray()
    init {
        sessionObserver.addListener(object : SessionListener {
            override suspend fun onSessionDeleted(userId: String, wasLastSession: Boolean) {
                if (sessionId.value == userId) {
                    clear()
                }
            }
        })
    }

    private val dataStoreFile = sessionId.value.hash().take(16).let { hashedUserId ->
        context.preferencesDataStoreFile("session_${hashedUserId}_seen-invites")
    }

    private val store = PreferenceDataStoreFactory.create(
        scope = sessionCoroutineScope,
        migrations = emptyList(),
    ) {
        dataStoreFile
    }

    override fun seenRoomIds(): Flow<Set<RoomId>> =
        store.data.map { prefs ->
            prefs[seenInvitesKey]
                .orEmpty()
                .map { RoomId(it) }
                .toSet()
        }

    override suspend fun markAsSeen(roomId: RoomId) {
        markNotificationSeen(roomId)
    }

    override suspend fun markAsUnSeen(roomId: RoomId) {
        store.edit { prefs ->
            prefs[seenInvitesKey] = prefs[seenInvitesKey].orEmpty() - roomId.value
        }
    }

    override fun notifications(): Flow<List<InviteNotification>> = store.data.map {
        mutex.withLock {
            try {
                InviteNotificationCodec.ordered(readRecords(store.data.first()))
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // Locked/unavailable snapshots must not escape the account vault.
                emptyList()
            }
        }
    }

    override suspend fun observeNotifications(notifications: List<InviteNotification>, completeSnapshot: Boolean) = mutex.withLock {
        store.edit { prefs ->
            val old = readRecords(prefs)
            val updated = InviteNotificationCodec.merge(old, notifications, completeSnapshot, System.currentTimeMillis())
            if (updated != old || prefs[notificationHistoryKey] != null) persist(prefs, updated)
            val unseen = updated.filter { !it.seen }.map { it.roomId.value }.toSet()
            prefs[seenInvitesKey] = prefs[seenInvitesKey].orEmpty() - unseen
        }
        retireOldReferences()
    }

    override suspend fun updateNotificationProfiles(profiles: Map<RoomId, Pair<String?, String?>>) = mutex.withLock {
        store.edit { prefs ->
            val old = readRecords(prefs)
            val updated = old.map { record -> profiles[record.roomId]?.let { (name, avatar) ->
                record.copy(displayName = name ?: record.displayName, avatarUrl = avatar ?: record.avatarUrl)
            } ?: record }
            if (updated != old) persist(prefs, updated)
        }
        retireOldReferences()
    }

    override suspend fun updateNotificationKinds(kinds: Map<RoomId, InviteNotification.Kind>) = mutex.withLock {
        store.edit { prefs ->
            val old = readRecords(prefs)
            val updated = old.map { record -> kinds[record.roomId]?.let {
                record.copy(kind = InviteNotificationCodec.refinedKind(record.kind, it))
            } ?: record }
            if (updated != old) persist(prefs, updated)
        }
        retireOldReferences()
    }

    override suspend fun recordOutcome(roomId: RoomId, outcome: InviteNotification.Outcome, seen: Boolean, expectedSequence: Long?) = mutex.withLock {
        store.edit { prefs ->
            val old = readRecords(prefs)
            val now = System.currentTimeMillis()
            if (expectedSequence != null && old.firstOrNull { it.roomId == roomId }?.sequence != expectedSequence) return@edit
            val sequence = (old.maxOfOrNull { it.sequence } ?: 0L) + 1
            val updated = old.map { record ->
                if (record.roomId != roomId) record else {
                    val confirmed = InviteNotificationCodec.confirmOutcome(record, outcome, now, seen)
                    if (!seen && confirmed.outcome != record.outcome) confirmed.copy(sequence = sequence, receivedAt = now) else confirmed
                }
            }
            if (updated != old) persist(prefs, InviteNotificationCodec.ordered(updated))
            val nowSeen = updated.firstOrNull { it.roomId == roomId }?.seen ?: seen
            prefs[seenInvitesKey] = if (nowSeen) prefs[seenInvitesKey].orEmpty() + roomId.value else
                prefs[seenInvitesKey].orEmpty() - roomId.value
        }
        retireOldReferences()
    }

    override suspend fun markNotificationSeen(roomId: RoomId) = mutex.withLock {
        store.edit { prefs ->
            val old = readRecords(prefs)
            val updated = old.map {
                if (it.roomId == roomId) it.copy(seen = true) else it
            }
            if (updated != old) persist(prefs, updated)
            prefs[seenInvitesKey] = prefs[seenInvitesKey].orEmpty() + roomId.value
        }
        retireOldReferences()
    }

    override suspend fun clear() = mutex.withLock {
        check(sensitiveFileStore.deleteObject(SecureObjectIdentity(objectId, vaultId, purpose)))
        if (sessionCoroutineScope.isActive) store.edit { it.clear() }
        dataStoreFile.safeDelete()
    }

    private suspend fun readRecords(prefs: Preferences): List<InviteNotification> {
        val reference = prefs[historyReferenceKey]?.let(::decodeReference)
            ?: return prefs[notificationHistoryKey].orEmpty().mapNotNull(InviteNotificationCodec::decode)
        require(reference.vaultId == vaultId && reference.objectId == objectId && reference.purpose == purpose)
        return sensitiveFileStore.read(reference) { input ->
            val bytes = input.readBytes()
            try { bytes.decodeToString().lineSequence().mapNotNull(InviteNotificationCodec::decode).toList() }
            finally { bytes.fill(0) }
        }
    }

    private suspend fun persist(prefs: MutablePreferences, records: List<InviteNotification>) {
        val bytes = records.joinToString("\n", transform = InviteNotificationCodec::encode).encodeToByteArray()
        val reference = try {
            sensitiveFileStore.write(SecureObjectWriteRequest(objectId, vaultId, purpose, associatedData, bytes.size.toLong())) { it.write(bytes) }
        } finally { bytes.fill(0) }
        prefs[historyReferenceKey]?.let { previous -> prefs[retiredReferencesKey] = prefs[retiredReferencesKey].orEmpty() + previous }
        prefs[historyReferenceKey] = encodeReference(reference)
        prefs.remove(notificationHistoryKey)
    }

    private suspend fun retireOldReferences() {
        val retired = store.data.first()[retiredReferencesKey].orEmpty()
        val deleted = retired.filter { value ->
            try { sensitiveFileStore.delete(decodeReference(value)) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure else false }
        }.toSet()
        if (deleted.isNotEmpty()) store.edit { it[retiredReferencesKey] = it[retiredReferencesKey].orEmpty() - deleted }
    }

    private fun encodeReference(reference: SecureObjectReference): String = JSONObject().apply {
        put("object", reference.objectId.value); put("vault", reference.vaultId.value); put("purpose", reference.purpose.name)
        put("key", reference.keyEnvelopeId.value); put("version", reference.formatVersion)
        put("plain", reference.plaintextLength); put("cipher", reference.ciphertextLength); put("aad", reference.associatedDataSha256)
    }.toString()

    private fun decodeReference(value: String): SecureObjectReference = JSONObject(value).let {
        SecureObjectReference(SecureObjectId(it.getString("object")), AccountVaultId(it.getString("vault")),
            SecureObjectPurpose.valueOf(it.getString("purpose")), KeyEnvelopeId(it.getString("key")), it.getInt("version"),
            it.getLong("plain"), it.getLong("cipher"), it.getString("aad"))
    }.also {
        require(it.vaultId == vaultId && it.objectId == objectId && it.purpose == purpose)
        val expectedAad = MessageDigest.getInstance("SHA-256").digest(associatedData).joinToString("") { byte -> "%02x".format(byte) }
        require(it.associatedDataSha256 == expectedAad)
    }
}
