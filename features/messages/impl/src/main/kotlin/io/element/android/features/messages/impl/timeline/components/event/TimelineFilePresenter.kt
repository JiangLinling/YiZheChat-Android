/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import io.element.android.features.messages.impl.timeline.di.TimelineItemEventContentKey
import io.element.android.features.messages.impl.timeline.di.TimelineItemPresenterFactory
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemFileContent
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.features.messages.api.MessageReferenceStore

@BindingContainer
@ContributesTo(RoomScope::class)
interface TimelineFilePresenterModule {
    @Binds
    @IntoMap
    @TimelineItemEventContentKey(TimelineItemFileContent::class)
    fun bindFactory(factory: TimelineFilePresenter.Factory): TimelineItemPresenterFactory<*, *>
}

@AssistedInject
class TimelineFilePresenter(
    @Assisted private val content: TimelineItemFileContent,
    private val room: JoinedRoom,
    private val references: MessageReferenceStore,
) : Presenter<TimelineItemFileContent> {
    @AssistedFactory
    fun interface Factory : TimelineItemPresenterFactory<TimelineItemFileContent, TimelineItemFileContent> {
        override fun create(content: TimelineItemFileContent): TimelineFilePresenter
    }

    @Composable
    override fun present(): TimelineItemFileContent {
        val eventId = content.eventId?.takeIf(String::isNotBlank) ?: return content
        if (content.isBurnAfterRead) return content
        val state by references.state.collectAsState()
        return content.copy(isOpenedLocally = state.isFileOpened(room.roomId, EventId(eventId)))
    }
}
