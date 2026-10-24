/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text

internal fun LazyListScope.discoveryComments(state: DiscoveryCommentsState, onReply: ((DiscoveryArticleComment) -> Unit)? = null) {
    item(key = "comments-heading") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.items.isEmpty() && (state.loading || state.failed)) stringResource(R.string.screen_discovery_comments_title) else stringResource(R.string.screen_discovery_comments, state.total), style = ElementTheme.typography.fontHeadingSmMedium)
            TextButton(onClick = { state.eventSink(DiscoveryPersonalEvent.Refresh) }, enabled = !state.loading) {
                Text(stringResource(R.string.screen_discovery_refresh))
            }
        }
    }
    items(state.items, key = { "comment-${it.id}" }) { comment ->
        Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DiscoveryCommentContent(comment)
            if (onReply != null) TextButton(onClick = { onReply(comment) }) { Text(stringResource(R.string.screen_discovery_comment_reply)) }
        }
    }
    if (state.failed) item(key = "comments-error") {
        Text(stringResource(if (state.missing) R.string.screen_discovery_article_missing else R.string.screen_discovery_unavailable))
        if (!state.missing) TextButton(onClick = { state.eventSink(DiscoveryPersonalEvent.Retry) }) { Text(stringResource(R.string.screen_discovery_retry)) }
    }
    if (state.loading) item(key = "comments-loading") { CircularProgressIndicator() }
    if (!state.loading && !state.failed) item(key = "comments-end") {
        if (state.items.isEmpty()) Text(stringResource(R.string.screen_discovery_comments_empty), color = ElementTheme.colors.textSecondary)
        else if (state.items.size < state.total && state.page < 1000) {
            TextButton(onClick = { state.eventSink(DiscoveryPersonalEvent.More) }) { Text(stringResource(R.string.screen_discovery_more)) }
        } else Text(stringResource(if (state.items.size < state.total) R.string.screen_discovery_browse_limit else R.string.screen_discovery_list_end),
            color = ElementTheme.colors.textSecondary)
    }
}

@Composable
internal fun DiscoveryCommentContent(comment: DiscoveryArticleComment, myReply: Boolean = false) {
    Text(comment.author.ifBlank { stringResource(R.string.screen_discovery_comment_user) }, style = ElementTheme.typography.fontBodyLgMedium)
    if (comment.createdAt.isNotBlank()) Text(comment.createdAt, color = ElementTheme.colors.textSecondary)
    if (comment.pinned) Text(stringResource(R.string.screen_discovery_comment_pinned), color = ElementTheme.colors.textSecondary)
    if (comment.authorReply) Text(stringResource(R.string.screen_discovery_comment_author), color = ElementTheme.colors.textSecondary)
    Text(comment.content)
    if (comment.parentId != null) {
        Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgCanvasDefault, RoundedCornerShape(8.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (myReply) stringResource(R.string.screen_discovery_reply_context) else stringResource(R.string.screen_discovery_reply_to,
                comment.parentAuthor.ifBlank { stringResource(R.string.screen_discovery_comment_user) }), color = ElementTheme.colors.textSecondary)
            Text(comment.parentContent, color = ElementTheme.colors.textSecondary)
        }
    }
    if (!myReply && comment.likes > 0) Text(stringResource(R.string.screen_discovery_article_likes, comment.likes), color = ElementTheme.colors.textSecondary)
}

class DiscoveryCommentsStateProvider : PreviewParameterProvider<DiscoveryCommentsState> {
    override val values = sequenceOf(
        DiscoveryCommentsState(1, eventSink = {}),
        DiscoveryCommentsState(1, loading = false, eventSink = {}),
        DiscoveryCommentsState(1, loading = false, failed = true, eventSink = {}),
        DiscoveryCommentsState(1, loading = false, failed = true, missing = true, eventSink = {}),
        DiscoveryCommentsState(1, loading = false, total = 1, items = listOf(
            DiscoveryArticleComment(9, 1, "共读伙伴", "从不同的经历出发，读到了同样的温度。", "2026-10-24 09:00", 8, "这一句让我想到身边的人。", "读者", 3)
        ), eventSink = {}),
    )
}
@PreviewsDayNight
@Composable
private fun CommentsPreview(@PreviewParameter(DiscoveryCommentsStateProvider::class) state: DiscoveryCommentsState) = ElementPreview {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { discoveryComments(state) }
}
