/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.mall

import io.element.android.features.discovery.impl.actions.DiscoveryMallResultLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

internal enum class MallOperationPhase { CHECKING, READY, SUBMITTING, UNCERTAIN, CONFIRMED, RESULT, FAILED }
internal data class MallOperationState(val phase: MallOperationPhase = MallOperationPhase.CHECKING,
    val reason: String? = null, val locator: DiscoveryMallResultLocator? = null, val result: MallConfirmation? = null)

/** Page memory is disposable; original commands and minimal confirmed locators live in the account Vault. */
internal class DiscoveryMallOperationController(private val port: DiscoveryMallPort) {
    private val mutable = MutableStateFlow(MallOperationState())
    val state = mutable.asStateFlow()
    private val busy = Mutex()
    private var closed = false

    suspend fun recover() = exclusively {
        mutable.value = state.value.copy(phase = MallOperationPhase.CHECKING)
        try { accept(port.recover())
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (!closed) mutable.value = state.value.copy(phase = MallOperationPhase.UNCERTAIN) }
    }

    suspend fun submit(action: suspend () -> MallMutation) = exclusively {
        if (state.value.phase != MallOperationPhase.READY) return@exclusively
        mutable.value = MallOperationState(MallOperationPhase.SUBMITTING)
        try { accept(action())
        } catch (cancelled: CancellationException) { uncertain(); throw cancelled
        } catch (_: Exception) { uncertain() }
    }

    suspend fun refreshResult() = exclusively {
        val locator = state.value.locator ?: return@exclusively
        mutable.value = state.value.copy(phase = MallOperationPhase.CONFIRMED, result = null)
        try {
            val result = port.refresh(locator)
            currentCoroutineContext().ensureActive()
            if (!closed) mutable.value = MallOperationState(MallOperationPhase.RESULT, locator = locator, result = result)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (!closed) mutable.value = state.value.copy(phase = MallOperationPhase.FAILED) }
    }

    suspend fun finish(onFinished: (Long) -> Unit) = exclusively {
        val locator = state.value.locator ?: return@exclusively
        if (state.value.phase != MallOperationPhase.RESULT) return@exclusively
        try {
            port.acknowledge(locator)
            currentCoroutineContext().ensureActive()
            if (!closed) onFinished(locator.orderId)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (!closed) mutable.value = state.value.copy(reason = "RESULT_ACK_FAILED") }
    }

    fun close() { closed = true; mutable.value = MallOperationState() }
    private suspend fun accept(outcome: MallMutation) {
        currentCoroutineContext().ensureActive()
        if (closed) return
        mutable.value = when (outcome) {
            MallMutation.Clear -> MallOperationState(MallOperationPhase.READY)
            MallMutation.Pending -> MallOperationState(MallOperationPhase.UNCERTAIN)
            is MallMutation.Rejected -> MallOperationState(MallOperationPhase.READY, reason = outcome.reason)
            is MallMutation.Confirmed -> MallOperationState(MallOperationPhase.CONFIRMED, locator = outcome.locator)
        }
    }
    private fun uncertain() { if (!closed) mutable.value = MallOperationState(MallOperationPhase.UNCERTAIN) }
    private suspend fun exclusively(block: suspend () -> Unit) {
        if (closed || !busy.tryLock()) return
        try { if (!closed) block() } finally { busy.unlock() }
    }
}
