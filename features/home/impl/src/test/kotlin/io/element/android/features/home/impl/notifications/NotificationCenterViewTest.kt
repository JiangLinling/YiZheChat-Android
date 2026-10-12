/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package io.element.android.features.home.impl.notifications

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.home.impl.R
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.features.home.impl.model.aRoomListRoomSummary
import io.element.android.features.home.impl.roomlist.RoomListEvent
import io.element.android.features.home.impl.roomlist.RoomListState
import io.element.android.features.home.impl.roomlist.aRoomListState
import io.element.android.features.invite.api.InviteNotification
import io.element.android.libraries.architecture.AsyncData
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationCenterViewTest {
    private val group = aRoomListRoomSummary(displayType = RoomSummaryDisplayType.INVITE, isDm = false)
    private val notice = requireNotNull(group.toNotification())
    private fun state(events: EventsRecorder<RoomListEvent>) = aRoomListState(eventSink = events).copy(
        contactsSummaries = AsyncData.Success(persistentListOf(group)), notifications = persistentListOf(notice))
    private fun AndroidComposeUiTest<ComponentActivity>.show(state: RoomListState) {
        setSafeContent { NotificationCenterView(state, "ALL", {}, { error("must not navigate automatically") }, {}, { it.name.orEmpty() }) }
    }
    @Test fun `group join requires viewing invitation details`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomListEvent>()
        show(state(events))
        events.clear()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_join)).assertDoesNotExist()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_view_invite)).performClick()
        events.assertSingle(RoomListEvent.MarkInvitesAsSeen(setOf(group.roomId)))
        events.clear()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_join)).performClick()
        events.assertList(listOf(RoomListEvent.MarkInvitesAsSeen(setOf(group.roomId)), RoomListEvent.AcceptInvite(group)))
    }
    @Test fun `mark all read does not accept or decline`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomListEvent>()
        show(state(events))
        events.clear()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_read_all)).performClick()
        events.assertSingle(RoomListEvent.MarkInvitesAsSeen(setOf(group.roomId)))
        onNodeWithText(activity!!.getString(R.string.screen_notifications_view_invite)).assertExists()
    }
    @Test fun `mark read respects selected type`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomListEvent>()
        val friend = group.copy(roomId = io.element.android.libraries.matrix.api.core.RoomId("!friend:example.test"), isDm = true)
        show(state(events).copy(contactsSummaries = AsyncData.Success(persistentListOf(group, friend)),
            notifications = persistentListOf(notice, requireNotNull(friend.toNotification()))))
        onNodeWithText(activity!!.getString(R.string.screen_notifications_type)).performClick()
        onNode(hasText(activity!!.getString(R.string.screen_notifications_group)) and hasAnyAncestor(isPopup())).performClick()
        events.clear()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_read_all)).performClick()
        events.assertSingle(RoomListEvent.MarkInvitesAsSeen(setOf(group.roomId)))
    }
    @Test fun `opening an accepted group marks it read before navigation`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomListEvent>()
        val room = group.copy(displayType = RoomSummaryDisplayType.ROOM)
        var opened: io.element.android.libraries.matrix.api.core.RoomId? = null
        setSafeContent { NotificationCenterView(state(events).copy(contactsSummaries = AsyncData.Success(persistentListOf(room)),
            notifications = persistentListOf(notice.copy(outcome = InviteNotification.Outcome.ACCEPTED))),
            "ALL", {}, { opened = it }, {}, { it.name.orEmpty() }) }
        events.clear()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_open_group)).performClick()
        events.assertSingle(RoomListEvent.MarkInvitesAsSeen(setOf(group.roomId)))
        com.google.common.truth.Truth.assertThat(opened).isEqualTo(group.roomId)
    }
    @Test fun `arrival while reading older cards offers return to newest without moving reading position`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomListEvent>()
        val rooms = (0..19).map { group.copy(roomId = io.element.android.libraries.matrix.api.core.RoomId("!r$it:example.test"),
            name = "Group $it", displayType = RoomSummaryDisplayType.ROOM) }
        var current by mutableStateOf(state(events).copy(contactsSummaries = AsyncData.Success(rooms.toImmutableList()),
            notifications = rooms.mapIndexed { i, room ->
                notice.copy(roomId = room.roomId, sequence = (20 - i).toLong(), outcome = InviteNotification.Outcome.ACCEPTED, seen = true)
            }.toImmutableList()))
        setSafeContent { NotificationCenterView(current, "ALL", {}, {}, {}, { it.name.orEmpty() }) }
        onNode(hasScrollAction()).performScrollToNode(hasText("Group 8"))
        onNodeWithText("Group 8").assertIsDisplayed()
        runOnIdle { current = current.copy(notifications = (listOf(
            notice.copy(roomId = io.element.android.libraries.matrix.api.core.RoomId("!new:example.test"), sequence = 21, displayName = "Newest")
        ) + current.notifications).toImmutableList()) }
        onNodeWithText("Group 8").assertIsDisplayed()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_new)).performClick()
        onNodeWithText("Newest").assertIsDisplayed()
    }
    @Test fun `long title with large fonts stays separate from group action`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomListEvent>()
        val title = "产品研发与项目进展讨论群的完整名称"
        val room = group.copy(name = title, displayType = RoomSummaryDisplayType.ROOM)
        setSafeContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, 2f)) {
                NotificationCenterView(state(events).copy(contactsSummaries = AsyncData.Success(persistentListOf(room)),
                    notifications = persistentListOf(notice.copy(outcome = InviteNotification.Outcome.ACCEPTED))),
                    "ALL", {}, {}, {}, { it.name.orEmpty() })
            }
        }
        val titleBounds = onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val action = onNodeWithText(activity!!.getString(R.string.screen_notifications_open_group)).assertIsDisplayed()
        com.google.common.truth.Truth.assertThat(action.fetchSemanticsNode().boundsInRoot.left).isGreaterThan(titleBounds.right)
        onNodeWithText(title).performClick()
        onNodeWithText(title).assertIsDisplayed()
    }
    @Test fun `cached history without live invitation cannot be acted on`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomListEvent>()
        show(state(events).copy(contactsSummaries = AsyncData.Success(persistentListOf())))
        onNodeWithText(activity!!.getString(R.string.screen_notifications_join)).assertDoesNotExist()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_decline)).assertDoesNotExist()
    }
    @Test fun `processed records remain visible under handled filter`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomListEvent>()
        show(state(events).copy(notifications = persistentListOf(notice.copy(outcome = InviteNotification.Outcome.DECLINED, seen = true))))
        onNodeWithText(activity!!.getString(R.string.screen_notifications_handled)).performClick()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_declined), substring = true).assertExists()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_join)).assertDoesNotExist()
    }
    @Test fun `loading invitation disables accept and decline`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomListEvent>()
        val friend = group.copy(isDm = true)
        val notice = requireNotNull(friend.toNotification())
        show(state(events).copy(contactsSummaries = AsyncData.Success(persistentListOf(friend)),
            notifications = persistentListOf(notice), acceptDeclineInviteState =
                io.element.android.features.invite.api.acceptdecline.AcceptDeclineInviteState(
                    io.element.android.libraries.architecture.AsyncAction.Loading,
                    io.element.android.libraries.architecture.AsyncAction.Uninitialized, {}, group.roomId)))
        onNodeWithText(activity!!.getString(R.string.screen_notifications_processing)).assertIsNotEnabled()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_decline)).assertIsNotEnabled()
    }
    @Test fun `failure stays in center and can retry without navigation`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomListEvent>()
        val friend = group.copy(isDm = true)
        val notice = requireNotNull(friend.toNotification())
        show(state(events).copy(contactsSummaries = AsyncData.Success(persistentListOf(friend)),
            notifications = persistentListOf(notice), acceptDeclineInviteState =
                io.element.android.features.invite.api.acceptdecline.AcceptDeclineInviteState(
                    io.element.android.libraries.architecture.AsyncAction.Failure(IllegalStateException("blocked")),
                    io.element.android.libraries.architecture.AsyncAction.Uninitialized, {}, group.roomId)))
        onNodeWithText(activity!!.getString(R.string.screen_notifications_failed)).assertExists()
        events.clear()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_agree)).performClick()
        events.assertList(listOf(RoomListEvent.MarkInvitesAsSeen(setOf(friend.roomId)), RoomListEvent.AcceptInvite(friend)))
    }

    @Test fun `type dropdown composes with status filter without changing requests`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomListEvent>()
        val friend = group.copy(roomId = io.element.android.libraries.matrix.api.core.RoomId("!friend:example.test"), isDm = true)
        val processed = requireNotNull(friend.toNotification()).copy(outcome = InviteNotification.Outcome.DECLINED, seen = true)
        show(state(events).copy(contactsSummaries = AsyncData.Success(persistentListOf(group, friend)),
            notifications = persistentListOf(notice, processed)))
        events.clear()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_type)).performClick()
        onNode(hasText(activity!!.getString(R.string.screen_notifications_friend)) and hasAnyAncestor(isPopup())).performClick()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_declined), substring = true).assertExists()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_view_invite)).assertDoesNotExist()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_pending)).performClick()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_empty)).assertExists()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_type_friend)).performClick()
        onNode(hasText(activity!!.getString(R.string.screen_notifications_group)) and hasAnyAncestor(isPopup())).performClick()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_view_invite)).assertExists()
        onNodeWithText(activity!!.getString(R.string.screen_notifications_declined), substring = true).assertDoesNotExist()
        events.assertList(emptyList())
    }

}
