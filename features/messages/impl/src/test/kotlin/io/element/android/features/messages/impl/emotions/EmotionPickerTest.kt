/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.emotions

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.R
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmotionPickerTest {
    @Test fun `all sixty pictures can be reached and send exactly their own expression`() = runAndroidComposeUiTest<ComponentActivity> {
        val sent = mutableListOf<ChatEmotion>()
        setSafeContent { Box(Modifier.height(244.dp)) { EmotionPicker(true, sent::add) } }
        val firstRow = ChatEmotions.all.take(6).map { onNodeWithTag("emotion_choice_${it.id}").fetchSemanticsNode().boundsInRoot }
        assertThat(firstRow.map { it.top }.distinct()).hasSize(1)
        val nextRow = onNodeWithTag("emotion_choice_thanks").fetchSemanticsNode().boundsInRoot
        assertThat(nextRow.top).isGreaterThan(firstRow.first().top)
        ChatEmotions.all.forEach { emotion ->
            onNodeWithTag("emotion_choices").performScrollToKey(emotion.id)
            onNodeWithTag("emotion_badge_${emotion.id}", useUnmergedTree = true).assertExists()
            onNodeWithTag("emotion_choice_${emotion.id}").performClick()
        }
        assertThat(sent.map { it.id }).containsExactlyElementsIn(ChatEmotions.all.map { it.id }).inOrder()
    }

    @Test fun `one grid retains plain insertion animated sending backspace and common information`() = runAndroidComposeUiTest<ComponentActivity> {
        val inserted = mutableListOf<String>()
        val sent = mutableListOf<ChatEmotion>()
        var backspaces = 0
        var commonOpened = false
        setSafeContent { Box(Modifier.width(360.dp).height(244.dp)) {
            EmotionPicker(true, sent::add, listOf("😂", "😀"), inserted::add, { backspaces++ }, { commonOpened = true }, "Common")
        } }
        onNodeWithTag("emoji_😂").performClick()
        assertThat(inserted).containsExactly("😂")
        assertThat(sent).isEmpty()
        onNodeWithTag("emotion_choice_laugh").performClick()
        assertThat(sent.map { it.id }).containsExactly("laugh")
        onNodeWithText(activity!!.getString(R.string.emotion_tab)).assertDoesNotExist()
        onNodeWithText(activity!!.getString(R.string.emotion_tier_daily)).assertDoesNotExist()
        onNodeWithText(activity!!.getString(R.string.emotion_unified_hint)).assertDoesNotExist()
        onNodeWithText(activity!!.getString(R.string.emotion_settings)).assertDoesNotExist()
        val commonBounds = onNodeWithTag("emoji_common").fetchSemanticsNode().boundsInRoot
        val deleteBounds = onNodeWithTag("emoji_backspace").fetchSemanticsNode().boundsInRoot
        assertThat(commonBounds.right).isEqualTo(deleteBounds.right)
        assertThat(commonBounds.bottom).isAtMost(deleteBounds.top)
        val gridBounds = onNodeWithTag("emotion_choices").fetchSemanticsNode().boundsInRoot
        assertThat(gridBounds.bottom).isGreaterThan(deleteBounds.bottom)
        onNodeWithTag("emoji_backspace").performTouchInput { click() }
        onNodeWithText("Common").performTouchInput { click() }
        assertThat(backspaces).isEqualTo(1)
        assertThat(commonOpened).isTrue()
    }

    @Test fun `vertical swipes reach last and first expressions without sending while scrolling`() = runAndroidComposeUiTest<ComponentActivity> {
        val sent = mutableListOf<ChatEmotion>()
        setSafeContent { Box(Modifier.width(360.dp).height(160.dp)) { EmotionPicker(true, sent::add) } }
        onNodeWithTag("emotion_choice_laugh").performClick()
        repeat(10) { onNodeWithTag("emotion_choices").performTouchInput { swipeUp() } }
        onNodeWithTag("emotion_choice_gift_bouquet").assertIsDisplayed()
        assertThat(sent.map { it.id }).containsExactly("laugh")
        onNodeWithTag("emotion_choice_gift_bouquet").performTouchInput { click() }
        repeat(10) { onNodeWithTag("emotion_choices").performTouchInput { swipeDown() } }
        onNodeWithTag("emotion_choice_laugh").assertIsDisplayed()
        assertThat(sent.map { it.id }).containsExactly("laugh", "gift_bouquet").inOrder()
    }

    @Test fun `holding previews without a dialog and release closes without sending`() = runAndroidComposeUiTest<ComponentActivity> {
        val sent = mutableListOf<ChatEmotion>()
        setSafeContent { Box(Modifier.height(244.dp)) { EmotionPicker(true, sent::add) } }
        mainClock.autoAdvance = false
        onNodeWithTag("emotion_choice_laugh").performTouchInput { down(center) }
        mainClock.advanceTimeBy(800)
        onNodeWithTag("emotion_preview").assertExists()
        onNodeWithText(activity!!.getString(R.string.emotion_send)).assertDoesNotExist()
        mainClock.advanceTimeBy(3000)
        onNodeWithTag("emotion_preview").assertExists()
        assertThat(sent).isEmpty()
        onNodeWithTag("emotion_choice_laugh").performTouchInput { up() }
        mainClock.autoAdvance = true
        onNodeWithTag("emotion_preview").assertDoesNotExist()
        assertThat(sent).isEmpty()
        onNodeWithTag("emotion_choice_laugh").performClick()
        assertThat(sent.map { it.id }).containsExactly("laugh")
    }

    @Test fun `preview only mode cannot send an effect`() = runAndroidComposeUiTest<ComponentActivity> {
        val sent = mutableListOf<ChatEmotion>()
        setSafeContent { Box(Modifier.height(244.dp)) { EmotionPicker(false, sent::add) } }
        onNodeWithTag("emotion_choices").performScrollToKey("prosperity")
        mainClock.autoAdvance = false
        onNodeWithTag("emotion_choice_prosperity").performTouchInput { down(center) }
        mainClock.advanceTimeBy(800)
        onNodeWithTag("emotion_preview").assertExists()
        onNodeWithTag("emotion_choice_prosperity").performTouchInput { up() }
        mainClock.autoAdvance = true
        onNodeWithTag("emotion_preview").assertDoesNotExist()
        assertThat(sent).isEmpty()
    }

    @Test fun `cancelled hold removes transparent preview without sending`() = runAndroidComposeUiTest<ComponentActivity> {
        val sent = mutableListOf<ChatEmotion>()
        setSafeContent { Box(Modifier.height(244.dp)) { EmotionPicker(true, sent::add) } }
        mainClock.autoAdvance = false
        onNodeWithTag("emotion_choice_laugh").performTouchInput { down(center) }
        mainClock.advanceTimeBy(800)
        onNodeWithTag("emotion_preview").assertExists()
        onNodeWithTag("emotion_choice_laugh").performTouchInput { cancel() }
        mainClock.autoAdvance = true
        onNodeWithTag("emotion_preview").assertDoesNotExist()
        assertThat(sent).isEmpty()
    }

    @Test fun `holding a plain emoji previews transparently and release does not insert it`() = runAndroidComposeUiTest<ComponentActivity> {
        val inserted = mutableListOf<String>()
        setSafeContent { Box(Modifier.height(244.dp)) { EmotionPicker(true, {}, listOf("😀"), inserted::add) } }
        mainClock.autoAdvance = false
        onNodeWithTag("emoji_😀").performTouchInput { down(center) }
        mainClock.advanceTimeBy(800)
        onNodeWithTag("emoji_preview").assertExists()
        assertThat(inserted).isEmpty()
        onNodeWithTag("emoji_😀").performTouchInput { up() }
        mainClock.autoAdvance = true
        onNodeWithTag("emoji_preview").assertDoesNotExist()
        assertThat(inserted).isEmpty()
    }
}
