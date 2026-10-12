/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.clipboard

import androidx.annotation.VisibleForTesting
import io.element.android.security.dataprotection.api.RuntimeDataProtectionPolicy
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * A process-local clipboard for moving text between YiZheChat screens without exposing it to Android's system clipboard.
 *
 * Values never leave this process, expire after a short period and are also cleared whenever the app locks or leaves
 * the foreground. This store deliberately keeps no persistent representation of copied text.
 */
object InAppClipboard {
    private data class Entry(
        val text: String,
        val token: ClipboardWriteToken,
        val protectedSource: ProtectedClipboardSource?,
        val expiresAtNanos: Long,
        val enforceRuntimePolicyOnRead: Boolean,
    )

    @Volatile
    private var entry: Entry? = null
    private val invalidatedSessionRevisions = mutableMapOf<String, Long>()

    fun copyPlainText(text: CharSequence): Boolean = commitPlainText(
        text = text,
        enforceRuntimePolicyOnRead = true,
    ) != null

    /** JVM tests do not install the application-scoped policy provider. Never use from production code. */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun copyPlainTextForTest(text: CharSequence): Boolean = commitPlainText(
        text = text,
        clipboardAllowed = { true },
    ) != null

    @Synchronized
    internal fun commitPlainText(
        text: CharSequence,
        protectedSource: ProtectedClipboardSource? = null,
        authorizationRevision: () -> Long = { protectedSource?.authorizationRevision ?: 0L },
        nowEpochMillis: Long = System.currentTimeMillis(),
        nowNanos: Long = System.nanoTime(),
        clipboardAllowed: () -> Boolean = RuntimeDataProtectionPolicy::allowsClipboard,
        enforceRuntimePolicyOnRead: Boolean = false,
    ): ClipboardWriteToken? {
        if (!clipboardAllowed()) return null
        val ttlNanos = if (protectedSource == null) {
            ENTRY_TTL_NANOS
        } else {
            val invalidatedThroughRevision = invalidatedSessionRevisions[protectedSource.sessionId]
            if (invalidatedThroughRevision != null && protectedSource.authorizationRevision <= invalidatedThroughRevision) return null
            if (authorizationRevision() != protectedSource.authorizationRevision) return null
            val remainingMillis = protectedSource.expiresAtEpochMillis - nowEpochMillis
            if (remainingMillis <= 0L) return null
            minOf(ENTRY_TTL_NANOS, TimeUnit.MILLISECONDS.toNanos(remainingMillis))
        }
        val expiresAtNanos = saturatedAdd(nowNanos, ttlNanos)
        val token = ClipboardWriteToken(UUID.randomUUID(), expiresAtNanos)
        entry = Entry(
            text = text.toString(),
            token = token,
            protectedSource = protectedSource?.copy(contentIds = protectedSource.contentIds.toSet()),
            expiresAtNanos = expiresAtNanos,
            enforceRuntimePolicyOnRead = enforceRuntimePolicyOnRead,
        )
        return token
    }

    @Synchronized
    fun getPlainText(): String? = getPlainText(System.nanoTime())

    @Synchronized
    internal fun getPlainText(nowNanos: Long): String? {
        val current = entry ?: return null
        if ((current.enforceRuntimePolicyOnRead && !RuntimeDataProtectionPolicy.allowsClipboard()) || nowNanos >= current.expiresAtNanos) {
            entry = null
            return null
        }
        return current.text
    }

    fun hasPlainText(): Boolean = getPlainText() != null

    @Synchronized
    fun clear() {
        entry = null
    }

    @Synchronized
    internal fun clear(token: ClipboardWriteToken) {
        if (entry?.token == token) entry = null
    }

    @Synchronized
    internal fun isCurrent(token: ClipboardWriteToken): Boolean = entry?.token == token

    @Synchronized
    fun clearForSession(sessionId: String, throughAuthorizationRevision: Long) {
        require(throughAuthorizationRevision >= 0L)
        val previousRevision = invalidatedSessionRevisions[sessionId]
        invalidatedSessionRevisions[sessionId] = previousRevision?.let { maxOf(it, throughAuthorizationRevision) }
            ?: throughAuthorizationRevision
        val source = entry?.protectedSource
        if (source?.sessionId == sessionId && source.authorizationRevision <= throughAuthorizationRevision) entry = null
    }

    @Synchronized
    internal fun clearForContent(contentId: String) {
        if (entry?.protectedSource?.contentIds?.contains(contentId) == true) entry = null
    }

    @Synchronized
    internal fun clearProtectedBeforeAuthorizationRevision(revision: Long) {
        val source = entry?.protectedSource ?: return
        if (source.authorizationRevision < revision) entry = null
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    @Synchronized
    internal fun resetSessionInvalidationsForTest() {
        invalidatedSessionRevisions.clear()
    }

    private fun saturatedAdd(value: Long, increment: Long): Long =
        if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment

    private val ENTRY_TTL_NANOS = TimeUnit.MINUTES.toNanos(5)
}

internal data class ClipboardWriteToken(
    val operationId: UUID,
    val expiresAtNanos: Long,
)
