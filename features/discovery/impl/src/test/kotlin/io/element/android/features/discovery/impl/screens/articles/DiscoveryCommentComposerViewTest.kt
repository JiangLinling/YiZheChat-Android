/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.discovery.impl.screens.articles

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryCommentComposerViewTest {
    @Test
    fun `article reply button selects the clicked comment and enables the fixed bottom composer`() = runAndroidComposeUiTest<ComponentActivity> {
        var selected: Long? = null
        var sent = false
        val state = mutableStateOf(DiscoveryArticleDetailState(loading = false, reload = {},
            article = DiscoveryArticle(5, "共读札记", "", "从经典回到生活。", commentEnabled = true)))
        val comment = DiscoveryArticleComment(7, 5, "共读伙伴", "这句话值得再读一遍。", "2026-10-24 09:00")
        val comments = DiscoveryCommentsState(5, listOf(comment), 1, loading = false, eventSink = {})
        setContent { DiscoveryArticleDetailView(state.value.copy(eventSink = { event ->
            when (event) {
                is DiscoveryArticleDetailEvent.Reply -> {
                    selected = event.comment.id
                    state.value = state.value.copy(commentReply = DiscoveryCommentReply(event.comment.id, event.comment.author))
                }
                is DiscoveryArticleDetailEvent.CommentInput -> state.value = state.value.copy(commentText = event.value)
                DiscoveryArticleDetailEvent.SendComment -> sent = true
                else -> Unit
            }
        }), onBack = {}, comments = comments, openComments = true) }
        onNodeWithText("回复").performScrollTo().performClick()
        onNodeWithText("发表评论").performTextInput("谢谢分享，我也有这样的感受。")
        onNodeWithText("发送").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(7L, selected); assertTrue(sent) }
        System.getProperty("discovery.test.evidence")?.let { directory -> runOnIdle {
            val view = requireNotNull(activity).window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "comment-detail-reply.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        } }
    }

    @Test
    fun `compose text cancel reply and send route through explicit events`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = mutableListOf<DiscoveryArticleDetailEvent>()
        val state = mutableStateOf(DiscoveryArticleDetailState(loading = false, reload = {},
            article = DiscoveryArticle(5, "", "", commentEnabled = true), commentReply = DiscoveryCommentReply(7, "共读伙伴")))
        setContent { DiscoveryCommentComposer(state.value.copy(eventSink = { event ->
            events += event
            when (event) {
                is DiscoveryArticleDetailEvent.CommentInput -> state.value = state.value.copy(commentText = event.value)
                DiscoveryArticleDetailEvent.CancelReply -> state.value = state.value.copy(commentReply = null)
                else -> Unit
            }
        })) }
        onNodeWithText("发表评论").performTextInput("这句话值得再读一遍。")
        onNodeWithText("发送").assertIsEnabled()
        System.getProperty("discovery.test.evidence")?.let { directory -> runOnIdle {
            val view = requireNotNull(activity).window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "comment-composer.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        } }
        onNodeWithText("取消回复").performClick()
        onNodeWithText("发送").performClick()
        runOnIdle { assertTrue(events.last() == DiscoveryArticleDetailEvent.SendComment); assertNull(state.value.commentReply) }
    }

    @Test
    fun `unknown result disables send and offers recovery without editing text`() = runAndroidComposeUiTest<ComponentActivity> {
        var recovered = false
        setContent { DiscoveryCommentComposer(DiscoveryArticleDetailState(loading = false, reload = {},
            article = DiscoveryArticle(5, "", "", commentEnabled = true), commentText = "原来的正文", commentPending = true,
            interactionPending = true, eventSink = { recovered = it == DiscoveryArticleDetailEvent.Recover })) }
        onNodeWithText("发送").assertIsNotEnabled()
        onNodeWithText("发表评论").assertIsNotEnabled()
        onNodeWithText("确认上次操作").performClick()
        runOnIdle { assertTrue(recovered) }
    }
}
