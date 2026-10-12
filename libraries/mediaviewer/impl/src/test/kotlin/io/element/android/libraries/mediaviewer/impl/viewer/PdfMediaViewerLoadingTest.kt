@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.viewer

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.mediaviewer.api.anImageMediaInfo
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.R
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfMediaViewerLoadingTest {
    private val info = anImageMediaInfo().copy(filename = "fixture.pdf", mimeType = MimeTypes.Pdf, fileExtension = "pdf")
    private val media = aLocalMedia(Uri.parse("content://com.yizhe.chat.next.debug.securemedia/v1/${"a".repeat(64)}"), mediaInfo = info)

    @Test
    fun `PDF preparation does not flash terminal text then truly missing lease is rejected`() = runAndroidComposeUiTest<ComponentActivity> {
        val download = mutableStateOf<AsyncData<LocalMedia>>(AsyncData.Loading())
        val page = aMediaViewerPageData(mediaInfo = info).copy(downloadedMedia = download)
        setSafeContent {
            MediaViewerView(aMediaViewerState(listData = listOf(page)), audioFocus = null, textFileViewer = { _, _ -> }, onBackClick = {})
        }
        val terminal = activity!!.getString(R.string.local_pdf_source_unavailable)
        onNodeWithText(terminal).assertDoesNotExist()
        // The central preparation indicator exists immediately, before the delayed transfer bar.
        onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes().also { check(it.isNotEmpty()) }
        runOnIdle { download.value = AsyncData.Success(media) }
        onNodeWithText(terminal).assertIsDisplayed()
    }

    @Test
    fun `PDF denied download never renders previous media or gets stuck as loading`() = runAndroidComposeUiTest<ComponentActivity> {
        val page = aMediaViewerPageData(mediaInfo = info, downloadedMedia = AsyncData.Failure(SecurityException("denied"), prevData = media))
        setSafeContent {
            MediaViewerView(aMediaViewerState(listData = listOf(page)), audioFocus = null, textFileViewer = { _, _ -> }, onBackClick = {})
        }
        onNodeWithText(activity!!.getString(CommonStrings.error_unknown)).assertIsDisplayed()
        onNodeWithText(activity!!.getString(R.string.local_pdf_source_unavailable)).assertDoesNotExist()
        onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
    }
}
