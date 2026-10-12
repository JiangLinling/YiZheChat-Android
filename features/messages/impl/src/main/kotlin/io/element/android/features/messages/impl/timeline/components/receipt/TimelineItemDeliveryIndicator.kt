/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.receipt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.delay

@Composable
fun TimelineItemDeliveryIndicator(
    state: ReadReceiptViewState,
    renderReadReceipts: Boolean,
    onFailureClick: () -> Unit,
    onReadReceiptsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val indicator = resolveDeliveryIndicator(state, renderReadReceipts)
    val isPending = indicator == DeliveryIndicator.Sending || indicator == DeliveryIndicator.Encrypting
    var canShowSending by remember { mutableStateOf(false) }
    LaunchedEffect(isPending) {
        canShowSending = false
        if (isPending) {
            delay(SENDING_INDICATOR_DELAY_MILLIS)
            canShowSending = true
        }
    }

    if (indicator == DeliveryIndicator.Hidden ||
        isPending && !canShowSending) {
        return
    }

    val actionModifier = when (indicator) {
        DeliveryIndicator.Failed -> Modifier.clickable(onClick = onFailureClick)
        DeliveryIndicator.Read -> Modifier
            .clickable(onClick = onReadReceiptsClick)
            .semantics { testTag = TestTags.messageReadReceipts.value }
        else -> Modifier
    }
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(actionModifier),
        contentAlignment = Alignment.Center,
    ) {
        when (indicator) {
            DeliveryIndicator.Hidden -> Unit
            DeliveryIndicator.Encrypting, DeliveryIndicator.Sending ->
                MessageSendProgressIndicator(encrypting = indicator == DeliveryIndicator.Encrypting)
            DeliveryIndicator.Failed -> Icon(
                imageVector = CompoundIcons.ErrorSolid(),
                contentDescription = stringResource(CommonStrings.common_sending_failed),
                tint = ElementTheme.colors.iconCriticalPrimary,
                modifier = Modifier.size(20.dp),
            )
            DeliveryIndicator.Read -> Text(
                text = stringResource(R.string.screen_room_read_status_read),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
            DeliveryIndicator.Unread -> Text(
                text = stringResource(R.string.screen_room_read_status_unread),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
        }
    }
}

internal enum class DeliveryIndicator {
    Hidden,
    Encrypting,
    Sending,
    Failed,
    Read,
    Unread,
}

internal fun resolveDeliveryIndicator(
    state: ReadReceiptViewState,
    renderReadReceipts: Boolean,
): DeliveryIndicator = when {
    !state.isMine -> DeliveryIndicator.Hidden
    state.sendState is LocalEventSendState.Failed -> DeliveryIndicator.Failed
    state.sendState == LocalEventSendState.Sending.Encrypting -> DeliveryIndicator.Encrypting
    state.sendState is LocalEventSendState.Sending -> DeliveryIndicator.Sending
    renderReadReceipts && state.isLastOutgoingMessage && state.receipts.isNotEmpty() -> DeliveryIndicator.Read
    renderReadReceipts && state.isLastOutgoingMessage -> DeliveryIndicator.Unread
    else -> DeliveryIndicator.Hidden
}

/** Tiny visual label, with a full accessible description rather than tiny-text-only feedback. */
@Composable
internal fun MessageSendProgressIndicator(encrypting: Boolean, modifier: Modifier = Modifier) {
    val label = stringResource(if (encrypting) R.string.screen_room_send_encrypting_short else R.string.screen_room_send_sending_short)
    val description = stringResource(if (encrypting) R.string.screen_room_send_encrypting else R.string.screen_room_read_status_sending)
    Box(
        modifier = modifier.size(28.dp).clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = ElementTheme.colors.iconSecondary, strokeWidth = 1.5.dp)
        Text(
            text = label,
            style = ElementTheme.typography.fontBodyXsRegular.copy(fontSize = 8.sp, lineHeight = 10.sp),
            color = ElementTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}

private const val SENDING_INDICATOR_DELAY_MILLIS = 200L

@PreviewsDayNight
@Composable
internal fun MessageSendProgressIndicatorPreview() = ElementPreview {
    Row {
        MessageSendProgressIndicator(encrypting = true)
        MessageSendProgressIndicator(encrypting = false)
    }
}
