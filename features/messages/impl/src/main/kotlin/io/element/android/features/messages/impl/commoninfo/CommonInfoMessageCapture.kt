/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.commoninfo

import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.features.messages.impl.timeline.model.event.canBeCopied
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource

/** Explicit copy into a new private resource, never a favourite reference or a retained burn message. */
internal data class CommonInfoMessageCapture(val eventId: EventId, val draft: CommonInfoItem, val source: MessageClipboardSource) {
    fun isValid(items: List<TimelineItem>): Boolean = items.filterIsInstance<TimelineItem.Event>()
        .firstOrNull { it.eventId == eventId && it.clipboardSource == source }?.let(::commonInfoMessageText) == draft.content
}

internal fun commonInfoMessageText(event: TimelineItem.Event): String? {
    if (!event.isRemote || !event.content.canBeCopied()) return null
    when (val source = event.clipboardSource) {
        null -> return null
        MessageClipboardSource.Unprotected -> Unit
        is MessageClipboardSource.Protected ->
            if (source.eventId != event.eventId?.value || source.expiresAtEpochMillis <= System.currentTimeMillis()) return null
    }
    return (event.content as? TimelineItemTextBasedContent)?.plainText?.takeIf { it.isNotBlank() && it.length <= 20_000 }
}

internal fun captureCommonInfoMessage(event: TimelineItem.Event): CommonInfoMessageCapture? {
    val text = commonInfoMessageText(event) ?: return null
    // Recognize only complete standalone values. Mixed text remains editable as custom content.
    val value = text.trim()
    val kind = when {
        value.matches(Regex("[0-9]{17}[0-9Xx]")) -> CommonInfoKind.IDENTITY
        value.matches(Regex("1[3-9][0-9]{9}")) -> CommonInfoKind.PHONE
        else -> CommonInfoKind.TEXT
    }
    return CommonInfoMessageCapture(checkNotNull(event.eventId), CommonInfoItem(content = text, kind = kind), checkNotNull(event.clipboardSource))
}
