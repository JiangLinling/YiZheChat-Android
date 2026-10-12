/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local.office

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseResult
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseState
import io.element.android.libraries.matrix.api.media.MediaFile
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FilePreviewReleaseRecoveryTest {
    @Test
    fun `failed host close retains original revoked handle and retries only that work`() {
        val closes = AtomicInteger()
        val file = TestFile(closeAction = { if (closes.incrementAndGet() == 1) throw IOException("busy") })
        val token = FilePreviewLeaseRegistry.register(OPERATION, file)
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, OPERATION, AUTHORITY))
        val first = FilePreviewLeaseRegistry.release(token, REASON)
        assertThat(first.hostHandleCloseCompleted).isFalse()
        assertThat(FilePreviewLeaseRegistry.acquire(token, OPERATION, AUTHORITY)).isNull()
        assertThrows(IllegalStateException::class.java) { lease.openInputStream() }
        val second = FilePreviewLeaseRegistry.release(token, FilePreviewLeaseReleaseReason.USER_CLOSE)
        assertThat(second.hostHandleCloseCompleted).isTrue()
        assertThat(lease.state.value).isEqualTo(FilePreviewLeaseState.Released(REASON))
        assertThat(closes.get()).isEqualTo(2)
        assertThat(FilePreviewLeaseRegistry.release(token, REASON).leaseTokenMatched).isFalse()
    }

    @Test
    fun `one failed stream remains retryable while other streams and host are closed`() {
        val firstCloses = AtomicInteger()
        val secondCloses = AtomicInteger()
        val hostCloses = AtomicInteger()
        val streams = ArrayDeque<InputStream>().apply {
            add(object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { if (firstCloses.incrementAndGet() == 1) throw IOException("busy") }
            })
            add(object : ByteArrayInputStream(byteArrayOf(2)) {
                override fun close() { secondCloses.incrementAndGet() }
            })
        }
        val token = FilePreviewLeaseRegistry.register(OPERATION, TestFile({ streams.removeFirst() }, { hostCloses.incrementAndGet() }))
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, OPERATION, AUTHORITY))
        val firstStream = lease.openInputStream()
        lease.openInputStream()
        val first = lease.release(REASON)
        assertThat(first.activeStreamsClosed).isFalse()
        assertThat(first.hostHandleCloseCompleted).isTrue()
        assertThat(secondCloses.get()).isEqualTo(1)
        assertThrows(IOException::class.java) { firstStream.read() }
        val retried = FilePreviewLeaseRegistry.release(token, REASON)
        assertThat(retried.activeStreamsClosed).isTrue()
        assertThat(firstCloses.get()).isEqualTo(2)
        assertThat(secondCloses.get()).isEqualTo(1)
        assertThat(hostCloses.get()).isEqualTo(1)
    }

    @Test
    fun `observer failure retains the same result without closing resources again`() {
        val hostCloses = AtomicInteger()
        val notifications = AtomicInteger()
        val observations = mutableListOf<FilePreviewLeaseReleaseResult>()
        val file = TestFile(closeAction = { hostCloses.incrementAndGet() })
        val token = FilePreviewLeaseRegistry.register(FilePreviewLeaseBinding(OPERATION, null, file.uriString()), file,
            onRelease = {
                observations.add(it)
                if (notifications.incrementAndGet() == 1) throw IOException("observer unavailable")
            })
        assertThrows(IOException::class.java) { FilePreviewLeaseRegistry.release(token, REASON) }
        FilePreviewLeaseRegistry.retryPendingReleases()
        assertThat(notifications.get()).isEqualTo(2)
        assertThat(observations[0]).isEqualTo(observations[1])
        assertThat(hostCloses.get()).isEqualTo(1)
        assertThat(FilePreviewLeaseRegistry.release(token, REASON).leaseTokenMatched).isFalse()
    }

    @Test
    fun `revoked in flight read cannot deliver bytes or report stream quiescence early`() {
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val hostCloses = AtomicInteger()
        val observations = mutableListOf<FilePreviewLeaseReleaseResult>()
        val raw = object : InputStream() {
            override fun read(): Int = error("array read only")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                started.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                buffer.fill(7, offset, offset + length)
                return length
            }
            override fun close() = Unit
        }
        val file = TestFile({ raw }, { hostCloses.incrementAndGet() })
        val token = FilePreviewLeaseRegistry.register(FilePreviewLeaseBinding(OPERATION, null, file.uriString()), file,
            onRelease = { synchronized(observations) { observations.add(it) } })
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, OPERATION, AUTHORITY))
        val stream = lease.openInputStream()
        val buffer = ByteArray(8)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<Boolean> {
                assertThrows(IOException::class.java) { stream.read(buffer, 0, buffer.size) }
                true
            }
            check(started.await(5, TimeUnit.SECONDS))
            assertThat(lease.release(REASON).activeStreamsClosed).isFalse()
            assertThat(FilePreviewLeaseRegistry.acquire(token, OPERATION, AUTHORITY)).isNull()
            resume.countDown()
            assertThat(read.get(5, TimeUnit.SECONDS)).isTrue()
            assertThat(buffer.toList()).containsExactlyElementsIn(List(8) { 0.toByte() })
            assertThat(synchronized(observations) { observations.map { it.activeStreamsClosed } }).containsExactly(false, true).inOrder()
            assertThat(hostCloses.get()).isEqualTo(1)
            assertThat(FilePreviewLeaseRegistry.release(token, REASON).leaseTokenMatched).isFalse()
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `raw close can wait for read exit without an owner lock deadlock`() {
        val reading = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val readerExited = CountDownLatch(1)
        val raw = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                check(closing.await(5, TimeUnit.SECONDS))
                throw IOException("revoked")
            }
            override fun close() {
                closing.countDown()
                check(readerExited.await(5, TimeUnit.SECONDS))
            }
        }
        val token = FilePreviewLeaseRegistry.register(OPERATION, TestFile({ raw }))
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, OPERATION, AUTHORITY))
        val stream = lease.openInputStream()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reader = executor.submit<Boolean> {
                try { assertThrows(IOException::class.java) { stream.read() }; true }
                finally { readerExited.countDown() }
            }
            check(reading.await(5, TimeUnit.SECONDS))
            val result = lease.release(REASON)
            assertThat(reader.get(5, TimeUnit.SECONDS)).isTrue()
            assertThat(result.activeStreamsClosed).isTrue()
        } finally {
            closing.countDown()
            readerExited.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent release cannot duplicate a host close still in progress`() {
        val closing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val calls = AtomicInteger()
        val file = TestFile(closeAction = {
            calls.incrementAndGet()
            closing.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        })
        val token = FilePreviewLeaseRegistry.register(OPERATION, file)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val release = executor.submit<FilePreviewLeaseReleaseResult> { FilePreviewLeaseRegistry.release(token, REASON) }
            check(closing.await(5, TimeUnit.SECONDS))
            assertThat(FilePreviewLeaseRegistry.release(token, REASON).hostHandleCloseCompleted).isFalse()
            resume.countDown()
            assertThat(release.get(5, TimeUnit.SECONDS).hostHandleCloseCompleted).isTrue()
            assertThat(calls.get()).isEqualTo(1)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `pending retry leaves still available owners untouched`() {
        val closes = AtomicInteger()
        val token = FilePreviewLeaseRegistry.register(OPERATION, TestFile(closeAction = { closes.incrementAndGet() }))
        FilePreviewLeaseRegistry.retryPendingReleases()
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, OPERATION, AUTHORITY))
        assertThat(closes.get()).isEqualTo(0)
        assertThat(lease.openInputStream().use { it.read() }).isEqualTo(1)
        lease.release(REASON)
    }

    private class TestFile(
        private val open: () -> InputStream = { ByteArrayInputStream(byteArrayOf(1)) },
        private val closeAction: () -> Unit = {},
    ) : MediaFile {
        override fun path(): String = error("no plaintext path")
        override fun uriString(): String = "content://$AUTHORITY/v1/${"a".repeat(64)}"
        override fun openInputStream(): InputStream = open()
        override fun persist(path: String): Boolean = false
        override fun close() = closeAction()
    }

    private companion object {
        const val OPERATION = "original-preview-operation"
        const val AUTHORITY = "com.yizhe.chat.debug.securemedia"
        val REASON = FilePreviewLeaseReleaseReason.ACCESS_REVOKED
    }
}
