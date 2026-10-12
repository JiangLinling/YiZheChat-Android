/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.clipboard

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.util.concurrent.TimeUnit

class InAppClipboardTest {
    @After
    fun tearDown() {
        InAppClipboard.clear()
        InAppClipboard.resetSessionInvalidationsForTest()
    }

    @Test
    fun `copied text remains available inside the app process`() {
        InAppClipboard.copyPlainTextForTest("temporary text")

        assertThat(InAppClipboard.hasPlainText()).isTrue()
        assertThat(InAppClipboard.getPlainText()).isEqualTo("temporary text")
    }

    @Test
    fun `clearing removes copied text`() {
        InAppClipboard.copyPlainTextForTest("temporary text")

        InAppClipboard.clear()

        assertThat(InAppClipboard.hasPlainText()).isFalse()
        assertThat(InAppClipboard.getPlainText()).isNull()
    }

    @Test
    fun `policy rejection does not overwrite the current entry`() {
        InAppClipboard.commitPlainText("current", clipboardAllowed = { true })

        val rejected = InAppClipboard.commitPlainText("rejected", clipboardAllowed = { false })

        assertThat(rejected).isNull()
        assertThat(InAppClipboard.getPlainText()).isEqualTo("current")
    }

    @Test
    fun `protected write rejects stale authorization revision without overwriting`() {
        InAppClipboard.commitPlainText("current", clipboardAllowed = { true })

        val rejected = commitProtected(currentAuthorizationRevision = 8L)

        assertThat(rejected).isNull()
        assertThat(InAppClipboard.getPlainText()).isEqualTo("current")
    }

    @Test
    fun `protected write expiry is capped by source expiry`() {
        val token = commitProtected(
            source = protectedSource(expiresAtEpochMillis = 3_000L),
            nowNanos = 10L,
        )

        assertThat(token).isNotNull()
        assertThat(token!!.expiresAtNanos).isEqualTo(10L + TimeUnit.SECONDS.toNanos(2L))
        assertThat(InAppClipboard.getPlainText(token.expiresAtNanos - 1L)).isEqualTo("protected")
        assertThat(InAppClipboard.getPlainText(token.expiresAtNanos)).isNull()
    }

    @Test
    fun `expired protected source is rejected`() {
        val token = commitProtected(source = protectedSource(expiresAtEpochMillis = 1_000L))

        assertThat(token).isNull()
        assertThat(InAppClipboard.hasPlainText()).isFalse()
    }

    @Test
    fun `late expiry for an old operation cannot clear a newer write`() {
        val oldToken = InAppClipboard.commitPlainText("old", clipboardAllowed = { true })!!
        InAppClipboard.commitPlainText("new", clipboardAllowed = { true })

        InAppClipboard.clear(oldToken)

        assertThat(InAppClipboard.getPlainText()).isEqualTo("new")
    }

    @Test
    fun `account cleanup cannot clear another account or a newer generic write`() {
        commitProtected(source = protectedSource(sessionId = "session-a"))

        InAppClipboard.clearForSession("session-b", throughAuthorizationRevision = 7L)
        assertThat(InAppClipboard.getPlainText()).isEqualTo("protected")

        InAppClipboard.commitPlainText("new user text", clipboardAllowed = { true })
        InAppClipboard.clearForSession("session-a", throughAuthorizationRevision = 7L)
        assertThat(InAppClipboard.getPlainText()).isEqualTo("new user text")
    }

    @Test
    fun `removed account fences its old authorization revision but allows a newer revision`() {
        InAppClipboard.clearForSession("session-a", throughAuthorizationRevision = 7L)

        val stale = commitProtected(
            text = "stale",
            source = protectedSource(sessionId = "session-a", authorizationRevision = 7L),
        )
        assertThat(stale).isNull()

        val current = commitProtected(
            text = "current",
            source = protectedSource(sessionId = "session-a", authorizationRevision = 8L),
            currentAuthorizationRevision = 8L,
        )
        assertThat(current).isNotNull()
        assertThat(InAppClipboard.getPlainText()).isEqualTo("current")
    }

    @Test
    fun `late account cleanup cannot clear a newer authorization revision for the same account`() {
        commitProtected(
            text = "new session",
            source = protectedSource(sessionId = "session-a", authorizationRevision = 8L),
            currentAuthorizationRevision = 8L,
        )

        InAppClipboard.clearForSession("session-a", throughAuthorizationRevision = 7L)

        assertThat(InAppClipboard.getPlainText()).isEqualTo("new session")
    }

    private fun protectedSource(
        sessionId: String = "session-a",
        contentIds: Set<String> = setOf("content-a"),
        authorizationRevision: Long = 7L,
        expiresAtEpochMillis: Long = 10_000L,
    ) = ProtectedClipboardSource(
        sessionId = sessionId,
        roomId = "room-a",
        eventId = "event-a",
        contentVersion = "version-a",
        contentIds = contentIds,
        authorizationRevision = authorizationRevision,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    private fun commitProtected(
        text: String = "protected",
        source: ProtectedClipboardSource = protectedSource(),
        currentAuthorizationRevision: Long = source.authorizationRevision,
        nowNanos: Long = System.nanoTime(),
    ) = InAppClipboard.commitPlainText(
        text = text,
        protectedSource = source,
        authorizationRevision = { currentAuthorizationRevision },
        nowEpochMillis = 1_000L,
        nowNanos = nowNanos,
        clipboardAllowed = { true },
    )
}
