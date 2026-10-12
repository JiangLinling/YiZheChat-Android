/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.voicemessages.composer

import io.element.android.libraries.voicerecorder.api.VoiceRecorder
import io.element.android.security.dataprotection.api.AppOperationalCoordinatorState
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Bridges actual operation-owned native cleanup facts into the existing durable lifecycle outbox. */
internal class VoiceCaptureLifecycle(
    private val recorder: VoiceRecorder,
    private val coordinator: AppOperationalStateCoordinator,
    private val roomIdHash: String,
    private val accountIdHash: String,
) {
    fun operationId(draftId: String): String = identityPrefix() + draftId

    private fun identityPrefix(): String {
        val snapshot = (coordinator.state.value as? AppOperationalCoordinatorState.Available)?.snapshot
            ?: error("Voice capture requires a verified operational scope.")
        check(snapshot.accountIdHash == accountIdHash) { "Voice capture account scope changed." }
        return "lc17-capture-${accountIdHash.take(16)}-${snapshot.matrixDeviceIdHash.take(16)}-"
    }

    suspend fun admit(operationId: String) {
        coordinator.start(request(operationId))
    }

    fun hasOwnedInterruptedCleanup(): Boolean = runCatching {
        val prefix = identityPrefix()
        recorder.pendingCaptureCleanups().any {
            it.startsWith(prefix) && recorder.captureCleanupResult(it) in setOf(
                "VOICE_CAPTURE_CANCELLED_AND_CLEANED", "VOICE_CAPTURE_FAILED_AND_CLEANED",
            )
        }
    }.getOrDefault(false)

    /** Called after composition observed a stopped recorder and its audio-focus owner was released. */
    suspend fun completeCleanups() {
        for (operationId in recorder.pendingCaptureCleanups()) {
            try {
                if (!operationId.startsWith(identityPrefix())) continue
                // Idempotent admission also recovers an intent whose process died before the response.
                val operation = coordinator.start(request(operationId))
                check(operation.lifecycleId == FullAppLifecycleId.LC_17 && operation.subjects.roomIdHash == roomIdHash)
                val now = System.currentTimeMillis()
                val receipts = listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND).map { component ->
                    LifecycleClientReceipt(
                        operationId = operationId,
                        component = component,
                        phase = LifecyclePhase.CLIENT_APPLIED,
                        resultCode = recorder.captureCleanupResult(operationId),
                        receiptReference = "android:voice-capture-cleanup:$operationId:${component.name.lowercase()}",
                        occurredAtEpochMillis = now,
                    )
                }
                withContext(NonCancellable) {
                    coordinator.enqueueClientReceipts(receipts)
                    recorder.acknowledgeCaptureCleanup(operationId)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                // Keep the original identity until genuine cleanup facts are durably queued.
                Timber.w(failure, "Voice capture cleanup receipt remains pending")
            }
        }
    }

    private fun request(operationId: String): io.element.android.security.dataprotection.api.LifecycleOperationRequest {
        check(operationId.startsWith(identityPrefix())) { "Voice capture device scope changed." }
        val snapshot = (coordinator.state.value as AppOperationalCoordinatorState.Available).snapshot
        return lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_17,
            trigger = "record_start",
            operationId = operationId,
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = snapshot.matrixDeviceIdHash,
            roomIdHash = roomIdHash,
        )
    }
}
