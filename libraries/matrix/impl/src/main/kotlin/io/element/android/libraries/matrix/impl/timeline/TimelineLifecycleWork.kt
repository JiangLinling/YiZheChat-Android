/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleStageTiming
import io.element.android.security.dataprotection.api.LifecyclePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/** Transport bookkeeping only: no plaintext, keys, or authorization decisions are cached here. */
internal class TimelineLifecycleWork(private val now: () -> Long) {
    private class Receive {
        val mutex = Mutex()
        var started = false
        val receipts = mutableSetOf<LifecycleComponent>()
        var retryAt = 0L
        @Volatile var completed = false
        @Volatile var foregroundRequested = false
    }
    private data class Registration(
        var running: Boolean = false,
        var done: Boolean = false,
        var retryAt: Long = 0,
        var failures: Int = 0,
        var completion: CompletableDeferred<Boolean>? = null,
    )
    private val receives = LinkedHashMap<String, Receive>()
    private val registrations = LinkedHashMap<String, Registration>()
    private val originatingOperations = LinkedHashMap<String, LifecycleOperationContext>()
    private val transport = TimelineTransportScheduler()
    private val backgroundTransport = Semaphore(1)
    private val historicalRegistration = Semaphore(1)

    suspend fun <T> receive(
        coordinator: AppOperationalStateCoordinator,
        request: LifecycleOperationRequest,
        isForeground: Boolean = true,
        block: suspend () -> T,
    ): T {
        val state = synchronized(receives) {
            // Never evict work in progress. Completed entries contain only opaque operation IDs.
            if (request.operationId !in receives && receives.size >= CAPACITY) {
                receives.entries.firstOrNull { it.value.completed }?.let { receives.remove(it.key) }
                if (receives.size >= CAPACITY) throw IOException("Timeline lifecycle work capacity is awaiting completion.")
            }
            receives.getOrPut(request.operationId) { Receive() }
        }
        if (isForeground) {
            state.foregroundRequested = true
            transport.promoteReceive(request.operationId)
        }
        val startWaitAt = System.nanoTime()
        state.mutex.withLock {
            if (!state.started) {
                checkRetry(state.retryAt)
                transportAttempt(state, request.operationId) {
                    val started = coordinator.start(request)
                    check(started.operationId == request.operationId && started.lifecycleId == request.lifecycleId)
                    state.started = true
                }
            }
        }
        trace(request.operationId, "receive_admission", startWaitAt)
        LifecycleStageTiming.point("receive_operation_bind", request.eventIdHash.orEmpty(), operationId = request.operationId)
        // Always rerun the actual protected read/registration, including current Vault/KMS checks.
        // Do not hold the transport mutex here: decryption can depend on concurrent event registration.
        return withContext(LifecycleOperationContext(request.operationId, request.lifecycleId)) {
            val readAt = System.nanoTime()
            val result = LifecycleStageTiming.measure("receive_protected_read", request.eventIdHash.orEmpty()) { block() }
            trace(request.operationId, "protected_read", readAt)
            val receiptAt = System.nanoTime()
            withContext(NonCancellable) {
                state.mutex.withLock {
                    val components = listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND)
                        .filterNot { it in state.receipts }
                    if (components.isNotEmpty()) {
                        coordinator.enqueueClientReceipts(components.map { component ->
                            LifecycleClientReceipt(
                                request.operationId, component, LifecyclePhase.CLIENT_APPLIED,
                                "${component.name}_APPLIED", "${component.name.lowercase().replace('_', '-')}:${request.operationId}", now(),
                            )
                        })
                        state.receipts += components
                    }
                    // Transport ownership has moved to the coordinator's durable queue. This
                    // only releases local bookkeeping capacity; the server operation stays pending.
                    state.completed = state.receipts.size == 2
                }
            }
            trace(request.operationId, "receipt_enqueue", receiptAt)
            result
        }
    }

    @Synchronized
    fun claimRegistration(key: String): Boolean {
        if (key !in registrations && registrations.size >= CAPACITY) {
            registrations.entries.firstOrNull { it.value.done || !it.value.running && now() >= it.value.retryAt }
                ?.let { registrations.remove(it.key) }
            if (registrations.size >= CAPACITY) return false
        }
        val state = registrations.getOrPut(key) { Registration() }
        if (state.running || state.done || now() < state.retryAt) return false
        state.running = true
        state.completion = CompletableDeferred()
        return true
    }

    @Synchronized
    fun finishRegistration(key: String, success: Boolean) {
        val state = checkNotNull(registrations[key])
        state.running = false
        state.done = success
        state.completion?.complete(success)
        if (!success) {
            state.failures = (state.failures + 1).coerceAtMost(5)
            state.retryAt = now() + (2_000L shl state.failures).coerceAtMost(60_000L)
        }
    }

    @Synchronized
    fun abortRegistration(key: String) {
        if (registrations[key]?.running == true) finishRegistration(key, success = false)
    }

    /** Join the real mapping without holding a transport permit or cancelling its producer. */
    suspend fun awaitRegistration(key: String): Boolean? {
        val completion = synchronized(this) { registrations[key]?.completion } ?: return null
        return completion.await()
    }

    suspend fun <T> registrationTransport(
        isForeground: Boolean = true,
        isLiveSend: Boolean = false,
        block: suspend () -> T,
    ): T = if (isLiveSend) transport.withPermit(isLiveSend = true, block = block) else shortTransport(isForeground, block)

    // Background waits outside the shared semaphore, leaving one physical slot for current reads.
    private suspend fun <T> shortTransport(isForeground: Boolean, block: suspend () -> T): T =
        if (isForeground) transport.withPermit { block() }
        else backgroundTransport.withPermit { transport.withPermit { block() } }

    /**
     * A restored timeline can schedule hundreds of old event mappings at once. Admit only one
     * historical repair before it starts/joins a receive operation, so it cannot populate the
     * shared transport's FIFO ahead of current reads. A persisted originating send operation is
     * live work and bypasses this repair gate, but still uses the same two transport permits.
     * This gate owns scheduling only; every operation, registration and KMS check still runs.
     */
    suspend fun <T> registrationWork(isLiveSend: Boolean, block: suspend () -> T): T =
        if (isLiveSend) block() else historicalRegistration.withPermit { block() }

    // Room summaries and room timelines have distinct adapter instances. The existing shared work
    // owner must retain the originating operation hint for both, otherwise the summary can claim a
    // new send as historical repair before its sending adapter observes the Matrix event. Keys bind
    // account, Matrix device, room and content; this is bookkeeping, never a grant or CEK cache.
    @Synchronized
    fun rememberOriginatingOperation(scopeKey: String, operation: LifecycleOperationContext) {
        if (scopeKey !in originatingOperations && originatingOperations.size >= CAPACITY) {
            originatingOperations.keys.firstOrNull()?.let(originatingOperations::remove)
        }
        originatingOperations[scopeKey] = operation
    }

    @Synchronized
    fun originatingOperation(scopeKey: String): LifecycleOperationContext? = originatingOperations[scopeKey]

    @Synchronized
    fun forgetOriginatingOperation(scopeKey: String, expected: LifecycleOperationContext?) {
        if (expected != null && originatingOperations[scopeKey] == expected) originatingOperations.remove(scopeKey)
    }

    @Synchronized
    fun clearOriginatingOperations() = originatingOperations.clear()

    private suspend fun transportAttempt(state: Receive, operationId: String, block: suspend () -> Unit) {
        val waitAt = System.nanoTime()
        try {
            // Keep only one background receive in a physical slot, but keep waiting admissions
            // promotable when a visible reader joins the SAME operation. The owning read still
            // uses its original release intent and every normal KMS authorization check.
            transport.withPermit(receiveIdentity = operationId, foregroundRequested = { state.foregroundRequested }) {
                trace(operationId, "transport_wait", waitAt)
                val httpAt = System.nanoTime()
                block()
                trace(operationId, "start_http", httpAt)
            }
            state.retryAt = 0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            state.retryAt = now() + 30_000L
            throw failure
        }
    }

    private fun checkRetry(retryAt: Long) {
        if (now() < retryAt) throw IOException("Timeline lifecycle transport is awaiting retry.")
    }

    private fun trace(operationId: String, stage: String, startedAt: Long) {
        Timber.tag("YiZheTimelinePerf").d(
            "stage=lifecycle_%s operation=%s duration_ms=%d", stage, operationId,
            (System.nanoTime() - startedAt) / 1_000_000L,
        )
    }

    private companion object { const val CAPACITY = 4096 }
}
