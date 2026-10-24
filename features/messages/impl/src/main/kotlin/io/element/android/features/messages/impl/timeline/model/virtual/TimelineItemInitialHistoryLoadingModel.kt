/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline.model.virtual

import io.element.android.features.messages.impl.timeline.InitialHistoryPresentationControl

/** Presentation only; not a Matrix event, pagination request or read-receipt target. */
data class TimelineItemInitialHistoryLoadingModel(val control: InitialHistoryPresentationControl) : TimelineItemVirtualModel {
    override val type: String = "TimelineItemInitialHistoryLoadingModel"
}
