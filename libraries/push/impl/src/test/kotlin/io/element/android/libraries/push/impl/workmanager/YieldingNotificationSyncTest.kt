package io.element.android.libraries.push.impl.workmanager

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.push.impl.notifications.fixtures.aPushRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YieldingNotificationSyncTest {
    @Test
    fun `incoming call behind an earlier push cancels ten second cache warming`() = runTest {
        val pending = MutableStateFlow(listOf(aPushRequest().copy(eventId = "earlier")))
        var stopped = false
        val job = launch {
            runYieldingNotificationSync(pending, setOf("earlier")) {
                try { delay(10_000) } finally { stopped = true }
            }
        }
        runCurrent()
        advanceTimeBy(100)
        pending.value = listOf(aPushRequest().copy(eventId = "incoming-call"))
        runCurrent()
        assertThat(job.isCompleted).isTrue()
        assertThat(stopped).isTrue()
        assertThat(testScheduler.currentTime).isEqualTo(100)
    }

    @Test
    fun `already queued new push prevents cache warming from starting`() = runTest {
        var started = false
        runYieldingNotificationSync(MutableStateFlow(listOf(aPushRequest())), emptySet()) {
            started = true
        }
        assertThat(started).isFalse()
    }

    @Test
    fun `current batch retry does not cause cancellation and normal sync finishes`() = runTest {
        val request = aPushRequest()
        var finished = false
        runYieldingNotificationSync(MutableStateFlow(listOf(request)), setOf(request.eventId)) {
            delay(10_000)
            finished = true
        }
        assertThat(finished).isTrue()
    }

    @Test
    fun `worker cancellation releases both observer and sync`() = runTest {
        val pending = MutableStateFlow(emptyList<io.element.android.libraries.push.impl.db.PushRequest>())
        var stopped = false
        val job = launch {
            runYieldingNotificationSync(pending, emptySet()) {
                try { awaitCancellation() } finally { stopped = true }
            }
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertThat(stopped).isTrue()
        assertThat(pending.subscriptionCount.value).isEqualTo(0)
    }
}
