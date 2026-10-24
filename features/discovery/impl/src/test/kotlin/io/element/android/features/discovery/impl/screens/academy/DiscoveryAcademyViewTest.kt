/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package io.element.android.features.discovery.impl.screens.academy

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.discovery.impl.screens.articles.DiscoveryArticle
import io.element.android.features.discovery.impl.screens.home.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryAcademyViewTest {
    @Test
    fun `four home entries route with their article identity`() = runAndroidComposeUiTest<ComponentActivity> {
        var opened: Long? = null
        val entries = listOf("我们在做什么", "课程如何开展", "适合人群", "学习收获").mapIndexed { index, title ->
            DiscoveryHomeCard((index + 100).toString(), title, "从经典回到生活", "realm_articles", (index + 5).toLong())
        }
        setContent { DiscoveryHomeView(DiscoveryHomeState(loading = false,
            content = DiscoveryHomeContent(emptyList(), entries, emptyList(), emptyList()), eventSink = {}), onAcademy = { opened = it }) }
        entries.forEach { card ->
            onNodeWithText(card.title).performScrollTo().performClick()
            runOnIdle { assertEquals(card.targetArticleId, opened) }
        }
        save("academy-entries.png")
    }

    @Test
    fun `reader next uses next article id and last page returns home`() = runAndroidComposeUiTest<ComponentActivity> {
        var next: Long? = null
        var home = false
        val article = DiscoveryArticle(5, "我们在做什么", "研习经典，也照见当下的生活", "读一段文字，也读自己的生活。\n\n在相互聆听中，慢慢理解经典。", typeId = "realm")
        val content = mutableStateOf(DiscoveryAcademyContent(article,
            DiscoveryHomeCard("7", "课程如何开展", "共读文本，围谈经验，回到实践", "realm_articles", 7)))
        setContent { DiscoveryAcademyView(DiscoveryAcademyState(loading = false, content = content.value, reload = {}), {}, { next = it }, { home = true }) }
        onNodeWithText("下一篇").performScrollTo()
        save("academy-reader.png")
        onNodeWithText("课程如何开展").performClick()
        runOnIdle { assertEquals(7L, next); content.value = content.value.copy(next = null) }
        onNodeWithText("回到书院之境").performScrollTo().performClick()
        runOnIdle { assertTrue(home) }
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.save(name: String) {
    System.getProperty("discovery.test.evidence")?.let { directory -> runOnIdle {
        val view = requireNotNull(activity).window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    } }
}
