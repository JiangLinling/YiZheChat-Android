/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.createroom.impl.configureroom

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.createroom.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rGB-w411dp-h1000dp")
class GroupCreationBackTest {
    @Test
    fun `system Back collapses retention before returning to contacts`() = checkBack(RETENTION, false)

    @Test
    fun `system Back collapses join method before returning to contacts`() = checkBack(ACCESS, false)

    @Test
    fun `top Back collapses retention before returning to contacts`() = checkBack(RETENTION, true)

    @Test
    fun `top Back collapses join method before returning to contacts`() = checkBack(ACCESS, true)

    @Test
    fun `member entry clears retention before reentering confirmation`() = checkMembers(RETENTION)

    @Test
    fun `member entry clears join method before reentering confirmation`() = checkMembers(ACCESS)

    private fun checkBack(setting: Pair<Int, Int>, topBack: Boolean) = runAndroidComposeUiTest<ComponentActivity> {
        val events = mutableListOf<ConfigureRoomEvents>()
        var exits = 0
        showForm(events) { exits++ }
        expand(setting)
        back(topBack)
        onNodeWithText(label(setting.second)).assertDoesNotExist()
        onNodeWithText(label(R.string.screen_create_room_confirm_group)).assertIsDisplayed()
        assertThat(exits).isEqualTo(0)
        back(topBack)
        assertContactsAndReenter(setting)
        assertThat(events).isEmpty()
        assertThat(exits).isEqualTo(0)
    }

    private fun checkMembers(setting: Pair<Int, Int>) = runAndroidComposeUiTest<ComponentActivity> {
        val events = mutableListOf<ConfigureRoomEvents>()
        showForm(events) { error("Member entry must remain within the group wizard") }
        expand(setting)
        onNodeWithText(label(R.string.screen_create_room_group_members)).performScrollTo().performClick()
        assertContactsAndReenter(setting)
        assertThat(events).isEmpty()
    }

    private fun AndroidComposeUiTest<ComponentActivity>.showForm(events: MutableList<ConfigureRoomEvents>, exit: () -> Unit) {
        val contact = MatrixUser(UserId("@fc010:example.org"), "QA contact", null)
        val state = aConfigureRoomState(
            config = CreateRoomConfig(roomName = "FC-010 draft", invites = persistentListOf(contact)),
            eventSink = events::add,
        ).copy(contacts = persistentListOf(contact), contactsLoading = false)
        setContent {
            ElementPreview { GroupCreationView(state, exit, { error("No room should be created") }, initialConfirmation = true) }
        }
    }

    private fun AndroidComposeUiTest<ComponentActivity>.expand(setting: Pair<Int, Int>) {
        onNodeWithText(label(setting.first)).performScrollTo().performClick()
        onNodeWithText(label(setting.second)).performScrollTo().assertIsDisplayed()
    }

    private fun AndroidComposeUiTest<ComponentActivity>.back(topBack: Boolean) {
        if (topBack) {
            onNodeWithContentDescription(label(CommonStrings.action_back)).performClick()
        } else {
            runOnUiThread { checkNotNull(activity).onBackPressedDispatcher.onBackPressed() }
            waitForIdle()
        }
    }

    private fun AndroidComposeUiTest<ComponentActivity>.assertContactsAndReenter(setting: Pair<Int, Int>) {
        onNodeWithText(label(R.string.screen_create_room_start_group)).assertIsDisplayed()
        onNodeWithText(checkNotNull(activity).getString(R.string.screen_create_room_next_count, 1)).performClick()
        onNodeWithText(label(R.string.screen_create_room_confirm_group)).assertIsDisplayed()
        onNodeWithText(label(setting.second)).assertDoesNotExist()
        onNodeWithText("FC-010 draft").assertIsDisplayed()
    }

    private fun AndroidComposeUiTest<ComponentActivity>.label(id: Int) = checkNotNull(activity).getString(id)

    private companion object {
        val RETENTION = R.string.screen_create_room_message_retention_title to R.string.screen_create_room_message_retention_standard_title
        val ACCESS = R.string.screen_create_room_join_method to R.string.screen_create_room_room_access_section_public_option_title
    }
}
