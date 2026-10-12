/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineRoomInfo
import io.element.android.features.messages.impl.timeline.factories.event.toRtcNotificationState
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRtcNotificationContent
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
class TimelineItemCallNotifyActorViewTest {
    private val caller = UserId("@caller:example.test")
    private val receiver = UserId("@receiver:example.test")

    @Test fun `outgoing refusal uses neutral English detail with private invitation name`() = checkCard(
        session = caller, actor = receiver, expected = "Declined", forbidden = "Private caller declined",
    )

    @Test @Config(qualifiers = "zh-w411dp-h891dp")
    fun `outgoing refusal uses neutral Chinese detail`() = checkCard(
        session = caller, actor = receiver, expected = "已拒绝", forbidden = "Private caller已拒绝",
    )

    @Test fun `incoming self refusal says you in English`() = checkCard(
        session = receiver, actor = receiver, expected = "You declined", forbidden = "Private caller declined",
    )

    @Test @Config(qualifiers = "zh-w411dp-h891dp")
    fun `incoming self refusal says you in Chinese`() = checkCard(
        session = receiver, actor = receiver, expected = "你已拒绝", forbidden = "Private caller已拒绝",
    )

    @Test fun `redacted or unknown actor never falls back to private sender name`() = checkCard(
        session = caller, actor = null, expected = "Declined", forbidden = "Private caller declined",
    )

    @Test fun `legacy outgoing refusal uses the same neutral rendering`() = checkCard(
        session = caller, actor = receiver, legacy = true, expected = "Declined", forbidden = "Private caller declined",
    )

    @Test fun `group refuses without claiming one member refused the whole call`() = checkCard(
        session = caller, actor = receiver, isDm = false, expected = null, forbidden = "Declined",
    )

    @Test fun `other actor cancellation is labelled cancelled rather than declined`() = checkCard(
        session = receiver, actor = caller, status = CallLifecycleState.CANCELLED,
        expected = "Cancelled", forbidden = "Private caller cancelled",
    )

    private fun checkCard(
        session: UserId, actor: UserId?, expected: String?, forbidden: String,
        legacy: Boolean = false, isDm: Boolean = true, status: CallLifecycleState = CallLifecycleState.DECLINED,
    ) = runComposeUiTest {
        val raw = CallNotifyContent(CallIntent.AUDIO, if (legacy) listOfNotNull(actor) else emptyList(),
            lifecycleState = status.takeUnless { legacy }, lifecycleActor = actor.takeUnless { legacy })
        val content = TimelineItemRtcNotificationContent(raw.callIntent, raw.toRtcNotificationState(session))
        val event = aTimelineItemEvent(isMine = session == caller, senderDisplayName = "Private caller", content = content).copy(senderId = caller)
        setContent {
            ElementPreview {
                TimelineItemCallNotifyView(aTimelineRoomInfo(isDm = isDm), event, content,
                    renderReadReceipts = false, isLastOutgoingMessage = false, onClick = {}, onLongClick = {}, onReadReceiptsClick = {})
            }
        }
        expected?.let { onNodeWithText(it).assertIsDisplayed() }
        onNodeWithText(forbidden).assertDoesNotExist()
        onNodeWithText(receiver.value).assertDoesNotExist()
    }
}
