/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package io.element.android.libraries.mediaviewer.impl.viewer

import android.net.Uri
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.local.ProtectedMediaSurface
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.security.dataprotection.test.FakeProtectedPreviewAuthorizationGate
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.setSafeContent
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectedMediaViewerLoadingTest {
    @Test
    fun `download completion keeps its owner while a revoked source disposes the decoded page`() = runAndroidComposeUiTest {
        val events = EventsRecorder<MediaViewerEvent>()
        val downloaded = mutableStateOf<AsyncData<LocalMedia>>(AsyncData.Uninitialized)
        val page = aMediaViewerPageData().copy(downloadedMedia = downloaded)
        val media = aLocalMedia(Uri.parse("content://test.securemedia/v1/" + "c".repeat(64)))
        val allowed = AtomicBoolean(true)
        val renderedDisposed = AtomicBoolean(false)
        val gate = FakeProtectedPreviewAuthorizationGate()
        val manager = mockk<SecureMediaLeaseManager> {
            every { isReadAuthorized(any()) } answers { allowed.get() }
        }
        setSafeContent {
            LaunchedEffect(Unit) {
                delay(200)
                downloaded.value = AsyncData.Success(media)
            }
            ProtectedMediaSurface(gate, manager, localMedia = null) {
                MediaViewerView(
                    state = aMediaViewerState(listData = listOf(page), eventSink = events),
                    audioFocus = null,
                    textFileViewer = { _, _ -> },
                    onBackClick = {},
                    protectedSurface = { source, _ ->
                        ProtectedMediaSurface(gate, manager, source) {
                            if (source != null) {
                                DisposableEffect(source) { onDispose { renderedDisposed.set(true) } }
                                Text("DECODED TEST SURFACE")
                            }
                        }
                    },
                )
            }
        }
        mainClock.advanceTimeBy(1_000)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("DECODED TEST SURFACE").fetchSemanticsNodes().isNotEmpty() }
        events.assertList(listOf(MediaViewerEvent.LoadMedia(page)))
        allowed.set(false)
        mainClock.advanceTimeBy(1_000)
        waitUntil(timeoutMillis = 5_000) {
            renderedDisposed.get() && onAllNodesWithText("DECODED TEST SURFACE").fetchSemanticsNodes().isEmpty()
        }
        // Renderer revocation must not cancel/restart the completed download.
        events.assertList(listOf(MediaViewerEvent.LoadMedia(page)))
        assertThat(renderedDisposed.get()).isTrue()
    }
}
