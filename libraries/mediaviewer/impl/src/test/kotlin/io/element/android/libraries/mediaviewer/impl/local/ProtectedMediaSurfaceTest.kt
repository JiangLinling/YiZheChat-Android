/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package io.element.android.libraries.mediaviewer.impl.local

import android.net.Uri
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.security.dataprotection.test.FakeProtectedPreviewAuthorizationGate
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.tests.testutils.setSafeContent
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectedMediaSurfaceTest {
    @Test
    fun `an already rendered attachment is disposed after revocation and the same URI never revives`() = runAndroidComposeUiTest {
        val allowed = AtomicBoolean(true)
        val disposed = AtomicBoolean(false)
        val manager = mockk<SecureMediaLeaseManager> {
            every { isReadAuthorized(any()) } answers { allowed.get() }
        }
        val media = aLocalMedia(Uri.parse("content://test.securemedia/v1/" + "a".repeat(64)))
        setSafeContent {
            ProtectedMediaSurface(FakeProtectedPreviewAuthorizationGate(), manager, media) {
                DisposableEffect(Unit) { onDispose { disposed.set(true) } }
                Text("TEST PLAINTEXT")
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("TEST PLAINTEXT").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("TEST PLAINTEXT").assertExists()
        allowed.set(false)
        mainClock.advanceTimeBy(1_000)
        waitUntil(timeoutMillis = 5_000) { disposed.get() && onAllNodesWithText("TEST PLAINTEXT").fetchSemanticsNodes().isEmpty() }
        allowed.set(true)
        mainClock.advanceTimeBy(2_000)
        assertThat(onAllNodesWithText("TEST PLAINTEXT").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `unverifiable source never composes plaintext even with an unlocked trusted device`() = runAndroidComposeUiTest {
        val composed = AtomicInteger(0)
        val manager = mockk<SecureMediaLeaseManager> {
            every { isReadAuthorized(any()) } throws SecurityException("test-only authority unavailable")
        }
        val media = aLocalMedia(Uri.parse("content://test.securemedia/v1/" + "b".repeat(64)))
        setSafeContent {
            ProtectedMediaSurface(FakeProtectedPreviewAuthorizationGate(), manager, media) {
                composed.incrementAndGet()
                Text("NEVER DISPLAY")
            }
        }
        mainClock.advanceTimeBy(1_000)
        assertThat(composed.get()).isEqualTo(0)
        assertThat(onAllNodesWithText("NEVER DISPLAY").fetchSemanticsNodes()).isEmpty()
    }
}
