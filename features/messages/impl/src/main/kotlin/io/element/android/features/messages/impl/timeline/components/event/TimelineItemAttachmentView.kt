/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayout
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayoutData
import io.element.android.libraries.designsystem.components.attachment.FileTypeIcon
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.LinearProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text

/**
 * package-private, you should only use TimelineItemFileView and TimelineItemAudioView.
 * https://www.figma.com/design/G1xy0HDZKJf5TCRFmKb5d5/Compound-Android-Components?node-id=2019-8180
 */
@Composable
fun TimelineItemAttachmentView(
    icon: ImageVector,
    iconContentDescription: String?,
    filename: String,
    fileExtensionAndSize: String,
    caption: String?,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier,
    fileTypeLabel: String? = null,
    fileTypeColor: Color = ElementTheme.colors.bgAccentRest,
    transferStatus: String? = null,
    transferProgress: Float? = null,
    isTransferActive: Boolean = false,
    isTransferFailed: Boolean = false,
) {
    Column(
        modifier = modifier
            .widthIn(min = 220.dp, max = 310.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        TimelineItemAttachmentHeaderView(
            icon = icon,
            iconContentDescription = iconContentDescription,
            filename = filename,
            fileExtensionAndSize = fileExtensionAndSize,
            hasCaption = caption != null,
            onContentLayoutChange = onContentLayoutChange,
            fileTypeLabel = fileTypeLabel,
            fileTypeColor = fileTypeColor,
            transferStatus = transferStatus,
            transferProgress = transferProgress,
            isTransferActive = isTransferActive,
            isTransferFailed = isTransferFailed,
        )
        if (isTransferActive) {
            if (transferProgress == null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .height(2.dp),
                    color = fileTypeColor,
                )
            } else {
                LinearProgressIndicator(
                    progress = { transferProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .height(2.dp),
                    color = fileTypeColor,
                )
            }
        }
        if (caption != null) {
            TimelineItemAttachmentCaptionView(
                modifier = Modifier.padding(top = 4.dp),
                caption = caption,
                onContentLayoutChange = onContentLayoutChange,
            )
        }
    }
}

@Composable
private fun TimelineItemAttachmentHeaderView(
    icon: ImageVector,
    iconContentDescription: String?,
    filename: String,
    fileExtensionAndSize: String,
    hasCaption: Boolean,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    fileTypeLabel: String?,
    fileTypeColor: Color,
    transferStatus: String?,
    transferProgress: Float?,
    isTransferActive: Boolean,
    isTransferFailed: Boolean,
    modifier: Modifier = Modifier,
) {
    val iconSize = if (fileTypeLabel == null) 36.dp else 52.dp
    val spacing = 12.dp
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (fileTypeLabel == null) {
            Box(
                modifier = Modifier
                    .size(iconSize)
                    .background(ElementTheme.colors.bgSubtleSecondary, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    tint = ElementTheme.colors.iconPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = filename,
                color = ElementTheme.colors.textPrimary,
                maxLines = 2,
                style = ElementTheme.typography.fontBodyLgRegular,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(fileExtensionAndSize.takeIf { it.isNotBlank() }, transferStatus)
                    .joinToString(" · "),
                color = ElementTheme.colors.textSecondary,
                style = ElementTheme.typography.fontBodySmRegular,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = if (hasCaption) {
                    {}
                } else {
                    ContentAvoidingLayout.measureLastTextLine(
                        onContentLayoutChange = onContentLayoutChange,
                        extraWidth = iconSize + spacing
                    )
                },
            )
        }
        if (fileTypeLabel != null) {
            when {
                isTransferActive -> FileTransferProgressIcon(
                    progress = transferProgress,
                    color = fileTypeColor,
                    modifier = Modifier.size(width = 46.dp, height = iconSize),
                )
                isTransferFailed -> FileTransferFailedIcon(
                    modifier = Modifier.size(width = 46.dp, height = iconSize),
                )
                else -> FileTypeIcon(
                    label = fileTypeLabel,
                    color = fileTypeColor,
                    modifier = Modifier.size(width = 46.dp, height = iconSize),
                )
            }
        }
    }
}

@Composable
private fun FileTransferProgressIcon(
    progress: Float?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (progress == null) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                color = color,
                strokeWidth = 3.dp,
            )
        } else {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.size(34.dp),
                color = color,
                strokeWidth = 3.dp,
            )
        }
        Icon(
            imageVector = CompoundIcons.PauseSolid(),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun FileTransferFailedIcon(
    modifier: Modifier = Modifier,
) {
    val color = ElementTheme.colors.textCriticalPrimary
    Box(
        modifier = modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CompoundIcons.FileError(),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun TimelineItemAttachmentCaptionView(
    caption: String,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = caption,
        color = ElementTheme.colors.textPrimary,
        style = ElementTheme.typography.fontBodyLgRegular,
        onTextLayout = ContentAvoidingLayout.measureLastTextLine(
            onContentLayoutChange = onContentLayoutChange,
        )
    )
}
