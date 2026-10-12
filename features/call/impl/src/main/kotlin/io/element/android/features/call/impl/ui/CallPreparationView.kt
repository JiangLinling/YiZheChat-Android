package io.element.android.features.call.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import androidx.compose.material3.CircularProgressIndicator
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.call.impl.R
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.avatar.*
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.features.call.impl.security.CallProtectionDeniedException
import io.element.android.features.call.impl.security.CallProtectionDenialReason
import io.element.android.features.call.impl.utils.IncomingCallUnavailableException

@Composable
internal fun CallPreparationView(state: CallScreenState) {
    val failure = (state.urlState as? AsyncData.Failure)?.error
    val text = when (failure) {
        is IncomingCallUnavailableException -> if (failure.ended) R.string.call_invitation_ended else R.string.call_connection_failed
        is CallProtectionDeniedException -> when (failure.reason) {
            CallProtectionDenialReason.VAULT_LOCKED -> R.string.call_vault_locked
            CallProtectionDenialReason.DEVICE_ACCESS_BLOCKED,
            CallProtectionDenialReason.HARDWARE_REQUIREMENT_NOT_MET -> R.string.call_device_unavailable
            else -> R.string.call_authorization_unavailable
        }
        null -> if (state.isNetworkDisconnected) R.string.call_network_disconnected
            else if (state.isIncoming) R.string.call_accept_connecting else R.string.call_preparing
        else -> if (state.isNetworkDisconnected) R.string.call_network_unavailable else R.string.call_connection_failed
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF252A2E))
            .windowInsetsPadding(WindowInsets.systemBars).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(avatarData = AvatarData(id = "call-peer", name = state.peerName,
                url = state.peerAvatarUrl, size = AvatarSize.UserHeader), avatarType = AvatarType.User)
            Spacer(Modifier.height(24.dp))
            Text(state.peerName ?: stringResource(R.string.call_private_caller_name),
                style = ElementTheme.typography.fontHeadingMdBold, color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(stringResource(if (state.isAudioCall) R.string.call_voice_type else R.string.call_video_type), color = Color(0xFFCDD5CF))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (failure == null) CircularProgressIndicator(color = Color(0xFFCDD5CF))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(text), color = Color.White)
        }
        Button(
            onClick = { state.eventSink(CallScreenEvent.Hangup) },
            modifier = Modifier.heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (failure == null) Color(0xFFDD4D54) else Color(0xFF435148),
                contentColor = Color.White,
            ),
        ) {
            Text(stringResource(if (failure == null) R.string.call_cancel_preparation else R.string.call_back_to_chat))
        }
    }
}

@PreviewsDayNight
@Composable
internal fun CallPreparationViewPreview(
    @PreviewParameter(CallScreenStateProvider::class) state: CallScreenState,
) = ElementPreview {
    CallPreparationView(state)
}
