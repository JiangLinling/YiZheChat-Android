package io.element.android.features.messages.impl.timeline.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.features.roomcall.api.RoomCallState
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight

@Composable
internal fun GroupCallBanner(call: RoomCallState.OnGoing, onJoin: () -> Unit) {
    val accent = ElementTheme.colors.iconAccentTertiary
    Row(
        modifier = Modifier.fillMaxWidth().background(accent.copy(alpha = 0.08f)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (call.isAudioCall) CompoundIcons.VoiceCallSolid() else CompoundIcons.VideoCallSolid(),
            contentDescription = null, tint = accent, modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(if (call.isAudioCall) R.string.screen_messages_group_voice_ongoing else R.string.screen_messages_group_video_ongoing),
            modifier = Modifier.weight(1f), style = ElementTheme.typography.fontBodyMdRegular,
        )
        TextButton(onClick = onJoin, enabled = call.canJoinCall) {
            Text(text = stringResource(CommonStrings.action_join), color = if (call.canJoinCall) accent else ElementTheme.colors.textDisabled)
        }
    }
}

@PreviewsDayNight
@Composable
internal fun GroupCallBannerPreview() = ElementPreview {
    GroupCallBanner(RoomCallState.OnGoing(canJoinCall = true, isUserInTheCall = false, isUserLocallyInTheCall = false, isAudioCall = false), {})
}
