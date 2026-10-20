/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.messagefavourites

import android.content.Context
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.NoticeMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EmoteMessageType
import io.element.android.libraries.matrix.ui.messages.toHtmlDocument
import io.element.android.libraries.matrix.ui.messages.toPlainText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.api.MessageReference
import io.element.android.features.messages.api.MessageReferenceStore
import io.element.android.features.preferences.impl.R
import io.element.android.features.userprofile.api.FriendProfileRepository
import io.element.android.features.userprofile.api.privateDirectDisplayName
import io.element.android.features.userprofile.api.privateDisplayName
import io.element.android.features.userprofile.api.matrixDisplayNameOrNull
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.designsystem.utils.snackbar.LocalSnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.api.timeline.item.event.EventContent
import io.element.android.libraries.matrix.api.timeline.item.event.LiveLocationContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.RedactedContent
import io.element.android.libraries.matrix.api.timeline.item.event.StickerContent
import io.element.android.libraries.matrix.api.timeline.item.event.UnableToDecryptContent
import io.element.android.libraries.matrix.api.timeline.item.event.getDisplayName
import io.element.android.libraries.matrix.api.timeline.item.event.isAuthorizedForUserDisplay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@ContributesNode(SessionScope::class)
@AssistedInject
class MessageFavouritesNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val matrixClient: MatrixClient,
    private val messageReferenceStore: MessageReferenceStore,
    private val friendProfileRepository: FriendProfileRepository,
    @ApplicationContext private val context: Context,
    private val protectedRuntime: MatrixContentProtectionRuntime,
    private val permalinkParser: PermalinkParser,
) : Node(buildContext, plugins = plugins) {
    interface Callback : Plugin {
        fun navigateToEvent(roomId: RoomId, eventId: EventId)
    }

    private val callback: Callback = callback()
    private val previewPermits = Semaphore(2)

    @Composable
    override fun View(modifier: Modifier) {
        val referenceState by messageReferenceStore.state.collectAsState()
        val friendProfiles by friendProfileRepository.profiles.collectAsState()
        var favourites by remember { mutableStateOf(emptyList<MessageFavourite>()) }
        var isLoading by remember { mutableStateOf(true) }
        val coroutineScope = rememberCoroutineScope()
        val snackbar = LocalSnackbarDispatcher.current
        var updatingFavourite by remember { mutableStateOf(false) }
        var referenceLimit by remember { mutableStateOf(12) }
        var authorizationRevision by remember { mutableStateOf(0L) }
        var previewInvalidated by remember { mutableStateOf(false) }
        var previewCache by remember { mutableStateOf(emptyMap<MessageReference, MessageFavourite>()) }
        LifecycleResumeEffect(Unit) {
            // The favourite identity is unchanged by edits. Refresh when returning to
            // this retained screen so its preview cannot reuse a previous revision.
            favourites = emptyList()
            previewCache = emptyMap()
            authorizationRevision++
            onPauseOrDispose { }
        }
        DisposableEffect(protectedRuntime) {
            val cacheClearer = protectedRuntime.registerScopedSensitiveCacheClearer {
                previewInvalidated = true
                favourites = emptyList(); previewCache = emptyMap(); authorizationRevision++
            }
            val listener = protectedRuntime.registerAuthorizationRefreshListener { refresh ->
                if (refresh.mode == MatrixAuthorizationRefreshMode.HARD_INVALIDATION) {
                    previewInvalidated = true
                    favourites = emptyList(); previewCache = emptyMap(); authorizationRevision++
                }
            }
            val invalidation = protectedRuntime.registerContentInvalidationListener {
                favourites = emptyList(); previewCache = emptyMap(); authorizationRevision++
            }
            onDispose { listener.close(); invalidation.close(); cacheClearer.close() }
        }

        LaunchedEffect(messageReferenceStore) {
            messageReferenceStore.load()
            friendProfileRepository.load()
        }
        LaunchedEffect(referenceState.favouriteKeys, referenceLimit, authorizationRevision) {
            if (previewInvalidated) {
                favourites = referenceState.favourites.take(referenceLimit).map(::unavailableFavourite)
                isLoading = false
                return@LaunchedEffect
            }
            isLoading = true
            val generation = protectedRuntime.authorizationRevision()
            val viewGeneration = authorizationRevision
            val references = referenceState.favourites.toList().take(referenceLimit)
            previewCache = previewCache.filterKeys { it in references }
            favourites = references.map { previewCache[it] ?: unavailableFavourite(it) }
            for (batch in references.filterNot { it in previewCache }.chunked(8)) {
                val resolved = coroutineScope {
                    batch.map { reference -> async { previewPermits.withPermit { reference to (resolveFavourite(reference) ?: unavailableFavourite(reference)) } } }.awaitAll()
                }
                if (protectedRuntime.authorizationRevision() != generation || authorizationRevision != viewGeneration) return@LaunchedEffect
                previewCache = previewCache + resolved.toMap()
                favourites = references.map { previewCache[it] ?: unavailableFavourite(it) }
            }
            isLoading = false
        }

        MessageFavouritesView(
            favourites = favourites,
            isLoading = isLoading,
            onBackClick = this::navigateUp,
            onOpen = { callback.navigateToEvent(it.roomId, it.eventId) },
            onDelete = { favourite ->
                if (!updatingFavourite) {
                    updatingFavourite = true
                    coroutineScope.launch {
                        try { messageReferenceStore.removeFavourite(favourite.roomId, favourite.eventId) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { snackbar.post(SnackbarMessage(CommonStrings.common_error)) }
                        finally { updatingFavourite = false }
                    }
                }
            },
            modifier = modifier,
            hasMore = referenceLimit < referenceState.favouriteKeys.size,
            onLoadMore = { referenceLimit += 12 },
            onSearchChanged = { query -> if (query.isNotBlank()) referenceLimit = referenceState.favouriteKeys.size else referenceLimit = 12 },
        )
    }

    private suspend fun resolveFavourite(reference: MessageReference): MessageFavourite? {
        val generation = protectedRuntime.authorizationRevision()
        val room = matrixClient.getJoinedRoom(reference.roomId) ?: return null
        // A focused timeline may initially project the original event without the
        // edit relation already present in the room's live timeline. Prefer that
        // current authorized projection when the event is loaded there.
        val liveEvent = withTimeoutOrNull(750L) {
            room.liveTimeline.timelineItems
                .map { items ->
                    items.filterIsInstance<MatrixTimelineItem.Event>()
                        .firstOrNull { it.event.eventId == reference.eventId }
                        ?.event
                }
                .filterNotNull()
                .first { it.content.isReadyForFavouritePreview() }
        }
        val timeline = room.createTimeline(CreateTimelineParams.Focused(reference.eventId)).getOrNull() ?: return null
        return try {
            val event = liveEvent ?: withTimeoutOrNull(FAVOURITE_RESOLVE_TIMEOUT_MS) {
                timeline.timelineItems
                    .map { timelineItems ->
                        timelineItems.filterIsInstance<MatrixTimelineItem.Event>()
                            .firstOrNull { it.event.eventId == reference.eventId }
                            ?.event
                    }
                    .filterNotNull()
                    // Focused timelines first publish a plaintext-free DECRYPTING shell, then
                    // replace it with the authorized projection. Consuming the first shell made
                    // every protected favourite appear permanently unavailable even when the
                    // protected-content service completed successfully a few milliseconds later.
                    .first { it.content.isReadyForFavouritePreview() }
            } ?: return null
            val roomInfo = room.info()
            if (protectedRuntime.authorizationRevision() != generation) return null
            val dmMember = if (roomInfo.isDm) room.getDirectRoomMember() else null
            MessageFavourite(
                roomId = reference.roomId,
                eventId = reference.eventId,
                roomName = dmMember?.userId?.let { friendProfileRepository.profiles.value.privateDirectDisplayName(it, dmMember.displayName) }
                    ?: roomInfo.name.matrixDisplayNameOrNull()
                    ?: unnamedRoomText,
                senderName = friendProfileRepository.profiles.value.privateDisplayName(
                    event.sender,
                    (event.senderProfile as? ProfileDetails.Ready)?.getDisplayName(),
                ),
                timestamp = event.timestamp,
                preview = favouritePreview(event.content),
            )
        } finally {
            timeline.close()
        }
    }

    private fun unavailableFavourite(reference: MessageReference) = MessageFavourite(reference.roomId, reference.eventId,
        unnamedRoomText, "", 0, unavailableText)

    private fun favouritePreview(content: EventContent): String = when (content) {
        is MessageContent -> if (content.isAuthorizedForUserDisplay()) {
            val formatted = when (val type = content.type) {
                is TextMessageType -> type.formatted
                is NoticeMessageType -> type.formatted
                is EmoteMessageType -> type.formatted
                else -> null
            }
            formatted?.toHtmlDocument(permalinkParser)?.toPlainText() ?: content.body
        } else unavailableText
        is StickerContent -> content.body ?: stickerText
        is PollContent -> content.question
        is LiveLocationContent -> content.description ?: locationText
        is CallNotifyContent -> callText
        RedactedContent -> redactedText
        is UnableToDecryptContent -> unavailableText
        else -> messageText
    }

    private val unavailableText: String get() = context.getString(R.string.screen_message_favourites_unavailable)
    private val unnamedRoomText: String get() = context.getString(R.string.screen_message_favourites_unnamed_room)
    private val stickerText: String get() = context.getString(R.string.screen_message_favourites_sticker)
    private val locationText: String get() = context.getString(R.string.screen_message_favourites_location)
    private val callText: String get() = context.getString(R.string.screen_message_favourites_call)
    private val redactedText: String get() = context.getString(R.string.screen_message_favourites_redacted)
    private val messageText: String get() = context.getString(R.string.screen_message_favourites_message)

    private companion object {
        const val FAVOURITE_RESOLVE_TIMEOUT_MS = 8_000L
    }
}

/**
 * A favourite stores only the room/event reference. Its preview must therefore come from the
 * same authorized timeline projection as the room and must never cache or persist plaintext.
 */
internal fun EventContent.isReadyForFavouritePreview(): Boolean = when (this) {
    is MessageContent -> protectedMessageStatus != ProtectedMessageStatus.DECRYPTING
    else -> true
}

private data class MessageFavourite(
    val roomId: RoomId,
    val eventId: EventId,
    val roomName: String,
    val senderName: String,
    val timestamp: Long,
    val preview: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageFavouritesView(
    favourites: List<MessageFavourite>,
    isLoading: Boolean,
    onBackClick: () -> Unit,
    onOpen: (MessageFavourite) -> Unit,
    onDelete: (MessageFavourite) -> Unit,
    modifier: Modifier = Modifier,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onSearchChanged: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(favourites, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) favourites else favourites.filter {
            it.preview.contains(normalized, ignoreCase = true) ||
                it.roomName.contains(normalized, ignoreCase = true) ||
                it.senderName.contains(normalized, ignoreCase = true)
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = stringResource(R.string.screen_message_favourites_title),
                navigationIcon = { BackButton(onClick = onBackClick) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; onSearchChanged(it) },
                placeholder = { Text(stringResource(R.string.screen_message_favourites_search)) },
                leadingIcon = { Icon(imageVector = CompoundIcons.Search(), contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            when {
                isLoading && favourites.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            if (query.isBlank()) R.string.screen_message_favourites_empty
                            else R.string.screen_message_favourites_no_results
                        ),
                        color = ElementTheme.colors.textSecondary,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { "${it.roomId.value}\u0000${it.eventId.value}" }) { favourite ->
                        ListItem(
                            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.FavouriteSolid())),
                            headlineContent = {
                                Text(
                                    text = favourite.preview,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = listOf(favourite.roomName, favourite.senderName)
                                            .filter(String::isNotBlank)
                                            .joinToString(" · "),
                                        color = ElementTheme.colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (favourite.timestamp > 0) Text(
                                        text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                            .format(Date(favourite.timestamp)),
                                        color = ElementTheme.colors.textSecondary,
                                    )
                                }
                            },
                            trailingContent = ListItemContent.Custom {
                                IconButton(onClick = { onDelete(favourite) }) {
                                    Icon(
                                        imageVector = CompoundIcons.Delete(),
                                        contentDescription = stringResource(R.string.screen_message_favourites_delete),
                                        tint = ElementTheme.colors.iconCriticalPrimary,
                                    )
                                }
                            },
                            onClick = { onOpen(favourite) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 64.dp))
                    }
                    if (hasMore) item { LaunchedEffect(Unit) { onLoadMore() } }
                }
            }
        }
    }
}
