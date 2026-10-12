/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.api

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.features.location.api.live.PausedLiveLocationShare

@Composable
fun LiveLocationSharingBanner(
    onClick: (() -> Unit)?,
    onStopClick: () -> Unit,
    pausedState: PausedLiveLocationShare? = null,
    onResumeClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ElementTheme.colors.bgCanvasDefault)
            .drawBannerBorder(ElementTheme.colors.separatorPrimary)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = CompoundIcons.LocationPinSolid(),
                contentDescription = null,
                tint = ElementTheme.colors.iconAccentPrimary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(
                    pausedState?.messageResource() ?: CommonStrings.screen_room_live_location_banner
                ),
                style = ElementTheme.typography.fontBodyMdMedium,
                color = ElementTheme.colors.textPrimary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pausedState == PausedLiveLocationShare.RECOVERABLE && onResumeClick != null) {
                Button(
                    text = stringResource(R.string.action_resume_live_location),
                    onClick = onResumeClick,
                    size = ButtonSize.Small,
                )
            }
            Button(
                text = stringResource(CommonStrings.action_stop),
                onClick = onStopClick,
                destructive = true,
                size = ButtonSize.Small,
            )
        }
    }
}

fun PausedLiveLocationShare.messageResource(): Int = when (this) {
    PausedLiveLocationShare.CHECKING -> R.string.screen_room_live_location_paused_checking
    PausedLiveLocationShare.RECOVERABLE -> R.string.screen_room_live_location_paused
    PausedLiveLocationShare.UNRECOVERABLE -> R.string.screen_room_live_location_unrecoverable
}

private fun Modifier.drawBannerBorder(borderColor: Color): Modifier = drawBehind {
    val strokeWidth = 1.dp.toPx()
    val bottomY = size.height - strokeWidth / 2
    drawLine(
        color = borderColor,
        start = Offset(0f, strokeWidth / 2),
        end = Offset(size.width, strokeWidth / 2),
        strokeWidth = strokeWidth,
    )
    drawLine(
        color = borderColor,
        start = Offset(0f, bottomY),
        end = Offset(size.width, bottomY),
        strokeWidth = strokeWidth,
    )
}

@PreviewsDayNight
@Composable
internal fun LiveLocationSharingBannerPreview() = ElementPreview {
    LiveLocationSharingBanner(
        onClick = {},
        onStopClick = {},
    )
}

@PreviewsDayNight
@Composable
internal fun LiveLocationPausedBannerPreview() = ElementPreview {
    androidx.compose.foundation.layout.Column {
        PausedLiveLocationShare.entries.forEach { state ->
            LiveLocationSharingBanner(onClick = null, onStopClick = {}, pausedState = state)
        }
    }
}
