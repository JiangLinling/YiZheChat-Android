/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Independent reads only; callers still validate both results before protecting or enqueueing content. */
internal suspend fun <Room, Recipients> readProtectedSendPrerequisites(
    resolveRoom: suspend () -> Room,
    resolveRecipients: suspend () -> Recipients,
): Pair<Room, Recipients> = coroutineScope {
    // Both children retain the originating lifecycle context. Failure/cancellation owns both
    // reads and cannot leave a producer running outside the original send's lifetime.
    val room = async { resolveRoom() }
    val recipients = async { resolveRecipients() }
    room.await() to recipients.await()
}
