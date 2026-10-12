/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.userprofile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.userprofile.api.UserProfileEvents
import io.element.android.features.userprofile.api.UserProfileState
import io.element.android.features.userprofile.api.UserProfileVerificationState
import io.element.android.features.userprofile.shared.R
import io.element.android.features.userprofile.shared.UserProfileView
import io.element.android.features.userprofile.shared.aUserProfileState
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.test.AN_AVATAR_URL
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_NAME
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.EnsureNeverCalled
import io.element.android.tests.testutils.EnsureNeverCalledWithParam
import io.element.android.tests.testutils.EnsureNeverCalledWithTwoParams
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.ensureCalledOnce
import io.element.android.tests.testutils.ensureCalledOnceWithParam
import io.element.android.tests.testutils.ensureCalledOnceWithTwoParams
import io.element.android.tests.testutils.pressBack
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class UserProfileViewTest {
    @Config(qualifiers = "h1024dp")
    @Test
    fun `private details save failure keeps editor and entered remark`() = runAndroidComposeUiTest<ComponentActivity> {
        val currentState = mutableStateOf(aUserProfileState())
        val eventSink: (UserProfileEvents) -> Unit = { event ->
            when (event) {
                is UserProfileEvents.SavePrivateProfile -> currentState.value = currentState.value.copy(
                    savePrivateProfileAction = AsyncAction.Failure(IllegalStateException("save failed")),
                )
                UserProfileEvents.ClearSavePrivateProfileState -> currentState.value = currentState.value.copy(
                    savePrivateProfileAction = AsyncAction.Uninitialized,
                )
                else -> Unit
            }
        }
        setContent {
            UserProfileView(
                state = currentState.value.copy(eventSink = eventSink),
                onShareUser = {}, onOpenDm = {}, onStartCall = { _, _ -> }, goBack = {},
                openAvatarPreview = { _, _ -> }, onVerifyClick = {},
            )
        }
        clickOn(R.string.screen_user_profile_private_details)
        onNodeWithText(activity!!.getString(R.string.screen_user_profile_remark_name)).performTextReplacement("New remark")
        clickOn(CommonStrings.action_save)
        onNodeWithText(activity!!.getString(R.string.screen_user_profile_save_details_error)).assertExists()
        clickOn(CommonStrings.action_ok)
        onNodeWithText("New remark").assertExists()
        onNodeWithText(activity!!.getString(R.string.screen_user_profile_edit_private_details)).assertExists()
    }

    @Test
    fun `account information exposes and copies the full account`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<UserProfileEvents>()
        setUserProfileView(state = aUserProfileState(userId = A_USER_ID, eventSink = events))
        clickOn(R.string.screen_user_profile_account_info)
        onNodeWithText(A_USER_ID.value).performClick()
        events.assertSingle(UserProfileEvents.CopyToClipboard(A_USER_ID.value))
    }

    @Test
    fun `on back button click - the expected callback is called`() = runAndroidComposeUiTest<ComponentActivity> {
        ensureCalledOnce { callback ->
            setUserProfileView(
                goBack = callback,
            )
            pressBack()
        }
    }

    @Test
    fun `on avatar clicked - the expected callback is called`() = runAndroidComposeUiTest<ComponentActivity> {
        ensureCalledOnceWithTwoParams(A_USER_NAME, AN_AVATAR_URL) { callback ->
            setUserProfileView(
                state = aUserProfileState(userName = A_USER_NAME, avatarUrl = AN_AVATAR_URL),
                openAvatarPreview = callback,
            )
            onNode(hasTestTag(TestTags.memberDetailAvatar.value)).performClick()
        }
    }

    @Test
    fun `on avatar clicked with no avatar - nothing happens`() = runAndroidComposeUiTest<ComponentActivity> {
        val callback = EnsureNeverCalledWithTwoParams<String, String>()
        setUserProfileView(
            state = aUserProfileState(userName = A_USER_NAME, avatarUrl = null),
            openAvatarPreview = callback,
        )
        onNode(hasTestTag(TestTags.memberDetailAvatar.value)).performClick()
    }

    @Test
    fun `on Share clicked - the expected callback is called`() = runAndroidComposeUiTest<ComponentActivity> {
        ensureCalledOnce { callback ->
            setUserProfileView(
                onShareUser = callback,
            )
            onNode(hasContentDescription(activity!!.getString(R.string.screen_user_profile_more))).performClick()
            clickOn(CommonStrings.action_share)
        }
    }

    @Test
    fun `on Message clicked - the StartDm event is emitted`() = runAndroidComposeUiTest<ComponentActivity> {
        val eventsRecorder = EventsRecorder<UserProfileEvents>()
        setUserProfileView(
            state = aUserProfileState(
                dmRoomId = A_ROOM_ID,
                eventSink = eventsRecorder,
            ),
        )
        clickOn(CommonStrings.action_message)
        eventsRecorder.assertSingle(UserProfileEvents.StartDM)
    }

    @Test
    fun `on Call clicked - the expected callback is called`() = runAndroidComposeUiTest<ComponentActivity> {
        ensureCalledOnceWithTwoParams(A_ROOM_ID, CallIntent.AUDIO) { callback ->
            setUserProfileView(
                state = aUserProfileState(
                    dmRoomId = A_ROOM_ID,
                    canCall = true,
                ),
                onStartCall = callback,
            )
            clickOn(R.string.screen_user_profile_calls)
            clickOn(R.string.screen_user_profile_voice_call)
        }
    }

    @Test
    fun `on Video Call clicked - the expected callback is called`() = runAndroidComposeUiTest<ComponentActivity> {
        ensureCalledOnceWithTwoParams(A_ROOM_ID, CallIntent.VIDEO) { callback ->
            setUserProfileView(
                state = aUserProfileState(
                    dmRoomId = A_ROOM_ID,
                    canCall = true,
                ),
                onStartCall = callback,
            )
            clickOn(R.string.screen_user_profile_calls)
            clickOn(R.string.screen_user_profile_video_call)
        }
    }

    @Test
    fun `opening and canceling call choices does not initiate a call or create a room`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<UserProfileEvents>()
        setUserProfileView(
            state = aUserProfileState(dmRoomId = A_ROOM_ID, canCall = true, eventSink = events),
            onStartCall = EnsureNeverCalledWithTwoParams(),
        )
        clickOn(R.string.screen_user_profile_calls)
        clickOn(CommonStrings.action_cancel)
        events.assertEmpty()
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `on Block user clicked - a BlockUser event is emitted with needsConfirmation`() = runAndroidComposeUiTest<ComponentActivity> {
        val eventsRecorder = EventsRecorder<UserProfileEvents>()
        setUserProfileView(
            state = aUserProfileState(
                eventSink = eventsRecorder,
            ),
        )
        onNode(hasContentDescription(activity!!.getString(R.string.screen_user_profile_more))).performClick()
        clickOn(R.string.screen_dm_details_block_user)
        eventsRecorder.assertSingle(UserProfileEvents.BlockUser(needsConfirmation = true))
    }

    @Test
    fun `on confirming block user - a BlockUser event is emitted without needsConfirmation`() = runAndroidComposeUiTest<ComponentActivity> {
        val eventsRecorder = EventsRecorder<UserProfileEvents>()
        setUserProfileView(
            state = aUserProfileState(
                displayConfirmationDialog = UserProfileState.ConfirmationDialog.Block,
                eventSink = eventsRecorder,
            ),
        )
        clickOn(R.string.screen_dm_details_block_alert_action)
        eventsRecorder.assertSingle(UserProfileEvents.BlockUser(needsConfirmation = false))
    }

    @Test
    fun `on canceling blocking a user - a ClearConfirmationDialog event is emitted`() = runAndroidComposeUiTest<ComponentActivity> {
        val eventsRecorder = EventsRecorder<UserProfileEvents>()
        setUserProfileView(
            state = aUserProfileState(
                displayConfirmationDialog = UserProfileState.ConfirmationDialog.Block,
                eventSink = eventsRecorder,
            ),
        )
        clickOn(CommonStrings.action_cancel)
        eventsRecorder.assertSingle(UserProfileEvents.ClearConfirmationDialog)
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `on Unblock user clicked - an UnblockUser event is emitted with needsConfirmation`() = runAndroidComposeUiTest<ComponentActivity> {
        val eventsRecorder = EventsRecorder<UserProfileEvents>()
        setUserProfileView(
            state = aUserProfileState(
                isBlocked = AsyncData.Success(true),
                eventSink = eventsRecorder,
            ),
        )
        onNode(hasContentDescription(activity!!.getString(R.string.screen_user_profile_more))).performClick()
        clickOn(R.string.screen_dm_details_unblock_user)
        eventsRecorder.assertSingle(UserProfileEvents.UnblockUser(needsConfirmation = true))
    }

    @Test
    fun `on confirming Unblock user - an UnblockUser event is emitted without needsConfirmation`() = runAndroidComposeUiTest<ComponentActivity> {
        val eventsRecorder = EventsRecorder<UserProfileEvents>()
        setUserProfileView(
            state = aUserProfileState(
                isBlocked = AsyncData.Success(true),
                displayConfirmationDialog = UserProfileState.ConfirmationDialog.Unblock,
                eventSink = eventsRecorder,
            ),
        )
        clickOn(R.string.screen_dm_details_unblock_alert_action)
        eventsRecorder.assertSingle(UserProfileEvents.UnblockUser(needsConfirmation = false))
    }

    @Test
    fun `on canceling unblocking a user - a ClearConfirmationDialog event is emitted`() = runAndroidComposeUiTest<ComponentActivity> {
        val eventsRecorder = EventsRecorder<UserProfileEvents>()
        setUserProfileView(
            state = aUserProfileState(
                isBlocked = AsyncData.Success(true),
                displayConfirmationDialog = UserProfileState.ConfirmationDialog.Unblock,
                eventSink = eventsRecorder,
            ),
        )
        clickOn(CommonStrings.action_cancel)
        eventsRecorder.assertSingle(UserProfileEvents.ClearConfirmationDialog)
    }

    @Test
    fun `on verify user clicked - the right callback is called`() = runAndroidComposeUiTest<ComponentActivity> {
        ensureCalledOnceWithParam(A_USER_ID) { callback ->
            setUserProfileView(
                state = aUserProfileState(userId = A_USER_ID, verificationState = UserProfileVerificationState.UNVERIFIED),
                onVerifyClick = callback,
            )
            clickOn(R.string.screen_user_profile_verify_identity)
        }
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.setUserProfileView(
    state: UserProfileState = aUserProfileState(
        eventSink = EventsRecorder(expectEvents = false),
    ),
    onShareUser: () -> Unit = EnsureNeverCalled(),
    onDmStarted: (RoomId) -> Unit = EnsureNeverCalledWithParam(),
    onStartCall: (RoomId, CallIntent) -> Unit = EnsureNeverCalledWithTwoParams(),
    onVerifyClick: (UserId) -> Unit = EnsureNeverCalledWithParam(),
    goBack: () -> Unit = EnsureNeverCalled(),
    openAvatarPreview: (String, String) -> Unit = EnsureNeverCalledWithTwoParams(),
) {
    setContent {
        UserProfileView(
            state = state,
            onShareUser = onShareUser,
            onOpenDm = onDmStarted,
            onStartCall = onStartCall,
            goBack = goBack,
            openAvatarPreview = openAvatarPreview,
            onVerifyClick = onVerifyClick,
        )
    }
}
