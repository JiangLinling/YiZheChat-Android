/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.clipboard

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class AndroidClipboardHelperTest {
    @After
    fun tearDown() {
        InAppClipboard.clear()
        InAppClipboard.resetSessionInvalidationsForTest()
    }

    @Test
    fun `targeted runtime invalidation clears only matching protected content`() = runTest {
        val runtime = FakeMatrixContentProtectionRuntime()
        AndroidClipboardHelper(this, runtime)
        InAppClipboard.commitPlainText(
            text = "protected",
            protectedSource = protectedSource(contentIds = setOf("content-a")),
            authorizationRevision = runtime::authorizationRevision,
            nowEpochMillis = 1_000L,
            clipboardAllowed = { true },
        )

        runtime.invalidate(ContentId("content-b"))
        assertThat(InAppClipboard.getPlainText()).isEqualTo("protected")

        runtime.invalidate(ContentId("content-a"))
        assertThat(InAppClipboard.getPlainText()).isNull()
    }

    @Test
    fun `scheduled expiry clears the matching operation without a read`() = runTest {
        val runtime = FakeMatrixContentProtectionRuntime()
        val helper = AndroidClipboardHelper(this, runtime)
        val nowEpochMillis = System.currentTimeMillis()
        val token = InAppClipboard.commitPlainText(
            text = "temporary",
            protectedSource = protectedSource(
                contentIds = setOf("content-a"),
                expiresAtEpochMillis = nowEpochMillis + 100L,
            ),
            authorizationRevision = runtime::authorizationRevision,
            nowEpochMillis = nowEpochMillis,
            nowNanos = System.nanoTime(),
            clipboardAllowed = { true },
        )!!

        helper.scheduleExpiry(token)
        advanceTimeBy(101L)
        runCurrent()

        assertThat(InAppClipboard.getPlainText()).isNull()
    }

    @Test
    fun `late scheduling of an old operation cannot cancel the current expiry`() = runTest {
        val runtime = FakeMatrixContentProtectionRuntime()
        val helper = AndroidClipboardHelper(this, runtime)
        val oldToken = InAppClipboard.commitPlainText("old", clipboardAllowed = { true })!!
        val nowEpochMillis = System.currentTimeMillis()
        val currentToken = InAppClipboard.commitPlainText(
            text = "current",
            protectedSource = protectedSource(
                contentIds = setOf("content-b"),
                expiresAtEpochMillis = nowEpochMillis + 100L,
            ),
            authorizationRevision = runtime::authorizationRevision,
            nowEpochMillis = nowEpochMillis,
            nowNanos = System.nanoTime(),
            clipboardAllowed = { true },
        )!!

        helper.scheduleExpiry(currentToken)
        helper.scheduleExpiry(oldToken)
        advanceTimeBy(101L)
        runCurrent()

        assertThat(InAppClipboard.getPlainText()).isNull()
    }

    @Test
    fun `scheduling a new operation replaces the already scheduled old expiry`() = runTest {
        val runtime = FakeMatrixContentProtectionRuntime()
        val helper = AndroidClipboardHelper(this, runtime)
        val oldToken = InAppClipboard.commitPlainText("old", clipboardAllowed = { true })!!
        helper.scheduleExpiry(oldToken)

        val nowEpochMillis = System.currentTimeMillis()
        val currentToken = InAppClipboard.commitPlainText(
            text = "current",
            protectedSource = protectedSource(
                contentIds = setOf("content-b"),
                expiresAtEpochMillis = nowEpochMillis + 100L,
            ),
            authorizationRevision = runtime::authorizationRevision,
            nowEpochMillis = nowEpochMillis,
            nowNanos = System.nanoTime(),
            clipboardAllowed = { true },
        )!!
        helper.scheduleExpiry(currentToken)
        advanceTimeBy(101L)
        runCurrent()

        assertThat(InAppClipboard.getPlainText()).isNull()
    }

    private fun protectedSource(
        contentIds: Set<String>,
        expiresAtEpochMillis: Long = 10_000L,
    ) = ProtectedClipboardSource(
        sessionId = "session-a",
        roomId = "room-a",
        eventId = "event-a",
        contentVersion = "version-a",
        contentIds = contentIds,
        authorizationRevision = 7L,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    private class FakeMatrixContentProtectionRuntime : MatrixContentProtectionRuntime {
        private var contentClearer: ((ContentId) -> Unit)? = null

        override fun attach() = Unit

        override fun detach() = Unit

        override fun isConnected(): Boolean = true

        override fun authorizationRevision(): Long = 7L

        override fun registerSensitiveContentClearer(clearer: (ContentId) -> Unit) {
            contentClearer = clearer
        }

        fun invalidate(contentId: ContentId) {
            contentClearer?.invoke(contentId)
        }
    }
}
