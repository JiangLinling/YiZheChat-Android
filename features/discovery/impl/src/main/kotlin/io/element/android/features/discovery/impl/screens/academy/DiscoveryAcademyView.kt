/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.academy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.features.discovery.impl.screens.articles.DiscoveryArticle
import io.element.android.features.discovery.impl.screens.home.DiscoveryHomeCard
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text

@Composable
fun DiscoveryAcademyView(state: DiscoveryAcademyState, onBack: () -> Unit, onNext: (Long) -> Unit,
    onHome: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { TextButton(onClick = onBack) { Text(stringResource(R.string.screen_discovery_back)) } }
        if (state.loading) item { CircularProgressIndicator() }
        if (state.failed) item {
            Text(stringResource(if (state.missing) R.string.screen_discovery_academy_missing else R.string.screen_discovery_unavailable))
            TextButton(onClick = state.reload) { Text(stringResource(R.string.screen_discovery_retry)) }
        }
        state.content?.let { page ->
            item {
                Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(20.dp)).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.screen_discovery_academy), color = ElementTheme.colors.textSecondary)
                    Text(page.article.title, style = ElementTheme.typography.fontHeadingLgBold.copy(fontFamily = FontFamily.Serif))
                    if (page.article.summary.isNotBlank()) Text(page.article.summary, color = ElementTheme.colors.textSecondary)
                }
            }
            item {
                Text(page.article.content.ifBlank { stringResource(R.string.screen_discovery_academy_no_text) },
                    style = ElementTheme.typography.fontBodyLgRegular.copy(fontFamily = FontFamily.Serif))
            }
            item {
                val next = page.next
                if (next?.targetArticleId != null) {
                    Column(Modifier.fillMaxWidth().clickable { onNext(next.targetArticleId) }
                        .background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(16.dp)).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.screen_discovery_academy_next), color = ElementTheme.colors.textSecondary)
                        Text(next.title, style = ElementTheme.typography.fontHeadingSmMedium)
                        if (next.subtitle.isNotBlank()) Text(next.subtitle)
                    }
                } else TextButton(onClick = onHome) { Text(stringResource(R.string.screen_discovery_academy_return)) }
            }
        }
    }
}

class DiscoveryAcademyStateProvider : PreviewParameterProvider<DiscoveryAcademyState> {
    private val article = DiscoveryArticle(1, "我们在做什么", "研习经典，也照见当下的生活", "读一段文字，也读自己的生活。\n\n在相互聆听中，慢慢理解经典。", typeId = "realm")
    override val values = sequenceOf(DiscoveryAcademyState(reload = {}),
        DiscoveryAcademyState(loading = false, failed = true, reload = {}),
        DiscoveryAcademyState(loading = false, failed = true, missing = true, reload = {}),
        DiscoveryAcademyState(loading = false, content = DiscoveryAcademyContent(article, null), reload = {}),
        DiscoveryAcademyState(loading = false, content = DiscoveryAcademyContent(article,
            DiscoveryHomeCard("2", "课程如何开展", "共读文本，围谈经验，回到实践", "realm_articles", 2)), reload = {}))
}

@PreviewsDayNight
@Composable
private fun AcademyPreview(@PreviewParameter(DiscoveryAcademyStateProvider::class) state: DiscoveryAcademyState) = ElementPreview {
    DiscoveryAcademyView(state, {}, {}, {})
}
