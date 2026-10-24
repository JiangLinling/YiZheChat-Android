/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import androidx.compose.runtime.Stable
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedOperationContent
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemInitialHistoryLoadingModel

/** Feature presentation only. Items still pass the original display authorization filter. */
data class TimelinePresentation(
    val items: List<MatrixTimelineItem>,
    val initialHistoryLoading: InitialHistoryPresentationControl? = null,
    /** The same current full authorized projection for existing cleanup consumers; never a retained past snapshot. */
    val completeItems: List<MatrixTimelineItem> = items,
)

/** A callback to this presentation collection only. Late UI work cannot retire a later collection. */
@Stable
class InitialHistoryPresentationControl internal constructor() {
    val id: UniqueId = UniqueId("initial-history:${UUID.randomUUID()}")
    internal val retired = MutableStateFlow(false)
    internal val closed = MutableStateFlow(false)
    fun retire() { if (!closed.value) retired.value = true }
    internal fun close() { retired.value = true; closed.value = true }
}

internal fun List<TimelineItem>.initialHistoryControl(): InitialHistoryPresentationControl? =
    asSequence().filterIsInstance<TimelineItem.Virtual>()
        .mapNotNull { (it.model as? TimelineItemInitialHistoryLoadingModel)?.control }.singleOrNull()

internal data class InitialHistoryProjection(val items: List<MatrixTimelineItem>, val loading: Boolean = false)

/** Owned by one Controller presentation collection. Retains bounded identities, never message content or authority. */
internal class InitialHistoryPresentation {
    private var owner: Any? = null
    private var captured: List<String>? = null
    private val readyKeys = mutableSetOf<String>()
    private var retired = false

    fun needsSource(owner: Any): Boolean = this.owner !== owner || !retired

    fun project(
        owner: Any,
        eligible: Boolean,
        sourceItems: List<MatrixTimelineItem>,
        orderedItems: List<MatrixTimelineItem>,
        historyStartReached: Boolean,
        retiredByIntent: Boolean = false,
        immediateIds: Set<UniqueId> = emptySet(),
    ): InitialHistoryProjection {
        if (this.owner !== owner) {
            this.owner = owner
            captured = null
            readyKeys.clear()
            retired = false
        }
        fun original(): InitialHistoryProjection = InitialHistoryProjection(orderedItems)
        if (!eligible || retiredByIntent) retired = true
        if (retired) return original()
        val source = sourceItems.filterIsInstance<MatrixTimelineItem.Event>()
            .filter { it.event.isRemote && it.event.content !is ProtectedOperationContent }
        if (source.isEmpty()) {
            // An empty first emission cannot consume the finite capture. Existing empty-room facts stay honest.
            return InitialHistoryProjection(orderedItems, sourceItems.isEmpty() && !historyStartReached)
        }
        val keys = captured ?: source.takeLast(INITIAL_WINDOW_SIZE).map(::key).also { captured = it }
        val currentByKey = source.associateBy(::key)
        val indices = source.mapIndexed { index, item -> key(item) to index }.toMap()
        val positions = keys.mapNotNull(indices::get)
        if (positions.size != keys.size || positions.zipWithNext().any { (left, right) -> left >= right }) {
            retired = true
            return original()
        }
        val capturedSet = keys.toSet()
        if (source.subList(positions.first(), positions.last() + 1).any { key(it) !in capturedSet }) {
            // A late meaningful structural insertion cannot silently extend/rearm this initial window.
            retired = true
            return original()
        }
        val pending = keys.filterTo(mutableSetOf()) { currentByKey.getValue(it).isPendingInitialBody() }
        if (readyKeys.any { it in pending }) {
            // Revalidation or replacement takes precedence over stability; never retain a prior readable body.
            retired = true
            return original()
        }
        readyKeys += keys.filterNot { it in pending }
        if (pending.isEmpty()) {
            retired = true
            return original()
        }
        val gap = keys.indexOfLast { it in pending }
        val allowed = keys.drop(gap + 1).toMutableSet()
        // Matrix stream order precedes the Controller's own-send timestamp reconciliation. New live
        // arrivals after the frozen sequence boundary bypass it even if their display timestamp is older.
        source.drop(positions.last() + 1).mapTo(allowed, ::key)
        orderedItems.filterIsInstance<MatrixTimelineItem.Event>()
            .filter { !it.event.isRemote || it.uniqueId in immediateIds || it.displayUniqueId in immediateIds }
            .mapTo(allowed, ::key)
        val firstKept = orderedItems.indexOfFirst { it is MatrixTimelineItem.Event && key(it) in allowed }
        val tail = orderedItems.filterIndexed { index, item ->
            when (item) {
                is MatrixTimelineItem.Event -> key(item) in allowed
                else -> firstKept >= 0 && index >= firstKept
            }
        }
        // A new list keeps no subList/backing reference to withheld older plaintext.
        return InitialHistoryProjection(tail, loading = true)
    }

    private fun key(item: MatrixTimelineItem.Event): String =
        item.eventId?.let { "event:${it.value}" }
            ?: item.transactionId?.let { "transaction:${it.value}" }
            ?: "item:${item.uniqueId.value}"

    private fun MatrixTimelineItem.Event.isPendingInitialBody(): Boolean {
        val content = event.content as? MessageContent ?: return false
        return !content.isBurnAfterRead && (content.protectedMessageStatus == ProtectedMessageStatus.DECRYPTING ||
            content.protectedMessageStatus == ProtectedMessageStatus.ATTACHMENT_DECRYPTING)
    }

    private companion object {
        const val INITIAL_WINDOW_SIZE = 30
    }
}
