/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.messagecomposer

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecentImageSuggestionsTest {
    @Test fun `non cancellable query cannot delay panel or acknowledge a late result`() = runTest {
        var readsFinished = false
        var claims = 0
        val result = async {
            queryRecentImageSuggestions(backgroundScope, {
                withContext(NonCancellable) { delay(5_000L) }
                readsFinished = true
                emptyList()
            }, { claims++; it })
        }
        runCurrent()
        advanceTimeBy(251L)
        runCurrent()
        assertTrue(result.isCompleted)
        assertTrue(result.await().isEmpty())
        assertFalse(readsFinished)
        assertEquals(0, claims)
        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue(readsFinished)
        assertEquals(0, claims)
    }

    @Test fun `dismissal cancels awaiting without waiting for system query`() = runTest {
        var claims = 0
        val result = async {
            queryRecentImageSuggestions(backgroundScope, {
                withContext(NonCancellable) { delay(5_000L) }
                emptyList()
            }, { claims++; it })
        }
        runCurrent()
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
        assertEquals(0, claims)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(0, claims)
    }

    @Test fun `provider failure does not cancel the composer scope`() = runTest {
        val result = queryRecentImageSuggestions(backgroundScope, {
            throw SecurityException("permission revoked")
        }, { it })
        assertTrue(result.isEmpty())
        assertTrue(backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test fun `storage failure falls back to normal attachments`() = runTest {
        val result = queryRecentImageSuggestions(backgroundScope, { emptyList() }, {
            throw java.io.IOException("storage unavailable")
        })
        assertTrue(result.isEmpty())
    }
}
