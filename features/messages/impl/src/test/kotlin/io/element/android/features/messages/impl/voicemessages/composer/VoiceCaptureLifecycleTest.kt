package io.element.android.features.messages.impl.voicemessages.composer

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.voicerecorder.api.VoiceRecorder
import io.element.android.libraries.voicerecorder.test.FakeVoiceRecorder
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VoiceCaptureLifecycleTest {
    private val account = "a".repeat(64)
    private val device = "b".repeat(64)
    private val room = "c".repeat(64)
    private val draft = "11111111-1111-4111-a111-111111111111"

    private class CleanupRecorder : VoiceRecorder by FakeVoiceRecorder() {
        val pending = linkedSetOf<String>()
        val acknowledged = mutableListOf<String>()
        override fun pendingCaptureCleanups(): Set<String> = pending.toSet()
        override fun acknowledgeCaptureCleanup(operationId: String) {
            check(pending.remove(operationId))
            acknowledged += operationId
        }
    }

    @Test
    fun `cleanup uses original capture identity and only actual Android owners`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        coordinator.refresh(account, device)
        val recorder = CleanupRecorder()
        val lifecycle = VoiceCaptureLifecycle(recorder, coordinator, room, account)
        val id = lifecycle.operationId(draft)
        lifecycle.admit(id)
        lifecycle.completeCleanups()
        assertThat(coordinator.clientReceipts).isEmpty()
        recorder.pending += id
        assertThat(lifecycle.hasOwnedInterruptedCleanup()).isTrue()
        lifecycle.completeCleanups()
        assertThat(coordinator.startedRequests.map { it.operationId }.distinct()).containsExactly(id)
        assertThat(coordinator.startedRequests.all { it.lifecycleId == FullAppLifecycleId.LC_17 && it.trigger == "record_start" }).isTrue()
        assertThat(coordinator.clientReceipts.map { it.component }).containsExactly(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND)
        assertThat(coordinator.clientReceipts.all { it.resultCode == "VOICE_CAPTURE_CANCELLED_AND_CLEANED" }).isTrue()
        assertThat(recorder.acknowledged).containsExactly(id)
        assertThat(lifecycle.hasOwnedInterruptedCleanup()).isFalse()
    }

    @Test
    fun `failed durable enqueue retains original native cleanup for recovery`() = runTest {
        val base = FakeAppOperationalStateCoordinator()
        base.refresh(account, device)
        var reject = true
        val coordinator = object : AppOperationalStateCoordinator by base {
            override suspend fun enqueueClientReceipts(receipts: List<LifecycleClientReceipt>) {
                if (reject) error("Outbox commit rejected")
                base.enqueueClientReceipts(receipts)
            }
        }
        val recorder = CleanupRecorder()
        val lifecycle = VoiceCaptureLifecycle(recorder, coordinator, room, account)
        val id = lifecycle.operationId(draft)
        recorder.pending += id
        lifecycle.completeCleanups()
        assertThat(recorder.pending).containsExactly(id)
        assertThat(recorder.acknowledged).isEmpty()
        reject = false
        lifecycle.completeCleanups()
        assertThat(recorder.acknowledged).containsExactly(id)
    }

    @Test
    fun `another account or device cannot acknowledge a recovered capture`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        coordinator.refresh(account, device)
        val recorder = CleanupRecorder()
        val lifecycle = VoiceCaptureLifecycle(recorder, coordinator, room, account)
        val oldId = lifecycle.operationId(draft)
        recorder.pending += oldId
        coordinator.refresh(account, "d".repeat(64))
        assertThat(lifecycle.hasOwnedInterruptedCleanup()).isFalse()
        lifecycle.completeCleanups()
        assertThat(recorder.pending).containsExactly(oldId)
        assertThat(coordinator.startedRequests).isEmpty()
        assertThat(coordinator.clientReceipts).isEmpty()
    }

    @Test
    fun `normal protected handoff cleanup cannot be reported as an unsent interrupted recording`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        coordinator.refresh(account, device)
        val pending = linkedSetOf<String>()
        val recorder = object : VoiceRecorder by FakeVoiceRecorder() {
            override fun pendingCaptureCleanups(): Set<String> = pending
            override fun captureCleanupResult(operationId: String): String = "VOICE_CAPTURE_RELEASED_AND_CLEANED"
        }
        val lifecycle = VoiceCaptureLifecycle(recorder, coordinator, room, account)
        pending += lifecycle.operationId(draft)
        assertThat(lifecycle.hasOwnedInterruptedCleanup()).isFalse()
    }
}
