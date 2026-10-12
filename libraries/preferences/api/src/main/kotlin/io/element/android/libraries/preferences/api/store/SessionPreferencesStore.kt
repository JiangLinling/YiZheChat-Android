/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.preferences.api.store

import kotlinx.coroutines.flow.Flow

interface SessionPreferencesStore {
    /** Atomically acknowledge one recent-image batch. Defaults fail closed for older adapters. */
    suspend fun claimRecentImageSuggestions(newestFirstIds: List<String>, nowEpochMillis: Long): Set<String> = emptySet()
    suspend fun acknowledgeSelectedImages(ids: List<String>, nowEpochMillis: Long) = Unit

    suspend fun setSharePresence(enabled: Boolean)
    fun isSharePresenceEnabled(): Flow<Boolean>

    suspend fun setSendPublicReadReceipts(enabled: Boolean)
    fun isSendPublicReadReceiptsEnabled(): Flow<Boolean>

    suspend fun setRenderReadReceipts(enabled: Boolean)
    fun isRenderReadReceiptsEnabled(): Flow<Boolean>

    suspend fun setSendTypingNotifications(enabled: Boolean)
    fun isSendTypingNotificationsEnabled(): Flow<Boolean>

    suspend fun setRenderTypingNotifications(enabled: Boolean)
    fun isRenderTypingNotificationsEnabled(): Flow<Boolean>

    suspend fun setSkipSessionVerification(skip: Boolean)
    fun isSessionVerificationSkipped(): Flow<Boolean>

    suspend fun setOptimizeImages(compress: Boolean)
    fun doesOptimizeImages(): Flow<Boolean>

    suspend fun setVideoCompressionPreset(preset: VideoCompressionPreset)
    fun getVideoCompressionPreset(): Flow<VideoCompressionPreset>

    /**
     * UI-only acknowledgement for a stable history-recovery notice. This never changes the
     * underlying recovery/security state and is cleared when that state converges.
     */
    suspend fun acknowledgeHistoryRecoveryBanner(roomId: String, status: String)
    suspend fun clearHistoryRecoveryBannerAcknowledgements(roomId: String)
    fun isHistoryRecoveryBannerAcknowledged(roomId: String, status: String): Flow<Boolean>

    /** Local UI acknowledgement only; never an authorization, read receipt, or media cache. */
    suspend fun markVoiceMessagePlayed(eventId: String)
    fun hasVoiceMessagePlayed(eventId: String): Flow<Boolean>

    suspend fun clear()
}
