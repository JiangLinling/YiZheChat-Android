/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text

@Composable
internal fun DiscoveryCommentComposer(state: DiscoveryArticleDetailState) {
    val editable = state.article?.commentEnabled == true && !state.failed && !state.loading && !state.interactionBusy &&
        !state.interactionPending && !state.interactionFailed
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.commentReply?.id) { if (state.commentReply != null && editable) focus.requestFocus() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!state.loading && state.article?.commentEnabled == false) {
            Text(stringResource(R.string.screen_discovery_comments_disabled), color = ElementTheme.colors.textSecondary)
        }
        state.commentReply?.let { reply ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.screen_discovery_reply_to, reply.author.ifBlank { stringResource(R.string.screen_discovery_comment_user) }),
                    modifier = Modifier.weight(1f), maxLines = 1, color = ElementTheme.colors.textSecondary)
                TextButton(onClick = { state.eventSink(DiscoveryArticleDetailEvent.CancelReply) }, enabled = editable) {
                    Text(stringResource(R.string.screen_discovery_comment_cancel_reply))
                }
            }
        }
        if (state.commentSent) Text(stringResource(R.string.screen_discovery_comment_sent), color = ElementTheme.colors.textSecondary)
        if (state.commentPending) {
            Text(stringResource(R.string.screen_discovery_comment_pending), color = ElementTheme.colors.textSecondary)
            TextButton(onClick = { state.eventSink(DiscoveryArticleDetailEvent.Recover) }, enabled = !state.interactionBusy) {
                Text(stringResource(R.string.screen_discovery_confirm_interaction))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = state.commentText, onValueChange = { state.eventSink(DiscoveryArticleDetailEvent.CommentInput(it)) },
                modifier = Modifier.weight(1f).focusRequester(focus), enabled = editable, minLines = 1, maxLines = 3,
                label = { Text(stringResource(R.string.screen_discovery_comment_write)) },
                placeholder = { Text(stringResource(if (state.article?.commentEnabled == true) R.string.screen_discovery_comment_placeholder else R.string.screen_discovery_comments_disabled)) },
                supportingText = { Text(stringResource(R.string.screen_discovery_comment_length, state.commentText.codePointCount(0, state.commentText.length))) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (editable) state.eventSink(DiscoveryArticleDetailEvent.SendComment) }))
            TextButton(onClick = { state.eventSink(DiscoveryArticleDetailEvent.SendComment) }, enabled = editable && state.commentText.isNotBlank()) {
                Text(stringResource(R.string.screen_discovery_comment_send))
            }
        }
    }
}

class DiscoveryCommentComposerStateProvider : PreviewParameterProvider<DiscoveryArticleDetailState> {
    private val base = DiscoveryArticleDetailState(loading = false, reload = {}, article = DiscoveryArticle(1, "", "", commentEnabled = true))
    override val values = sequenceOf(base, base.copy(commentText = "这一段让我想到身边的人。"),
        base.copy(commentText = "谢谢分享", commentReply = DiscoveryCommentReply(2, "共读伙伴")),
        base.copy(commentText = "待确认的评论", commentPending = true, interactionPending = true),
        base.copy(commentText = "发送中", interactionBusy = true), base.copy(commentSent = true),
        base.copy(article = base.article?.copy(commentEnabled = false)))
}

@PreviewsDayNight
@Composable
private fun ComposerPreview(@PreviewParameter(DiscoveryCommentComposerStateProvider::class) state: DiscoveryArticleDetailState) = ElementPreview {
    DiscoveryCommentComposer(state)
}
