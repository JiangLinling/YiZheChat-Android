/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayoutData
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemFileContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemFileContentProvider
import io.element.android.libraries.designsystem.components.attachment.fileTypeAppearance
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.ui.strings.CommonStrings

/**
 * https://www.figma.com/design/G1xy0HDZKJf5TCRFmKb5d5/Compound-Android-Components?node-id=2019-6477&t=2yr7kvVEdtsP4p26-4
 */
@Composable
fun TimelineItemFileView(
    content: TimelineItemFileContent,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier,
    isMine: Boolean = false,
    localSendState: LocalEventSendState? = null,
) {
    val appearance = fileTypeAppearance(content.fileExtension)
    val transferStatus = when (localSendState) {
        is LocalEventSendState.Sending.MediaWithProgress -> {
            val percent = if (localSendState.total > 0L) {
                (localSendState.progress * 100L / localSendState.total).coerceIn(0L, 100L)
            } else {
                0L
            }
            stringResource(R.string.screen_room_file_upload_progress, percent)
        }
        LocalEventSendState.Sending.Encrypting -> stringResource(R.string.screen_room_send_encrypting)
        LocalEventSendState.Sending.Event -> stringResource(R.string.screen_room_file_sending)
        is LocalEventSendState.Failed -> stringResource(R.string.screen_room_file_send_failed)
        is LocalEventSendState.Sent -> stringResource(R.string.screen_room_file_sent)
        null -> stringResource(if (isMine) R.string.screen_room_file_sent else R.string.screen_room_file_tap_to_download)
    }
    val transferProgress = (localSendState as? LocalEventSendState.Sending.MediaWithProgress)?.let { state ->
        if (state.total > 0L) state.progress.toFloat() / state.total.toFloat() else null
    }
    val isTransferActive = localSendState is LocalEventSendState.Sending
    val isTransferFailed = localSendState is LocalEventSendState.Failed
    TimelineItemAttachmentView(
        icon = CompoundIcons.Attachment(),
        iconContentDescription = stringResource(CommonStrings.common_file),
        filename = content.filename,
        fileExtensionAndSize = content.fileExtensionAndSize,
        caption = content.caption,
        onContentLayoutChange = onContentLayoutChange,
        modifier = modifier,
        fileTypeLabel = appearance.label,
        fileTypeColor = appearance.color,
        transferStatus = transferStatus,
        transferProgress = transferProgress,
        isTransferActive = isTransferActive,
        isTransferFailed = isTransferFailed,
    )
}

@PreviewsDayNight
@Composable
internal fun TimelineItemFileViewPreview(@PreviewParameter(TimelineItemFileContentProvider::class) content: TimelineItemFileContent) {
    ElementTimelineItemPreview {
        TimelineItemFileView(
            content,
            onContentLayoutChange = {},
        )
    }
}
