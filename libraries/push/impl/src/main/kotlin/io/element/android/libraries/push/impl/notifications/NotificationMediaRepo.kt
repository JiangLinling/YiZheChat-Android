/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource

/**
 * Fetches the media file for a notification.
 *
 * Media is downloaded from the rust sdk and stored in the application's cache directory.
 * Media files are indexed by their Matrix Content (mxc://) URI and considered immutable.
 * Whenever a given mxc is found in the cache, it is returned immediately.
 */
interface NotificationMediaRepo {
    /**
     * Factory for [NotificationMediaRepo].
     */
    fun interface Factory {
        /**
         * Creates a [NotificationMediaRepo].
         *
         */
        fun create(
            client: MatrixClient
        ): NotificationMediaRepo
    }

    /**
     * Returns the file.
     *
     * In case of a cache hit the file is returned immediately.
     * In case of a cache miss the file is downloaded and then returned.
     *
     * @param mediaSource the media source of the media.
     * @param mimeType the mime type of the media.
     * @param filename optional String which will be used to name the file.
     * @return A [Result] holding a revocable media handle or an [Exception].
     */
    suspend fun getMediaFile(
        mediaSource: MediaSource,
        mimeType: String?,
        filename: String?,
    ): Result<MediaFile>
}

@AssistedInject
class DefaultNotificationMediaRepo(
    @Assisted private val client: MatrixClient,
) : NotificationMediaRepo {
    @ContributesBinding(AppScope::class)
    @AssistedFactory
    fun interface Factory : NotificationMediaRepo.Factory {
        override fun create(
            client: MatrixClient,
        ): DefaultNotificationMediaRepo
    }

    private val matrixMediaLoader = client.matrixMediaLoader

    override suspend fun getMediaFile(
        mediaSource: MediaSource,
        mimeType: String?,
        filename: String?,
    ): Result<MediaFile> = matrixMediaLoader.downloadMediaFile(
        source = mediaSource,
        mimeType = mimeType,
        filename = filename,
        useCache = false,
    )
}
