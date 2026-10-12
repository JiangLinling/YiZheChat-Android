/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.attachments

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewView
import io.element.android.features.messages.impl.attachments.preview.anAttachmentsPreviewState
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewEvent
import io.element.android.features.messages.impl.attachments.preview.MediaRecoveryItemCheck
import io.element.android.features.messages.impl.attachments.preview.MediaSubmissionCheck
import io.element.android.features.messages.impl.attachments.preview.OriginalMediaOperationObservation
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreviewDark
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderMode
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
class AttachmentsPreviewRecoveryViewTest {
    private val renderer = object : LocalMediaRenderer {
        @Composable
        override fun Render(localMedia: LocalMedia, accountId: String?, deviceId: String?, renderMode: LocalMediaRenderMode) {
            Box(Modifier.fillMaxSize())
        }
    }

    @Test
    fun `inflight recovery explanation is visible in a single image viewport`() = runComposeUiTest {
        val base = anAttachmentsPreviewState()
        val media = (base.attachment as Attachment.Media).copy(fromGallery = true)
        setContent {
            ElementPreviewDark {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AttachmentsPreviewView(base.copy(attachment = media, attachments = listOf(media), sourceAttachments = listOf(media),
                        selectedIndices = emptyList(), uncertainIndices = listOf(0), blockedIndices = listOf(0)), renderer)
                }
            }
        }
        onNodeWithText("Check send result").assertIsDisplayed()
    }

    @Test
    fun `locally blocked item always has an explanation and status entry`() = runComposeUiTest {
        val base = anAttachmentsPreviewState()
        val media = (base.attachment as Attachment.Media).copy(fromGallery = true)
        setContent {
            ElementPreviewDark {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AttachmentsPreviewView(base.copy(attachment = media, attachments = listOf(media), sourceAttachments = listOf(media),
                        selectedIndices = emptyList(), blockedIndices = listOf(0)), renderer)
                }
            }
        }
        onNodeWithText("Check send result").assertIsDisplayed()
    }

    @Test
    fun `expired legacy dialog distinguishes task projection from missing submission identity`() = runComposeUiTest {
        val base = anAttachmentsPreviewState()
        setContent {
            ElementPreviewDark {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AttachmentsPreviewView(base.copy(recoveryDetailsVisible = true, selectedIndices = emptyList(),
                        blockedIndices = listOf(0), uncertainIndices = listOf(0), recoveryChecks = listOf(
                            MediaRecoveryItemCheck(0, OriginalMediaOperationObservation("RUNNING", true, false, 4, false),
                                MediaSubmissionCheck.MISSING_IDENTITY))), renderer)
                }
            }
        }
        onNodeWithText("This older draft has no verifiable submission identifier. Its result remains unknown; retain it until the original operation can be reconciled.").assertIsDisplayed()
        onNodeWithText("The original task is past its processing deadline. Expiry does not prove that the item was never submitted.").assertIsDisplayed()
        onNodeWithText("4 required actions are still pending.").assertIsDisplayed()
    }

    @Test
    fun `more menu exposes recovery while draft is still restoring`() = runComposeUiTest {
        var emitted: AttachmentsPreviewEvent? = null
        val base = anAttachmentsPreviewState()
        setContent {
            ElementPreviewDark {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AttachmentsPreviewView(base.copy(draftReady = false, eventSink = { emitted = it }), renderer)
                }
            }
        }
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Check send result").assertIsDisplayed().performClick()
        assertThat(emitted).isEqualTo(AttachmentsPreviewEvent.ShowRecoveryDetails)
    }

    @Test
    fun `cancellation pending status remains visible and is not labelled accepted`() = runComposeUiTest {
        val base = anAttachmentsPreviewState()
        setContent {
            ElementPreviewDark {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AttachmentsPreviewView(base.copy(recoveryDetailsVisible = true, selectedIndices = emptyList(),
                        cancellationRequestedIndices = listOf(0)), renderer)
                }
            }
        }
        onNodeWithText("Cancellation was requested, but its result remains unknown. The original draft is retained and cannot be sent again.").assertIsDisplayed()
    }
}
