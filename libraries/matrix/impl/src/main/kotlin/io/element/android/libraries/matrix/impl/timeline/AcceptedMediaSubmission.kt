/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.media.MediaSubmissionFact
import io.element.android.libraries.matrix.api.media.MediaSubmissionPhase
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem

internal fun acceptedMediaSubmission(items: List<MatrixTimelineItem>, transportName: String): MediaSubmissionFact? {
    val matches = items.filterIsInstance<MatrixTimelineItem.Event>().filter {
        it.event.isOwn && it.protectedMediaTransportName == transportName && it.event.isRemote && it.eventId != null
    }
    if (matches.isEmpty() || matches.map { it.eventId }.distinct().size != 1 ||
        matches.mapNotNull { it.transactionId }.distinct().size > 1) return null
    val item = matches.first()
    return MediaSubmissionFact(MediaSubmissionPhase.ACCEPTED, transportName, item.eventId!!.value, item.transactionId?.value)
}
