/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.receipt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.testtags.TestTags

@Composable
fun TimelineItemReadReceiptView(
    state: ReadReceiptViewState,
    renderReadReceipts: Boolean,
    onReadReceiptsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!renderReadReceipts || !state.isMine) return

    val isPending = state.sendState is LocalEventSendState.Sending
    val isFailed = state.sendState is LocalEventSendState.Failed
    val shouldShowLabel = state.isLastOutgoingMessage || isPending || isFailed

    val hasReceipts = state.receipts.isNotEmpty()
    val contentDescription = when {
        !shouldShowLabel -> null
        hasReceipts && state.receipts.size == 1 -> stringResource(R.string.screen_room_read_status_read)
        hasReceipts -> stringResource(R.string.screen_room_read_status_read_count, state.receipts.size)
        state.sendState is LocalEventSendState.Sending -> stringResource(R.string.screen_room_read_status_sending)
        state.sendState is LocalEventSendState.Failed -> stringResource(R.string.screen_room_read_status_failed)
        else -> stringResource(R.string.screen_room_read_status_sent)
    }

    if (contentDescription == null) return

    // Invisible delivery metadata must not reserve a blank row or shrink message content.
    Box(
        modifier = modifier
            .width(READ_RECEIPT_SLOT_WIDTH)
            .height(READ_RECEIPT_SLOT_HEIGHT),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val icon = when {
            isFailed -> CompoundIcons.Close()
            isPending -> CompoundIcons.SendSolid()
            hasReceipts -> CompoundIcons.MarkAsRead()
            else -> CompoundIcons.Check()
        }
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFailed) ElementTheme.colors.iconCriticalPrimary else ElementTheme.colors.iconSecondary,
            modifier = Modifier
                .size(14.dp)
                .then(
                    if (hasReceipts) {
                        Modifier
                            .clickable(onClick = onReadReceiptsClick)
                            .semantics { testTag = TestTags.messageReadReceipts.value }
                    } else {
                        Modifier
                    }
                ),
        )
    }
}

private val READ_RECEIPT_SLOT_WIDTH = 20.dp
private val READ_RECEIPT_SLOT_HEIGHT = 18.dp

@PreviewsDayNight
@Composable
internal fun TimelineItemReadReceiptViewPreview(
    @PreviewParameter(ReadReceiptViewStateProvider::class) state: ReadReceiptViewState,
) = ElementPreview {
    TimelineItemReadReceiptView(
        state = state,
        renderReadReceipts = true,
        onReadReceiptsClick = {},
    )
}
