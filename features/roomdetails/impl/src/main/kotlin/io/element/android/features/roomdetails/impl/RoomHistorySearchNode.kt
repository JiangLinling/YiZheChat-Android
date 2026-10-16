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
import androidx.lifecycle.coroutineScope
import com.bumble.appyx.core.lifecycle.subscribe
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
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.services.appnavstate.api.AppForegroundStateService
import kotlinx.coroutines.flow.combine
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
    private val previewAuthorizationGate: ProtectedPreviewAuthorizationGate,
    private val appForegroundStateService: AppForegroundStateService,
    private val onBack: () -> Unit,
    private val onResult: (EventId) -> Unit,
) : Node(buildContext) {
    // Node-owned memory survives off-screen composition disposal, but is never saved/restored.
    private var query by mutableStateOf("")
    private var queryAuthorizationRevision = previewAuthorizationGate.authorizationRevision.value

    override fun onBuilt() {
        super.onBuilt()
        lifecycle.subscribe(onDestroy = { query = "" })
        // Observe for the whole node lifetime, including while a result covers the search page.
        lifecycle.coroutineScope.launch {
            combine(
                appForegroundStateService.isInForeground,
                previewAuthorizationGate.accessState,
                previewAuthorizationGate.authorizationRevision,
            ) { foreground, access, revision -> Triple(foreground, access.allowsPreviewAttempt, revision) }
                .collect { (foreground, allowed, revision) ->
                    if (!foreground || !allowed || revision != queryAuthorizationRevision) query = ""
                    queryAuthorizationRevision = revision
                }
        }
    }

    private fun canSearch(): Boolean = appForegroundStateService.isInForeground.value &&
        previewAuthorizationGate.canAttemptPreview() &&
        previewAuthorizationGate.authorizationRevision.value == queryAuthorizationRevision

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun View(modifier: Modifier) {
        val timelineItems by room.liveTimeline.timelineItems.collectAsState(initial = emptyList())
        val pagination by room.liveTimeline.backwardPaginationStatus.collectAsState()
        val profiles by friendProfileRepository.profiles.collectAsState()
        val foreground by appForegroundStateService.isInForeground.collectAsState()
        val access by previewAuthorizationGate.accessState.collectAsState()
        val revision by previewAuthorizationGate.authorizationRevision.collectAsState()
        // Gate the first returning frame as well as the node-level asynchronous cleanup.
        val visibleQuery = query.takeIf { foreground && access.allowsPreviewAttempt && revision == queryAuthorizationRevision }.orEmpty()
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) { friendProfileRepository.load() }

        val results = remember(timelineItems, visibleQuery, profiles) {
            val normalized = visibleQuery.trim()
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
                    navigationIcon = {
                        BackButton(onClick = {
                            query = ""
                            onBack()
                        })
                    },
                    actions = {
                        if (visibleQuery.isNotEmpty()) {
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
                    value = visibleQuery,
                    onValueChange = { query = if (canSearch()) it else "" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.screen_room_details_search_hint)) },
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (visibleQuery.isNotBlank() && results.isEmpty()) {
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
                            onClick = { if (canSearch() && query.isNotBlank()) onResult(result.eventId) },
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
