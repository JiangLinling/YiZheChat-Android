/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.clipboard

/**
 * Wrapper class for handling clipboard operations so it can be used in JVM environments.
 */
interface ClipboardHelper {
    /** Returns false when the current data-protection policy rejects the write. */
    fun copyPlainText(text: String): Boolean

    /**
     * Writes plaintext derived from an authenticated protected-content projection.
     *
     * Callers must pass the exact source authorization which produced [text]. Missing source
     * authorization deliberately fails closed until the Matrix projection layer supplies it.
     */
    fun copyProtectedPlainText(text: String, source: ProtectedClipboardSource): Boolean = false
}

/** Process-local ownership metadata. It is never persisted or written to Android's clipboard. */
data class ProtectedClipboardSource(
    val sessionId: String,
    val roomId: String,
    val eventId: String,
    val contentVersion: String,
    val contentIds: Set<String>,
    val authorizationRevision: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(sessionId.isNotBlank())
        require(roomId.isNotBlank())
        require(eventId.isNotBlank())
        require(contentVersion.isNotBlank())
        require(contentIds.isNotEmpty() && contentIds.none(String::isBlank))
        require(authorizationRevision >= 0L)
        require(expiresAtEpochMillis > 0L)
    }
}
