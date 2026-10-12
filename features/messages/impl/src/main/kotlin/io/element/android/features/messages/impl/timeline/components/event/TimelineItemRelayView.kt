/*
 * Copyright (c) 2026 YiZhe Chat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRelayContent
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.collections.immutable.persistentListOf

@Composable
internal fun TimelineItemRelayView(
    content: TimelineItemRelayContent,
    onParticipate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(min = 180.dp, max = 264.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.screen_room_relay_card_heading),
            style = ElementTheme.typography.fontBodyXsRegular,
            color = ElementTheme.colors.textActionAccent,
        )
        Text(
            text = content.title,
            style = ElementTheme.typography.fontBodyMdMedium.copy(fontSize = 14.sp),
            color = ElementTheme.colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (content.entries.isEmpty()) {
            Text(
                text = stringResource(R.string.screen_room_relay_empty),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
            )
        } else {
            content.entries.take(RELAY_CARD_MAX_VISIBLE_ENTRIES).forEachIndexed { index, entry ->
                Text(
                    text = "${index + 1}. $entry",
                    style = ElementTheme.typography.fontBodySmRegular.copy(fontSize = 13.sp),
                    color = ElementTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (content.entries.size > RELAY_CARD_MAX_VISIBLE_ENTRIES) {
                Text(
                    text = stringResource(
                        R.string.screen_room_relay_more_entries,
                        content.entries.size - RELAY_CARD_MAX_VISIBLE_ENTRIES,
                    ),
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
        }
        HorizontalDivider(color = ElementTheme.colors.borderDisabled)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = onParticipate != null) { onParticipate?.invoke() }
                .heightIn(min = 48.dp)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.screen_room_relay_entry_count, content.entries.size),
                modifier = Modifier.weight(1f),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
            Text(
                text = stringResource(
                    if (content.closed) R.string.screen_room_relay_view else R.string.screen_room_relay_participate
                ),
                style = ElementTheme.typography.fontBodySmMedium.copy(fontSize = 13.sp),
                color = if (content.closed) ElementTheme.colors.textSecondary else ElementTheme.colors.textActionAccent,
            )
        }
    }
}

private const val RELAY_CARD_MAX_VISIBLE_ENTRIES = 3

@Preview
@Composable
private fun TimelineItemRelayViewPreview() = ElementPreview {
    TimelineItemRelayView(
        content = TimelineItemRelayContent(
            body = "",
            title = "周四下午草莓采摘报名",
            entries = persistentListOf("哎哎 1人参加", "邻家小伙"),
            closed = false,
            isEdited = true,
        ),
        onParticipate = {},
        modifier = Modifier.padding(16.dp),
    )
}
