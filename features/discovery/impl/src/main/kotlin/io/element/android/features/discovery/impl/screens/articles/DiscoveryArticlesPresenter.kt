/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import io.element.android.features.discovery.impl.actions.DiscoveryArticleCommentCommand
import io.element.android.features.discovery.impl.actions.DiscoveryInteractionKind
import io.element.android.features.discovery.impl.actions.DiscoveryInteractions
import io.element.android.features.discovery.impl.actions.DiscoveryInteractionOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Inject
class DiscoveryArticlesPresenter internal constructor(
    private val repository: DiscoveryArticlesRepository,
    private val interactions: DiscoveryInteractions,
) {
    @Composable
    fun present(searchMode: Boolean, collection: ArticleCollection? = null, active: Boolean = true,
        resumeRevision: Long = 0, foreground: Boolean = active): DiscoveryArticlesState {
        // Preserve search/filter intent while a detail covers this page; backgrounding clears it.
        var state by remember(searchMode, collection, foreground, resumeRevision) {
            mutableStateOf(DiscoveryArticlesState(searchMode = searchMode, collection = collection, eventSink = {}))
        }
        var page by remember(searchMode, collection, active, resumeRevision) { mutableIntStateOf(1) }
        var revision by remember(searchMode, collection, active, resumeRevision) { mutableIntStateOf(0) }
        val epoch = remember(searchMode, collection, active, resumeRevision) { Any() }
        val currentEpoch by rememberUpdatedState(epoch)
        val scope = rememberCoroutineScope()
        val work = remember(epoch) { mutableStateOf<Job?>(null) }
        DisposableEffect(epoch) { onDispose { work.value?.cancel() } }
        suspend fun pending(): Boolean = try { interactions.pending() } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { true }
        fun follow(recover: Boolean) {
            if (!active || epoch !== currentEpoch) return
            val target = collection?.takeIf { it.kind == ArticleCollectionKind.AUTHOR } ?: return
            val header = state.header
            if (state.loading || state.interactionBusy || (!recover && (header?.followEnabled != true ||
                    state.interactionPending || state.interactionFailed))) return
            state = state.copy(interactionBusy = true, interactionFailed = false, interactionRejected = false)
            work.value = scope.launch {
                try {
                    val outcome = if (recover) interactions.recover() else interactions.interact(target.id, DiscoveryInteractionKind.FOLLOW, !requireNotNull(header).followed)
                    val current = repository.collection(target)
                    val outstanding = pending()
                    currentCoroutineContext().ensureActive()
                    state = state.copy(header = current, interactionBusy = false, interactionPending = outstanding,
                        interactionRejected = outcome == DiscoveryInteractionOutcome.REJECTED)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    val outstanding = pending()
                    currentCoroutineContext().ensureActive()
                    val missing = (failure as? DiscoveryFailure)?.status == 404
                    state = state.copy(interactionBusy = false, interactionFailed = !missing || outstanding, interactionPending = outstanding,
                        header = if (missing) null else state.header, items = if (missing) emptyList() else state.items,
                        failed = missing || state.failed, missing = missing)
                }
            }
        }
        LaunchedEffect(epoch) {
            if (!active) return@LaunchedEffect
            if (searchMode) try {
                val words = repository.words()
                currentCoroutineContext().ensureActive()
                state = state.copy(words = words)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* The list remains usable. */ }
        }
        LaunchedEffect(epoch, state.keyword, state.sort, page, revision) {
            if (!active) {
                state = state.copy(items = emptyList(), header = null, words = emptyList(), total = 0,
                    loading = true, interactionBusy = false)
                return@LaunchedEffect
            }
            state = state.copy(loading = true, failed = false, missing = false, items = if (page == 1) emptyList() else state.items,
                header = if (page == 1) null else state.header)
            try {
                val header = if (collection != null && page == 1) repository.collection(collection) else state.header
                val sort = if (searchMode && state.keyword.isEmpty()) ArticleSort.VIEWS else state.sort
                val result = repository.feed(state.keyword, sort, page, collection)
                val outstanding = if (collection?.kind == ArticleCollectionKind.AUTHOR) pending() else false
                currentCoroutineContext().ensureActive()
                state = state.copy(loading = false, total = result.total, header = header, interactionPending = outstanding, interactionFailed = false,
                    items = (if (page == 1) result.items else state.items + result.items).distinctBy { it.id })
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                state = state.copy(loading = false, failed = true, missing = (failure as? DiscoveryFailure)?.status == 404)
            }
        }
        val visibleState = if (active) state else DiscoveryArticlesState(searchMode = searchMode, collection = collection, eventSink = {})
        return visibleState.copy(eventSink = sink@{ event ->
            if (!active || epoch !== currentEpoch) return@sink
            if (event == DiscoveryArticlesEvent.Follow) follow(false)
            else if (event == DiscoveryArticlesEvent.ConfirmInteraction) follow(true)
            else if (!state.interactionBusy) {
                // Change query keys together: immediate recomposition must not issue intermediate requests.
                Snapshot.withMutableSnapshot {
                    when (event) {
                        is DiscoveryArticlesEvent.Input -> state = state.copy(draft = event.value.take(100))
                        is DiscoveryArticlesEvent.Search -> {
                            val keyword = (event.word ?: state.draft).trim().take(100)
                            state = state.copy(draft = keyword, keyword = keyword, loading = true, failed = false, items = emptyList(),
                                history = if (keyword.isEmpty()) state.history else (listOf(keyword) + state.history).distinct().take(8))
                            page = 1; revision++
                        }
                        is DiscoveryArticlesEvent.Sort -> if (event.value != state.sort) {
                            state = state.copy(sort = event.value, loading = true, failed = false, items = emptyList())
                            page = 1
                        }
                        DiscoveryArticlesEvent.Reload -> {
                            // Retry the failed page without discarding earlier rows; ordinary refresh starts over.
                            if (!state.failed) page = 1
                            state = state.copy(loading = true, failed = false)
                            revision++
                        }
                        DiscoveryArticlesEvent.More -> if (!state.loading && !state.failed && state.items.size < state.total) {
                            state = state.copy(loading = true)
                            page++
                        }
                        DiscoveryArticlesEvent.Clear -> {
                            state = state.copy(draft = "", keyword = "", loading = true, failed = false, items = emptyList())
                            page = 1; revision++
                        }
                        DiscoveryArticlesEvent.Follow, DiscoveryArticlesEvent.ConfirmInteraction -> Unit
                    }
                }
            }
        })
    }

    @Composable
    fun detail(id: Long, active: Boolean = true, resumeRevision: Long = 0): DiscoveryArticleDetailState {
        var revision by remember(id, active, resumeRevision) { mutableIntStateOf(0) }
        var state by remember(id, active, resumeRevision) { mutableStateOf(DiscoveryArticleDetailState(reload = {})) }
        val scope = rememberCoroutineScope()
        val work = remember(id, active, resumeRevision) { mutableStateOf<Job?>(null) }
        DisposableEffect(id, active, resumeRevision) { onDispose { work.value?.cancel() } }
        suspend fun pending(): Pair<Boolean, DiscoveryArticleCommentCommand?> = try {
            val outstanding = interactions.pending()
            outstanding to if (outstanding) interactions.pendingComment(id) else null
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { true to null }
        fun applyPending(value: Pair<Boolean, DiscoveryArticleCommentCommand?>) {
            val comment = value.second
            state = state.copy(interactionPending = value.first, commentPending = comment != null,
                commentText = comment?.content ?: state.commentText,
                commentReply = if (comment != null) comment.replyToCommentId?.let { DiscoveryCommentReply(it) } else state.commentReply)
        }
        LaunchedEffect(id, active, resumeRevision, revision) {
            if (!active) return@LaunchedEffect
            state = state.copy(loading = true, failed = false, missing = false)
            applyPending(pending())
            try {
                val article = repository.detail(id)
                currentCoroutineContext().ensureActive()
                state = state.copy(loading = false, article = article, interactionFailed = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                val status = (failure as? DiscoveryFailure)?.status
                state = state.copy(loading = false, failed = true, missing = status == 404, article = null,
                    commentText = if (status in setOf(401, 403, 409)) "" else state.commentText,
                    commentReply = if (status in setOf(401, 403, 409)) null else state.commentReply)
            }
        }
        return state.copy(reload = { if (active && !state.interactionBusy) revision++ }, eventSink = sink@{ event ->
            if (!active || state.loading || state.interactionBusy) return@sink
            val article = state.article
            val editable = article?.commentEnabled == true && !state.failed && !state.interactionPending && !state.interactionFailed
            when (event) {
                is DiscoveryArticleDetailEvent.CommentInput -> {
                    if (editable && DiscoveryArticleCommentCommand.validInput(event.value)) state = state.copy(commentText = event.value, commentSent = false)
                    return@sink
                }
                is DiscoveryArticleDetailEvent.Reply -> {
                    if (editable && event.comment.articleId == id) state = state.copy(commentReply = DiscoveryCommentReply(event.comment.id,
                        event.comment.author, event.comment.content), commentSent = false)
                    return@sink
                }
                DiscoveryArticleDetailEvent.CancelReply -> {
                    if (editable) state = state.copy(commentReply = null)
                    return@sink
                }
                DiscoveryArticleDetailEvent.SendComment -> if (!editable || state.commentText.isBlank()) return@sink
                else -> Unit
            }
            if (event != DiscoveryArticleDetailEvent.Recover && (state.failed || state.interactionPending || state.interactionFailed || article == null)) return@sink
            state = state.copy(interactionBusy = true, interactionFailed = false, interactionRejected = false, commentSent = false)
            work.value = scope.launch {
                try {
                    val recoveringComment = if (event == DiscoveryArticleDetailEvent.Recover && state.commentPending) interactions.pendingComment(id) else null
                    val isComment = event == DiscoveryArticleDetailEvent.SendComment || recoveringComment != null
                    val outcome = when (event) {
                        DiscoveryArticleDetailEvent.Like -> interactions.interact(id, DiscoveryInteractionKind.LIKE, !requireNotNull(article).liked)
                        DiscoveryArticleDetailEvent.Favorite -> interactions.interact(id, DiscoveryInteractionKind.FAVORITE, !requireNotNull(article).favorited)
                        DiscoveryArticleDetailEvent.SendComment -> interactions.comment(id, state.commentText.trim(), state.commentReply?.id)
                        DiscoveryArticleDetailEvent.Recover -> interactions.recover()
                        else -> error("Not an interaction")
                    }
                    currentCoroutineContext().ensureActive()
                    // Record the acknowledged result BEFORE refreshing. A failed read must never
                    // make an already-sent comment editable again as if the send had failed.
                    state = state.copy(interactionPending = false, commentPending = false,
                        interactionRejected = outcome == DiscoveryInteractionOutcome.REJECTED,
                        commentSent = isComment && outcome == DiscoveryInteractionOutcome.APPLIED,
                        commentText = if (isComment && outcome == DiscoveryInteractionOutcome.APPLIED) "" else state.commentText,
                        commentReply = if (isComment && outcome == DiscoveryInteractionOutcome.APPLIED) null else state.commentReply,
                        contentRevision = state.contentRevision + 1)
                    try {
                        val current = repository.detail(id)
                        currentCoroutineContext().ensureActive()
                        state = state.copy(article = current, failed = false, missing = false)
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: Exception) {
                        val status = (failure as? DiscoveryFailure)?.status
                        state = state.copy(article = if (status in setOf(401, 403, 404, 409)) null else state.article,
                            failed = true, missing = status == 404,
                            commentText = if (status in setOf(401, 403, 409)) "" else state.commentText,
                            commentReply = if (status in setOf(401, 403, 409)) null else state.commentReply)
                    }
                    applyPending(pending())
                    state = state.copy(interactionBusy = false)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    val outstanding = pending()
                    currentCoroutineContext().ensureActive()
                    applyPending(outstanding)
                    val status = (failure as? DiscoveryFailure)?.status
                    val denied = status in setOf(401, 403, 404, 409)
                    state = state.copy(interactionBusy = false, interactionFailed = true,
                        article = if (denied) null else state.article, failed = denied || state.failed, missing = status == 404,
                        commentText = if (denied) "" else state.commentText, commentReply = if (denied) null else state.commentReply)
                }
            }
        })
    }
}
