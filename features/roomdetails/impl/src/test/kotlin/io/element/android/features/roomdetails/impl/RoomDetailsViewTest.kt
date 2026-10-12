/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.roomdetails.impl

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.roomdetails.impl.members.aRoomMember
import io.element.android.features.userprofile.shared.aUserProfileState
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.EnsureNeverCalled
import io.element.android.tests.testutils.EnsureNeverCalledWithParam
import io.element.android.tests.testutils.EnsureNeverCalledWithTwoParams
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.ensureCalledOnce
import io.element.android.tests.testutils.ensureCalledOnceWithParam
import io.element.android.tests.testutils.pressBack
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "h1500dp")
@RunWith(AndroidJUnit4::class)
class RoomDetailsViewTest {
    @Test
    fun `click on back invokes expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setRoomDetailView(
                goBack = callback,
            )
            pressBack()
        }
    }

    @Test
    fun `click on share invokes expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setRoomDetailView(
                onShareRoom = callback,
            )
            clickOn(R.string.screen_room_details_share_group)
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `click on room members invokes expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setRoomDetailView(
                openRoomMemberList = callback,
            )
            clickOn(R.string.screen_room_details_group_members)
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `click on polls invokes expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setRoomDetailView(
                openPollHistory = callback,
            )
            clickOn(R.string.screen_polls_history_title)
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `click on media gallery invokes expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setRoomDetailView(
                openMediaGallery = callback,
            )
            clickOn(R.string.screen_room_details_chat_media)
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `click on invite invokes expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setRoomDetailView(
                state = aRoomDetailsState(
                    eventSink = EventsRecorder(expectEvents = false),
                    canInvite = true,
                ),
                invitePeople = callback,
            )
            clickOn(R.string.screen_room_details_add_members)
        }
    }

    @Test
    fun `direct chat settings hides duplicate actions and group management`() = runAndroidComposeUiTest {
        setRoomDetailView(
            state = aRoomDetailsState(
                eventSink = EventsRecorder(expectEvents = false),
                canInvite = true,
                roomType = RoomDetailsType.Dm(aRoomMember(UserId("@other:local.org"))),
            ),
        )
        listOf(
            CommonStrings.action_call,
            CommonStrings.common_video,
            CommonStrings.action_invite,
            R.string.screen_roomlist_mark_as_unread,
            R.string.screen_room_details_message_notifications,
            R.string.screen_room_details_star_conversation,
        ).forEach { resId -> onAllNodesWithText(activity!!.getString(resId)).assertCountEquals(0) }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `click on pinned messages invokes expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setRoomDetailView(
                state = aRoomDetailsState(
                    eventSink = EventsRecorder(expectEvents = false),
                    canInvite = true,
                ),
                onPinnedMessagesClick = callback,
            )
            clickOn(R.string.screen_room_details_chat_pinned_messages)
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `click on security and privacy invokes expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setRoomDetailView(
                state = aRoomDetailsState(
                    eventSink = EventsRecorder(expectEvents = false),
                    canShowSecurityAndPrivacy = true,
                ),
                onSecurityAndPrivacyClick = callback,
            )
            clickOn(R.string.screen_room_details_chat_security)
            clickOn(R.string.screen_room_details_security_and_privacy_title)
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `click on add topic emit expected event`() = runAndroidComposeUiTest {
        ensureCalledOnceWithParam<RoomDetailsAction>(RoomDetailsAction.AddTopic) { callback ->
            setRoomDetailView(
                state = aRoomDetailsState(
                    eventSink = EventsRecorder(expectEvents = false),
                    roomTopic = RoomTopicState.CanAddTopic,
                ),
                onActionClick = callback,
            )
            clickOn(R.string.screen_room_details_add_topic_title)
        }
    }

    @Test
    fun `click on editable group header opens group information`() = runAndroidComposeUiTest {
        ensureCalledOnceWithParam<RoomDetailsAction>(RoomDetailsAction.Edit) { callback ->
            setRoomDetailView(
                state = aRoomDetailsState(
                    eventSink = EventsRecorder(expectEvents = false),
                    canEdit = true,
                ),
                onActionClick = callback,
            )
            onNodeWithText(aRoomDetailsState().roomName).performClick()
        }
    }

    @Test
    fun `read only group header does not open avatar preview`() = runAndroidComposeUiTest {
        setRoomDetailView(state = aRoomDetailsState(eventSink = EventsRecorder(expectEvents = false)))
        onNodeWithText(aRoomDetailsState().roomName).assertHasNoClickAction()
    }

    @Test
    fun `click on contact header opens profile even without an avatar`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomDetailsEvent>(expectEvents = false)
        val state = aRoomDetailsState(
            roomType = RoomDetailsType.Dm(aDmRoomMember(userId = A_USER_ID, avatarUrl = null)),
            roomName = "Daniel",
            eventSink = eventsRecorder,
        )
        ensureCalledOnceWithParam(A_USER_ID) { callback ->
            setRoomDetailView(state = state, onProfileClick = callback)
            clickOn(R.string.screen_room_details_view_profile)
        }
    }

    @Test
    fun `click on mute emit expected event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomDetailsEvent>()
        val state = aRoomDetailsState(
            eventSink = eventsRecorder,
            roomNotificationSettings = aRoomNotificationSettings(mode = RoomNotificationMode.ALL_MESSAGES),
        )
        setRoomDetailView(
            state = state,
        )
        clickOn(R.string.screen_room_details_do_not_disturb)
        eventsRecorder.assertSingle(RoomDetailsEvent.MuteNotification)
    }

    @Test
    fun `click on unmute emit expected event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomDetailsEvent>()
        val state = aRoomDetailsState(
            eventSink = eventsRecorder,
            roomNotificationSettings = aRoomNotificationSettings(mode = RoomNotificationMode.MUTE),
        )
        setRoomDetailView(
            state = state,
        )
        clickOn(R.string.screen_room_details_do_not_disturb)
        eventsRecorder.assertSingle(RoomDetailsEvent.UnmuteNotification)
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `click on favorite emit expected Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomDetailsEvent>()
        setRoomDetailView(
            state = aRoomDetailsState(
                eventSink = eventsRecorder,
            ),
        )
        clickOn(R.string.screen_room_details_pin_chat)
        eventsRecorder.assertSingle(RoomDetailsEvent.SetFavorite(true))
    }

    @Config(qualifiers = "h1500dp")
    @Test
    fun `click on leave emit expected Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomDetailsEvent>()
        setRoomDetailView(
            state = aRoomDetailsState(
                eventSink = eventsRecorder,
            ),
        )
        clickOn(R.string.screen_room_details_leave_group)
        eventsRecorder.assertSingle(RoomDetailsEvent.LeaveRoom(needsConfirmation = true))
    }

    @Config(qualifiers = "h1500dp")
    @Test
    fun `click on report room  invokes expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setRoomDetailView(
                state = aRoomDetailsState(
                    eventSink = EventsRecorder(expectEvents = false),
                ),
                onReportRoomClick = callback,
            )
            clickOn(CommonStrings.action_report_room)
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `click on knock requests invokes expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setRoomDetailView(
                state = aRoomDetailsState(
                    eventSink = EventsRecorder(expectEvents = false),
                    canShowKnockRequests = true,
                ),
                onKnockRequestsClick = callback,
            )
            clickOn(R.string.screen_room_details_requests_to_join_title)
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `click on profile invokes the expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnceWithParam(A_USER_ID) { callback ->
            setRoomDetailView(
                state = aRoomDetailsState(
                    eventSink = EventsRecorder(expectEvents = false),
                    roomType = RoomDetailsType.Dm(aDmRoomMember(userId = A_USER_ID)),
                    dmOtherMemberDetailsState = aUserProfileState(userId = A_USER_ID),
                ),
                onProfileClick = callback,
            )
            clickOn(R.string.screen_room_details_view_profile)
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `invite is hidden for direct conversations`() = runAndroidComposeUiTest {
        setRoomDetailView(
            state = aRoomDetailsState(
                eventSink = EventsRecorder(expectEvents = false),
                roomType = RoomDetailsType.Dm(
                    aDmRoomMember(userId = UserId("@other:local.org")),
                ),
                dmOtherMemberDetailsState = aUserProfileState(userId = A_USER_ID),
                canInvite = true,
            ),
        )
        onAllNodesWithText(activity!!.getString(CommonStrings.action_invite)).assertCountEquals(0)
    }

    @Test
    fun `direct chat do not disturb emits mute`() = runAndroidComposeUiTest {
        val events = EventsRecorder<RoomDetailsEvent>()
        setRoomDetailView(state = aRoomDetailsState(
            roomType = RoomDetailsType.Dm(aDmRoomMember()),
            roomNotificationSettings = aRoomNotificationSettings(mode = RoomNotificationMode.ALL_MESSAGES),
            eventSink = events,
        ))
        clickOn(R.string.screen_room_details_do_not_disturb)
        events.assertSingle(RoomDetailsEvent.MuteNotification)
    }

    @Test
    fun `direct chat do not disturb emits unmute`() = runAndroidComposeUiTest {
        val events = EventsRecorder<RoomDetailsEvent>()
        setRoomDetailView(state = aRoomDetailsState(
            roomType = RoomDetailsType.Dm(aDmRoomMember()),
            roomNotificationSettings = aRoomNotificationSettings(mode = RoomNotificationMode.MUTE),
            eventSink = events,
        ))
        clickOn(R.string.screen_room_details_do_not_disturb)
        events.assertSingle(RoomDetailsEvent.UnmuteNotification)
    }

    @Test
    fun `direct chat pin uses authoritative favorite setting`() = runAndroidComposeUiTest {
        val events = EventsRecorder<RoomDetailsEvent>()
        setRoomDetailView(state = aRoomDetailsState(
            roomType = RoomDetailsType.Dm(aDmRoomMember()),
            eventSink = events,
        ))
        clickOn(R.string.screen_room_details_pin_chat)
        events.assertSingle(RoomDetailsEvent.SetFavorite(true))
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.setRoomDetailView(
    state: RoomDetailsState = aRoomDetailsState(
        eventSink = EventsRecorder(expectEvents = false),
    ),
    goBack: () -> Unit = EnsureNeverCalled(),
    onActionClick: (RoomDetailsAction) -> Unit = EnsureNeverCalledWithParam(),
    onShareRoom: () -> Unit = EnsureNeverCalled(),
    openRoomMemberList: () -> Unit = EnsureNeverCalled(),
    openRoomNotificationSettings: () -> Unit = EnsureNeverCalled(),
    invitePeople: () -> Unit = EnsureNeverCalled(),
    openAvatarPreview: (name: String, url: String) -> Unit = EnsureNeverCalledWithTwoParams(),
    openPollHistory: () -> Unit = EnsureNeverCalled(),
    openMediaGallery: () -> Unit = EnsureNeverCalled(),
    openAdminSettings: () -> Unit = EnsureNeverCalled(),
    onJoinCallClick: (CallIntent) -> Unit = EnsureNeverCalledWithParam(),
    onPinnedMessagesClick: () -> Unit = EnsureNeverCalled(),
    onFindChatContentClick: () -> Unit = EnsureNeverCalled(),
    onKnockRequestsClick: () -> Unit = EnsureNeverCalled(),
    onSecurityAndPrivacyClick: () -> Unit = EnsureNeverCalled(),
    onProfileClick: (UserId) -> Unit = EnsureNeverCalledWithParam(),
    onReportRoomClick: () -> Unit = EnsureNeverCalled(),
) {
    setContent {
        RoomDetailsView(
            state = state,
            goBack = goBack,
            onActionClick = onActionClick,
            onShareRoom = onShareRoom,
            openRoomMemberList = openRoomMemberList,
            openRoomNotificationSettings = openRoomNotificationSettings,
            invitePeople = invitePeople,
            openAvatarPreview = openAvatarPreview,
            openPollHistory = openPollHistory,
            openMediaGallery = openMediaGallery,
            openAdminSettings = openAdminSettings,
            onJoinCallClick = onJoinCallClick,
            onPinnedMessagesClick = onPinnedMessagesClick,
            onFindChatContentClick = onFindChatContentClick,
            onKnockRequestsClick = onKnockRequestsClick,
            onSecurityAndPrivacyClick = onSecurityAndPrivacyClick,
            onProfileClick = onProfileClick,
            onReportRoomClick = onReportRoomClick,
            leaveRoomView = {},
        )
    }
}
