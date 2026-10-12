/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline.components.event

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemPollContent
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.pressTag
import org.junit.Test
import org.junit.runner.RunWith
import io.element.android.features.poll.api.R as PollR

@RunWith(AndroidJUnit4::class)
class TimelineItemPollViewTest {
    @Test
    fun `peer ending poll restores committed selection and discards unsent choice from results`() = runAndroidComposeUiTest<ComponentActivity> {
        val original = aTimelineItemPollContent()
        val content = androidx.compose.runtime.mutableStateOf(original)
        val events = EventsRecorder<TimelineEvent.TimelineItemPollEvent>()
        setContent { TimelineItemPollView(content.value, eventSink = events) }
        clickOn(PollR.string.screen_poll_voted)
        val draft = original.answerItems.first { !it.isSelected }.answer
        val committed = original.answerItems.first { it.isSelected }.answer
        fun answer(text: String) = onNode(hasText(text) and isSelectable() and hasAnyAncestor(isDialog()))
        answer(draft.text).performClick().assertIsSelected()
        answer(committed.text).assertIsNotSelected()
        events.assertEmpty()
        runOnIdle {
            content.value = original.copy(isEnded = true, answerItems = original.answerItems.map { it.copy(isEnabled = false) })
        }
        answer(draft.text).assertIsNotSelected().assertIsNotEnabled()
        answer(committed.text).assertIsSelected().assertIsNotEnabled()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        onNode(hasText(context.getString(PollR.string.screen_poll_change_vote)) and hasAnyAncestor(isDialog())).assertDoesNotExist()
        events.assertEmpty()
    }

    @Test
    fun `answering a poll with first answer should emit a PollAnswerSelected event`() {
        testAnswer(answerIndex = 0)
    }

    @Test
    fun `answering a poll with second answer should emit a PollAnswerSelected event`() {
        testAnswer(answerIndex = 1)
    }

    private fun testAnswer(answerIndex: Int) = runAndroidComposeUiTest<ComponentActivity> {
        val eventsRecorder = EventsRecorder<TimelineEvent.TimelineItemPollEvent>()
        val content = aTimelineItemPollContent()
        setContent {
            TimelineItemPollView(
                content = content,
                eventSink = eventsRecorder
            )
        }
        clickOn(PollR.string.screen_poll_voted)
        val answer = content.answerItems[answerIndex].answer
        onNode(
            matcher = hasText(answer.text) and hasAnyAncestor(isDialog()),
            useUnmergedTree = true,
        ).performClick()
        eventsRecorder.assertEmpty()
        clickOn(PollR.string.screen_poll_change_vote)
        eventsRecorder.assertSize(1)
        eventsRecorder.assertTrue(0) { it is TimelineEvent.SelectPollAnswer && it.pollStartId == content.eventId && it.answerId == answer.id }
    }

    @Test
    fun `editing a poll should emit a PollEditClicked event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent.TimelineItemPollEvent>()
        val content = aTimelineItemPollContent(
            isMine = true,
            isEditable = true,
        )
        setContent {
            TimelineItemPollView(
                content = content,
                eventSink = eventsRecorder
            )
        }
        clickOn(PollR.string.screen_poll_voted)
        clickOn(CommonStrings.action_edit_poll)
        eventsRecorder.assertSingle(TimelineEvent.EditPoll(content.eventId!!))
    }

    @Test
    fun `closing a poll should emit a PollEndClicked event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent.TimelineItemPollEvent>()
        val content = aTimelineItemPollContent(
            isMine = true,
        )
        setContent {
            TimelineItemPollView(
                content = content,
                eventSink = eventsRecorder
            )
        }
        clickOn(PollR.string.screen_poll_voted)
        clickOn(CommonStrings.action_end_poll)
        // A confirmation dialog should be shown
        eventsRecorder.assertEmpty()
        pressTag(TestTags.dialogPositive.value)
        eventsRecorder.assertSize(1)
        eventsRecorder.assertTrue(0) { it is TimelineEvent.EndPoll && it.pollStartId == content.eventId }
    }

    @Test
    fun `vote submission disables duplicate taps and failure retains the selected option`() = runAndroidComposeUiTest<ComponentActivity> {
        val content = aTimelineItemPollContent()
        val submissions = mutableListOf<TimelineEvent.SelectPollAnswer>()
        setContent {
            TimelineItemPollView(content, eventSink = { if (it is TimelineEvent.SelectPollAnswer) submissions.add(it) })
        }
        clickOn(PollR.string.screen_poll_voted)
        onNode(hasText(content.answerItems[0].answer.text) and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        clickOn(PollR.string.screen_poll_change_vote)
        val text = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(PollR.string.screen_poll_change_vote)
        onNode(hasText(text) and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        com.google.common.truth.Truth.assertThat(submissions).hasSize(1)
        runOnIdle { submissions.single().onComplete(Result.failure(IllegalStateException("offline"))) }
        onNode(hasText(text) and hasAnyAncestor(isDialog())).assertIsEnabled()
        clickOn(PollR.string.screen_poll_change_vote)
        com.google.common.truth.Truth.assertThat(submissions.map { it.answerId })
            .containsExactly(content.answerItems[0].answer.id, content.answerItems[0].answer.id)
    }

    @Test
    fun `accepted vote waits for authoritative projection without permitting duplicate submission`() = runAndroidComposeUiTest<ComponentActivity> {
        val content = aTimelineItemPollContent()
        var submitted: TimelineEvent.SelectPollAnswer? = null
        setContent { TimelineItemPollView(content, eventSink = { if (it is TimelineEvent.SelectPollAnswer) submitted = it }) }
        clickOn(PollR.string.screen_poll_voted)
        onNode(hasText(content.answerItems[0].answer.text) and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        clickOn(PollR.string.screen_poll_change_vote)
        runOnIdle { submitted!!.onComplete(Result.success(Unit)) }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        onNode(hasText(context.getString(PollR.string.screen_poll_pending)) and hasAnyAncestor(isDialog())).assertExists()
        onNode(hasText(context.getString(PollR.string.screen_poll_change_vote)) and hasAnyAncestor(isDialog())).assertIsNotEnabled()
    }
}
