/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.roomdetails.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.userprofile.api.FriendProfileRepository
import io.element.android.features.userprofile.api.privateDisplayName
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.designsystem.utils.OnLifecycleEvent
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import io.element.android.libraries.matrix.api.timeline.item.event.StickerContent
import io.element.android.libraries.matrix.api.timeline.item.event.getDisplayName
import io.element.android.libraries.matrix.api.timeline.item.event.isAuthorizedForUserDisplay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Searches only the already-authorized in-memory timeline. No message body or query is indexed,
 * logged, written to saved state, or copied to a temporary file.
 */
class RoomHistorySearchNode(
    buildContext: BuildContext,
    private val room: JoinedRoom,
    private val friendProfileRepository: FriendProfileRepository,
    private val onBack: () -> Unit,
    private val onResult: (EventId) -> Unit,
) : Node(buildContext) {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun View(modifier: Modifier) {
        val timelineItems by room.liveTimeline.timelineItems.collectAsState(initial = emptyList())
        val pagination by room.liveTimeline.backwardPaginationStatus.collectAsState()
        val profiles by friendProfileRepository.profiles.collectAsState()
        var query by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) { friendProfileRepository.load() }
        OnLifecycleEvent { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) query = ""
        }

        val results = remember(timelineItems, query, profiles) {
            val normalized = query.trim()
            if (normalized.isEmpty()) emptyList() else timelineItems
                .filterIsInstance<MatrixTimelineItem.Event>()
                .mapNotNull { item ->
                    val eventId = item.event.eventId ?: return@mapNotNull null
                    val preview = searchablePreview(item.event.content) ?: return@mapNotNull null
                    val fallbackName = (item.event.senderProfile as? ProfileDetails.Ready)?.getDisplayName()
                    val senderName = profiles.privateDisplayName(item.event.sender, fallbackName)
                    if (!preview.contains(normalized, ignoreCase = true) &&
                        !senderName.contains(normalized, ignoreCase = true)
                    ) return@mapNotNull null
                    SearchResult(eventId, senderName, item.event.timestamp, preview)
                }
                .sortedByDescending(SearchResult::timestamp)
        }

        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.screen_room_details_find_chat)) },
                    navigationIcon = { BackButton(onClick = onBack) },
                    actions = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(CompoundIcons.Close(), contentDescription = stringResource(R.string.screen_room_details_search_clear))
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.screen_room_details_search_hint)) },
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (query.isNotBlank() && results.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.screen_room_details_search_empty),
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    items(results, key = { it.eventId.value }) { result ->
                        ListItem(
                            headlineContent = {
                                Text(result.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    "${result.senderName} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(result.timestamp))}",
                                    maxLines = 1,
                                )
                            },
                            onClick = { onResult(result.eventId) },
                            leadingContent = ListItemContent.Icon(
                                io.element.android.libraries.designsystem.theme.components.IconSource.Vector(CompoundIcons.Chat())
                            ),
                        )
                    }
                    if (pagination.hasMoreToLoad) {
                        item {
                            Button(
                                onClick = { scope.launch { room.liveTimeline.paginate(Timeline.PaginationDirection.BACKWARDS) } },
                                enabled = !pagination.isPaginating,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            ) {
                                Text(stringResource(R.string.screen_room_details_search_load_older))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun searchablePreview(content: io.element.android.libraries.matrix.api.timeline.item.event.EventContent): String? = when (content) {
    is MessageContent -> content.body.takeIf { content.isAuthorizedForUserDisplay() && !content.isBurnAfterRead && it.isNotBlank() }
    is StickerContent -> content.body?.takeIf(String::isNotBlank)
    is PollContent -> content.question.takeIf(String::isNotBlank)
    else -> null
}

private data class SearchResult(
    val eventId: EventId,
    val senderName: String,
    val timestamp: Long,
    val preview: String,
)
