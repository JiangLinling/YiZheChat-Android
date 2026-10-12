/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.test

import android.net.Uri
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMediaAlbum
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaSender
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.tests.testutils.lambda.lambdaError

class FakeMediaSender(
    private val preProcessMediaResult: () -> Result<MediaUploadInfo> = { lambdaError() },
    private val sendPreProcessedMediaResult: () -> Result<Unit> = { lambdaError() },
    private val sendMediaResult: () -> Result<Unit> = { lambdaError() },
    private val beforeVoiceUploadStarted: suspend () -> Unit = {},
    private val sendVoiceMessageResult: suspend () -> Result<Unit> = { lambdaError() },
    private val cleanUpResult: () -> Unit = { lambdaError() },
) : MediaSender {
    override suspend fun preProcessMedia(
        uri: Uri,
        mimeType: String,
        mediaOptimizationConfig: MediaOptimizationConfig,
        onProgress: (Float) -> Unit,
    ): Result<MediaUploadInfo> {
        onProgress(1f)
        return preProcessMediaResult()
    }

    override suspend fun sendPreProcessedMedia(
        mediaUploadInfo: MediaUploadInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
        mediaAlbum: ProtectedMediaAlbum?,
        onProgress: (Float) -> Unit,
        onUploadStarted: () -> Unit,
        onUploadIdentity: (String) -> Unit,
    ): Result<Unit> {
        onUploadStarted()
        return sendPreProcessedMediaResult()
    }

    override suspend fun sendMedia(
        uri: Uri,
        mimeType: String,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        mediaOptimizationConfig: MediaOptimizationConfig,
        burnAfterRead: Boolean,
        onUploadStarted: () -> Unit,
    ): Result<Unit> {
        onUploadStarted()
        return sendMediaResult()
    }

    override suspend fun sendVoiceMessage(
        uri: Uri,
        mimeType: String,
        waveForm: List<Float>,
        onUploadIdentity: (String) -> Unit,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
        onUploadStarted: () -> Unit,
    ): Result<Unit> {
        beforeVoiceUploadStarted()
        onUploadStarted()
        return sendVoiceMessageResult()
    }

    override fun cleanUp() {
        cleanUpResult()
    }
}
