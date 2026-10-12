/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package io.element.android.features.location.impl.show

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.location.api.LiveLocationSharingBanner
import io.element.android.features.location.api.live.PausedLiveLocationShare
import io.element.android.libraries.designsystem.preview.ElementPreview
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveLocationPauseViewTest {
    @Test fun `paused banner explains permission and Stop works without Resume`() = runAndroidComposeUiTest<ComponentActivity> {
        var stops = 0
        lateinit var composeView: View
        setContent {
            composeView = LocalView.current
            ElementPreview {
            LiveLocationSharingBanner(onClick = null, onStopClick = { stops++ }, pausedState = PausedLiveLocationShare.RECOVERABLE)
        } }
        onNodeWithText("Location updates paused. Check location permission.").assertIsDisplayed()
        onNodeWithText("Resume").assertDoesNotExist()
        saveScreenshot("recoverable", composeView)
        onNodeWithText("Stop").performClick()
        assertThat(stops).isEqualTo(1)
    }

    @Test fun `checking banner keeps stopping available`() = runAndroidComposeUiTest<ComponentActivity> {
        var stops = 0
        lateinit var composeView: View
        setContent {
            composeView = LocalView.current
            ElementPreview {
            LiveLocationSharingBanner(onClick = null, onStopClick = { stops++ }, pausedState = PausedLiveLocationShare.CHECKING)
        } }
        onNodeWithText("Location updates paused. Checking the original share…").assertIsDisplayed()
        saveScreenshot("checking", composeView)
        onNodeWithText("Stop").performClick()
        assertThat(stops).isEqualTo(1)
    }

    @Test fun `unrecoverable banner remains paused instead of claiming ended`() = runAndroidComposeUiTest<ComponentActivity> {
        lateinit var composeView: View
        setContent {
            composeView = LocalView.current
            ElementPreview {
            LiveLocationSharingBanner(onClick = null, onStopClick = {}, pausedState = PausedLiveLocationShare.UNRECOVERABLE)
        } }
        onNodeWithText("Location updates paused. End this share to continue.").assertIsDisplayed()
        onNodeWithText("Resume").assertDoesNotExist()
        saveScreenshot("unrecoverable", composeView)
    }

    private fun AndroidComposeUiTest<ComponentActivity>.saveScreenshot(name: String, view: View) {
        val directory = checkNotNull(System.getProperty("bug029.ui.screenshots"))
        val bitmap = runOnUiThread {
            assertThat(view.width).isGreaterThan(0)
            assertThat(view.height).isGreaterThan(0)
            // Compose captureToImage waits for a real window redraw, unavailable in Robolectric.
            // Draw the actual view tree using the already-enabled native graphics backend.
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).apply { view.draw(Canvas(this)) }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertThat(pixels.toSet().size).isGreaterThan(32)
        assertThat(pixels.count { (it and 0x00FFFFFF) < 0x00808080 }).isGreaterThan(100)
        val file = File(directory, "$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
