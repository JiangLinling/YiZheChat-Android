/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryArticlesView(state: DiscoveryArticlesState, onBack: () -> Unit, onArticle: (Long) -> Unit, modifier: Modifier = Modifier,
    onPersonal: (DiscoveryPersonalKind) -> Unit = {}) {
    Column(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.screen_discovery_back)) }
            TextButton(onClick = { state.eventSink(DiscoveryArticlesEvent.Reload) }, enabled = !state.loading && !state.interactionBusy) {
                Text(stringResource(R.string.screen_discovery_refresh))
            }
        }
        Text(state.header?.let { if (state.collection?.kind == ArticleCollectionKind.TAG) "#${it.title}" else it.title }
            ?: stringResource(when (state.collection?.kind) {
                ArticleCollectionKind.TAG -> R.string.screen_discovery_topic_title
                ArticleCollectionKind.AUTHOR -> R.string.screen_discovery_author_title
                null -> if (state.searchMode) R.string.screen_discovery_search_title else R.string.screen_discovery_news
            }),
            Modifier.padding(horizontal = 20.dp), style = ElementTheme.typography.fontHeadingLgBold)
        state.header?.let { header ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (header.description.isNotBlank()) Text(header.description, color = ElementTheme.colors.textSecondary)
                Text(stringResource(R.string.screen_discovery_article_count, header.articleCount), color = ElementTheme.colors.textSecondary)
                header.followerCount?.let { count -> Text(stringResource(R.string.screen_discovery_follower_count, count), color = ElementTheme.colors.textSecondary) }
                if (state.collection?.kind == ArticleCollectionKind.AUTHOR) {
                    if (header.followEnabled) {
                        FilterChip(selected = header.followed,
                            enabled = !state.loading && !state.interactionBusy && !state.interactionPending && !state.interactionFailed,
                            onClick = { state.eventSink(DiscoveryArticlesEvent.Follow) },
                            label = { Text(stringResource(if (header.followed) R.string.screen_discovery_unfollow else R.string.screen_discovery_follow)) })
                    } else Text(stringResource(R.string.screen_discovery_follow_unavailable), color = ElementTheme.colors.textSecondary)
                }
            }
        }
        if (state.interactionBusy) CircularProgressIndicator(Modifier.padding(horizontal = 20.dp))
        if (state.interactionRejected) Text(stringResource(R.string.screen_discovery_interaction_rejected), Modifier.padding(horizontal = 20.dp))
        if (!state.interactionBusy && (state.interactionPending || state.interactionFailed)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.screen_discovery_interaction_pending), color = ElementTheme.colors.textSecondary)
                TextButton(onClick = { state.eventSink(DiscoveryArticlesEvent.ConfirmInteraction) }) {
                    Text(stringResource(R.string.screen_discovery_confirm_interaction))
                }
            }
        }
        if (state.collection == null && !state.searchMode) {
            FlowRow(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { onPersonal(DiscoveryPersonalKind.FAVORITES) }) { Text(stringResource(R.string.screen_discovery_my_favorites)) }
                TextButton(onClick = { onPersonal(DiscoveryPersonalKind.FOLLOWS) }) { Text(stringResource(R.string.screen_discovery_my_follows)) }
                TextButton(onClick = { onPersonal(DiscoveryPersonalKind.HISTORY) }) { Text(stringResource(R.string.screen_discovery_my_history)) }
                TextButton(onClick = { onPersonal(DiscoveryPersonalKind.REPLIES) }) { Text(stringResource(R.string.screen_discovery_my_replies)) }
            }
        }
        if (state.collection == null) {
            OutlinedTextField(value = state.draft, onValueChange = { state.eventSink(DiscoveryArticlesEvent.Input(it)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), singleLine = true,
                placeholder = { Text(stringResource(R.string.screen_discovery_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { state.eventSink(DiscoveryArticlesEvent.Search()) }),
                trailingIcon = { TextButton(onClick = { state.eventSink(DiscoveryArticlesEvent.Clear) }) {
                    Text(stringResource(R.string.screen_discovery_clear))
                } })
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(ArticleSort.RECOMMEND, ArticleSort.LATEST, ArticleSort.HOT)) { sort ->
                    FilterChip(selected = state.sort == sort, onClick = { state.eventSink(DiscoveryArticlesEvent.Sort(sort)) },
                        label = { Text(stringResource(when (sort) {
                            ArticleSort.RECOMMEND -> R.string.screen_discovery_sort_recommend
                            ArticleSort.LATEST -> R.string.screen_discovery_sort_latest
                            else -> R.string.screen_discovery_sort_hot
                        })) })
                }
            }
        }
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = { state.eventSink(DiscoveryArticlesEvent.Search(state.keyword)) },
            modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.searchMode && state.keyword.isEmpty()) {
                    if (state.history.isNotEmpty()) item { WordRow(R.string.screen_discovery_search_history, state.history, state.eventSink) }
                    if (state.words.isNotEmpty()) item { WordRow(R.string.screen_discovery_search_words, state.words, state.eventSink) }
                    item { Text(stringResource(R.string.screen_discovery_popular_articles), style = ElementTheme.typography.fontHeadingMdBold) }
                }
                items(state.items, key = { it.id }) { article ->
                    Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(16.dp))
                        .clickable { onArticle(article.id) }.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(article.title, style = ElementTheme.typography.fontHeadingSmMedium)
                        if (article.summary.isNotBlank()) Text(article.summary, color = ElementTheme.colors.textSecondary, maxLines = 3)
                        Text(article.author.ifBlank { stringResource(R.string.screen_discovery_brand) }, color = ElementTheme.colors.textSecondary)
                        Text(stringResource(R.string.screen_discovery_article_likes, article.likes), color = ElementTheme.colors.textSecondary)
                    }
                }
                if (state.failed) item {
                    Text(stringResource(if (state.missing) R.string.screen_discovery_collection_missing else R.string.screen_discovery_unavailable))
                    if (!state.missing) TextButton(onClick = { state.eventSink(DiscoveryArticlesEvent.Reload) }) { Text(stringResource(R.string.screen_discovery_retry)) }
                } else if (!state.loading && state.items.isEmpty()) item { Text(stringResource(R.string.screen_discovery_search_empty)) }
                if (!state.loading && !state.failed && state.items.isNotEmpty()) item {
                    if (state.items.size < state.total) {
                        TextButton(onClick = { state.eventSink(DiscoveryArticlesEvent.More) }) { Text(stringResource(R.string.screen_discovery_more)) }
                    } else Text(stringResource(R.string.screen_discovery_list_end), color = ElementTheme.colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun WordRow(title: Int, words: List<String>, sink: (DiscoveryArticlesEvent) -> Unit) {
    Text(stringResource(title), style = ElementTheme.typography.fontBodyLgMedium)
    LazyRow { items(words, key = { it }) { word -> TextButton(onClick = { sink(DiscoveryArticlesEvent.Search(word)) }) { Text(word) } } }
}

@Composable
fun DiscoveryArticleDetailView(state: DiscoveryArticleDetailState, onBack: () -> Unit, modifier: Modifier = Modifier,
    onAuthor: (Long) -> Unit = {}, onTag: (Long) -> Unit = {}, comments: DiscoveryCommentsState? = null, openComments: Boolean = false) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var scrolledToComments by rememberSaveable { mutableStateOf(false) }
    // One back row, optional loading/error rows, and the article's four fixed rows plus tags/summary.
    val commentsIndex = 1 + (if (state.loading) 1 else 0) + (if (state.failed) 1 else 0) +
        (state.article?.let { 4 + (if (it.tags.isNotEmpty()) 1 else 0) + (if (it.summary.isNotBlank()) 1 else 0) } ?: 0)
    LaunchedEffect(openComments, state.article?.id, comments != null) {
        if (openComments && state.article != null && comments != null && !scrolledToComments) {
            listState.scrollToItem(commentsIndex)
            scrolledToComments = true
        }
    }
    Column(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding().imePadding()) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth(),
        state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { TextButton(onClick = onBack) { Text(stringResource(R.string.screen_discovery_back)) } }
        if (state.loading) item { CircularProgressIndicator() }
        if (state.failed) item {
            Text(stringResource(if (state.missing) R.string.screen_discovery_article_missing else R.string.screen_discovery_unavailable))
            if (!state.missing) TextButton(onClick = state.reload) { Text(stringResource(R.string.screen_discovery_retry)) }
        }
        state.article?.let { article ->
            item { Text(article.title, style = ElementTheme.typography.fontHeadingLgBold) }
            item {
                if (article.authorId != null) {
                    TextButton(onClick = { onAuthor(article.authorId) }) { Text(article.author.ifBlank { stringResource(R.string.screen_discovery_brand) }) }
                } else Text(article.author.ifBlank { stringResource(R.string.screen_discovery_brand) }, color = ElementTheme.colors.textSecondary)
            }
            if (article.tags.isNotEmpty()) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(article.tags, key = { it.id }) { tag ->
                        TextButton(onClick = { onTag(tag.id) }) { Text("#${tag.name}") }
                    }
                }
            }
            if (article.summary.isNotBlank()) item { Text(article.summary, style = ElementTheme.typography.fontBodyLgMedium) }
            item { Text(article.content, style = ElementTheme.typography.fontBodyLgRegular) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val enabled = !state.failed && !state.interactionBusy && !state.interactionPending && !state.interactionFailed
                    FilterChip(selected = article.liked, enabled = enabled,
                        onClick = { state.eventSink(DiscoveryArticleDetailEvent.Like) },
                        label = { Text(stringResource(if (article.liked) R.string.screen_discovery_liked else R.string.screen_discovery_like, article.likes)) })
                    FilterChip(selected = article.favorited, enabled = enabled,
                        onClick = { state.eventSink(DiscoveryArticleDetailEvent.Favorite) },
                        label = { Text(stringResource(if (article.favorited) R.string.screen_discovery_favorited else R.string.screen_discovery_favorite, article.favorites)) })
                    if (comments != null) TextButton(onClick = { scope.launch { listState.animateScrollToItem(commentsIndex) } }) {
                        Text(stringResource(R.string.screen_discovery_comments_title))
                    }
                }
            }
        }
        if (state.article != null && comments != null) discoveryComments(comments,
            onReply = if (state.article.commentEnabled && !state.failed && !state.interactionBusy && !state.interactionPending && !state.interactionFailed)
                { comment -> state.eventSink(DiscoveryArticleDetailEvent.Reply(comment)) } else null)
        if (state.readingPending) item {
            Text(stringResource(R.string.screen_discovery_reading_pending), color = ElementTheme.colors.textSecondary)
            TextButton(onClick = state.retryReading) { Text(stringResource(R.string.screen_discovery_retry)) }
        }
        if (state.interactionRejected) item { Text(stringResource(R.string.screen_discovery_interaction_rejected)) }
        if (state.interactionBusy) item { CircularProgressIndicator() }
        if (!state.interactionBusy && (state.interactionPending || state.interactionFailed)) item {
            Text(stringResource(R.string.screen_discovery_interaction_pending), color = ElementTheme.colors.textSecondary)
            TextButton(onClick = { state.eventSink(DiscoveryArticleDetailEvent.Recover) }) {
                Text(stringResource(R.string.screen_discovery_confirm_interaction))
            }
        }
    }
    if (state.article != null || state.commentPending) DiscoveryCommentComposer(state)
    }
}

class DiscoveryArticlesStateProvider : PreviewParameterProvider<DiscoveryArticlesState> {
    override val values = sequenceOf(
        DiscoveryArticlesState(eventSink = {}), DiscoveryArticlesState(loading = false, eventSink = {}),
        DiscoveryArticlesState(loading = false, failed = true, eventSink = {}),
        DiscoveryArticlesState(collection = ArticleCollection(ArticleCollectionKind.TAG, 1), eventSink = {}),
        DiscoveryArticlesState(loading = false, collection = ArticleCollection(ArticleCollectionKind.TAG, 1),
            header = ArticleCollectionHeader("经典共读", "在共读中照见生活", 0), eventSink = {}),
        DiscoveryArticlesState(loading = false, collection = ArticleCollection(ArticleCollectionKind.AUTHOR, 1),
            header = ArticleCollectionHeader("共读作者", "从经典回到日常", 1, 12, followed = true),
            items = listOf(DiscoveryArticle(1, "共读札记", "")), total = 1, eventSink = {}),
        DiscoveryArticlesState(loading = false, collection = ArticleCollection(ArticleCollectionKind.AUTHOR, 1),
            missing = true, failed = true, eventSink = {}),
        DiscoveryArticlesState(loading = false, collection = ArticleCollection(ArticleCollectionKind.AUTHOR, 1),
            header = ArticleCollectionHeader("共读作者", "", 1, 12, followEnabled = true), interactionBusy = true, eventSink = {}),
        DiscoveryArticlesState(loading = false, collection = ArticleCollection(ArticleCollectionKind.AUTHOR, 1),
            header = ArticleCollectionHeader("共读作者", "", 1, 12, followEnabled = true), interactionPending = true, eventSink = {}),
        DiscoveryArticlesState(loading = false, collection = ArticleCollection(ArticleCollectionKind.AUTHOR, 1),
            header = ArticleCollectionHeader("共读作者", "", 1, 12), interactionRejected = true, eventSink = {}),
        DiscoveryArticlesState(searchMode = true, loading = false, words = listOf("共读", "经典"), history = listOf("生活"),
            items = listOf(DiscoveryArticle(1, "在共读中照见生活", "从一段文字出发，和身边的人一起思考。", likes = 12)), total = 1, eventSink = {}),
    )
}

class DiscoveryArticleDetailStateProvider : PreviewParameterProvider<DiscoveryArticleDetailState> {
    override val values = sequenceOf(DiscoveryArticleDetailState(reload = {}),
        DiscoveryArticleDetailState(loading = false, article = DiscoveryArticle(1, "共读札记", ""), readingPending = true, reload = {}),
        DiscoveryArticleDetailState(loading = false, failed = true, reload = {}),
        DiscoveryArticleDetailState(loading = false, failed = true, missing = true, reload = {}),
        DiscoveryArticleDetailState(loading = false, article = DiscoveryArticle(1, "共读札记", "从经典回到日常", "阅读是一段向内的旅程。"), reload = {}),
        DiscoveryArticleDetailState(loading = false, article = DiscoveryArticle(1, "共读札记", "", liked = true), interactionBusy = true, reload = {}),
        DiscoveryArticleDetailState(loading = false, article = DiscoveryArticle(1, "共读札记", ""), interactionPending = true, reload = {}),
        DiscoveryArticleDetailState(loading = false, article = DiscoveryArticle(1, "共读札记", ""), interactionFailed = true, reload = {}))
}

@PreviewsDayNight
@Composable
private fun ArticlesPreview(@PreviewParameter(DiscoveryArticlesStateProvider::class) state: DiscoveryArticlesState) = ElementPreview {
    DiscoveryArticlesView(state, {}, {})
}

@PreviewsDayNight
@Composable
private fun DetailPreview(@PreviewParameter(DiscoveryArticleDetailStateProvider::class) state: DiscoveryArticleDetailState) = ElementPreview {
    DiscoveryArticleDetailView(state, {})
}
