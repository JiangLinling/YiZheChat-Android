/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.home.impl.roomlist

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.home.impl.HomeNavigationBarItem
import io.element.android.features.home.impl.HomeView
import io.element.android.features.home.impl.R
import io.element.android.features.home.impl.aHomeState
import io.element.android.features.home.impl.components.RoomListMenuAction
import io.element.android.features.home.impl.filters.RoomListFilter
import io.element.android.features.home.impl.filters.aRoomListFiltersState
import io.element.android.features.home.impl.filters.selection.FilterSelectionState
import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.features.home.impl.model.aRoomListRoomSummary
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.EnsureNeverCalled
import io.element.android.tests.testutils.EnsureNeverCalledWithParam
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.ensureCalledOnce
import io.element.android.tests.testutils.ensureCalledOnceWithParam
import io.element.android.tests.testutils.pressBackKey
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class RoomListViewTest {
    @Test
    fun `room list can scroll to reveal its last room`() = runAndroidComposeUiTest {
        setRoomListView(
            state = aRoomListState(
                contentState = aRoomsContentState(
                    summaries = generateRoomListRoomSummaryList(numberOfRooms = 30),
                ),
            ),
        )

        onNodeWithText("Room#29").assertDoesNotExist()
        repeat(12) {
            onNode(hasScrollAction()).performTouchInput { swipeUp() }
        }
        onNodeWithText("Room#29").assertExists()
    }

    @Test
    fun `contacts list can scroll to reveal its last friend`() = runAndroidComposeUiTest {
        val friends = List(30) { index ->
            val displayName = "Friend#${index.toString().padStart(2, '0')}"
            aRoomListRoomSummary(
                id = "!friend$index:domain",
                name = displayName,
                isDm = true,
                heroes = listOf(
                    AvatarData(
                        id = "@friend$index:domain",
                        name = displayName,
                        size = AvatarSize.RoomListItem,
                    )
                ),
            )
        }.toImmutableList()
        setRoomListView(
            state = aRoomListState(
                contentState = aRoomsContentState(summaries = emptyList<RoomListRoomSummary>().toImmutableList()),
            ).copy(contactsSummaries = AsyncData.Success(friends)),
            homeNavigationBarItem = HomeNavigationBarItem.Contacts,
        )

        // Directory computation runs on Dispatchers.Default; wait for its real semantic projection.
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Friend#00").fetchSemanticsNodes().size == 1
        }
        onNodeWithText("Friend#00").assertExists()
        onNodeWithText("Friend#29").assertDoesNotExist()
        repeat(12) {
            onNode(hasScrollAction()).performTouchInput { swipeUp() }
        }
        onNodeWithText("Friend#29").assertExists()
    }

    @Test
    fun `pressing back on the chats root moves the app to the background`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setRoomListView(
                state = aRoomListState(),
                onMoveToBackground = callback,
            )
            pressBackKey()
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `displaying the view reports preview rooms and visible range`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        setRoomListView(
            state = aRoomListState(
                contentState = aRoomsContentState(securityBannerState = SecurityBannerState.RecoveryKeyConfirmation),
                unreadSummary = HomeUnreadSummary(pendingGroupInviteCount = 1),
                eventSink = eventsRecorder,
            )
        )

        eventsRecorder.assertList(
            listOf(
                RoomListEvent.UpdatePreviewRooms(listOf(
                    RoomId("!roomId5:domain"),
                    RoomId("!roomId2:domain"),
                    RoomId("!roomId3:domain"),
                    RoomId("!roomId4:domain"),
                )),
                RoomListEvent.UpdateVisibleRange(0..4),
            )
        )
    }

    @Test
    fun `clicking on close recovery key banner emits the expected Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        setRoomListView(
            state = aRoomListState(
                contentState = aRoomsContentState(securityBannerState = SecurityBannerState.RecoveryKeyConfirmation),
                eventSink = eventsRecorder,
            )
        )

        // Remove automatic initial events
        eventsRecorder.clear()

        val close = activity!!.getString(CommonStrings.action_close)
        onNodeWithContentDescription(close).performClick()
        eventsRecorder.assertSingle(RoomListEvent.DismissBanner)
    }

    @Test
    fun `clicking on close setup key banner emits the expected Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        setRoomListView(
            state = aRoomListState(
                contentState = aRoomsContentState(securityBannerState = SecurityBannerState.SetUpRecovery),
                eventSink = eventsRecorder,
            )
        )

        // Remove automatic initial events
        eventsRecorder.clear()

        val close = activity!!.getString(CommonStrings.action_close)
        onNodeWithContentDescription(close).performClick()
        eventsRecorder.assertSingle(RoomListEvent.DismissBanner)
    }

    @Test
    fun `clicking on continue recovery key banner invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        ensureCalledOnce { callback ->
            setRoomListView(
                state = aRoomListState(
                    contentState = aRoomsContentState(securityBannerState = SecurityBannerState.RecoveryKeyConfirmation),
                    eventSink = eventsRecorder,
                ),
                onConfirmRecoveryKeyClick = callback,
            )

            // Remove automatic initial events
            eventsRecorder.clear()

            clickOn(CommonStrings.action_continue)

            eventsRecorder.assertEmpty()
        }
    }

    @Test
    fun `clicking on continue setup key banner invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        ensureCalledOnce { callback ->
            setRoomListView(
                state = aRoomListState(
                    contentState = aRoomsContentState(securityBannerState = SecurityBannerState.SetUpRecovery),
                    eventSink = eventsRecorder,
                ),
                onSetUpRecoveryClick = callback,
            )
            // Remove automatic initial events
            eventsRecorder.clear()
            clickOn(R.string.banner_set_up_recovery_submit)
            eventsRecorder.assertEmpty()
        }
    }

    @Test
    fun `clicking on start chat when the session has no room invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>(expectEvents = false)
        ensureCalledOnce { callback ->
            setRoomListView(
                state = aRoomListState(
                    eventSink = eventsRecorder,
                    contentState = anEmptyContentState(),
                ),
                onCreateRoomClick = callback,
            )
            clickOn(CommonStrings.action_start_chat)
        }
    }

    @Test
    fun `clicking on a room invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        val state = aRoomListState(
            eventSink = eventsRecorder,
        )
        val room0 = state.contentAsRooms().summaries.first {
            it.displayType == RoomSummaryDisplayType.ROOM
        }
        ensureCalledOnceWithParam(room0.roomId) { callback ->
            setRoomListView(
                state = state,
                onRoomClick = callback,
            )

            // Remove automatic initial events
            eventsRecorder.clear()

            onNodeWithText(room0.latestEvent.content().toString()).performClick()
        }

        eventsRecorder.assertEmpty()
    }

    @Test
    fun `clicking on a room twice invokes the expected callback only once`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        val state = aRoomListState(
            eventSink = eventsRecorder,
        )
        val room0 = state.contentAsRooms().summaries.first {
            it.displayType == RoomSummaryDisplayType.ROOM
        }
        ensureCalledOnceWithParam(room0.roomId) { callback ->
            setRoomListView(
                state = state,
                onRoomClick = callback,
            )
            // Remove automatic initial events
            eventsRecorder.clear()
            onNodeWithText(room0.latestEvent.content().toString())
                .performClick()
                .performClick()
        }
        eventsRecorder.assertEmpty()
    }

    @Test
    fun `long clicking on a room emits the expected Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        val state = aRoomListState(
            eventSink = eventsRecorder,
        )
        val room0 = state.contentAsRooms().summaries.first {
            it.displayType == RoomSummaryDisplayType.ROOM
        }
        setRoomListView(
            state = state,
        )
        // Remove automatic initial events
        eventsRecorder.clear()

        onNodeWithText(room0.latestEvent.content().toString()).performTouchInput { longClick() }
        eventsRecorder.assertSingle(RoomListEvent.ShowContextMenu(room0, displayName = room0.name))
    }

    @Test
    fun `room settings stay hidden from the compact room list menu`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        val state = aRoomListState(
            contextMenu = aContextMenuShown(),
            eventSink = eventsRecorder,
        )
        setRoomListView(state = state)
        eventsRecorder.clear()
        onNodeWithText(activity!!.getString(CommonStrings.common_settings)).assertDoesNotExist()
    }

    @Test
    fun `clicking on accept and decline invite emits the expected Events`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        val state = aRoomListState(
            eventSink = eventsRecorder,
            filtersState = aRoomListFiltersState(
                filterSelectionStates = listOf(FilterSelectionState(RoomListFilter.Invites, isSelected = true))
            ),
        )
        val invitedRoom = state.contentAsRooms().summaries.first {
            it.displayType == RoomSummaryDisplayType.INVITE
        }
        setRoomListView(state = state.copy(notifications = kotlinx.collections.immutable.persistentListOf(
            io.element.android.features.invite.api.InviteNotification(invitedRoom.roomId,
                io.element.android.features.invite.api.InviteNotification.Kind.GROUP, null, 100)
        )))
        eventsRecorder.clear()
        clickOn(R.string.screen_notifications_view_invite)
        eventsRecorder.clear()
        clickOn(R.string.screen_notifications_join)
        clickOn(R.string.screen_notifications_decline)
        eventsRecorder.assertList(listOf(
            RoomListEvent.MarkInvitesAsSeen(setOf(invitedRoom.roomId)),
            RoomListEvent.AcceptInvite(invitedRoom),
            RoomListEvent.DeclineInvite(invitedRoom, blockUser = false),
        ))
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.setRoomListView(
    state: RoomListState,
    homeNavigationBarItem: HomeNavigationBarItem = HomeNavigationBarItem.Chats,
    onRoomClick: (RoomId) -> Unit = EnsureNeverCalledWithParam(),
    onSetUpRecoveryClick: () -> Unit = EnsureNeverCalled(),
    onConfirmRecoveryKeyClick: () -> Unit = EnsureNeverCalled(),
    onAddFriendClick: () -> Unit = EnsureNeverCalled(),
    onCreateRoomClick: () -> Unit = EnsureNeverCalled(),
    onCreateSpaceClick: () -> Unit = EnsureNeverCalled(),
    onRoomSettingsClick: (RoomId) -> Unit = EnsureNeverCalledWithParam(),
    onMenuActionClick: (RoomListMenuAction) -> Unit = EnsureNeverCalledWithParam(),
    onReportRoomClick: (RoomId) -> Unit = EnsureNeverCalledWithParam(),
    onDeclineInviteAndBlockUser: (RoomListRoomSummary) -> Unit = EnsureNeverCalledWithParam(),
    onMoveToBackground: () -> Unit = EnsureNeverCalled(),
) {
    setSafeContent {
        HomeView(
            homeState = aHomeState(
                roomListState = state,
                currentHomeNavigationBarItem = homeNavigationBarItem,
            ),
            onRoomClick = onRoomClick,
            onSetUpRecoveryClick = onSetUpRecoveryClick,
            onConfirmRecoveryKeyClick = onConfirmRecoveryKeyClick,
            onAddFriendClick = onAddFriendClick,
            onCreateRoomClick = onCreateRoomClick,
            onCreateSpaceClick = onCreateSpaceClick,
            onRoomSettingsClick = onRoomSettingsClick,
            onMenuActionClick = onMenuActionClick,
            onDeclineInviteAndBlockUser = onDeclineInviteAndBlockUser,
            onReportRoomClick = onReportRoomClick,
            onMoveToBackground = onMoveToBackground,
            acceptDeclineInviteView = {},
            leaveRoomView = {},
        )
    }
}
