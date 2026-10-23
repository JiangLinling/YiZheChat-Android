/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.preferences.impl.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.preferences.api.store.RecentImageAcknowledgements
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.File

class DefaultSessionPreferencesStore(
    context: Context,
    sessionId: SessionId,
    @SessionCoroutineScope sessionCoroutineScope: CoroutineScope,
    previousStoreClosed: Deferred<Unit>? = null,
) : SessionPreferencesStore {
    companion object {
        fun storeFile(context: Context, sessionId: SessionId): File {
            val hashedUserId = sessionId.value.hash().take(16)
            return context.preferencesDataStoreFile("session_${hashedUserId}_preferences")
        }
    }

    private val sharePresenceKey = booleanPreferencesKey("sharePresence")
    private val sendPublicReadReceiptsKey = booleanPreferencesKey("sendPublicReadReceipts")
    private val renderReadReceiptsKey = booleanPreferencesKey("renderReadReceipts")
    private val sendTypingNotificationsKey = booleanPreferencesKey("sendTypingNotifications")
    private val renderTypingNotificationsKey = booleanPreferencesKey("renderTypingNotifications")
    private val skipSessionVerification = booleanPreferencesKey("skipSessionVerification")
    private val compressImages = booleanPreferencesKey("compressMedia")
    private val compressMediaPreset = stringPreferencesKey("compressMediaPreset")
    private val acknowledgedHistoryRecoveryBanners = stringSetPreferencesKey("acknowledgedHistoryRecoveryBannersV1")
    private val playedVoiceMessages = stringSetPreferencesKey("playedVoiceMessagesV1")
    private val recentImageAcknowledgements = stringSetPreferencesKey("recentImageAcknowledgementsV1")

    private val dataStoreFile = storeFile(context, sessionId)
    private val storeJob = Job(sessionCoroutineScope.coroutineContext[Job])
    private val delegate = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(sessionCoroutineScope.coroutineContext + storeJob),
        migrations = listOf(
            SessionPreferencesStoreMigration(
                sharePresenceKey,
                sendPublicReadReceiptsKey,
            )
        ),
    ) { dataStoreFile }

    // A same-account login may start while the old Matrix session is still alive. Do not open
    // its preference file until the retired DataStore has released it and any deletion finished.
    private val store = object : DataStore<Preferences> {
        override val data = flow {
            previousStoreClosed?.await()
            emitAll(delegate.data)
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            previousStoreClosed?.await()
            return delegate.updateData(transform)
        }
    }

    internal fun stop() = storeJob.cancel()

    internal suspend fun awaitStopped() = storeJob.join()

    override suspend fun setSharePresence(enabled: Boolean) {
        update(sharePresenceKey, enabled)
        // Also update all the other settings
        setSendPublicReadReceipts(enabled)
        setRenderReadReceipts(enabled)
        setSendTypingNotifications(enabled)
        setRenderTypingNotifications(enabled)
    }

    override fun isSharePresenceEnabled(): Flow<Boolean> {
        return get(sharePresenceKey) { true }
    }

    override suspend fun setSendPublicReadReceipts(enabled: Boolean) = update(sendPublicReadReceiptsKey, enabled)
    override fun isSendPublicReadReceiptsEnabled(): Flow<Boolean> = get(sendPublicReadReceiptsKey) { true }

    override suspend fun setRenderReadReceipts(enabled: Boolean) = update(renderReadReceiptsKey, enabled)
    override fun isRenderReadReceiptsEnabled(): Flow<Boolean> = get(renderReadReceiptsKey) { true }

    override suspend fun setSendTypingNotifications(enabled: Boolean) = update(sendTypingNotificationsKey, enabled)
    override fun isSendTypingNotificationsEnabled(): Flow<Boolean> = get(sendTypingNotificationsKey) { true }

    override suspend fun setRenderTypingNotifications(enabled: Boolean) = update(renderTypingNotificationsKey, enabled)
    override fun isRenderTypingNotificationsEnabled(): Flow<Boolean> = get(renderTypingNotificationsKey) { true }

    override suspend fun setSkipSessionVerification(skip: Boolean) = update(skipSessionVerification, skip)
    override fun isSessionVerificationSkipped(): Flow<Boolean> = get(skipSessionVerification) { false }

    override suspend fun setOptimizeImages(compress: Boolean) = update(compressImages, compress)
    override fun doesOptimizeImages(): Flow<Boolean> = get(compressImages) { true }

    override suspend fun setVideoCompressionPreset(preset: VideoCompressionPreset) = update(compressMediaPreset, preset.name)
    override fun getVideoCompressionPreset(): Flow<VideoCompressionPreset> = get(compressMediaPreset) { VideoCompressionPreset.STANDARD.name }
        .map { tryOrNull { VideoCompressionPreset.valueOf(it) } ?: VideoCompressionPreset.STANDARD }

    override suspend fun acknowledgeHistoryRecoveryBanner(roomId: String, status: String) {
        val token = historyRecoveryBannerToken(roomId, status)
        store.edit { preferences ->
            preferences[acknowledgedHistoryRecoveryBanners] =
                preferences[acknowledgedHistoryRecoveryBanners].orEmpty() + token
        }
    }

    override suspend fun claimRecentImageSuggestions(newestFirstIds: List<String>, nowEpochMillis: Long): Set<String> {
        var claimed = emptySet<String>()
        store.edit { prefs ->
            val records = RecentImageAcknowledgements.retain(prefs[recentImageAcknowledgements].orEmpty(), nowEpochMillis)
            claimed = RecentImageAcknowledgements.claimable(newestFirstIds, records)
            prefs[recentImageAcknowledgements] = RecentImageAcknowledgements.acknowledge(records, claimed, nowEpochMillis)
        }
        return claimed
    }

    override suspend fun acknowledgeSelectedImages(ids: List<String>, nowEpochMillis: Long) {
        store.edit { prefs ->
            prefs[recentImageAcknowledgements] = RecentImageAcknowledgements.acknowledge(
                prefs[recentImageAcknowledgements].orEmpty(), ids, nowEpochMillis,
            )
        }
    }

    override suspend fun clearHistoryRecoveryBannerAcknowledgements(roomId: String) {
        val prefix = historyRecoveryRoomPrefix(roomId)
        store.edit { preferences ->
            val retained = preferences[acknowledgedHistoryRecoveryBanners]
                .orEmpty()
                .filterNotTo(mutableSetOf()) { it.startsWith(prefix) }
            if (retained.isEmpty()) {
                preferences.remove(acknowledgedHistoryRecoveryBanners)
            } else {
                preferences[acknowledgedHistoryRecoveryBanners] = retained
            }
        }
    }

    override fun isHistoryRecoveryBannerAcknowledged(roomId: String, status: String): Flow<Boolean> {
        val token = historyRecoveryBannerToken(roomId, status)
        return store.data.map { preferences -> token in preferences[acknowledgedHistoryRecoveryBanners].orEmpty() }
    }

    override suspend fun clear() {
        storeJob.cancelAndJoin()
        check(!dataStoreFile.exists() || dataStoreFile.delete()) { "Session preferences deletion failed" }
    }

    private suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        store.edit { prefs -> prefs[key] = value }
    }

    private fun <T> get(key: Preferences.Key<T>, default: () -> T): Flow<T> {
        return store.data.map { prefs -> prefs[key] ?: default() }
    }

    override suspend fun markVoiceMessagePlayed(eventId: String) {
        require(eventId.isNotBlank())
        val token = eventId.hash()
        store.edit { preferences -> preferences[playedVoiceMessages] = preferences[playedVoiceMessages].orEmpty() + token }
    }

    override fun hasVoiceMessagePlayed(eventId: String): Flow<Boolean> {
        val token = eventId.hash()
        return store.data.map { token in it[playedVoiceMessages].orEmpty() }
    }

    private fun historyRecoveryRoomPrefix(roomId: String): String = "${roomId.hash().take(24)}:"

    private fun historyRecoveryBannerToken(roomId: String, status: String): String =
        historyRecoveryRoomPrefix(roomId) + status
}
