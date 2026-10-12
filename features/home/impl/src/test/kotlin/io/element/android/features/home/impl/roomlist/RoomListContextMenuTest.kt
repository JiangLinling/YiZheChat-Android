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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.home.impl.R
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.EnsureNeverCalledWithParam
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomListContextMenuTest {
    @Test
    fun `clicking on Mark as read generates expected Events`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        val contextMenu = aContextMenuShown(hasNewContent = true)
        setRoomListContextMenu(
            contextMenu = contextMenu,
            eventSink = eventsRecorder,
        )
        clickOn(R.string.screen_roomlist_mark_as_read)
        eventsRecorder.assertList(
            listOf(
                RoomListEvent.HideContextMenu,
                RoomListEvent.MarkAsRead(contextMenu.roomId),
            )
        )
    }

    @Test
    fun `clicking on Mark as unread generates expected Events`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        val contextMenu = aContextMenuShown(hasNewContent = false)
        setRoomListContextMenu(
            contextMenu = contextMenu,
            eventSink = eventsRecorder,
        )
        clickOn(R.string.screen_roomlist_mark_as_unread)
        eventsRecorder.assertList(
            listOf(
                RoomListEvent.HideContextMenu,
                RoomListEvent.MarkAsUnread(contextMenu.roomId),
            )
        )
    }

    @Test
    fun `leave room is hidden from the compact room list menu`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>(expectEvents = false)
        val contextMenu = aContextMenuShown(isDm = false)
        setRoomListContextMenu(
            contextMenu = contextMenu,
            eventSink = eventsRecorder,
        )
        onNodeWithText(activity!!.getString(R.string.screen_roomlist_leave_group)).assertDoesNotExist()
    }

    @Test
    fun `report room is hidden from the compact room list menu`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>(expectEvents = false)
        val contextMenu = aContextMenuShown()
        setRoomListContextMenu(
            contextMenu = contextMenu,
            canReportRoom = true,
            eventSink = eventsRecorder,
            onRoomSettingsClick = EnsureNeverCalledWithParam(),
            onReportRoomClick = EnsureNeverCalledWithParam(),
        )
        onNodeWithText(activity!!.getString(CommonStrings.action_report_room)).assertDoesNotExist()
    }

    @Test
    fun `settings are hidden from the compact room list menu`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>(expectEvents = false)
        val contextMenu = aContextMenuShown()
        setRoomListContextMenu(
            contextMenu = contextMenu,
            eventSink = eventsRecorder,
            onRoomSettingsClick = EnsureNeverCalledWithParam(),
        )
        onNodeWithText(activity!!.getString(CommonStrings.common_settings)).assertDoesNotExist()
    }

    @Test
    fun `clicking on Pin generates expected Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        val contextMenu = aContextMenuShown(isDm = false, isFavorite = false)
        val callback = EnsureNeverCalledWithParam<RoomId>()
        setRoomListContextMenu(
            contextMenu = contextMenu,
            eventSink = eventsRecorder,
            onRoomSettingsClick = callback,
        )
        clickOn(R.string.screen_roomlist_pin)
        eventsRecorder.assertList(
            listOf(
                RoomListEvent.SetRoomIsFavorite(contextMenu.roomId, true),
            )
        )
    }

    @Test
    fun `clicking on Mute generates expected Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<RoomListEvent>()
        val contextMenu = aContextMenuShown(isMuted = false)
        setRoomListContextMenu(
            contextMenu = contextMenu,
            eventSink = eventsRecorder,
        )
        clickOn(R.string.screen_roomlist_mute)
        eventsRecorder.assertSingle(RoomListEvent.SetRoomMuted(contextMenu.roomId, true))
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setRoomListContextMenu(
        contextMenu: RoomListState.ContextMenu.Shown,
        canReportRoom: Boolean = false,
        eventSink: (RoomListEvent) -> Unit,
        onRoomSettingsClick: (RoomId) -> Unit = EnsureNeverCalledWithParam(),
        onReportRoomClick: (RoomId) -> Unit = EnsureNeverCalledWithParam(),
    ) {
        setSafeContent {
            RoomListContextMenu(
                contextMenu = contextMenu,
                canReportRoom = canReportRoom,
                onRoomSettingsClick = onRoomSettingsClick,
                onReportRoomClick = onReportRoomClick,
                eventSink = eventSink,
            )
        }
    }
}
