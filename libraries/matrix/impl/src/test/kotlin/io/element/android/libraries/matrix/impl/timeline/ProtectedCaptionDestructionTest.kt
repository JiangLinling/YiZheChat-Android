/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.ContentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProtectedCaptionDestructionTest {
    private val first = ContentId("caption-first")
    private val second = ContentId("caption-second")

    @Test fun journalFailureStillAttemptsEveryKmsErase() = runTest {
        val journalFailure = IllegalStateException("journal unavailable")
        val attempted = mutableListOf<ContentId>()
        val thrown = expectSuspendFailure<IllegalStateException> {
            destroyContentIdsRetainingLineageDebt(
                contentIds = linkedSetOf(first, second),
                markDestructionPending = { if (it == first) throw journalFailure },
                destroyContentAccess = { attempted += it; true },
            )
        }
        assertThat(thrown).isSameInstanceAs(journalFailure)
        assertThat(attempted).containsExactly(first, second).inOrder()
    }

    @Test fun kmsFailureDoesNotSkipLaterContentOrClaimSuccess() = runTest {
        val kmsFailure = IllegalStateException("KMS unavailable")
        val attempted = mutableListOf<ContentId>()
        val thrown = expectSuspendFailure<IllegalStateException> {
            destroyContentIdsRetainingLineageDebt(
                contentIds = linkedSetOf(first, second),
                markDestructionPending = {},
                destroyContentAccess = {
                    attempted += it
                    if (it == first) throw kmsFailure
                    true
                },
            )
        }
        assertThat(thrown).isSameInstanceAs(kmsFailure)
        assertThat(attempted).containsExactly(first, second).inOrder()
        val refused = destroyContentIdsRetainingLineageDebt(
            contentIds = setOf(first),
            markDestructionPending = {},
            destroyContentAccess = { false },
        )
        assertThat(refused).isFalse()
    }

    @Test fun cancellationStillAttemptsEraseAndPropagates() = runTest {
        val cancellation = CancellationException("journal cancelled")
        val attempted = mutableListOf<ContentId>()
        val thrown = expectSuspendFailure<CancellationException> {
            destroyContentIdsRetainingLineageDebt(
                contentIds = linkedSetOf(first, second),
                markDestructionPending = { if (it == first) throw cancellation },
                destroyContentAccess = { attempted += it; true },
            )
        }
        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(attempted).containsExactly(first, second).inOrder()
    }
}

private suspend inline fun <reified T : Throwable> expectSuspendFailure(block: suspend () -> Unit): T {
    try {
        block()
    } catch (failure: Throwable) {
        if (failure is T) return failure
        throw AssertionError("Expected " + T::class.java.name + " but got " + failure::class.java.name, failure)
    }
    throw AssertionError("Expected " + T::class.java.name + " to be thrown")
}