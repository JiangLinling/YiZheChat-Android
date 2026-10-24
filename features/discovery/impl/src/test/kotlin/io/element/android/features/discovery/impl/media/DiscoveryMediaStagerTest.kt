/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.media

import io.element.android.security.securestorage.api.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryMediaStagerTest {
    private val request = SecureObjectWriteRequest(SecureObjectId("object"), AccountVaultId("vault"), SecureObjectPurpose.IMAGE,
        byteArrayOf(1), expectedPlaintextLength = 3)

    @Test fun `verified handoff always retires encrypted staging`() = runTest {
        val store = RecordingStaging()
        var consumed = false
        withDiscoveryMediaStaging(store, request, { it.write(byteArrayOf(1, 2, 3)) }) {
            assertEquals(3L, it.plaintextLength)
            consumed = true
        }
        assertTrue(consumed)
        assertEquals(1, store.transaction.aborts)
        assertEquals(0, store.transaction.commits)
    }

    @Test fun `download or integrity failure never reaches the publisher`() = runTest {
        val store = RecordingStaging()
        var consumed = false
        val failure = runCatching {
            withDiscoveryMediaStaging(store, request, { it.write(1); error("digest mismatch") }) { consumed = true }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(store.failedStageCleaned)
        assertFalse(consumed)
    }

    @Test fun `cancellation at ownership publication still aborts the returned transaction`() = runTest {
        lateinit var job: Job
        val store = RecordingStaging(afterStage = { job.cancel() })
        var consumed = false
        job = launch(start = CoroutineStart.LAZY) {
            withDiscoveryMediaStaging(store, request, { it.write(byteArrayOf(1, 2, 3)) }) { consumed = true }
        }
        job.start(); job.join()
        assertFalse(consumed)
        assertEquals(1, store.transaction.aborts)
    }

    @Test fun `incomplete staging cleanup preserves the original failure`() = runTest {
        val store = RecordingStaging(cleanupComplete = false)
        val original = IllegalStateException("consumer failed")
        val failure = runCatching {
            withDiscoveryMediaStaging(store, request, { it.write(byteArrayOf(1, 2, 3)) }) { throw original }
        }.exceptionOrNull()
        assertTrue(failure is SecureMediaCleanupException)
        assertSame(original, failure?.cause)
    }
}

private class RecordingStaging(private val cleanupComplete: Boolean = true, private val afterStage: () -> Unit = {}) : SecureObjectStagingStore {
    lateinit var transaction: RecordingTransaction
    var failedStageCleaned = false
    override suspend fun stage(request: SecureObjectWriteRequest, writePlaintext: suspend (OutputStream) -> Unit): SecureObjectStagingTransaction {
        val bytes = ByteArrayOutputStream()
        try { writePlaintext(bytes) } catch (failure: Throwable) { failedStageCleaned = true; throw failure }
        transaction = RecordingTransaction(request, bytes.size().toLong(), cleanupComplete)
        afterStage()
        return transaction
    }
}

private class RecordingTransaction(override val request: SecureObjectWriteRequest, override val plaintextLength: Long,
    private val cleanupComplete: Boolean) : SecureObjectStagingTransaction {
    override val transactionId = "test-staging"
    var aborts = 0
    var commits = 0
    override suspend fun commit(): SecureObjectReference { commits++; error("No unapproved lease publication in this test") }
    override suspend fun abort(): SecureObjectStagingCleanupReport {
        aborts++
        return SecureObjectStagingCleanupReport(transactionId, listOf(SecureObjectStagingCleanupItem("encrypted-staging", cleanupComplete)))
    }
}
