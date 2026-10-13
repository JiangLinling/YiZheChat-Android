/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import org.junit.Test

class DisplayIdentityProjectionCacheTest {
    private val source = MatrixTimelineItem.Event(UniqueId("sdk"), anEventTimelineItem())
    private val alias = UniqueId("optimistic:original")
    private val cache = DisplayIdentityProjectionCache()
    private fun project(item: MatrixTimelineItem = source, id: UniqueId? = alias) =
        cache.project(listOf(item), emptyMap()) { _, _ -> id }.single()

    @Test fun `unrelated emission reuses exact aliased source without rebuilding historical row`() {
        assertThat(project()).isSameInstanceAs(project())
    }

    @Test fun `changed source immediately replaces cached projection`() {
        val previous = project()
        val failed = source.copy(event = source.event.copy(localSendState = LocalEventSendState.Failed.Unknown("failure")))
        val result = project(failed) as MatrixTimelineItem.Event
        assertThat(result).isNotSameInstanceAs(previous)
        assertThat(result.event).isSameInstanceAs(failed.event)
        assertThat(result.event.localSendState).isEqualTo(failed.event.localSendState)
    }

    @Test fun `current identity wins over cached binding including a removed binding`() {
        val previous = project()
        val changed = project(id = UniqueId("different")) as MatrixTimelineItem.Event
        assertThat(changed).isNotSameInstanceAs(previous)
        assertThat(changed.displayUniqueId).isEqualTo(UniqueId("different"))
        assertThat(project(id = null)).isSameInstanceAs(source)
        assertThat(project(id = source.displayUniqueId)).isSameInstanceAs(source)
    }

    @Test fun `terminal replacement wins and evicts the old aliased source`() {
        val previous = project()
        val replacement = source.copy(uniqueId = UniqueId("authoritative-terminal"))
        assertThat(cache.project(listOf(source), mapOf(0 to replacement)) { _, _ -> alias }.single()).isSameInstanceAs(replacement)
        assertThat(project()).isNotSameInstanceAs(previous)
    }

    @Test fun `removed rows are not retained across snapshots`() {
        val previous = project()
        cache.project(emptyList(), emptyMap()) { _, _ -> alias }
        assertThat(project()).isNotSameInstanceAs(previous)
    }
}
