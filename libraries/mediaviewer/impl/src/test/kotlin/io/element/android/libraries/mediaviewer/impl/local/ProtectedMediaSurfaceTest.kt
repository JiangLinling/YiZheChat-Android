/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package io.element.android.libraries.mediaviewer.impl.local

import android.net.Uri
import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.libraries.mediaviewer.impl.R
import io.element.android.security.dataprotection.test.FakeProtectedPreviewAuthorizationGate
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.tests.testutils.setSafeContent
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectedMediaSurfaceTest {
    @Test
    fun `first source check displays checking without failure or plaintext then displays authorized content`() = runAndroidComposeUiTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val checking = context.getString(R.string.screen_media_protected_read_checking)
        val unavailable = context.getString(R.string.error_protected_media_read_unavailable)
        val manager = mockk<SecureMediaLeaseManager> {
            every { isReadAuthorized(any()) } answers {
                started.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                true
            }
        }
        val media = aLocalMedia(Uri.parse("content://test.securemedia/v1/" + "c".repeat(64)))
        try {
            setSafeContent {
                ProtectedMediaSurface(FakeProtectedPreviewAuthorizationGate(), manager, media) {
                    Text("AUTHORIZED TEST CONTENT")
                }
            }
            waitUntil(timeoutMillis = 5_000) { started.count == 0L }
            onNodeWithText(checking).assertExists()
            assertThat(onAllNodesWithText(unavailable).fetchSemanticsNodes()).isEmpty()
            assertThat(onAllNodesWithText("AUTHORIZED TEST CONTENT").fetchSemanticsNodes()).isEmpty()
            release.countDown()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("AUTHORIZED TEST CONTENT").fetchSemanticsNodes().isNotEmpty() }
            assertThat(onAllNodesWithText(checking).fetchSemanticsNodes()).isEmpty()
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a late check for the same URI cannot authorize a replacement operation`() = runAndroidComposeUiTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val checks = AtomicInteger(0)
        val replacementComposed = AtomicInteger(0)
        val gate = FakeProtectedPreviewAuthorizationGate()
        val unavailable = ApplicationProvider.getApplicationContext<Context>().getString(R.string.error_protected_media_read_unavailable)
        var media by mutableStateOf(aLocalMedia(Uri.parse("content://test.securemedia/v1/" + "d".repeat(64))))
        val manager = mockk<SecureMediaLeaseManager> {
            every { isReadAuthorized(any()) } answers {
                if (checks.incrementAndGet() == 1) {
                    started.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    returned.countDown()
                    true
                } else {
                    false
                }
            }
        }
        try {
            setSafeContent {
                ProtectedMediaSurface(gate, manager, media) {
                    if (media.lifecycleOperationId == "replacement-operation") {
                        replacementComposed.incrementAndGet()
                        Text("REPLACEMENT TEST CONTENT")
                    } else {
                        Text("ORIGINAL TEST CONTENT")
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { started.count == 0L }
            runOnIdle { media = media.copy(lifecycleOperationId = "replacement-operation") }
            mainClock.advanceTimeByFrame()
            // Admission may serialize reads. Finish the old check, then require a fresh rejection for the new operation.
            release.countDown()
            waitUntil(timeoutMillis = 5_000) { returned.count == 0L }
            waitUntil(timeoutMillis = 5_000) { checks.get() >= 2 }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(unavailable).fetchSemanticsNodes().isNotEmpty() }
            mainClock.advanceTimeBy(1_000)
            onNodeWithText(unavailable).assertExists()
            assertThat(replacementComposed.get()).isEqualTo(0)
            assertThat(onAllNodesWithText("REPLACEMENT TEST CONTENT").fetchSemanticsNodes()).isEmpty()
        } finally {
            release.countDown()
        }
    }

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
        val unavailable = ApplicationProvider.getApplicationContext<Context>().getString(R.string.error_protected_media_read_unavailable)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(unavailable).fetchSemanticsNodes().isNotEmpty() }
        assertThat(composed.get()).isEqualTo(0)
        assertThat(onAllNodesWithText("NEVER DISPLAY").fetchSemanticsNodes()).isEmpty()
    }
}
