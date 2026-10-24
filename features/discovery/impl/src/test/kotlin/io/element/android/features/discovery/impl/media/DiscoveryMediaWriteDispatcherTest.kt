/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.media

import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryMediaWriteDispatcherTest {
    @Test fun `encrypted write leaves caller thread and cancellation waits for byte ownership to return`() = runTest {
        val caller = Thread.currentThread()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = byteArrayOf(1, 2, 3)
        val reference = DiscoveryMediaReference("1", DiscoveryMediaBinding.ARTICLE_MEDIA, "2", DiscoveryMediaAccess.PRIVATE,
            DiscoveryMediaKind.IMAGE, "image/png", 3, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val integrity = DiscoveryMediaIntegrity(reference)
        var writerThread: Thread? = null
        val job = launch {
            try {
                writeDiscoveryMediaChunk(integrity, reference.range(0, 3), bytes, object : OutputStream() {
                    override fun write(value: Int) = error("Expected bounded array write")
                    override fun write(value: ByteArray) {
                        writerThread = Thread.currentThread()
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        assertArrayEquals(byteArrayOf(1, 2, 3), value)
                    }
                })
            } finally { bytes.fill(0); integrity.close() }
        }
        testScheduler.runCurrent()
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertNotSame(caller, writerThread)
            job.cancel()
            testScheduler.runCurrent()
            assertFalse(job.isCompleted)
            assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
        } finally { release.countDown(); job.join() }
        assertTrue(job.isCancelled)
        assertArrayEquals(ByteArray(3), bytes)
    }
}
