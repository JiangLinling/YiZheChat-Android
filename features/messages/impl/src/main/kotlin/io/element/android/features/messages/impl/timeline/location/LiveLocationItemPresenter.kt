/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import io.element.android.features.location.api.live.ActiveLiveLocationShareManager
import io.element.android.features.messages.impl.timeline.di.TimelineItemEventContentKey
import io.element.android.features.messages.impl.timeline.di.TimelineItemPresenterFactory
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemLocationContent
import io.element.android.features.messages.impl.timeline.model.event.ensureActiveLiveLocation
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.JoinedRoom

@BindingContainer
@ContributesTo(RoomScope::class)
interface LiveLocationItemPresenterModule {
    @Binds
    @IntoMap
    @TimelineItemEventContentKey(TimelineItemLocationContent::class)
    fun bindFactory(factory: LiveLocationItemPresenter.Factory): TimelineItemPresenterFactory<*, *>
}

@AssistedInject
class LiveLocationItemPresenter(
    @Assisted private val content: TimelineItemLocationContent,
    private val room: JoinedRoom,
    private val manager: ActiveLiveLocationShareManager,
) : Presenter<TimelineItemLocationContent> {
    @AssistedFactory
    fun interface Factory : TimelineItemPresenterFactory<TimelineItemLocationContent, TimelineItemLocationContent> {
        override fun create(content: TimelineItemLocationContent): LiveLocationItemPresenter
    }

    @Composable
    override fun present(): TimelineItemLocationContent {
        val current = content.ensureActiveLiveLocation()
        val mode = current.mode as? TimelineItemLocationContent.Mode.Live ?: return current
        val aggregateId = mode.protectedAggregateId
        if (!mode.isOwnUser || current.senderId != room.sessionId || !mode.isActive || aggregateId.isNullOrBlank()) return current
        val pausedState by remember(room.roomId, aggregateId) {
            manager.pausedShare(room.roomId, EventId("\$" + aggregateId))
        }.collectAsState(initial = null)
        return current.copy(mode = mode.copy(pausedState = pausedState))
    }
}
