/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.voicemessages.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.element.android.libraries.designsystem.components.dialogs.ErrorDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
internal fun VoiceRecordingPermissionLostDialog(onDismiss: () -> Unit) {
    ErrorDialog(
        title = stringResource(CommonStrings.screen_voice_recording_stopped_title),
        content = stringResource(CommonStrings.screen_voice_recording_permission_lost),
        onSubmit = onDismiss,
        submitText = stringResource(CommonStrings.action_ok),
    )
}

@PreviewsDayNight
@Composable
private fun VoiceRecordingPermissionLostDialogPreview() = ElementPreview {
    VoiceRecordingPermissionLostDialog(onDismiss = {})
}
