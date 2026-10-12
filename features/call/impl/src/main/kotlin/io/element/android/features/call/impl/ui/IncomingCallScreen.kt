/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.call.impl.R
import io.element.android.features.call.impl.notifications.CallNotificationData
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings

/**
 * Ref: https://www.figma.com/design/0MMNu7cTOzLOlWb7ctTkv3/Element-X?node-id=16501-5740
 */
@Composable
internal fun IncomingCallScreen(
    notificationData: CallNotificationData,
    onAnswer: (CallNotificationData) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF252A2E)).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp)
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.35f))
            Avatar(
                avatarData = AvatarData(
                    id = notificationData.senderId.value,
                    name = notificationData.senderName,
                    url = notificationData.avatarUrl,
                    size = AvatarSize.UserHeader,
                ),
                avatarType = AvatarType.User,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = notificationData.senderName ?: notificationData.senderId.value,
                style = ElementTheme.typography.fontHeadingMdBold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (notificationData.isGroupCall && !notificationData.roomName.isNullOrBlank()) {
                Text(
                    text = notificationData.roomName,
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = Color(0xFFCDD5CF),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = stringResource(if (io.element.android.appconfig.NextDeploymentConfig.isNextDeployment)
                    (if (notificationData.isGroupCall) {
                        if (notificationData.audioOnly) R.string.call_group_voice_incoming else R.string.call_group_video_incoming
                    } else if (notificationData.audioOnly) R.string.call_voice_incoming else R.string.call_video_incoming)
                    else R.string.screen_incoming_call_subtitle_android),
                style = ElementTheme.typography.fontBodySmRegular,
                color = Color(0xFFCDD5CF),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.weight(0.65f))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            ActionButton(
                size = 64.dp,
                onClick = onCancel,
                icon = CompoundIcons.EndCall(),
                title = stringResource(CommonStrings.action_reject),
                backgroundColor = ElementTheme.colors.iconCriticalPrimary,
                borderColor = ElementTheme.colors.borderCriticalSubtle
            )
            ActionButton(
                size = 64.dp,
                onClick = { onAnswer(notificationData) },
                icon = if (notificationData.audioOnly) CompoundIcons.VoiceCallSolid() else CompoundIcons.VideoCallSolid(),
                title = stringResource(CommonStrings.action_accept),
                backgroundColor = ElementTheme.colors.iconSuccessPrimary,
                borderColor = ElementTheme.colors.borderSuccessSubtle
            )
        }
    }
}

@Composable
private fun ActionButton(
    size: Dp,
    onClick: () -> Unit,
    icon: ImageVector,
    title: String,
    backgroundColor: Color,
    borderColor: Color,
    contentDescription: String? = title,
    borderSize: Dp = 1.33.dp,
) {
    Column(
        modifier = Modifier.width(120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledIconButton(
            modifier = Modifier
                .size(size + borderSize)
                .border(borderSize, borderColor, CircleShape),
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = backgroundColor,
                contentColor = ElementTheme.colors.iconOnSolidPrimary,
            )
        ) {
            Icon(
                modifier = Modifier.size(32.dp),
                imageVector = icon,
                contentDescription = contentDescription
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = ElementTheme.typography.fontBodySmMedium,
            color = Color.White,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun IncomingCallScreenPreview(
    @PreviewParameter(CallNotificationDataProvider::class) state: CallNotificationData,
) = ElementPreview {
    IncomingCallScreen(
        notificationData = state,
        onAnswer = {},
        onCancel = {},
    )
}
