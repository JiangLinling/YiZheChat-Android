/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.startchat.impl.root

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.startchat.impl.R
import io.element.android.features.startchat.impl.userlist.aRecentDirectRoomList
import io.element.android.features.startchat.impl.userlist.aUserListState
import io.element.android.libraries.designsystem.theme.components.SearchBarResultState
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.ui.components.aMatrixUser
import io.element.android.libraries.matrix.ui.model.getBestName
import io.element.android.libraries.usersearch.api.UserSearchResult
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.EnsureNeverCalled
import io.element.android.tests.testutils.EnsureNeverCalledWithParam
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.ensureCalledOnce
import io.element.android.tests.testutils.ensureCalledOnceWithParam
import io.element.android.tests.testutils.pressBack
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class StartChatViewTest {
    @Test
    fun `clicking on back invokes the expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setStartChatView(aCreateRoomRootState(eventSink = EventsRecorder(expectEvents = false)), onCloseClick = callback)
            pressBack()
        }
    }

    @Test
    fun `friend search excludes room creation directory and room-address controls`() = runAndroidComposeUiTest {
        setStartChatView(aCreateRoomRootState(applicationName = "test", eventSink = EventsRecorder(expectEvents = false)))
        listOf(R.string.screen_create_room_action_create_room, R.string.screen_room_directory_search_title,
            R.string.screen_start_chat_join_room_by_address_action).forEach { resource ->
            onNodeWithText(activity!!.getString(resource)).assertDoesNotExist()
        }
        onNodeWithText(activity!!.getString(CommonStrings.action_invite_friends_to_app, "test")).assertDoesNotExist()
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `clicking scan opens the scan flow`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setStartChatView(aCreateRoomRootState(eventSink = EventsRecorder(expectEvents = false)), onScanClick = callback)
            clickOn(R.string.screen_add_friend_scan)
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `clicking my QR code opens the current account QR flow`() = runAndroidComposeUiTest {
        ensureCalledOnce { callback ->
            setStartChatView(aCreateRoomRootState(eventSink = EventsRecorder(expectEvents = false)), onMyQrCodeClick = callback)
            clickOn(R.string.screen_add_friend_my_qr_code)
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `friend search does not render recent direct-chat suggestions`() = runAndroidComposeUiTest {
        val rooms = aRecentDirectRoomList()
        setStartChatView(aCreateRoomRootState(
            userListState = aUserListState(recentDirectRooms = rooms),
            eventSink = EventsRecorder(expectEvents = false),
        ))
        onNodeWithText(rooms.first().matrixUser.getBestName()).assertDoesNotExist()
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `clicking an actual searched user invokes the profile selection callback`() = runAndroidComposeUiTest {
        val user = aMatrixUser()
        ensureCalledOnceWithParam(user) { callback ->
            setStartChatView(aCreateRoomRootState(
                userListState = aUserListState(
                    searchQuery = user.getBestName(), isSearchActive = true,
                    searchResults = SearchBarResultState.Results(persistentListOf(UserSearchResult(user, false))),
                ),
                eventSink = EventsRecorder(expectEvents = false),
            ), onUserSelected = callback)
            onNodeWithText(user.getBestName()).performClick()
        }
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.setStartChatView(
    state: StartChatState,
    onCloseClick: () -> Unit = EnsureNeverCalled(),
    onNewRoomClick: () -> Unit = EnsureNeverCalled(),
    onOpenDM: (RoomId) -> Unit = EnsureNeverCalledWithParam(),
    onInviteFriendsClick: () -> Unit = EnsureNeverCalled(),
    onJoinRoomByAddressClick: () -> Unit = EnsureNeverCalled(),
    onRoomDirectorySearchClick: () -> Unit = EnsureNeverCalled(),
    onScanClick: () -> Unit = EnsureNeverCalled(),
    onMyQrCodeClick: () -> Unit = EnsureNeverCalled(),
    onUserSelected: (MatrixUser) -> Unit = EnsureNeverCalledWithParam(),
) {
    setContent {
        StartChatView(
            state = state, onCloseClick = onCloseClick, onNewRoomClick = onNewRoomClick,
            onOpenDM = onOpenDM, onInviteFriendsClick = onInviteFriendsClick,
            onJoinByAddressClick = onJoinRoomByAddressClick, onRoomDirectorySearchClick = onRoomDirectorySearchClick,
            onScanClick = onScanClick, onMyQrCodeClick = onMyQrCodeClick, onUserSelected = onUserSelected,
        )
    }
}
