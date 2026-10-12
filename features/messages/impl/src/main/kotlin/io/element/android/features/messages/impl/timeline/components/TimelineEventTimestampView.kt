/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.components.receipt.ReadReceiptViewState
import io.element.android.features.messages.impl.timeline.components.receipt.TimelineItemReadReceiptView
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.isEdited
import io.element.android.features.messages.impl.timeline.model.event.isRedacted
import io.element.android.libraries.core.bool.orFalse
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun TimelineEventTimestampView(
    event: TimelineItem.Event,
    eventSink: (TimelineEvent.TimelineItemEvent) -> Unit,
    showTime: Boolean = true,
    showDeliveryState: Boolean = true,
    readReceiptState: ReadReceiptViewState? = null,
    renderReadReceipts: Boolean = false,
    onReadReceiptsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val formattedTime = event.sentTime
    val pendingError = (event.localSendState as? LocalEventSendState.Failed.Unknown)?.error
    val pendingLabel = if (pendingError == "MediaCancellationPendingException") CommonStrings.screen_room_media_cancellation_pending
        else CommonStrings.screen_room_media_submission_pending
    val cancellationPending = (event.localSendState as? LocalEventSendState.Failed.Unknown)?.error in setOf("MediaCancellationPendingException", "MediaSendOutcomeUnknownException")
    val hasError = showDeliveryState && event.failedToSend
    val hasEncryptionCritical = event.messageShield?.isCritical.orFalse()
    val isMessageEdited = event.content.isEdited()
    val isMessageRedacted = event.content.isRedacted()
    val showReadReceipt = showDeliveryState && readReceiptState?.isMine == true && renderReadReceipts
    val tint = if (hasError || hasEncryptionCritical && !isMessageRedacted) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textSecondary
    val shield = event.messageShield

    if (!showTime && !isMessageEdited && !hasError && !showReadReceipt && (shield == null || isMessageRedacted)) {
        return
    }

    val isVerifiedUserSendFailure = event.localSendState is LocalEventSendState.Failed.VerifiedUser
    val onClickLabel = when {
        shield != null -> stringResource(CommonStrings.a11y_view_details)
        hasError && isVerifiedUserSendFailure -> stringResource(CommonStrings.action_open_context_menu)
        hasError && !cancellationPending -> stringResource(CommonStrings.action_retry)
        else -> null
    }
    val clickableModifier = remember(shield, hasError, cancellationPending, isVerifiedUserSendFailure, event.id) {
        when {
            shield != null -> {
                Modifier.clickable(
                    onClickLabel = onClickLabel,
                ) {
                    eventSink(TimelineEvent.ShowShieldDialog(shield))
                }
            }
            hasError && !cancellationPending -> Modifier.clickable(onClickLabel = onClickLabel) {
                if (isVerifiedUserSendFailure) {
                    eventSink(TimelineEvent.ComputeVerifiedUserSendFailure(event))
                } else {
                    eventSink(TimelineEvent.RetrySend(event))
                }
            }
            else -> Modifier
        }
    }
    Row(
        modifier = Modifier
            .padding(PaddingValues(start = TimelineEventTimestampViewDefaults.spacing))
            // For a better click target, make the corners rounded
            .clip(RoundedCornerShape(8.dp))
            .then(clickableModifier)
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isMessageEdited) {
            Text(
                stringResource(CommonStrings.common_edited_suffix),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = tint,
            )
            if (showTime) Spacer(modifier = Modifier.width(4.dp))
        }
        if (showTime) {
            Text(
                formattedTime,
                style = ElementTheme.typography.fontBodyXsRegular,
                color = tint,
            )
        }
        if (showReadReceipt) {
            if (showTime || isMessageEdited) Spacer(modifier = Modifier.width(4.dp))
            TimelineItemReadReceiptView(
                state = readReceiptState,
                renderReadReceipts = renderReadReceipts,
                onReadReceiptsClick = onReadReceiptsClick,
            )
        }
        if (hasError) {
            if (cancellationPending) {
                Text(stringResource(pendingLabel), style = ElementTheme.typography.fontBodyXsRegular, color = tint)
            }
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = CompoundIcons.ErrorSolid(),
                contentDescription = stringResource(id = if (cancellationPending) pendingLabel else CommonStrings.common_sending_failed),
                tint = tint,
                modifier = Modifier.size(15.dp, 18.dp),
            )
        }

        if (!isMessageRedacted) {
            shield?.let { shield ->
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = shield.toIcon(),
                    contentDescription = stringResource(id = CommonStrings.a11y_encryption_details),
                    modifier = Modifier.size(15.dp),
                    tint = shield.toIconColor(),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun TimelineEventTimestampViewPreview(@PreviewParameter(TimelineItemEventForTimestampViewProvider::class) event: TimelineItem.Event) = ElementPreview {
    TimelineEventTimestampView(
        event = event,
        eventSink = {},
    )
}

object TimelineEventTimestampViewDefaults {
    val spacing = 16.dp
}
