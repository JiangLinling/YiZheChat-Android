/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/** Diagnostic spans only. No operation, authorization, key, payload, or receipt state is stored here. */
object LifecycleStageTiming {
    private val sequence = AtomicLong()
    private val safeIdentity = Regex("[A-Za-z0-9_:$!./+#=-]{1,256}")
    private val safeStage = Regex("[a-z][a-z0-9_]{0,63}")

    suspend fun <T> measure(
        stage: String,
        identity: String = "",
        scope: String = "",
        operationId: String? = null,
        block: suspend () -> T,
    ): T {
        val context = currentCoroutineContext()
        val operation = operationId ?: context[LifecycleOperationContext]?.operationId.orEmpty()
        val span = sequence.incrementAndGet()
        val parent = 0L // Intervals may overlap; no mutable/thread-local or extra cancellable context is introduced.
        val start = System.nanoTime()
        emit("start", stage, identity, scope, operation, span, parent, start, start, "running")
        var outcome = "error"
        try {
            return block().also { outcome = "ok" }
        } catch (failure: CancellationException) {
            outcome = "cancelled"
            throw failure
        } finally {
            emit("finish", stage, identity, scope, operation, span, parent, start, System.nanoTime(), outcome)
        }
    }

    suspend fun point(stage: String, identity: String = "", scope: String = "", operationId: String? = null) {
        val context = currentCoroutineContext()
        observed(stage, identity, scope, operationId ?: context[LifecycleOperationContext]?.operationId.orEmpty())
    }

    /** Non-suspending queue boundary; an absent lifecycle remains explicitly unbound. */
    fun observed(stage: String, identity: String = "", scope: String = "", operationId: String = "") {
        val at = System.nanoTime()
        emit(
            "point",
            stage,
            identity,
            scope,
            operationId,
            sequence.incrementAndGet(),
            0L,
            at,
            at,
            "observed"
        )
    }

    private fun token(value: String): String = if (value.matches(safeIdentity)) value else "unbound"

    /** External transport callbacks already have monotonic boundaries; do not introduce a coroutine or cancellation point. */
    fun interval(stage: String, startNs: Long, endNs: Long, identity: String, scope: String, operationId: String) {
        if (endNs < startNs) return
        val span = sequence.incrementAndGet()
        emit("start", stage, identity, scope, operationId, span, 0L, startNs, startNs, "running")
        emit("finish", stage, identity, scope, operationId, span, 0L, startNs, endNs, "ok")
    }

    /** A server duration has no position on the client monotonic clock. Never fabricate start/end timestamps for it. */
    fun reportedDuration(stage: String, durationUs: Long, identity: String, scope: String, operationId: String, clock: String = "server") {
        if (durationUs !in 0..120_000_000L) return
        runCatching {
            Timber.tag("YiZheMessageStage").d(
                "phase=metric stage=%s identity=%s scope=%s operation=%s duration_us=%d clock=%s epoch_ms=%d",
                stage.takeIf { it.matches(safeStage) } ?: "invalid",
                token(identity),
                token(scope),
                token(operationId),
                durationUs,
                clock.takeIf { it in setOf("server", "derived") } ?: "unknown",
                System.currentTimeMillis(),
            )
        }
    }

    private fun emit(
        phase: String, stage: String, identity: String, scope: String, operation: String,
        span: Long, parent: Long, start: Long, end: Long, outcome: String,
    ) {
        // Logging cannot alter the product result, including cancellation and fail-closed errors.
        runCatching {
            Timber.tag("YiZheMessageStage").d(
                "phase=%s stage=%s identity=%s scope=%s operation=%s span=%d parent=%d start_ns=%d end_ns=%d duration_us=%d epoch_ms=%d outcome=%s",
                phase,
                stage.takeIf { it.matches(safeStage) } ?: "invalid",
                token(identity),
                token(scope),
                token(operation),
                span,
                parent,
                start,
                end,
                ((end - start) / 1_000L).coerceAtLeast(0),
                System.currentTimeMillis(),
                outcome,
            )
        }
    }
}
