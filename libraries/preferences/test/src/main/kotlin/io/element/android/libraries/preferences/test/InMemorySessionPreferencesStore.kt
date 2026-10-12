/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.preferences.test

import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.preferences.api.store.RecentImageAcknowledgements
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class InMemorySessionPreferencesStore(
    isSharePresenceEnabled: Boolean = true,
    isSendPublicReadReceiptsEnabled: Boolean = true,
    isRenderReadReceiptsEnabled: Boolean = true,
    isSendTypingNotificationsEnabled: Boolean = true,
    isRenderTypingNotificationsEnabled: Boolean = true,
    isSessionVerificationSkipped: Boolean = false,
    doesCompressMedia: Boolean = true,
    videoCompressionPreset: VideoCompressionPreset = VideoCompressionPreset.STANDARD,
) : SessionPreferencesStore {
    private val isSharePresenceEnabled = MutableStateFlow(isSharePresenceEnabled)
    private val isSendPublicReadReceiptsEnabled = MutableStateFlow(isSendPublicReadReceiptsEnabled)
    private val isRenderReadReceiptsEnabled = MutableStateFlow(isRenderReadReceiptsEnabled)
    private val isSendTypingNotificationsEnabled = MutableStateFlow(isSendTypingNotificationsEnabled)
    private val isRenderTypingNotificationsEnabled = MutableStateFlow(isRenderTypingNotificationsEnabled)
    private val isSessionVerificationSkipped = MutableStateFlow(isSessionVerificationSkipped)
    private val doesCompressMedia = MutableStateFlow(doesCompressMedia)
    private val videoCompressionPreset = MutableStateFlow(videoCompressionPreset)
    private val acknowledgedHistoryRecoveryBanners = MutableStateFlow<Set<String>>(emptySet())
    private val playedVoiceMessages = MutableStateFlow<Set<String>>(emptySet())
    private var recentImageAcknowledgements = emptySet<String>()

    override suspend fun claimRecentImageSuggestions(newestFirstIds: List<String>, nowEpochMillis: Long): Set<String> {
        val records = RecentImageAcknowledgements.retain(recentImageAcknowledgements, nowEpochMillis)
        val claimed = RecentImageAcknowledgements.claimable(newestFirstIds, records)
        recentImageAcknowledgements = RecentImageAcknowledgements.acknowledge(records, claimed, nowEpochMillis)
        return claimed
    }

    override suspend fun acknowledgeSelectedImages(ids: List<String>, nowEpochMillis: Long) {
        recentImageAcknowledgements = RecentImageAcknowledgements.acknowledge(recentImageAcknowledgements, ids, nowEpochMillis)
    }
    var clearCallCount = 0
        private set

    override suspend fun setSharePresence(enabled: Boolean) {
        isSharePresenceEnabled.tryEmit(enabled)
    }

    override fun isSharePresenceEnabled(): Flow<Boolean> = isSharePresenceEnabled

    override suspend fun setSendPublicReadReceipts(enabled: Boolean) {
        isSendPublicReadReceiptsEnabled.tryEmit(enabled)
    }

    override fun isSendPublicReadReceiptsEnabled(): Flow<Boolean> = isSendPublicReadReceiptsEnabled

    override suspend fun setRenderReadReceipts(enabled: Boolean) {
        isRenderReadReceiptsEnabled.tryEmit(enabled)
    }

    override fun isRenderReadReceiptsEnabled(): Flow<Boolean> = isRenderReadReceiptsEnabled

    override suspend fun setSendTypingNotifications(enabled: Boolean) {
        isSendTypingNotificationsEnabled.tryEmit(enabled)
    }

    override fun isSendTypingNotificationsEnabled(): Flow<Boolean> = isSendTypingNotificationsEnabled

    override suspend fun setRenderTypingNotifications(enabled: Boolean) {
        isRenderTypingNotificationsEnabled.tryEmit(enabled)
    }

    override fun isRenderTypingNotificationsEnabled(): Flow<Boolean> = isRenderTypingNotificationsEnabled

    override suspend fun setSkipSessionVerification(skip: Boolean) {
        isSessionVerificationSkipped.tryEmit(skip)
    }

    override fun isSessionVerificationSkipped(): Flow<Boolean> {
        return isSessionVerificationSkipped
    }

    override suspend fun setOptimizeImages(compress: Boolean) = doesCompressMedia.emit(compress)

    override fun doesOptimizeImages(): Flow<Boolean> = doesCompressMedia

    override suspend fun setVideoCompressionPreset(preset: VideoCompressionPreset) {
        videoCompressionPreset.value = preset
    }

    override fun getVideoCompressionPreset(): Flow<VideoCompressionPreset> {
        return videoCompressionPreset
    }

    override suspend fun acknowledgeHistoryRecoveryBanner(roomId: String, status: String) {
        acknowledgedHistoryRecoveryBanners.value += "$roomId\u0000$status"
    }

    override suspend fun clearHistoryRecoveryBannerAcknowledgements(roomId: String) {
        acknowledgedHistoryRecoveryBanners.value = acknowledgedHistoryRecoveryBanners.value
            .filterNotTo(mutableSetOf()) { it.startsWith("$roomId\u0000") }
    }

    override fun isHistoryRecoveryBannerAcknowledged(roomId: String, status: String): Flow<Boolean> {
        val token = "$roomId\u0000$status"
        return acknowledgedHistoryRecoveryBanners.map { token in it }
    }

    override suspend fun clear() {
        clearCallCount++
        isSendPublicReadReceiptsEnabled.tryEmit(true)
        acknowledgedHistoryRecoveryBanners.value = emptySet()
        playedVoiceMessages.value = emptySet()
        recentImageAcknowledgements = emptySet()
    }

    override suspend fun markVoiceMessagePlayed(eventId: String) { playedVoiceMessages.value += eventId }
    override fun hasVoiceMessagePlayed(eventId: String): Flow<Boolean> = playedVoiceMessages.map { eventId in it }
}
