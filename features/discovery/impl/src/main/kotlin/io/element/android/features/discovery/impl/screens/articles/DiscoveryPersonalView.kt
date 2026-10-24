/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
fun DiscoveryPersonalView(state: DiscoveryPersonalState, onBack: () -> Unit, onArticle: (Long) -> Unit,
    onAuthor: (Long) -> Unit, modifier: Modifier = Modifier, onReply: (Long) -> Unit = onArticle) {
    Column(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.screen_discovery_back)) }
            TextButton(onClick = { state.eventSink(DiscoveryPersonalEvent.Refresh) }, enabled = !state.loading) {
                Text(stringResource(R.string.screen_discovery_refresh))
            }
        }
        Text(stringResource(when (state.kind) {
            DiscoveryPersonalKind.FAVORITES -> R.string.screen_discovery_my_favorites
            DiscoveryPersonalKind.FOLLOWS -> R.string.screen_discovery_my_follows
            DiscoveryPersonalKind.HISTORY -> R.string.screen_discovery_my_history
            DiscoveryPersonalKind.REPLIES -> R.string.screen_discovery_my_replies
        }),
            Modifier.padding(horizontal = 20.dp), style = ElementTheme.typography.fontHeadingLgBold)
        PullToRefreshBox(isRefreshing = state.loading && state.items.isEmpty(), onRefresh = { state.eventSink(DiscoveryPersonalEvent.Refresh) },
            modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(state.items, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(16.dp)).clickable {
                        when (item) {
                            is DiscoveryPersonalItem.Reply -> onReply(item.comment.articleId)
                            is DiscoveryPersonalItem.Article -> onArticle(item.id)
                            is DiscoveryPersonalItem.Author -> onAuthor(item.id)
                        }
                    }.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (item) {
                            is DiscoveryPersonalItem.Reply -> {
                                DiscoveryCommentContent(item.comment, myReply = true)
                                Text(stringResource(R.string.screen_discovery_reply_article, item.articleTitle), color = ElementTheme.colors.textSecondary)
                            }
                            is DiscoveryPersonalItem.Article -> {
                                Text(item.value.title, style = ElementTheme.typography.fontHeadingSmMedium)
                                if (item.value.summary.isNotBlank() || state.kind == DiscoveryPersonalKind.HISTORY) Text(
                                    item.value.summary.ifBlank { stringResource(R.string.screen_discovery_no_summary) },
                                    color = ElementTheme.colors.textSecondary, maxLines = 3)
                                Text(item.value.author.ifBlank { stringResource(R.string.screen_discovery_brand) }, color = ElementTheme.colors.textSecondary)
                                Text(stringResource(if (state.kind == DiscoveryPersonalKind.HISTORY) R.string.screen_discovery_article_views else R.string.screen_discovery_article_likes,
                                    if (state.kind == DiscoveryPersonalKind.HISTORY) item.value.views else item.value.likes), color = ElementTheme.colors.textSecondary)
                            }
                            is DiscoveryPersonalItem.Author -> {
                                Text(item.header.title, style = ElementTheme.typography.fontHeadingSmMedium)
                                if (item.type.isNotBlank()) Text(if (item.type == "official") stringResource(R.string.screen_discovery_author_official) else item.type,
                                    color = ElementTheme.colors.textSecondary)
                                Text(item.header.description.ifBlank { stringResource(R.string.screen_discovery_author_no_bio) },
                                    color = ElementTheme.colors.textSecondary, maxLines = 3)
                                Text(stringResource(R.string.screen_discovery_article_count, item.header.articleCount), color = ElementTheme.colors.textSecondary)
                                Text(stringResource(R.string.screen_discovery_follower_count, item.header.followerCount ?: 0), color = ElementTheme.colors.textSecondary)
                            }
                        }
                    }
                }
                if (state.failed) item {
                    Text(stringResource(R.string.screen_discovery_unavailable))
                    TextButton(onClick = { state.eventSink(DiscoveryPersonalEvent.Retry) }) { Text(stringResource(R.string.screen_discovery_retry)) }
                } else if (!state.loading && state.items.isEmpty()) item {
                    Text(stringResource(when (state.kind) {
                        DiscoveryPersonalKind.FAVORITES -> R.string.screen_discovery_favorites_empty
                        DiscoveryPersonalKind.FOLLOWS -> R.string.screen_discovery_follows_empty
                        DiscoveryPersonalKind.HISTORY -> R.string.screen_discovery_history_empty
                        DiscoveryPersonalKind.REPLIES -> R.string.screen_discovery_replies_empty
                    }))
                }
                if (state.loading && state.items.isNotEmpty()) item { CircularProgressIndicator() }
                if (!state.loading && !state.failed && state.items.isNotEmpty()) item {
                    if (state.items.size < state.total && state.page < 1000) {
                        TextButton(onClick = { state.eventSink(DiscoveryPersonalEvent.More) }) { Text(stringResource(R.string.screen_discovery_more)) }
                    } else Text(stringResource(if (state.items.size < state.total) R.string.screen_discovery_browse_limit else R.string.screen_discovery_list_end),
                        color = ElementTheme.colors.textSecondary)
                }
            }
        }
    }
}

class DiscoveryPersonalStateProvider : PreviewParameterProvider<DiscoveryPersonalState> {
    override val values = sequenceOf(
        DiscoveryPersonalState(DiscoveryPersonalKind.REPLIES, eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.REPLIES, loading = false, eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.REPLIES, loading = false, failed = true, eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.REPLIES, loading = false, total = 1,
            items = listOf(DiscoveryPersonalItem.Reply(DiscoveryArticleComment(9, 1, "共读伙伴", "读完也有相同感受。", "2026-10-24 09:00", 8, "从日常重新理解这一段。"), "共读札记")), eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.HISTORY, eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.HISTORY, loading = false, eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.HISTORY, loading = false, failed = true, eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.HISTORY, loading = false, total = 1,
            items = listOf(DiscoveryPersonalItem.Article(DiscoveryArticle(1, "共读札记", "从经典回到日常", views = 128))), eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.FAVORITES, eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.FAVORITES, loading = false, eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.FOLLOWS, loading = false, eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.FOLLOWS, loading = false, failed = true, eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.FAVORITES, loading = false, total = 1,
            items = listOf(DiscoveryPersonalItem.Article(DiscoveryArticle(1, "共读札记", "从经典回到日常", likes = 12))), eventSink = {}),
        DiscoveryPersonalState(DiscoveryPersonalKind.FOLLOWS, loading = false, total = 1,
            items = listOf(DiscoveryPersonalItem.Author(1, ArticleCollectionHeader("共读作者", "在共读中照见生活", 12, 28, followed = true), "official")), eventSink = {}),
    )
}
@PreviewsDayNight
@Composable
private fun PersonalPreview(@PreviewParameter(DiscoveryPersonalStateProvider::class) state: DiscoveryPersonalState) = ElementPreview {
    DiscoveryPersonalView(state, {}, {}, {})
}
