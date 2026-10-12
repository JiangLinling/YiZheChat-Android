/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.security.contentprotection.api.ContentProtectionException

/** A transaction/local-list identity is not a committed Matrix receive authority. */
internal suspend fun <T> withProtectedReceiveLifecycle(
    eventId: String?,
    isOwn: Boolean,
    isRemote: Boolean,
    receive: suspend (String) -> T,
    pendingLocalEcho: suspend () -> T,
): T = if (eventId != null) {
    require(eventId.isNotBlank())
    receive(eventId)
} else {
    if (!isOwn || isRemote) throw ContentProtectionException.AccessDenied()
    pendingLocalEcho()
}

/** Unconfirmed sender echoes can only use the existing short-lived sender payload. */
internal suspend fun protectedReceivePayload(
    pendingPayload: ByteArray?,
    pendingLocalOnly: Boolean,
    release: suspend () -> ByteArray,
): ByteArray = pendingPayload ?: if (pendingLocalOnly) {
    throw ContentProtectionException.AccessDenied()
} else {
    release()
}
