/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package io.element.android.features.discovery.impl.screens.courses

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
@Config(sdk=[35],qualifiers="zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryCoursesViewTest {
    @Test
    fun `current actions carry both course and session ids and filters keep their own target`() = runAndroidComposeUiTest<ComponentActivity> {
        var target: Pair<Long,Long>?=null
        var location: Long?=null
        val state=mutableStateOf(DiscoveryCoursesState(loading=false,catalog=sampleCourseCatalog(),eventSink={}))
        setContent { DiscoveryCoursesView(state.value.copy(eventSink={ event -> state.value=when(event) {
            is DiscoveryCoursesEvent.Filter -> state.value.copy(filter=event.value)
            is DiscoveryCoursesEvent.Course -> state.value.copy(courseId=event.id)
            else -> state.value
        } }),{ course,session -> target=course to session },{ location=it.id }) }
        onNodeWithText("了解并报名").performScrollTo().performClick()
        runOnIdle { assertEquals(1L to 10L,target) }
        onAllNodesWithText("杭州 · 书院").onFirst().performScrollTo().performClick()
        runOnIdle { assertEquals(10L,location) }
        save("courses-current.png")
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("课程浏览"))
        onNode(hasText("已结束") and hasClickAction()).performScrollTo().performClick()
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("往期共读回顾"))
        onNodeWithText("回顾").performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1L to 30L,target);assertEquals(10L,state.value.catalog?.current?.id) }
        save("courses-history.png")
    }

    @Test
    fun `load failure offers retry and no stale course actions`() = runAndroidComposeUiTest<ComponentActivity> {
        var reload=false
        setContent { DiscoveryCoursesView(DiscoveryCoursesState(loading=false,failed=true,eventSink={ reload=true }),{ _,_->fail() },{fail()}) }
        onNodeWithText("重试").performClick()
        runOnIdle { assertTrue(reload) }
        onNodeWithText("了解并报名").assertDoesNotExist()
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.save(name: String) {
    System.getProperty("discovery.test.evidence")?.let { directory -> runOnIdle {
        val view=requireNotNull(activity).window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(directory,name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    } }
}
