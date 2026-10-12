/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.messagecomposer

import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.mediapickers.api.RecentImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun queryRecentImageSuggestions(
    scope: CoroutineScope,
    readCandidates: suspend () -> List<RecentImage>,
    claimCandidates: suspend (List<RecentImage>) -> List<RecentImage>,
): List<RecentImage> {
    // A ContentResolver query may ignore cancellation. Await a sibling job so the
    // panel's deadline does not wait for that query to finish. This job has no UI
    // or acknowledgement side effects; a late result can never consume a batch.
    val lookup = scope.async {
        runCatchingExceptions { readCandidates() }.getOrDefault(emptyList())
    }
    return try {
        runCatchingExceptions {
            withTimeoutOrNull(250L) { claimCandidates(lookup.await()) }.orEmpty()
        }.getOrDefault(emptyList())
    } finally {
        lookup.cancel()
    }
}
