/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package io.element.android.features.discovery.impl.screens.articles

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import org.robolectric.annotation.GraphicsMode
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryRepliesViewTest {
    @Test
    fun `reply click navigates with article identity instead of comment identity`() = runAndroidComposeUiTest<ComponentActivity> {
        var opened: Long? = null
        val reply = DiscoveryPersonalItem.Reply(DiscoveryArticleComment(9, 42, "共读伙伴", "读完也有同样的感受。", "2026-10-24 09:00", 8, "这一段让我想到身边的人。"), "共读札记")
        setContent {
            DiscoveryPersonalView(DiscoveryPersonalState(DiscoveryPersonalKind.REPLIES, listOf(reply), total = 1, loading = false, eventSink = {}),
                onBack = {}, onArticle = { error("ordinary article callback must not be used") }, onAuthor = {}, onReply = { opened = it })
        }
        savePreview("replies.png")
        onNodeWithText("读完也有同样的感受。").performClick()
        runOnIdle { assertEquals(42L, opened) }
    }

    @Test
    fun `reply entry waits for article then scrolls beyond long body to comments`() = runAndroidComposeUiTest<ComponentActivity> {
        val detail = mutableStateOf(DiscoveryArticleDetailState(reload = {}))
        val comments = DiscoveryCommentsState(42, listOf(DiscoveryArticleComment(9, 42, "共读伙伴", "这句话值得再读一遍。", "2026-10-24 09:00")), 1, loading = false, eventSink = {})
        setContent { DiscoveryArticleDetailView(detail.value, onBack = {}, comments = comments, openComments = true) }
        onNodeWithText("这句话值得再读一遍。").assertDoesNotExist()
        runOnIdle {
            detail.value = DiscoveryArticleDetailState(loading = false, reload = {}, article = DiscoveryArticle(42, "一篇长文章", "文章摘要",
                "从经典回到生活。\n".repeat(180), author = "共读作者", tags = listOf(ArticleTag(7, "经典"))))
        }
        onNodeWithText("这句话值得再读一遍。").assertIsDisplayed()
        onNodeWithText("一篇长文章").assertIsNotDisplayed()
        savePreview("comments.png")
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.savePreview(name: String) {
    System.getProperty("discovery.test.evidence")?.let { directory ->
        runOnIdle {
            val view = requireNotNull(activity).window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, name).outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            bitmap.recycle()
        }
    }
}
