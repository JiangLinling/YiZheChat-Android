/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineRoomInfo
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.protection.aTimelineProtectionState
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1080dp-h2340dp")
class DirectMessageRowLayoutTest {
    private data class Case(
        val mine: Boolean = false,
        val rtl: Boolean = false,
        val decorated: Boolean = false,
        val body: Int = 0,
        val width: Int = 320,
        val density: Float = 1f,
        val fontScale: Float = 1f,
    )

    @Test
    fun `plain message geometry matches the original constraint layout in both directions`() = verifyGeometry(false)

    @Test
    fun `pins reactions large fonts and media geometry match the original constraint layout`() = verifyGeometry(true)

    private fun verifyGeometry(decorated: Boolean) = runAndroidComposeUiTest<ComponentActivity> {
        val state = mutableStateOf(Case(decorated = decorated))
        setSafeContent {
            val c = state.value
            // ConstraintLayout 1.1.1 remembers its initial Density in Measurer2.
            // Give each screen configuration a fresh, identical starting context for both engines.
            key(c.density, c.fontScale) {
                CompositionLocalProvider(
                    LocalDensity provides Density(c.density, c.fontScale),
                    LocalLayoutDirection provides if (c.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    // Each independent layout receives the same full height budget.
                    Box(Modifier.width(c.width.dp)) {
                        OriginalGeometry(c, "old")
                        DirectMessageRowLayout(
                            isMine = c.mine,
                            modifier = Modifier.wrapContentHeight().fillMaxWidth().testTag("new-root"),
                            avatar = { Slot(c, "new", "avatar") },
                            bubble = { Slot(c, "new", "bubble") },
                            deliveryIndicator = { if (c.mine) Slot(c, "new", "delivery") },
                            pinIcon = { if (c.decorated) Slot(c, "new", "pin") },
                            reactions = { if (c.decorated) Slot(c, "new", "reactions", Modifier.padding(start = 22.dp, end = 16.dp)) },
                        )
                    }
                }
            }
        }
        for (mine in listOf(false, true)) for (rtl in listOf(false, true)) for (body in 0..2) {
            val c = Case(
                mine, rtl, decorated, body,
                if (body == 1) 240 else 320,
                if (body == 2) 2.75f else 1f,
                if (body == 1) 1.5f else 1f,
            )
            runOnIdle { state.value = c }
            val oldRoot = onNodeWithTag("old-root", true).fetchSemanticsNode().boundsInRoot
            val newRoot = onNodeWithTag("new-root", true).fetchSemanticsNode().boundsInRoot
            assertWithMessage("$c intended physical width").that(oldRoot.width).isEqualTo(c.width * c.density)
            assertWithMessage("$c row width").that(newRoot.width).isEqualTo(oldRoot.width)
            assertWithMessage("$c row height").that(newRoot.height).isEqualTo(oldRoot.height)
            val slots = listOf("avatar", "bubble") + (if (mine) listOf("delivery") else emptyList()) +
                (if (decorated) listOf("pin", "reactions") else emptyList())
            for (slot in slots) {
                val old = onNodeWithTag("old-$slot", true).fetchSemanticsNode().boundsInRoot
                val new = onNodeWithTag("new-$slot", true).fetchSemanticsNode().boundsInRoot
                assertWithMessage("$c $slot").that(listOf(new.left - newRoot.left, new.top - newRoot.top, new.width, new.height))
                    .isEqualTo(listOf(old.left - oldRoot.left, old.top - oldRoot.top, old.width, old.height))
            }
        }
    }

    /** Frozen geometry from TimelineItemEventRowContent before the solver replacement. */
    @Composable
    private fun OriginalGeometry(c: Case, prefix: String) {
        ConstraintLayout(Modifier.wrapContentHeight().fillMaxWidth().testTag("$prefix-root")) {
            val (avatar, bubble, delivery, pin, reactions) = createRefs()
            Slot(c, prefix, "avatar", Modifier.constrainAs(avatar) {
                top.linkTo(parent.top)
                if (c.mine) end.linkTo(parent.end, 12.dp) else start.linkTo(parent.start, 12.dp)
            })
            Slot(c, prefix, "bubble", Modifier.constrainAs(bubble) {
                top.linkTo(avatar.top)
                if (c.mine) end.linkTo(avatar.start, 8.dp) else start.linkTo(avatar.end, 8.dp)
            })
            if (c.mine) Slot(c, prefix, "delivery", Modifier.constrainAs(delivery) {
                end.linkTo(bubble.start, 2.dp)
                bottom.linkTo(bubble.bottom)
            })
            if (c.decorated) {
                Slot(c, prefix, "pin", Modifier.constrainAs(pin) {
                    top.linkTo(bubble.top)
                    if (c.mine) end.linkTo(bubble.start, 8.dp) else start.linkTo(bubble.end, 8.dp)
                })
                Slot(c, prefix, "reactions", Modifier.constrainAs(reactions) {
                    top.linkTo(bubble.bottom, (-4).dp)
                    if (c.mine) end.linkTo(bubble.end) else start.linkTo(parent.start)
                }.zIndex(1f).padding(start = 22.dp, end = 16.dp))
            }
        }
    }

    @Composable
    private fun Slot(c: Case, prefix: String, name: String, modifier: Modifier = Modifier) {
        val tagged = modifier.testTag("$prefix-$name")
        when (name) {
            "avatar" -> Box(tagged.size(32.dp))
            "delivery" -> Box(tagged.size(44.dp))
            "pin" -> Box(tagged.size(18.dp))
            "reactions" -> Box(tagged.size(128.dp, 32.dp))
            else -> BoxWithConstraints(tagged) {
                Box(Modifier.widthIn(max = maxWidth * MessageEventBubbleDefaults.BUBBLE_WIDTH_RATIO)) {
                    when (c.body) {
                        0 -> Text("Hi", Modifier.padding(8.dp))
                        1 -> Text("A longer message must keep the same available width and line wrapping.", Modifier.padding(8.dp), maxLines = 3)
                        else -> Box(Modifier.size(180.dp, 96.dp))
                    }
                }
            }
        }
    }

    @Test
    fun `message content survives room metadata receipt pin width and direction updates`() = runAndroidComposeUiTest<ComponentActivity> {
        val dm = mutableStateOf(false)
        val pinned = mutableStateOf(false)
        val width = mutableStateOf(320)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val event = mutableStateOf(aTimelineItemEvent(isMine = true, sendState = LocalEventSendState.Sending.Event))
        var mounted = 0
        var disposed = 0
        setSafeContent {
            val snapshot = event.value
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                Box(Modifier.width(width.value.dp)) {
                    TestEventRow(snapshot, dm.value, pinned.value, {}) {
                        DisposableEffect(Unit) { mounted++; onDispose { disposed++ } }
                        Text("Stable media-like content ${snapshot.sentTime}", Modifier.testTag("body"))
                    }
                }
            }
        }
        onNodeWithTag("body", true).assertExists()
        for (step in 0..4) {
            runOnIdle {
                dm.value = step != 4
                pinned.value = step % 2 == 0
                width.value = if (step % 2 == 0) 280 else 320
                direction.value = if (step % 2 == 0) LayoutDirection.Rtl else LayoutDirection.Ltr
                event.value = event.value.copy(
                    sentTime = "QA-$step",
                    localSendState = if (step % 2 == 0) LocalEventSendState.Failed.Unknown("QA") else null,
                )
            }
            onNodeWithTag("body", true).assertExists()
            onNodeWithTag("body", true).assertTextEquals("Stable media-like content QA-$step")
            assertThat(mounted).isEqualTo(1)
            assertThat(disposed).isEqualTo(0)
        }
    }

    @Test
    fun `normal and verified-user failure clicks retain their original event routes`() = runAndroidComposeUiTest<ComponentActivity> {
        val event = mutableStateOf(aTimelineItemEvent(isMine = true, sendState = LocalEventSendState.Failed.Unknown("QA")))
        val actions = mutableListOf<TimelineEvent.TimelineItemEvent>()
        setSafeContent { Box(Modifier.width(320.dp)) { TestEventRow(event.value, true, false, actions::add) { Text("QA") } } }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val failed = context.getString(CommonStrings.common_sending_failed)
        onNodeWithContentDescription(failed).performClick()
        assertThat(actions.last()).isInstanceOf(TimelineEvent.RetrySend::class.java)
        runOnIdle { event.value = event.value.copy(localSendState = LocalEventSendState.Failed.VerifiedUserChangedIdentity(emptyList())) }
        onNodeWithContentDescription(failed).performClick()
        assertThat(actions.last()).isInstanceOf(TimelineEvent.ComputeVerifiedUserSendFailure::class.java)
    }

    @Composable
    private fun TestEventRow(
        event: TimelineItem.Event,
        dm: Boolean,
        pinned: Boolean,
        sink: (TimelineEvent.TimelineItemEvent) -> Unit,
        content: @Composable () -> Unit,
    ) {
        TimelineItemEventRow(
            event = event, timelineMode = Timeline.Mode.Live,
            timelineRoomInfo = aTimelineRoomInfo(isDm = dm, pinnedEventIds = if (pinned) listOfNotNull(event.eventId) else emptyList()),
            timelineProtectionState = aTimelineProtectionState(), renderReadReceipts = true, isLastOutgoingMessage = true,
            displayThreadSummaries = false, onEventClick = {}, onLongClick = {}, onLinkClick = {}, onLinkLongClick = {},
            onUserDataClick = {}, inReplyToClick = {}, onReactionClick = { _, _ -> }, onReactionLongClick = { _, _ -> },
            onMoreReactionsClick = {}, onReadReceiptClick = {}, onSwipeToReply = {}, eventSink = sink,
            eventContentView = { _, _ -> content() },
        )
    }
}
