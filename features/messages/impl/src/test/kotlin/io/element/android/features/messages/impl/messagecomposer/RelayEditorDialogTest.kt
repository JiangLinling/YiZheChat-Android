/*
 * Copyright (c) 2026 YiZhe Chat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.messagecomposer

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.tests.testutils.clickOn
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedRelayEntry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayEditorDialogTest {
    @Test
    fun `unavailable recovery blocks new edits and retry loads original state first`() = runAndroidComposeUiTest<ComponentActivity> {
        var loads = 0
        setContent { ElementPreview {
            RelayEditorDialog(initialTitle = "Team", entries = persistentListOf("Original"), isCreating = false,
                itemDetails = persistentListOf(ProtectedRelayEntry("own-item", UserId("@me:test"), "Original")),
                editableItemIds = persistentSetOf("own-item"),
                loadPendingEdit = { callback ->
                    loads++
                    callback(if (loads == 1) Result.failure(IllegalStateException("Vault unavailable")) else Result.success(null))
                }, onSubmit = { _, _, _ -> error("recovery retry must not submit") }, onDismiss = {})
        } }
        val edit = ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_edit_entry)
        onNodeWithContentDescription(edit).assertIsNotEnabled()
        clickOn(R.string.screen_room_relay_check_recovery)
        onNodeWithContentDescription(edit).assertIsEnabled()
        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun `reopened pending edit checks original status without saving or appending`() = runAndroidComposeUiTest<ComponentActivity> {
        val operation = "6ac2ab77-a873-4a0a-9e5f-81526fa54d78"
        val recovery = io.element.android.libraries.matrix.api.timeline.RelayEditRecovery(
            "relay-edit-$operation", "\$root", "own-item", 2L, "Original pending revision",
        )
        var loads = 0
        setContent { ElementPreview {
            RelayEditorDialog(initialTitle = "Team", entries = persistentListOf("Current revision"), isCreating = false,
                itemDetails = persistentListOf(ProtectedRelayEntry("own-item", UserId("@me:test"), "Current revision", revision = 3L)),
                editableItemIds = persistentSetOf("own-item"), loadPendingEdit = { loads++; it(Result.success(recovery)) },
                onSubmit = { _, _, _ -> error("must not append a pending edit") },
                onEditEntry = { _, _, _, _, _ -> error("Recovery must never dispatch an edit") }, onDismiss = {})
        } }
        onNodeWithText(recovery.entry).assertIsNotEnabled()
        onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_save_edit)).assertDoesNotExist()
        clickOn(R.string.screen_room_relay_check_recovery)
        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun `unknown edit result retains original identity and prevents a conflicting new intent`() = runAndroidComposeUiTest<ComponentActivity> {
        val identities = mutableListOf<String>()
        var complete: ((Result<Unit>) -> Unit)? = null
        setContent { ElementPreview {
            RelayEditorDialog(initialTitle="Team",entries=persistentListOf("Original"),isCreating=false,
                itemDetails=persistentListOf(ProtectedRelayEntry("own-item",UserId("@me:test"),"Original")),
                editableItemIds=persistentSetOf("own-item"),onSubmit={ _,_,_ -> error("must not append") },
                loadPendingEdit = { callback -> callback(Result.success(identities.firstOrNull()?.let {
                    io.element.android.libraries.matrix.api.timeline.RelayEditRecovery("relay-edit-$it", "\$root", "own-item", 0, "Revision A")
                })) },
                onEditEntry={ _,_,_,id,callback -> identities+=id;complete=callback },onDismiss={})
        } }
        onNodeWithContentDescription(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_edit_entry)).performClick()
        onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Revision A")
        clickOn(R.string.screen_room_relay_save_edit)
        runOnIdle { complete!!(Result.failure(IllegalStateException("network failure"))) }
        clickOn(R.string.screen_room_relay_check_recovery)
        assertThat(identities).hasSize(1)
        onNodeWithText("Revision A").assertIsNotEnabled()
        assertThat(identities).hasSize(1)
        identities.forEach { assertThat(java.util.UUID.fromString(it).toString()).isEqualTo(it) }
    }

    @Test
    fun `editing own item keeps position waits for revision and never invokes append`() = runAndroidComposeUiTest<ComponentActivity> {
        val own = ProtectedRelayEntry("own-item",UserId("@me:test"),"My original")
        val items = mutableStateOf(persistentListOf(own,ProtectedRelayEntry("other-item",UserId("@other:test"),"Other")))
        var complete: ((Result<Unit>) -> Unit)? = null
        var captured: Triple<String,Long,String>? = null
        setContent { ElementPreview {
            RelayEditorDialog(initialTitle="Team",entries=persistentListOf("My original","Other"),isCreating=false,
                itemDetails=items.value,editableItemIds=persistentSetOf("own-item"),
                onSubmit={ _,_,_ -> error("editing must not append") },
                onEditEntry={ id,revision,text,_,callback -> captured=Triple(id,revision,text);complete=callback },onDismiss={})
        } }
        assertThat(onAllNodesWithContentDescription(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_edit_entry)).fetchSemanticsNodes()).hasSize(1)
        onNodeWithContentDescription(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_edit_entry)).performClick()
        onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("My original revised")
        clickOn(R.string.screen_room_relay_save_edit)
        assertThat(captured).isEqualTo(Triple("own-item",0L,"My original revised"))
        runOnIdle { complete!!(Result.success(Unit)) }
        onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_save_edit)).assertDoesNotExist()
        runOnIdle { items.value=items.value.set(0,own.copy(text="My original revised",revision=1,isConfirmed=false)) }
        onAllNodesWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_pending)).onFirst().assertExists()
        runOnIdle { items.value=items.value.set(0,own.copy(text="My original revised",revision=1)) }
        onAllNodesWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_back)).onFirst().assertIsEnabled()
    }

    @Test
    fun `concurrent own revision blocks stale edit and retains typed text`() = runAndroidComposeUiTest<ComponentActivity> {
        val own = ProtectedRelayEntry("own-item",UserId("@me:test"),"Original")
        val items = mutableStateOf(persistentListOf(own))
        setContent { ElementPreview {
            RelayEditorDialog(initialTitle="Team",entries=persistentListOf("Original"),isCreating=false,
                itemDetails=items.value,editableItemIds=persistentSetOf("own-item"),onSubmit={ _,_,_ -> },
                onEditEntry={ _,_,_,_,_ -> error("stale revision must not submit") },onDismiss={})
        } }
        onNodeWithContentDescription(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_edit_entry)).performClick()
        onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Original draft")
        runOnIdle { items.value=persistentListOf(own.copy(text="Concurrent update",revision=1)) }
        onAllNodes(hasSetTextAction()).onFirst().assertTextContains("Original draft")
        onAllNodesWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_save_edit)).onFirst().assertIsNotEnabled()
        onAllNodesWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_edit_conflict)).onFirst().assertExists()
    }

    @Test
    fun `accepted submission waits for list projection then offers return without resubmitting`() = runAndroidComposeUiTest<ComponentActivity> {
        val entries = mutableStateOf(persistentListOf("Alice 1"))
        var complete: ((Result<Unit>) -> Unit)? = null
        var calls = 0
        var dismissed = false
        setContent {
            ElementPreview {
                RelayEditorDialog(
                    initialTitle = "Weekend",
                    entries = entries.value,
                    isCreating = false,
                    onSubmit = { _, _, callback ->
                        calls++
                        complete = callback
                    },
                    onDismiss = { dismissed = true },
                )
            }
        }
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("Bob 2")
        clickOn(R.string.screen_room_relay_submit)
        runOnIdle { complete!!(Result.success(Unit)) }
        onAllNodesWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_submit)).onFirst().assertIsNotEnabled()
        runOnIdle { entries.value = entries.value.add("Carol 1") }
        onAllNodesWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_pending)).onFirst().assertExists()
        runOnIdle { entries.value = entries.value.add("Bob 2") }
        onAllNodesWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_back)).onFirst().assertIsEnabled()
        clickOn(R.string.screen_room_relay_back)
        assertThat(calls).isEqualTo(1)
        assertThat(dismissed).isTrue()
    }

    @Test
    fun `failed entry is retained and loading prevents repeated submission`() = runAndroidComposeUiTest<ComponentActivity> {
        var calls = 0
        var complete: ((Result<Unit>) -> Unit)? = null
        setContent {
            ElementPreview {
                RelayEditorDialog(
                    initialTitle = "Weekend",
                    entries = persistentListOf("Alice 1"),
                    isCreating = false,
                    onSubmit = { _, _, callback ->
                        calls++
                        complete = callback
                    },
                    onDismiss = {}
                )
            }
        }
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("Bob 2")
        clickOn(R.string.screen_room_relay_submit)
        onAllNodesWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_submit)).onFirst().assertIsNotEnabled()
        assertThat(calls).isEqualTo(1)
        runOnIdle { complete!!(Result.failure(IllegalStateException("unavailable"))) }
        onAllNodes(hasSetTextAction()).onFirst().assertTextContains("Bob 2")
        clickOn(R.string.screen_room_relay_submit)
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `concurrent entries do not clear the typed draft`() = runAndroidComposeUiTest<ComponentActivity> {
        val entries = mutableStateOf(persistentListOf("Alice 1"))
        setContent {
            ElementPreview {
                RelayEditorDialog(
                    initialTitle = "Weekend",
                    entries = entries.value,
                    isCreating = false,
                    onSubmit = { _, _, _ -> },
                    onDismiss = {}
                )
            }
        }
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("Bob 2")
        runOnIdle { entries.value = entries.value.add("Carol 1") }
        onAllNodes(hasSetTextAction()).onFirst().assertTextContains("Bob 2")
    }


    @Test
    fun `closed pending edit returns while reconciliation is loading and never dispatches`() = runAndroidComposeUiTest<ComponentActivity> {
        var loaded: ((Result<io.element.android.libraries.matrix.api.timeline.RelayEditRecovery?>) -> Unit)? = null
        var dismissed = false
        setContent { ElementPreview {
            RelayEditorDialog(initialTitle = "Team", entries = persistentListOf("Confirmed revision"), closed = true, isCreating = false,
                loadPendingEdit = { loaded = it }, onSubmit = { _, _, _ -> error("closed append") },
                onEditEntry = { _, _, _, _, _ -> error("closed edit") }, onDismiss = { dismissed = true })
        } }
        onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_back)).assertIsEnabled()
        runOnIdle { loaded!!(Result.success(io.element.android.libraries.matrix.api.timeline.RelayEditRecovery(
            "relay-edit-11111111-1111-4111-8111-111111111111", "\$root", "own-item", 0, "Confirmed revision"))) }
        onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_save_edit)).assertDoesNotExist()
        assertThat(onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).isEmpty()
        clickOn(R.string.screen_room_relay_back)
        assertThat(dismissed).isTrue()
    }

    @Test
    fun `unknown edit then close offers return even when recovery authority is unavailable`() = runAndroidComposeUiTest<ComponentActivity> {
        val closed = mutableStateOf(false)
        var complete: ((Result<Unit>) -> Unit)? = null
        var loads = 0
        var edits = 0
        setContent { ElementPreview {
            RelayEditorDialog(initialTitle = "Team", entries = persistentListOf("Original"), closed = closed.value, isCreating = false,
                itemDetails = persistentListOf(ProtectedRelayEntry("own-item", UserId("@me:test"), "Original")),
                editableItemIds = persistentSetOf("own-item"),
                loadPendingEdit = { callback -> loads++; callback(if (loads == 1) Result.success(null)
                    else Result.failure(SecurityException("Failed requirement."))) },
                onSubmit = { _, _, _ -> error("append") }, onEditEntry = { _, _, _, _, callback -> edits++; complete = callback }, onDismiss = {})
        } }
        onNodeWithContentDescription(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_edit_entry)).performClick()
        onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Revision")
        clickOn(R.string.screen_room_relay_save_edit)
        runOnIdle { complete!!(Result.failure(IllegalStateException("UNKNOWN"))); closed.value = true }
        onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_back)).assertIsEnabled()
        onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_save_edit)).assertDoesNotExist()
        onNodeWithText("Failed requirement.").assertDoesNotExist()
        assertThat(edits).isEqualTo(1)
        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun `closing projection dismisses existing close confirmation and leaves only return`() = runAndroidComposeUiTest<ComponentActivity> {
        val closed = mutableStateOf(false)
        setContent { ElementPreview {
            RelayEditorDialog(initialTitle = "Team", entries = persistentListOf("Original"), closed = closed.value,
                canClose = true, isCreating = false, onSubmit = { _, _, _ -> error("closed submit") },
                onCloseRelay = { error("closed relay must not close again") }, onDismiss = {})
        } }
        clickOn(R.string.screen_room_relay_close_action)
        onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_close_confirmation)).assertExists()
        runOnIdle { closed.value = true }
        onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_close_confirmation)).assertDoesNotExist()
        onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_back)).assertIsEnabled()
    }

    @Test
    fun `closed relay remains readable without an entry field`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            ElementPreview {
                RelayEditorDialog(
                    initialTitle = "Weekend",
                    entries = persistentListOf("Alice 1"),
                    closed = true,
                    itemDetails = persistentListOf(ProtectedRelayEntry("own-item",UserId("@me:test"),"Alice 1")),
                    editableItemIds = persistentSetOf("own-item"),
                    isCreating = false,
                    onSubmit = { _, _, _ -> error("closed relay must not submit") },
                    onDismiss = {}
                )
            }
        }
        assertThat(onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).isEmpty()
        assertThat(onAllNodesWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_relay_edit_entry)).fetchSemanticsNodes()).isEmpty()
        assertThat(onAllNodesWithText("Alice 1").fetchSemanticsNodes()).hasSize(1)
    }
}
