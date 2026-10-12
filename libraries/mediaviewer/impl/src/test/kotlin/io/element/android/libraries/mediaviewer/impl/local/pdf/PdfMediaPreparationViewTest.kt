@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import android.net.Uri
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseState
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.R
import io.element.android.libraries.mediaviewer.impl.local.LocalMediaViewState
import io.element.android.libraries.mediaviewer.impl.local.office.FilePreviewLeaseRegistry
import io.element.android.libraries.mediaviewer.impl.local.rememberLocalMediaViewState
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class PdfMediaPreparationViewTest {
    private val media = aLocalMedia(Uri.parse("content://com.yizhe.chat.next.debug.securemedia/v1/${"a".repeat(64)}"))

    @Test
    fun `uninitialized and loading never enter renderer or become ready then success passes exact original media`() = runComposeUiTest {
        var download by mutableStateOf<AsyncData<LocalMedia>>(AsyncData.Uninitialized)
        lateinit var viewState: LocalMediaViewState
        var rendered: LocalMedia? = null
        setContent {
            ElementPreview {
                viewState = rememberLocalMediaViewState()
                PdfMediaPreparationView(download, viewState) {
                    rendered = it
                    Text("first page")
                }
            }
        }
        onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        runOnIdle {
            assertThat(viewState.isReady).isFalse()
            assertThat(rendered).isNull()
            download = AsyncData.Loading(prevData = media)
        }
        onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        onNodeWithText("first page").assertDoesNotExist()
        runOnIdle {
            assertThat(rendered).isNull()
            download = AsyncData.Success(media)
        }
        onNodeWithText("first page").assertIsDisplayed()
        onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
        runOnIdle { assertThat(rendered).isSameInstanceAs(media) }
    }

    @Test
    fun `denied failure with previous media removes content and does not masquerade as preparing`() = runComposeUiTest {
        var download by mutableStateOf<AsyncData<LocalMedia>>(AsyncData.Success(media))
        var disposed = 0
        lateinit var viewState: LocalMediaViewState
        setContent {
            ElementPreview {
                viewState = rememberLocalMediaViewState()
                PdfMediaPreparationView(download, viewState) {
                    viewState.isReady = true
                    DisposableEffect(it) { onDispose { disposed++ } }
                    Text("first page")
                }
            }
        }
        onNodeWithText("first page").assertIsDisplayed()
        runOnIdle { download = AsyncData.Failure(SecurityException("denied"), prevData = media) }
        onNodeWithText("first page").assertDoesNotExist()
        onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
        runOnIdle {
            assertThat(viewState.isReady).isFalse()
            assertThat(disposed).isEqualTo(1)
        }
    }

    @Test
    fun `close removes active content and releases original lease without reviving it on loading or failure`() = runComposeUiTest {
        val file = object : MediaFile {
            var closes = 0
            override fun path(): String = ""
            override fun uriString(): String = media.uri.toString()
            override fun openInputStream(): InputStream = ByteArrayInputStream(byteArrayOf(1))
            override fun persist(path: String): Boolean = false
            override fun close() { closes++ }
        }
        val token = FilePreviewLeaseRegistry.register("pdf-close", file)
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, "pdf-close", "com.yizhe.chat.next.debug.securemedia"))
        var visible by mutableStateOf(true)
        var download by mutableStateOf<AsyncData<LocalMedia>>(AsyncData.Success(media))
        setContent {
            ElementPreview {
                if (visible) {
                    PdfMediaPreparationView(download, rememberLocalMediaViewState()) {
                        DisposableEffect(it) { onDispose { lease.release(FilePreviewLeaseReleaseReason.HOST_DISPOSED) } }
                        Text("first page")
                    }
                }
            }
        }
        onNodeWithText("first page").assertIsDisplayed()
        runOnIdle { visible = false }
        onNodeWithText("first page").assertDoesNotExist()
        runOnIdle {
            assertThat(file.closes).isEqualTo(1)
            assertThat(lease.state.value).isEqualTo(FilePreviewLeaseState.Released(FilePreviewLeaseReleaseReason.HOST_DISPOSED))
            download = AsyncData.Loading(prevData = media)
            visible = true
        }
        onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        runOnIdle { download = AsyncData.Failure(SecurityException("revoked"), prevData = media) }
        onNodeWithText("first page").assertDoesNotExist()
        runOnIdle { assertThat(file.closes).isEqualTo(1) }
    }

    @Test
    fun `ready media without a lease still displays true unavailable instead of loading`() = runComposeUiTest {
        setContent {
            ElementPreview {
                val state = rememberLocalMediaViewState()
                PdfMediaPreparationView(AsyncData.Success(media), state) {
                    LocalPdfMediaView(state, it, onClick = {})
                }
            }
        }
        onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
        onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.local_pdf_source_unavailable)).assertIsDisplayed()
    }
}
