/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.office

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.element.android.libraries.localofficeviewer.api.LocalOfficeCleanupResult
import io.element.android.libraries.localofficeviewer.api.LocalOfficeCloseReason
import io.element.android.libraries.localofficeviewer.api.LocalOfficeContentSource
import io.element.android.libraries.localofficeviewer.api.LocalOfficePreviewRequest
import io.element.android.libraries.localofficeviewer.api.LocalOfficePreviewState
import io.element.android.libraries.localofficeviewer.impl.LocalOfficePreviewView
import io.element.android.libraries.mediaviewer.api.MediaInfo
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderMode
import io.element.android.libraries.mediaviewer.impl.local.LocalMediaViewState
import io.element.android.libraries.mediaviewer.impl.viewer.officeDocumentTopBarContentHeight
import timber.log.Timber

/**
 * LC-16 host adapter for the isolated, network-disabled local Office viewer.
 *
 * The adapter does not own the underlying Matrix/Secure Storage lease. It only gives the viewer a
 * single-attachment stream while this composition is alive. The existing media data source remains
 * responsible for closing the lease and deleting its encrypted object when the viewer is dismissed.
 */
@Composable
internal fun LocalOfficeMediaView(
    localMediaViewState: LocalMediaViewState,
    localMedia: LocalMedia?,
    mediaInfo: MediaInfo,
    modifier: Modifier = Modifier,
    renderMode: LocalMediaRenderMode = LocalMediaRenderMode.STANDALONE,
) {
    val standaloneTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        officeDocumentTopBarContentHeight
    val topContentPadding = officeTopContentPadding(renderMode, standaloneTopPadding)
    val contentModifier = modifier.padding(top = topContentPadding)
    if (localMedia == null) {
        localMediaViewState.isReady = true
        Box(modifier = contentModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val context = LocalContext.current
    val filePreviewLease = rememberBoundFilePreviewLease(
        localMedia = localMedia,
        expectedSecureMediaAuthority = "${context.packageName}.securemedia",
    )
    val request = remember(
        localMedia.uri,
        localMedia.lifecycleOperationId,
        localMedia.lifecycleRoomIdHash,
        localMedia.filePreviewLeaseToken,
        mediaInfo,
    ) {
        LocalOfficePreviewRequest(
            operationId = localMedia.lifecycleOperationId,
            attachmentId = localMedia.lifecycleOperationId,
            displayName = mediaInfo.filename,
            declaredMimeType = mediaInfo.mimeType,
            declaredSize = mediaInfo.fileSize,
            contentSource = LocalOfficeContentSource {
                checkNotNull(filePreviewLease) { "The LC-16 file-preview lease is unavailable." }
                    .openInputStream()
            },
        )
    }

    LocalOfficePreviewView(
        request = request,
        hostLease = filePreviewLease,
        requireHostLease = true,
        onState = { state ->
            localMediaViewState.isReady = state in setOf(
                LocalOfficePreviewState.READY,
                LocalOfficePreviewState.UNSUPPORTED,
                LocalOfficePreviewState.SECURITY_BLOCKED,
                LocalOfficePreviewState.VALIDATION_FAILED,
                LocalOfficePreviewState.RENDER_FAILED,
                LocalOfficePreviewState.CLOSED,
                LocalOfficePreviewState.RESOURCES_RELEASED,
            )
        },
        onClose = { reason: LocalOfficeCloseReason, cleanup: LocalOfficeCleanupResult ->
            localMediaViewState.isReady = true
            Timber.d(
                "LC-16 local Office viewer closed reason=%s webReleased=%s sourceClosed=%s payloadCleared=%s hostLeaseReleased=%s pendingRecovery=%s",
                reason,
                cleanup.webRuntimeReleased,
                cleanup.sourceClosed,
                cleanup.validatedPayloadCleared,
                cleanup.hostLeaseReleased,
                cleanup.pendingRecovery,
            )
        },
        modifier = contentModifier,
    )
}

internal fun officeTopContentPadding(
    renderMode: LocalMediaRenderMode,
    standaloneTopPadding: Dp,
): Dp = if (renderMode == LocalMediaRenderMode.EMBEDDED) 0.dp else standaloneTopPadding
