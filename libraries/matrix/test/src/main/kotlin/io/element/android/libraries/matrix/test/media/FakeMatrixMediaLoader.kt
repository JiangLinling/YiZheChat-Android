/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.test.media

import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaCachePolicy
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.tests.testutils.simulateLongTask

class FakeMatrixMediaLoader : MatrixMediaLoader {
    var shouldFail = false
    var path: String = ""
    var lastContentCachePolicy: MediaCachePolicy? = null
    var lastThumbnailCachePolicy: MediaCachePolicy? = null

    override suspend fun loadMediaContent(source: MediaSource, cachePolicy: MediaCachePolicy): Result<ByteArray> = simulateLongTask {
        lastContentCachePolicy = cachePolicy
        if (shouldFail) {
            Result.failure(RuntimeException())
        } else {
            Result.success(ByteArray(0))
        }
    }

    override suspend fun loadMediaThumbnail(
        source: MediaSource,
        width: Long,
        height: Long,
        cachePolicy: MediaCachePolicy,
    ): Result<ByteArray> = simulateLongTask {
        lastThumbnailCachePolicy = cachePolicy
        if (shouldFail) {
            Result.failure(RuntimeException())
        } else {
            Result.success(ByteArray(0))
        }
    }

    override suspend fun downloadMediaFile(
        source: MediaSource,
        mimeType: String?,
        filename: String?,
        useCache: Boolean,
    ): Result<MediaFile> = simulateLongTask {
        if (shouldFail) {
            Result.failure(RuntimeException())
        } else {
            Result.success(FakeMediaFile(path))
        }
    }
}
