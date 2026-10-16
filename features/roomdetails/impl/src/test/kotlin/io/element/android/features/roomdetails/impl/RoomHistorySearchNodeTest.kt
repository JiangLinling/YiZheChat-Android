/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.roomdetails.impl

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bumble.appyx.core.modality.BuildContext
import com.google.common.truth.Truth.assertThat
import io.element.android.features.userprofile.test.FakeFriendProfileRepository
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import io.element.android.security.dataprotection.api.ProtectedPreviewAccessState
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.services.appnavstate.api.AppForegroundStateService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "h1500dp")
@RunWith(AndroidJUnit4::class)
class RoomHistorySearchNodeTest {
    private val foreground = MutableStateFlow(true)
    private val access = MutableStateFlow(ProtectedPreviewAccessState(true, true, true))
    private val revision = MutableStateFlow(1L)
    private val gate = object : ProtectedPreviewAuthorizationGate {
        override val accessState = access
        override val authorizationRevision = revision
        override fun canAttemptPreview() = access.value.allowsPreviewAttempt
        override fun canDisplayAuthorizedContent(contentProtectionAuthorized: Boolean) = contentProtectionAuthorized && canAttemptPreview()
    }
    private val foregroundService = mockk<AppForegroundStateService> {
        every { isInForeground } returns foreground
    }
    private val items = MutableStateFlow(listOf(event("first", "needle first"), event("second", "needle second")))
    private val selected = mutableListOf<EventId>()
    private var backCount = 0

    private fun node() = RoomHistorySearchNode(
        buildContext = BuildContext.root(savedStateMap = null),
        room = FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = items)),
        friendProfileRepository = FakeFriendProfileRepository(),
        previewAuthorizationGate = gate,
        appForegroundStateService = foregroundService,
        onBack = { backCount++ },
        onResult = { selected += it },
    )

    @Test
    fun `result jump pause and composition disposal preserve query and allow another result`() = runAndroidComposeUiTest<ComponentActivity> {
        val node = node()
        val shown = mutableStateOf(true)
        runOnUiThread { node.onBuilt(); node.updateLifecycleState(Lifecycle.State.RESUMED) }
        setContent { if (shown.value) node.View(Modifier) }
        onNode(hasSetTextAction()).performTextInput("needle")
        onNodeWithText("needle first").performClick()
        runOnIdle { node.updateLifecycleState(Lifecycle.State.CREATED); shown.value = false }
        waitForIdle()
        runOnIdle { node.updateLifecycleState(Lifecycle.State.RESUMED); shown.value = true }
        onNode(hasSetTextAction()).assertTextEquals("needle")
        onNodeWithText("needle second").performClick()
        assertThat(selected).containsExactly(EventId("\$first"), EventId("\$second")).inOrder()
        runOnIdle { node.updateLifecycleState(Lifecycle.State.DESTROYED) }
    }

    @Test
    fun `return rebuilds results from current timeline without replaying previous body`() = runAndroidComposeUiTest<ComponentActivity> {
        val node = node()
        val shown = mutableStateOf(true)
        runOnUiThread { node.onBuilt(); node.updateLifecycleState(Lifecycle.State.RESUMED) }
        setContent { if (shown.value) node.View(Modifier) }
        onNode(hasSetTextAction()).performTextInput("needle")
        onNodeWithText("needle first").performClick()
        runOnIdle { shown.value = false; node.updateLifecycleState(Lifecycle.State.CREATED) }
        waitForIdle()
        runOnIdle {
            items.value = listOf(event("third", "needle current"))
            node.updateLifecycleState(Lifecycle.State.RESUMED)
            shown.value = true
        }
        onNode(hasSetTextAction()).assertTextEquals("needle")
        onNodeWithText("needle first").assertDoesNotExist()
        onNodeWithText("needle second").assertDoesNotExist()
        onNodeWithText("needle current").performClick()
        assertThat(selected.last()).isEqualTo(EventId("\$third"))
        runOnIdle { node.updateLifecycleState(Lifecycle.State.DESTROYED) }
    }

    @Test
    fun `explicit clear and toolbar exit clear query`() = runAndroidComposeUiTest<ComponentActivity> {
        val node = node()
        runOnUiThread { node.onBuilt(); node.updateLifecycleState(Lifecycle.State.RESUMED) }
        setContent { node.View(Modifier) }
        onNode(hasSetTextAction()).performTextInput("needle")
        onNodeWithContentDescription("Clear search").performClick()
        onNode(hasSetTextAction()).assertEmptyInput()
        onNodeWithText("needle first").assertDoesNotExist()
        onNode(hasSetTextAction()).performTextInput("needle")
        onNodeWithContentDescription("Back").performClick()
        onNode(hasSetTextAction()).assertEmptyInput()
        assertThat(backCount).isEqualTo(1)
        runOnIdle { node.updateLifecycleState(Lifecycle.State.DESTROYED) }
    }

    @Test
    fun `destroyed node clears and new search starts empty`() = runAndroidComposeUiTest<ComponentActivity> {
        val current = mutableStateOf(node())
        runOnUiThread { current.value.onBuilt(); current.value.updateLifecycleState(Lifecycle.State.RESUMED) }
        setContent { current.value.View(Modifier) }
        onNode(hasSetTextAction()).performTextInput("needle")
        runOnIdle { current.value.updateLifecycleState(Lifecycle.State.DESTROYED) }
        onNode(hasSetTextAction()).assertEmptyInput()
        runOnIdle { current.value = node().also { it.onBuilt(); it.updateLifecycleState(Lifecycle.State.RESUMED) } }
        onNode(hasSetTextAction()).assertEmptyInput()
        runOnIdle { current.value.updateLifecycleState(Lifecycle.State.DESTROYED) }
    }

    @Test
    fun `real background clears query even while search composition is absent`() = runAndroidComposeUiTest<ComponentActivity> {
        val node = node()
        val shown = mutableStateOf(true)
        runOnUiThread { node.onBuilt(); node.updateLifecycleState(Lifecycle.State.RESUMED) }
        setContent { if (shown.value) node.View(Modifier) }
        onNode(hasSetTextAction()).performTextInput("needle")
        runOnIdle { shown.value = false; node.updateLifecycleState(Lifecycle.State.CREATED) }
        waitForIdle()
        runOnIdle { foreground.value = false }
        waitForIdle()
        runOnIdle { foreground.value = true; shown.value = true; node.updateLifecycleState(Lifecycle.State.RESUMED) }
        onNode(hasSetTextAction()).assertEmptyInput()
        onNodeWithText("needle first").assertDoesNotExist()
        runOnIdle { node.updateLifecycleState(Lifecycle.State.DESTROYED) }
    }

    @Test
    fun `platform lock app lock and trust loss each clear offscreen query`() = runAndroidComposeUiTest<ComponentActivity> {
        val node = node()
        val shown = mutableStateOf(true)
        runOnUiThread { node.onBuilt(); node.updateLifecycleState(Lifecycle.State.RESUMED) }
        setContent { if (shown.value) node.View(Modifier) }
        for (denied in listOf(
            ProtectedPreviewAccessState(false, true, true),
            ProtectedPreviewAccessState(true, false, true),
            ProtectedPreviewAccessState(true, true, false),
        )) {
            onNode(hasSetTextAction()).performTextInput("needle")
            runOnIdle { shown.value = false; node.updateLifecycleState(Lifecycle.State.CREATED) }
            waitForIdle()
            runOnIdle { access.value = denied }
            waitForIdle()
            runOnIdle { access.value = ProtectedPreviewAccessState(true, true, true); shown.value = true; node.updateLifecycleState(Lifecycle.State.RESUMED) }
            onNode(hasSetTextAction()).assertEmptyInput()
            onNodeWithText("needle first").assertDoesNotExist()
        }
        runOnIdle { node.updateLifecycleState(Lifecycle.State.DESTROYED) }
    }

    @Test
    fun `authorization generation change clears query offscreen without restoring it on return`() = runAndroidComposeUiTest<ComponentActivity> {
        val node = node()
        val shown = mutableStateOf(true)
        runOnUiThread { node.onBuilt(); node.updateLifecycleState(Lifecycle.State.RESUMED) }
        setContent { if (shown.value) node.View(Modifier) }
        onNode(hasSetTextAction()).performTextInput("needle")
        runOnIdle { shown.value = false; node.updateLifecycleState(Lifecycle.State.CREATED) }
        waitForIdle()
        runOnIdle { revision.value++ }
        waitForIdle()
        runOnIdle { shown.value = true; node.updateLifecycleState(Lifecycle.State.RESUMED) }
        onNode(hasSetTextAction()).assertEmptyInput()
        onNode(hasSetTextAction()).performTextInput("needle")
        onNodeWithText("needle first").assertExists()
        runOnIdle { node.updateLifecycleState(Lifecycle.State.DESTROYED) }
    }

    @Test
    fun `return never includes expired revoked or burn after read content`() = runAndroidComposeUiTest<ComponentActivity> {
        val node = node()
        val shown = mutableStateOf(true)
        runOnUiThread { node.onBuilt(); node.updateLifecycleState(Lifecycle.State.RESUMED) }
        setContent { if (shown.value) node.View(Modifier) }
        onNode(hasSetTextAction()).performTextInput("needle")
        runOnIdle { shown.value = false; node.updateLifecycleState(Lifecycle.State.CREATED) }
        waitForIdle()
        runOnIdle {
            items.value = listOf(
                event("first", "needle expired", aMessageContent("needle expired").copy(protectedMessageStatus = ProtectedMessageStatus.EXPIRED)),
                event("second", "needle revoked", aMessageContent("needle revoked").copy(protectedMessageStatus = ProtectedMessageStatus.REVOKED)),
                event("burn", "needle burn", aMessageContent("needle burn").copy(isContentProtectionAuthorized = true, isBurnAfterRead = true)),
                event("ok", "needle allowed"),
            )
            shown.value = true
            node.updateLifecycleState(Lifecycle.State.RESUMED)
        }
        onNode(hasSetTextAction()).assertTextEquals("needle")
        for (body in listOf("needle first", "needle second", "needle expired", "needle revoked", "needle burn")) {
            onNodeWithText(body).assertDoesNotExist()
        }
        onNodeWithText("needle allowed").performClick()
        assertThat(selected).containsExactly(EventId("\$ok"))
        runOnIdle { node.updateLifecycleState(Lifecycle.State.DESTROYED) }
    }

    private fun event(id: String, body: String, content: MessageContent = aMessageContent(body).copy(isContentProtectionAuthorized = true)) =
        MatrixTimelineItem.Event(UniqueId(id), anEventTimelineItem(eventId = EventId("\$$id"), content = content))

    private fun SemanticsNodeInteraction.assertEmptyInput() =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
}
