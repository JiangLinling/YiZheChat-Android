/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.api

import android.net.Uri
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMediaAlbum

fun interface MediaSenderFactory {
    /**
     * Create a [MediaSender] for the given [Timeline.Mode], in the Room Scope.
     */
    fun create(
        timelineMode: Timeline.Mode,
    ): MediaSender
}

fun interface MediaSenderRoomFactory {
    /**
     * Create a [MediaSender] for the given [JoinedRoom], with timeline mode Live.
     */
    fun create(
        room: JoinedRoom,
    ): MediaSender
}

interface MediaSender {
    suspend fun mediaSubmissionFact(transportName: String): io.element.android.libraries.matrix.api.media.MediaSubmissionFact? = null

    suspend fun preProcessMedia(
        uri: Uri,
        mimeType: String,
        mediaOptimizationConfig: MediaOptimizationConfig,
        onProgress: (Float) -> Unit = {},
    ): Result<MediaUploadInfo>

    suspend fun sendPreProcessedMedia(
        mediaUploadInfo: MediaUploadInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean = false,
        mediaAlbum: ProtectedMediaAlbum? = null,
        onProgress: (Float) -> Unit = {},
        onUploadStarted: () -> Unit = {},
        onUploadIdentity: (String) -> Unit = {},
    ): Result<Unit>

    suspend fun sendMedia(
        uri: Uri,
        mimeType: String,
        caption: String? = null,
        formattedCaption: String? = null,
        inReplyToEventId: EventId? = null,
        mediaOptimizationConfig: MediaOptimizationConfig,
        burnAfterRead: Boolean = false,
        onUploadStarted: () -> Unit = {},
    ): Result<Unit>

    suspend fun sendVoiceMessage(
        uri: Uri,
        mimeType: String,
        waveForm: List<Float>,
        onUploadIdentity: (String) -> Unit = {},
        inReplyToEventId: EventId? = null,
        burnAfterRead: Boolean = false,
        onUploadStarted: () -> Unit = {},
    ): Result<Unit>

    suspend fun sendSticker(
        uri: Uri,
        mimeType: String,
        mediaOptimizationConfig: MediaOptimizationConfig,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Sticker sending is unavailable."))

    /**
     * Sends one already PREPARED Contract B segmented-media root event.
     *
     * This method has no legacy fallback. Implementations that do not have the complete signed
     * Contract A authority, durable journal, and caller-transaction Matrix queue must fail closed.
     */
    suspend fun sendPreparedSegmentedMedia(
        request: PreparedSegmentedMediaSend,
    ): Result<SegmentedMediaSendOutcome> = Result.failure(
        UnsupportedOperationException("Contract-bound segmented-media sending is unavailable."),
    )

    fun cleanUp()
}
