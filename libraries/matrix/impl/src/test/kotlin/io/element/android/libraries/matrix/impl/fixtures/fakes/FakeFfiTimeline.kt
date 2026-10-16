/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.fixtures.fakes

import org.matrix.rustcomponents.sdk.EditedContent
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.PaginationStatusListener
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.SendHandle
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineListener
import uniffi.matrix_sdk.PaginationStatus

class FakeFfiTimeline(
    private val failSends: Boolean = true,
) : Timeline(NoHandle) {
    var paginationFailure: Exception? = null
    val redactedEventIds = mutableListOf<String>()
    val fetchedEventIds = mutableListOf<String>()
    var sentMessageCount = 0
        private set
    val sentReplyEventIds = mutableListOf<String>()
    private val eventsById = mutableMapOf<String, EventTimelineItem>()
    var latestEventIdentity: String? = null
    var closeCount = 0
    override suspend fun latestEventId(): String? = latestEventIdentity
    override fun close() = Unit
    override fun destroy() { closeCount++ }
    private var listener: TimelineListener? = null
    override suspend fun addListener(listener: TimelineListener): TaskHandle {
        this.listener = listener
        return FakeFfiTaskHandle()
    }

    fun emitDiff(diff: List<TimelineDiff>) {
        listener!!.onUpdate(diff)
    }

    fun storeEvent(eventId: String, item: EventTimelineItem) {
        eventsById[eventId] = item
    }

    override suspend fun getEventTimelineItemByEventId(eventId: String): EventTimelineItem {
        fetchedEventIds += eventId
        return checkNotNull(eventsById[eventId])
    }

    private var paginationStatusListener: PaginationStatusListener? = null
    override suspend fun subscribeToBackPaginationStatus(listener: PaginationStatusListener): TaskHandle {
        this.paginationStatusListener = listener
        return FakeFfiTaskHandle()
    }

    fun emitPaginationStatus(status: PaginationStatus) {
        paginationStatusListener!!.onUpdate(status)
    }

    override suspend fun paginateBackwards(numEvents: UShort): Boolean {
        paginationFailure?.let { throw it }
        return true
    }

    override suspend fun fetchMembers() = Unit

    override suspend fun send(content: RoomMessageEventContentWithoutRelation): SendHandle {
        if (failSends) error("Fake Matrix send failure")
        sentMessageCount++
        return SendHandle(NoHandle)
    }

    override suspend fun sendReply(msg: RoomMessageEventContentWithoutRelation, eventId: String) {
        if (failSends) error("Fake Matrix send failure")
        sentReplyEventIds += eventId
    }

    override suspend fun edit(eventOrTransactionId: EventOrTransactionId, newContent: EditedContent) {
        error("Fake Matrix edit failure")
    }

    override suspend fun redactEvent(eventOrTransactionId: EventOrTransactionId, reason: String?) {
        redactedEventIds += eventOrTransactionId.toString()
    }
}
