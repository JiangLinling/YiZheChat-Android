/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.textcomposer.R

/**
 * Send button for the message composer.
 * Figma: https://www.figma.com/design/G1xy0HDZKJf5TCRFmKb5d5/Compound-Android-Components?node-id=1956-37575&node-type=frame&m=dev
 */
@Composable
internal fun SendButtonIcon(
    canSendMessage: Boolean,
    isEditing: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val iconVector = when {
        isEditing -> CompoundIcons.Check()
        else -> CompoundIcons.SendSolid()
    }
    val iconStartPadding = when {
        isEditing -> 0.dp
        else -> 2.dp
    }
    val backgroundColor = if (canSendMessage) {
        ElementTheme.colors.bgAccentRest
    } else {
        Color.Transparent
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .size(width = 72.dp, height = 38.dp)
            .background(backgroundColor)
    ) {
        if (isEditing) {
            Icon(
                modifier = Modifier
                    .padding(start = iconStartPadding)
                    .size(24.dp)
                    .align(Alignment.Center),
                imageVector = iconVector,
                contentDescription = null,
                tint = if (canSendMessage) ElementTheme.colors.iconOnSolidPrimary else ElementTheme.colors.iconQuaternary,
            )
        } else {
            Text(
                modifier = Modifier.align(Alignment.Center),
                text = label ?: stringResource(R.string.text_composer_send),
                style = ElementTheme.typography.fontBodyMdMedium.copy(fontSize = 16.sp),
                color = if (canSendMessage) ElementTheme.colors.iconOnSolidPrimary else ElementTheme.colors.iconQuaternary,
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun SendButtonIconPreview() = ElementPreview {
    Row {
        IconButton(onClick = {}) {
            SendButtonIcon(canSendMessage = true, isEditing = false)
        }
        IconButton(onClick = {}) {
            SendButtonIcon(canSendMessage = false, isEditing = false)
        }
        IconButton(onClick = {}) {
            SendButtonIcon(canSendMessage = true, isEditing = true)
        }
        IconButton(onClick = {}) {
            SendButtonIcon(canSendMessage = false, isEditing = true)
        }
    }
}
