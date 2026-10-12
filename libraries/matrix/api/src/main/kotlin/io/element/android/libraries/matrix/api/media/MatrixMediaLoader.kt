/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.media

interface MatrixMediaLoader {
    /** Null only when disabled or ineligible. An admitted progressive fetch must fail closed on verification errors. */
    suspend fun openProgressiveVideo(source: MediaSource, wireLength: Long?): Result<MediaFile?> = Result.success(null)

    /**
     * @param source to fetch the content for.
     * @return a [Result] of ByteArray. It contains the binary data for the media.
     */
    suspend fun loadMediaContent(
        source: MediaSource,
        cachePolicy: MediaCachePolicy = MediaCachePolicy.REUSABLE,
    ): Result<ByteArray>

    /**
     * @param source to fetch the data for.
     * @param width: the desired width for rescaling the media as thumbnail
     * @param height: the desired height for rescaling the media as thumbnail
     * @return a [Result] of ByteArray. It contains the binary data for the media.
     */
    suspend fun loadMediaThumbnail(
        source: MediaSource,
        width: Long,
        height: Long,
        cachePolicy: MediaCachePolicy = MediaCachePolicy.REUSABLE,
    ): Result<ByteArray>

    /**
     * Loads public profile metadata media (user, room, or space avatars). Profile media is not a message carrier and
     * therefore does not require a YiZhe content envelope. Implementations must keep it memory-only and must never use
     * this path for timeline attachments.
     */
    suspend fun loadPublicProfileThumbnail(source: MediaSource, width: Long, height: Long): Result<ByteArray> =
        loadMediaThumbnail(source, width, height, MediaCachePolicy.REUSABLE)

    /**
     * @param source to fetch the data for.
     * @param mimeType: optional mime type.
     * @param filename: optional String which will be used to name the file.
     * @param useCache: if true, the rust sdk will cache the media in its store.
     * @return a [Result] of [MediaFile]
     */
    suspend fun downloadMediaFile(
        source: MediaSource,
        mimeType: String?,
        filename: String?,
        useCache: Boolean = true,
    ): Result<MediaFile>
}

/**
 * Persistence contract for every media-loader layer, including the Rust SDK media store.
 *
 * [EPHEMERAL] must fail closed when an implementation cannot prove that the request bypasses reusable storage. It
 * must never silently fall back to [REUSABLE].
 */
enum class MediaCachePolicy {
    REUSABLE,
    EPHEMERAL,
}

class EphemeralMediaCacheUnavailableException : IllegalStateException(
    "This media backend cannot guarantee an ephemeral fetch.",
)
