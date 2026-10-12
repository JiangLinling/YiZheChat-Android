/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import android.text.Spanned
import android.text.SpannedString
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayout
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayoutData
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContentProvider
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.features.messages.impl.utils.containsOnlyEmojis
import io.element.android.features.messages.impl.utils.emojiGraphemeCountInternal
import io.element.android.libraries.androidutils.text.LinkifyHelper
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.textcomposer.ElementRichTextEditorStyle
import io.element.android.libraries.textcomposer.mentions.LocalMentionSpanUpdater
import io.element.android.libraries.textcomposer.mentions.getMentionSpans
import io.element.android.wysiwyg.compose.EditorStyledText
import io.element.android.wysiwyg.link.Link

@Composable
fun TimelineItemTextView(
    content: TimelineItemTextBasedContent,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    modifier: Modifier = Modifier,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit = {},
) {
    val card = content.takeIf { !it.isBurnAfterRead && !it.isProtectedContentShell && !it.isTerminalProtectedContentShell }
        ?.let { io.element.android.libraries.matrix.api.permalink.SharedIdentityCard.parse(it.body) }
    if (card != null) {
        IdentityCardView(card, { onLinkClick(Link(url = card.url, text = card.name)) }, modifier)
        return
    }
    val emotion = (content as? io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextContent)
        ?.takeIf { !it.isBurnAfterRead && !it.isProtectedContentShell && !it.isTerminalProtectedContentShell && it.formattedBody.toString() == it.body }
        ?.let { io.element.android.features.messages.impl.emotions.ChatEmotions.fromBody(it.body) }
    if (emotion != null) {
        io.element.android.features.messages.impl.emotions.EmotionBubble(emotion,
            modifier.then(Modifier.size(96.dp)).semantics { contentDescription = content.plainText })
        return
    }
    val emojiCount = emojiOnlyCount(content)
    val textStyle = when {
        emojiCount == 1 -> ElementTheme.typography.fontHeadingXlRegular.copy(fontSize = 48.sp)
        emojiCount == 2 -> ElementTheme.typography.fontHeadingXlRegular.copy(fontSize = 42.sp)
        emojiCount == 3 -> ElementTheme.typography.fontHeadingXlRegular.copy(fontSize = 36.sp)
        emojiCount != null -> ElementTheme.typography.fontHeadingXlRegular
        else -> ElementTheme.typography.fontBodyLgRegular
    }
    CompositionLocalProvider(
        LocalContentColor provides ElementTheme.colors.textPrimary,
        LocalTextStyle provides textStyle
    ) {
        val text = getTextWithResolvedMentions(content)
        Box(modifier.semantics { contentDescription = content.plainText }) {
            if (canUseComposeText(text)) {
                // The timeline factory has already linkified URLs and converted rich content to spans.
                // A span-free value is therefore ordinary text and does not need the AndroidView-based
                // WYSIWYG renderer (which initializes HTML and mention machinery for every new cell).
                Text(
                    text = text.toString(),
                    // EditorStyledText does not apply the design token's letter spacing. Keep the
                    // fast path's wrapping and bubble height equivalent to the existing renderer.
                    style = textStyle.copy(letterSpacing = 0.em),
                    onTextLayout = ContentAvoidingLayout.measureLastTextLine(onContentLayoutChange = onContentLayoutChange),
                )
            } else {
                EditorStyledText(
                    text = text,
                    onLinkClickedListener = onLinkClick,
                    onLinkLongClickedListener = onLinkLongClick,
                    style = ElementRichTextEditorStyle.textStyle(),
                    onTextLayout = ContentAvoidingLayout.measureLegacyLastTextLine(onContentLayoutChange = onContentLayoutChange),
                    releaseOnDetach = false,
                )
            }
        }
    }
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
@Composable
internal fun getTextWithResolvedMentions(content: TimelineItemTextBasedContent): CharSequence {
    val formattedBody = content.formattedBody
    // Most timeline messages do not contain mentions. Avoid creating two room-cache Flow
    // collectors and their Compose state for every ordinary text cell entering the viewport.
    val bodyWithResolvedMentions = if (formattedBody.getMentionSpans().isEmpty()) {
        formattedBody
    } else {
        LocalMentionSpanUpdater.current.rememberMentionSpans(formattedBody)
    }
    // Mention spans may be updated in-place when member/room information or the theme changes.
    // Always snapshot the current values so the renderer observes those mutations.
    return SpannedString.valueOf(bodyWithResolvedMentions)
}

@Composable
internal fun isEmojiOnlyContent(content: TimelineItemTextBasedContent): Boolean {
    if (!content.isBurnAfterRead && !content.isProtectedContentShell && content.formattedBody.toString() == content.body &&
        io.element.android.features.messages.impl.emotions.ChatEmotions.fromBody(content.body) != null) return true
    val emojiCount = emojiOnlyCount(content)
    return emojiCount != null && emojiCount in 1..3
}

@Composable
private fun emojiOnlyCount(content: TimelineItemTextBasedContent): Int? {
    val compactBody = content.body.replace(" ", "")
    val emojiOnly = content.formattedBody.toString() == content.body && compactBody.containsOnlyEmojis()
    return compactBody.emojiGraphemeCountInternal().takeIf { emojiOnly }
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun canUseComposeText(text: CharSequence): Boolean =
    text !is Spanned || text.getSpans(0, text.length, Any::class.java).isEmpty()

@PreviewsDayNight
@Composable
internal fun TimelineItemTextViewPreview(
    @PreviewParameter(TimelineItemTextBasedContentProvider::class) content: TimelineItemTextBasedContent
) = ElementPreview {
    TimelineItemTextView(
        content = content,
        onLinkClick = {},
        onLinkLongClick = {},
    )
}

@Preview
@Composable
internal fun TimelineItemTextViewWithLinkifiedUrlPreview() = ElementPreview {
    val content = aTimelineItemTextContent(
        formattedBody = LinkifyHelper.linkify("The link should end after the first '?' (url: github.com/element-hq/element-x-android/README?)?.")
    )
    TimelineItemTextView(
        content = content,
        onLinkClick = {},
        onLinkLongClick = {},
    )
}

@Preview
@Composable
internal fun TimelineItemTextViewWithLinkifiedUrlAndNestedParenthesisPreview() = ElementPreview {
    val content = aTimelineItemTextContent(
        formattedBody = LinkifyHelper.linkify("The link should end after the '(ME)' ((url: github.com/element-hq/element-x-android/READ(ME)))!")
    )
    TimelineItemTextView(
        content = content,
        onLinkClick = {},
        onLinkLongClick = {},
    )
}
