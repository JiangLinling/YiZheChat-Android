/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.voiceplayer.impl

import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.test.media.FakeMediaFile
import io.element.android.tests.testutils.simulateLongTask

/**
 * A fake implementation of [VoiceMessageMediaRepo] for testing purposes.
 */
class FakeVoiceMessageMediaRepo : VoiceMessageMediaRepo {
    var shouldFail = false
    var mediaFile: MediaFile = FakeMediaFile("")

    override suspend fun getMediaFile(): Result<MediaFile> = simulateLongTask {
        if (shouldFail) {
            Result.failure(IllegalStateException("Failed to get media file"))
        } else {
            Result.success(mediaFile)
        }
    }
}
