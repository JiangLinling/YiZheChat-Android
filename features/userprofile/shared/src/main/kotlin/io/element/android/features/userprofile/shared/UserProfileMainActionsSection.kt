/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.userprofile.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.button.MainActionButton
import io.element.android.libraries.designsystem.modifiers.niceClickable
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.ui.strings.CommonStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileMainActionsSection(
    isCurrentUser: Boolean,
    hasDm: Boolean,
    canCall: Boolean,
    onShareUser: () -> Unit,
    onStartDM: () -> Unit,
    onCall: (CallIntent) -> Unit,
    modifier: Modifier = Modifier,
    isBlocked: Boolean = false,
) {
    var choosingCall by remember { mutableStateOf(false) }
    if (isCurrentUser) {
        Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            MainActionButton(
                title = stringResource(CommonStrings.action_share),
                imageVector = CompoundIcons.ShareAndroid(),
                onClick = onShareUser,
            )
        }
    } else if (isBlocked) {
        Text(text = stringResource(CommonStrings.contact_blocked_by_you), modifier = Modifier.padding(16.dp))
    } else {
        Column(modifier.fillMaxWidth()) {
            ProfileActionRow(
                text = stringResource(if (hasDm) CommonStrings.action_message else R.string.screen_user_profile_add_friend),
                icon = CompoundIcons.Chat(),
                onClick = onStartDM,
            )
            // Opening the chooser is local UI only. RTC admission starts after an explicit selection.
            if (canCall && hasDm) {
                ProfileActionRow(
                    text = stringResource(R.string.screen_user_profile_calls),
                    icon = CompoundIcons.VideoCall(),
                    onClick = { choosingCall = true },
                )
            }
        }
    }
    if (choosingCall && canCall && hasDm && !isBlocked) {
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismissRequest = { choosingCall = false },
            scrollable = false,
        ) {
            ProfileActionRow(stringResource(R.string.screen_user_profile_video_call), CompoundIcons.VideoCall()) {
                choosingCall = false
                onCall(CallIntent.VIDEO)
            }
            ProfileActionRow(stringResource(R.string.screen_user_profile_voice_call), CompoundIcons.VoiceCall()) {
                choosingCall = false
                onCall(CallIntent.AUDIO)
            }
            ProfileActionRow(stringResource(CommonStrings.action_cancel)) { choosingCall = false }
        }
    }
}

@Composable
private fun ProfileActionRow(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().niceClickable(onClick = onClick)
            .heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(imageVector = icon, contentDescription = null, tint = ElementTheme.colors.textActionAccent)
        Text(text = text, style = ElementTheme.typography.fontBodyMdMedium, color = ElementTheme.colors.textActionAccent)
    }
}

@PreviewsDayNight()
@Composable
internal fun UserProfileMainActionsSectionPreview() = ElementPreview {
    UserProfileMainActionsSection(
        isCurrentUser = false,
        hasDm = false,
        canCall = true,
        onShareUser = { },
        onStartDM = { },
        onCall = { }
    )
}
