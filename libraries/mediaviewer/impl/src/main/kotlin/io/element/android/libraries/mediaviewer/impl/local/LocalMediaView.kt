/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.element.android.features.viewfolder.api.TextFileViewer
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeAudio
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeImage
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.mediaviewer.api.MediaInfo
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderMode
import io.element.android.libraries.mediaviewer.impl.local.archive.MediaArchiveView
import io.element.android.libraries.mediaviewer.impl.local.audio.MediaAudioView
import io.element.android.libraries.mediaviewer.impl.local.file.MediaFileView
import io.element.android.libraries.mediaviewer.impl.local.image.MediaImageView
import io.element.android.libraries.mediaviewer.impl.local.office.LocalOfficeMediaView
import io.element.android.libraries.mediaviewer.impl.local.office.OfficePreviewService
import io.element.android.libraries.mediaviewer.impl.local.pdf.LocalPdfMediaView
import io.element.android.libraries.mediaviewer.impl.local.txt.TextFileView
import io.element.android.libraries.mediaviewer.impl.local.video.MediaVideoView

@Composable
fun LocalMediaView(
    localMedia: LocalMedia?,
    bottomPaddingInPixels: Int,
    audioFocus: AudioFocus?,
    onClick: () -> Unit,
    onOpenWith: (() -> Unit)?,
    textFileViewer: TextFileViewer,
    modifier: Modifier = Modifier,
    isDisplayed: Boolean = true,
    isUserSelected: Boolean = false,
    localMediaViewState: LocalMediaViewState = rememberLocalMediaViewState(),
    mediaInfo: MediaInfo? = localMedia?.info,
    onPlaybackEnded: () -> Unit = {},
    officePreviewService: OfficePreviewService? = null,
    officePreviewAccountId: String? = null,
    officePreviewDeviceId: String? = null,
    isOneTimeView: Boolean = false,
    localMediaRenderMode: LocalMediaRenderMode = LocalMediaRenderMode.STANDALONE,
) {
    val mimeType = mediaInfo?.mimeType
    when {
        mimeType.isMimeTypeImage() -> MediaImageView(
            localMediaViewState = localMediaViewState,
            localMedia = localMedia,
            modifier = modifier,
            onClick = onClick,
        )
        mimeType.isMimeTypeVideo() -> MediaVideoView(
            isDisplayed = isDisplayed,
            localMediaViewState = localMediaViewState,
            bottomPaddingInPixels = bottomPaddingInPixels,
            localMedia = localMedia,
            autoplay = isUserSelected,
            audioFocus = audioFocus,
            onPlaybackEnded = onPlaybackEnded,
            modifier = modifier,
        )
        mediaInfo.isOfficeDocument() -> {
            if (!isOneTimeView) {
                LocalOfficeMediaView(
                    localMediaViewState = localMediaViewState,
                    localMedia = localMedia,
                    mediaInfo = requireNotNull(mediaInfo),
                    modifier = modifier,
                    renderMode = localMediaRenderMode,
                )
            } else {
                MediaFileView(
                    localMediaViewState = localMediaViewState,
                    uri = localMedia?.uri,
                    info = mediaInfo,
                    modifier = modifier,
                    onOpenWith = onOpenWith,
                )
            }
        }
        mimeType == MimeTypes.PlainText -> TextFileView(
            localMedia = localMedia,
            textFileViewer = textFileViewer,
            modifier = modifier,
        )
        mimeType == MimeTypes.Pdf -> LocalPdfMediaView(
            localMediaViewState = localMediaViewState,
            localMedia = localMedia,
            modifier = modifier,
            onClick = onClick,
        )
        mediaInfo.isZipArchive() -> MediaArchiveView(
            localMediaViewState = localMediaViewState,
            localMedia = localMedia,
            modifier = modifier,
        )
        mimeType.isMimeTypeAudio() -> MediaAudioView(
            isDisplayed = isDisplayed,
            localMediaViewState = localMediaViewState,
            bottomPaddingInPixels = bottomPaddingInPixels,
            localMedia = localMedia,
            info = mediaInfo,
            audioFocus = audioFocus,
            onPlaybackEnded = onPlaybackEnded,
            modifier = modifier,
        )
        else -> MediaFileView(
            localMediaViewState = localMediaViewState,
            uri = localMedia?.uri,
            info = mediaInfo,
            modifier = modifier,
            onOpenWith = onOpenWith,
        )
    }
}

internal fun MediaInfo?.isOfficeDocument(): Boolean {
    val mimeType = this?.mimeType
    val extension = this?.fileExtension?.lowercase()
    return mimeType == "application/msword" ||
        mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
        mimeType.equals("application/vnd.ms-word.document.macroenabled.12", ignoreCase = true) ||
        mimeType == "application/vnd.ms-excel" ||
        mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
        mimeType.equals("application/vnd.ms-excel.sheet.macroenabled.12", ignoreCase = true) ||
        mimeType == "application/vnd.ms-powerpoint" ||
        mimeType == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
        mimeType == "text/csv" ||
        mimeType == "application/csv" ||
        extension in setOf("doc", "docx", "docm", "xls", "xlsx", "xlsm", "ppt", "pptx", "csv")
}

internal fun MediaInfo?.requiresFilePreviewLease(): Boolean =
    isOfficeDocument() || this?.mimeType == MimeTypes.Pdf

private fun MediaInfo?.isZipArchive(): Boolean {
    val mimeType = this?.mimeType
    val extension = this?.fileExtension?.lowercase()
    return mimeType == "application/zip" || mimeType == "application/x-zip-compressed" || extension == "zip"
}
