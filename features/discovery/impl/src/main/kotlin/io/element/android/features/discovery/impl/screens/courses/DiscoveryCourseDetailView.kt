/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Composable
internal fun DiscoveryCourseDetailView(
    state: DiscoveryCourseDetailState,
    onBack: () -> Unit,
    onEnroll: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding(),
        bottomBar = {
            state.promo?.let { promo ->
                Row(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary).padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(if (promo.feeFen == 0L) stringResource(R.string.screen_discovery_course_free)
                            else stringResource(R.string.screen_discovery_course_price, promo.feeFen / 100, (promo.feeFen % 100).toString().padStart(2, '0')),
                            style = ElementTheme.typography.fontHeadingMdBold)
                        Text(promo.remaining?.let { stringResource(R.string.screen_discovery_course_remaining, it) }
                            ?: stringResource(R.string.screen_discovery_course_quota_unknown), color = ElementTheme.colors.textSecondary)
                    }
                    TextButton(onClick = { onEnroll(promo.courseId, promo.sessionId) },
                        enabled = promo.status == CourseStatus.OPEN || (promo.status == CourseStatus.FULL && promo.allowWaitlist)) {
                        Text(stringResource(if (promo.status == CourseStatus.FULL) R.string.screen_discovery_courses_waitlist
                            else R.string.screen_discovery_courses_enroll))
                    }
                }
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { TextButton(onClick = onBack) { Text(stringResource(R.string.screen_discovery_back)) } }
            if (state.loading) item { CircularProgressIndicator() }
            if (state.failed) item {
                Text(stringResource(R.string.screen_discovery_unavailable))
                TextButton(onClick = state.onRetry) { Text(stringResource(R.string.screen_discovery_retry)) }
            }
            state.promo?.let { promo ->
                val data = promo.data
                val hero = data.objectField("hero")
                item {
                    Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(20.dp)).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(promo.status.label()), color = ElementTheme.colors.textSecondary)
                        Text(hero.string("title"), style = ElementTheme.typography.fontHeadingLgBold.copy(fontFamily = FontFamily.Serif))
                        Body(hero.string("subtitle")); Body(hero.string("summary")); Body(hero.string("scroll_hint"))
                    }
                }
                val value = data.objectField("value_proposition")
                item { Section(value.string("title")) { Body(value.string("body")); Body(value.string("quote")) } }
                val video = data.objectField("video")
                item { Section(video.string("title")) {
                    Body(video.string("description")); Body(video.string("duration"))
                    if (video.string("video_url").isNotBlank()) Text(stringResource(R.string.screen_discovery_course_media_pending), color = ElementTheme.colors.textSecondary)
                } }
                val resonance = data.objectField("resonance")
                item { Section(resonance.string("title")) {
                    Body(resonance.strings("decor_words", 4).joinToString(" · "))
                    resonance.strings("paragraphs", 40).forEach { Body(it) }
                    Body(resonance.string("highlight"))
                } }
                val pain = data.objectField("pain_points")
                item { Section(pain.string("title")) { pain.strings("items", 40).forEach { Body("• $it") } } }
                val transition = data.objectField("quiet_transition")
                item { Section("") { Body(transition.string("overlay_text")) } }
                item { Text(stringResource(R.string.screen_discovery_course_steps), style = ElementTheme.typography.fontHeadingMdBold) }
                items(data.objectField("entry_steps").objects("items", 20)) { step ->
                    Section(step.string("seal")) { Body(step.string("title")) }
                }
                item { Text(stringResource(R.string.screen_discovery_course_experience), style = ElementTheme.typography.fontHeadingMdBold) }
                items(data.objectField("experience").objects("items", 40)) { experience ->
                    Section(experience.string("icon") + " " + experience.string("title")) { Body(experience.string("description")) }
                }
                val flow = data.objectField("daily_flow")
                item { Text(flow.string("title"), style = ElementTheme.typography.fontHeadingMdBold) }
                items(flow.objects("items", 40)) { step -> Section(step.string("title")) { Body(step.string("description")) } }
                val closing = data.objectField("closing")
                item { Section(closing.string("title")) {
                    Body(closing.string("short_sentence")); Body(closing.string("summary"))
                    Body(closing.string("endorsement")); Body(closing.string("invitation"))
                } }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title.isNotBlank()) Text(title, style = ElementTheme.typography.fontHeadingMdBold.copy(fontFamily = FontFamily.Serif))
        content()
    }
}

@Composable
private fun Body(value: String) {
    if (value.isNotBlank()) Text(value, style = ElementTheme.typography.fontBodyMdRegular)
}

private fun CourseStatus.label(): Int = when (this) {
    CourseStatus.OPEN -> R.string.screen_discovery_courses_open
    CourseStatus.FULL -> R.string.screen_discovery_courses_full
    CourseStatus.PENDING -> R.string.screen_discovery_courses_pending
    CourseStatus.ENDED -> R.string.screen_discovery_courses_ended
    CourseStatus.CLOSED -> R.string.screen_discovery_courses_closed
    CourseStatus.CANCELLED -> R.string.screen_discovery_courses_cancelled
}

private class CourseDetailPreviewStates : PreviewParameterProvider<DiscoveryCourseDetailState> {
    override val values = sequenceOf(
        DiscoveryCourseDetailState(),
        DiscoveryCourseDetailState(loading = false, failed = true),
        DiscoveryCourseDetailState(loading = false, promo = DiscoveryPromo(1, 10, 12345, 30, 12, CourseStatus.OPEN, false,
            Json.parseToJsonElement("""{
                "version":1,
                "hero":{"title":"周末共读","subtitle":"回到经典与生活","summary":"在阅读与对话中重新看见日常。","scroll_hint":"向下探索"},
                "value_proposition":{"title":"为何共修","body":"一起读书，一起实践。","quote":"读书让生活更清明。"},
                "video":{"title":"课程介绍","description":"课程短片","duration":"02:30","video_url":""},
                "resonance":{"title":"与你相遇","decor_words":["停下","回望"],"paragraphs":["慢一点，看看身边。"],"highlight":""},
                "pain_points":{"title":"你是否也有这样的时刻","items":["忙碌中想慢下来"]},
                "quiet_transition":{"overlay_text":"从这里开始"},
                "entry_steps":{"items":[{"seal":"一","title":"了解课程"},{"seal":"二","title":"报名"}]},
                "experience":{"items":[{"icon":"书","title":"共读","description":"走进经典"}]},
                "daily_flow":{"title":"一日之间","items":[{"title":"晨读","description":"从文字开始"}]},
                "closing":{"title":"带走些什么","short_sentence":"把触动带回日常","summary":"继续实践","endorsement":"","invitation":"欢迎你"},
                "bottom_bar":{}
            }""").jsonObject)),
    )
}

@PreviewsDayNight
@Composable
private fun CourseDetailPreview(@PreviewParameter(CourseDetailPreviewStates::class) state: DiscoveryCourseDetailState) = ElementPreview {
    DiscoveryCourseDetailView(state, onBack = {}, onEnroll = { _, _ -> })
}
