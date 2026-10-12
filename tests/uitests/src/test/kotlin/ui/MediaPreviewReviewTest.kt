/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewView
import io.element.android.features.messages.impl.attachments.preview.anAttachmentsPreviewState
import io.element.android.libraries.designsystem.preview.ElementPreviewDark
import io.element.android.libraries.designsystem.utils.CommonDrawables
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderMode
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderer
import org.junit.Rule
import org.junit.Test

/** Layout evidence only: the placeholder renderer does not prove device decoding or encrypted delivery. */
class MediaPreviewReviewTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_5.copy(locale = "zh"), showSystemUi = false)

    private val renderer = object : LocalMediaRenderer {
        @Composable
        override fun Render(localMedia: LocalMedia, accountId: String?, deviceId: String?, renderMode: LocalMediaRenderMode) {
            Image(painterResource(CommonDrawables.sample_background), null, Modifier.fillMaxSize())
        }
    }

    @Test
    fun selectedMedia() {
        val initial = anAttachmentsPreviewState()
        val source = initial.attachment as Attachment.Media
        val items = List(4) { source.copy(localMedia = source.localMedia.copy(lifecycleOperationId = "review-$it")) }
        paparazzi.snapshot {
            ElementPreviewDark {
                AttachmentsPreviewView(initial.copy(attachments = items, attachmentCount = 3, selectedIndices = listOf(0, 2, 1)), renderer)
            }
        }
    }

    @Test
    fun emptySelection() {
        paparazzi.snapshot {
            ElementPreviewDark {
                AttachmentsPreviewView(anAttachmentsPreviewState().copy(selectedIndices = emptyList(), attachmentCount = 0), renderer)
            }
        }
    }

    @Test
    fun interruptedSend() {
        paparazzi.snapshot {
            ElementPreviewDark {
                AttachmentsPreviewView(anAttachmentsPreviewState().copy(selectedIndices = emptyList(), uncertainIndices = listOf(0)), renderer)
            }
        }
    }
}
