/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.attachments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.attachments.preview.AttachmentFilePreviewState
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewView
import io.element.android.features.messages.impl.attachments.preview.anAttachmentsPreviewState
import io.element.android.libraries.designsystem.preview.ElementPreviewDark
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderMode
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
class AttachmentsFilePreviewViewTest {
    @Test
    fun `preparing failure revoked and closed never mount the renderer`() = runComposeUiTest {
        val phase = mutableStateOf(AttachmentFilePreviewState.Preparing)
        var rendererEntries = 0
        val renderer = renderer { rendererEntries++ }
        val base = anAttachmentsPreviewState()
        setContent {
            ElementPreviewDark {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AttachmentsPreviewView(base.copy(filePreviewStates = mapOf(0 to phase.value)), renderer)
                }
            }
        }
        onNodeWithText("Preparing the file for local preview…").assertIsDisplayed()
        onNodeWithText("This preview session has ended. Return to the file list and open it again.").assertDoesNotExist()
        runOnIdle { phase.value = AttachmentFilePreviewState.Failed }
        onNodeWithText("The file could not be prepared for preview. Return to the file list and open it again.").assertIsDisplayed()
        runOnIdle { phase.value = AttachmentFilePreviewState.Revoked }
        onNodeWithText("Access to this preview is unavailable. Return to the file list and open it again.").assertIsDisplayed()
        runOnIdle { phase.value = AttachmentFilePreviewState.Closed }
        onNodeWithText("This preview session has ended. Return to the file list and open it again.").assertIsDisplayed()
        runOnIdle { assertThat(rendererEntries).isEqualTo(0) }
    }

    @Test
    fun `ready mounts once and a subsequent close removes its content`() = runComposeUiTest {
        val phase = mutableStateOf(AttachmentFilePreviewState.Preparing)
        var rendererEntries = 0
        val renderer = renderer { rendererEntries++ }
        val base = anAttachmentsPreviewState()
        setContent {
            ElementPreviewDark {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AttachmentsPreviewView(base.copy(filePreviewStates = mapOf(0 to phase.value)), renderer)
                }
            }
        }
        runOnIdle { assertThat(rendererEntries).isEqualTo(0); phase.value = AttachmentFilePreviewState.Ready }
        onNodeWithText("leased document").assertIsDisplayed()
        runOnIdle { assertThat(rendererEntries).isGreaterThan(0); phase.value = AttachmentFilePreviewState.Closed }
        onNodeWithText("leased document").assertDoesNotExist()
    }

    private fun renderer(entered: () -> Unit) = object : LocalMediaRenderer {
        @Composable
        override fun Render(localMedia: LocalMedia, accountId: String?, deviceId: String?, renderMode: LocalMediaRenderMode) {
            SideEffect(entered)
            Text("leased document")
        }
    }
}
