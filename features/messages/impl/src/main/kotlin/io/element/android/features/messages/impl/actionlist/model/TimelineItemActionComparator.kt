/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.actionlist.model

import androidx.annotation.VisibleForTesting

class TimelineItemActionComparator : Comparator<TimelineItemAction> {
    // See order in https://www.figma.com/design/ux3tYoZV9WghC7hHT9Fhk0/Compound-iOS-Components?node-id=2946-2392
    @VisibleForTesting
    val orderedList = listOf(
        TimelineItemAction.Retry,
        TimelineItemAction.EndPoll,
        TimelineItemAction.ViewInTimeline,
        TimelineItemAction.CopyText,
        TimelineItemAction.CopyUnavailable,
        TimelineItemAction.CopyCaption,
        TimelineItemAction.Forward,
        TimelineItemAction.Favourite,
        TimelineItemAction.Unfavourite,
        TimelineItemAction.AddToCommon,
        TimelineItemAction.Withdraw,
        TimelineItemAction.Redact,
        TimelineItemAction.MultiSelect,
        TimelineItemAction.Reply,
        TimelineItemAction.ReplyInThread,
        TimelineItemAction.Edit,
        TimelineItemAction.EditPoll,
        TimelineItemAction.DeleteLocal,
        TimelineItemAction.AddCaption,
        TimelineItemAction.EditCaption,
        TimelineItemAction.Pin,
        TimelineItemAction.Unpin,
        TimelineItemAction.RemoveCaption,
        TimelineItemAction.ReportContent,
        TimelineItemAction.CopyLink,
        TimelineItemAction.ViewSource,
    )

    override fun compare(o1: TimelineItemAction, o2: TimelineItemAction): Int {
        val index1 = orderedList.indexOf(o1)
        val index2 = orderedList.indexOf(o2)
        return index1.compareTo(index2)
    }
}
