/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
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

@Composable
fun DiscoveryHomeView(state: DiscoveryHomeState, modifier: Modifier = Modifier, onNews: () -> Unit = {},
    onSearch: () -> Unit = {}, onArticle: (Long) -> Unit = {}, onAcademy: (Long) -> Unit = {}, onCourses: () -> Unit = {},
    onSession: (Long, Long) -> Unit = { _, _ -> }, onBanner: (DiscoveryHomeCard) -> Unit = {}) {
    LazyColumn(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(stringResource(R.string.screen_discovery_brand), style = ElementTheme.typography.fontHeadingLgBold)
            Text(stringResource(R.string.screen_discovery_brand_subtitle), color = ElementTheme.colors.textSecondary)
            TextButton(onClick = onSearch) { Text(stringResource(R.string.screen_discovery_search_hint)) }
            TextButton(onClick = onCourses) { Text(stringResource(R.string.screen_discovery_courses_browse)) }
        }
        if (state.loading) item { CircularProgressIndicator() }
        if (state.failed) item {
            Text(stringResource(R.string.screen_discovery_unavailable))
            TextButton(onClick = { state.eventSink(DiscoveryHomeEvent.Reload) }) { Text(stringResource(R.string.screen_discovery_retry)) }
        }
        state.content?.let { content ->
            items(content.banners, key = { "banner-${it.id}" }) { card ->
                val actionable = card.bannerTarget() != null
                HomeCard(card, Modifier.clickable(enabled = actionable) { onBanner(card) })
            }
            item { Text(stringResource(R.string.screen_discovery_academy), style = ElementTheme.typography.fontHeadingMdBold) }
            if (content.academy.isEmpty()) item { Text(stringResource(R.string.screen_discovery_academy_empty), color = ElementTheme.colors.textSecondary) }
            items(content.academy.chunked(2), key = { "academy-${it.first().id}" }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { card ->
                        HomeCard(card, Modifier.weight(1f).clickable(enabled = card.targetArticleId != null) { card.targetArticleId?.let(onAcademy) }, compact = true)
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item { Text(stringResource(R.string.screen_discovery_enrollment), style = ElementTheme.typography.fontHeadingMdBold) }
            if (content.sessions.isEmpty()) item { Text(stringResource(R.string.screen_discovery_no_sessions), color = ElementTheme.colors.textSecondary) }
            items(content.sessions, key = { "session-${it.id}" }) { card ->
                HomeCard(card, Modifier.clickable(enabled = card.targetCourseId != null && card.targetSessionId != null) {
                    val courseId = card.targetCourseId
                    val sessionId = card.targetSessionId
                    if (courseId != null && sessionId != null) onSession(courseId, sessionId)
                })
            }
            item { TextButton(onClick = onNews) { Text(stringResource(R.string.screen_discovery_news), style = ElementTheme.typography.fontHeadingMdBold) } }
            if (content.news.isEmpty()) item { Text(stringResource(R.string.screen_discovery_no_news), color = ElementTheme.colors.textSecondary) }
            items(content.news, key = { "news-${it.id}" }) { card ->
                HomeCard(card, Modifier.clickable(enabled = card.id.toLongOrNull() != null) { card.id.toLongOrNull()?.let(onArticle) })
            }
        }
    }
}

@Composable
private fun HomeCard(card: DiscoveryHomeCard, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(16.dp)).padding(if (compact) 16.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(card.title, style = ElementTheme.typography.fontBodyLgMedium)
        if (card.subtitle.isNotBlank()) Text(card.subtitle, color = ElementTheme.colors.textSecondary)
    }
}

class DiscoveryHomeStateProvider : PreviewParameterProvider<DiscoveryHomeState> {
    override val values = sequenceOf(
        DiscoveryHomeState(eventSink = {}),
        DiscoveryHomeState(loading = false, failed = true, eventSink = {}),
        DiscoveryHomeState(loading = false, content = DiscoveryHomeContent(emptyList(), emptyList(), emptyList(), emptyList()), eventSink = {}),
        DiscoveryHomeState(loading = false, content = DiscoveryHomeContent(
            listOf(DiscoveryHomeCard("1", "在课程中相遇", "共读经典，回到生活", "banner")),
            listOf(DiscoveryHomeCard("2", "我们在做什么", "研习经典，也照见当下的生活", "academy")),
            listOf(DiscoveryHomeCard("3", "周末共读", "书院", "session")),
            listOf(DiscoveryHomeCard("4", "共读札记", "书院动态", "news")),
        ), eventSink = {}),
    )
}

@PreviewsDayNight
@Composable
private fun HomePreview(@PreviewParameter(DiscoveryHomeStateProvider::class) state: DiscoveryHomeState) = ElementPreview {
    DiscoveryHomeView(state)
}
