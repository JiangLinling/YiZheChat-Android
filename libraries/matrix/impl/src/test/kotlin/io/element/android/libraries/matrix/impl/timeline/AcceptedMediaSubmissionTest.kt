/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.EventTimelineItem
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class AcceptedMediaSubmissionTest {
    private val transport = ".yizhe-original-content.yzm"
    private fun event(id: String? = "\$accepted", own: Boolean = true, remote: Boolean = true, tx: String = "original-txn",
        name: String = transport): MatrixTimelineItem.Event {
        val item = mockk<EventTimelineItem> {
            every { eventId } returns id?.let(::EventId)
            every { transactionId } returns TransactionId(tx)
            every { isOwn } returns own
            every { isRemote } returns remote
        }
        return MatrixTimelineItem.Event(UniqueId("row-$tx"), item, protectedMediaTransportName = name)
    }

    @Test fun `matching remote Matrix event retains event transaction and resource identity`() {
        val fact = acceptedMediaSubmission(listOf(event()), transport)!!
        assertEquals("\$accepted", fact.eventId)
        assertEquals("original-txn", fact.transactionId)
        assertEquals(transport, fact.transportName)
    }
    @Test fun `local echo queue acceptance and missing projection never prove committed or absent`() {
        assertNull(acceptedMediaSubmission(listOf(event(remote = false)), transport))
        assertNull(acceptedMediaSubmission(listOf(event(id = null)), transport))
        assertNull(acceptedMediaSubmission(emptyList(), transport))
    }
    @Test fun `other sender transport ambiguous event or transaction cannot confirm original`() {
        assertNull(acceptedMediaSubmission(listOf(event(own = false)), transport))
        assertNull(acceptedMediaSubmission(listOf(event(name = ".yizhe-other.yzm")), transport))
        assertNull(acceptedMediaSubmission(listOf(event(), event(id = "\$second")), transport))
        assertNull(acceptedMediaSubmission(listOf(event(), event(tx = "another-txn")), transport))
    }
}
