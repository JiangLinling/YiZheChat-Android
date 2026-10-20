/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.securebackup.impl.reset

import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.encryption.EncryptionService
import io.element.android.libraries.matrix.api.encryption.IdentityResetHandle
import io.element.android.security.dataprotection.api.AccountIdentityResetLifecycleRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Inject
class ResetIdentityFlowManager(
    private val encryptionService: EncryptionService,
    private val identityResetLifecycleRecorder: AccountIdentityResetLifecycleRecorder,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
) {
    private val resetHandleFlow: MutableStateFlow<AsyncData<IdentityResetHandle?>> = MutableStateFlow(AsyncData.Uninitialized)
    val currentHandleFlow: StateFlow<AsyncData<IdentityResetHandle?>> = resetHandleFlow
    private var handleJob: Job? = null

    /**
     * Complete through whichever trustworthy signal arrives first: the SDK reset call itself,
     * or an independently refreshed proof that the reset's security state is fully active.
     */
    suspend fun completeReset(reset: suspend () -> Result<Unit>): Result<Unit> = supervisorScope {
        val previousHash = identityResetLifecycleRecorder.beginExplicitReset().getOrElse {
            return@supervisorScope Result.failure(it)
        }
        val resetJob = async { reset() }
        val completionProofJob = async { awaitCompletionProof(previousHash) }
        val resetResult = try {
            select {
                resetJob.onAwait { it }
                completionProofJob.onAwait { it }
            }
        } finally {
            resetJob.cancel()
            completionProofJob.cancel()
        }
        if (resetResult.isFailure) return@supervisorScope resetResult
        // Matrix has already completed the destructive operation. Do not report UI success until
        // the device-root-key protected lifecycle marker is durable; a later activation must then
        // receive a server-signed RESET_CONFIRMED receipt before Home is reachable.
        identityResetLifecycleRecorder.recordExplicitReset().onSuccess {
            // A completed destructive attempt must never be replayed by a later tap. A new reset
            // intent must obtain a fresh SDK handle and fresh authorization state.
            resetHandleFlow.value = AsyncData.Uninitialized
        }
    }

    private suspend fun awaitCompletionProof(previousHash: String): Result<Unit> {
        return try {
            withTimeout(COMPLETION_TIMEOUT) {
                var lastState: Any? = null
                while (true) {
                    val state = encryptionService.getIdentityResetCompletionState()
                        .onFailure { Timber.w(it, "Could not refresh identity reset completion state") }
                        .getOrNull()
                    if (state != null && state != lastState) {
                        Timber.d("Identity reset completion state: $state")
                        lastState = state
                    }
                    if (state?.isComplete == true && state.identityKeyHash != null && state.identityKeyHash != previousHash) {
                        Timber.i("Identity reset completion independently verified")
                        return@withTimeout
                    }
                    delay(COMPLETION_POLL_INTERVAL)
                }
            }
            Result.success(Unit)
        } catch (_: TimeoutCancellationException) {
            Result.failure(IdentityResetCompletionTimeoutException())
        }
    }

    fun getResetHandle(): StateFlow<AsyncData<IdentityResetHandle?>> {
        return if (resetHandleFlow.value.isLoading() || resetHandleFlow.value.isSuccess()) {
            resetHandleFlow
        } else {
            resetHandleFlow.value = AsyncData.Loading()

            handleJob = sessionCoroutineScope.launch {
                val resumed = identityResetLifecycleRecorder.resumeCompletedReset()
                if (resumed.isFailure) {
                    resetHandleFlow.value = AsyncData.Failure(checkNotNull(resumed.exceptionOrNull()))
                    return@launch
                }
                if (resumed.getOrThrow()) {
                    resetHandleFlow.value = AsyncData.Success(null)
                    return@launch
                }
                val intent = identityResetLifecycleRecorder.beginExplicitReset()
                if (intent.isFailure) {
                    resetHandleFlow.value = AsyncData.Failure(checkNotNull(intent.exceptionOrNull()))
                    return@launch
                }
                encryptionService.startIdentityReset()
                    .onSuccess { handle ->
                        if (handle == null) {
                            val recorded = identityResetLifecycleRecorder.recordExplicitReset()
                            if (recorded.isFailure) {
                                resetHandleFlow.value = AsyncData.Failure(checkNotNull(recorded.exceptionOrNull()))
                                return@onSuccess
                            }
                        }
                        resetHandleFlow.value = AsyncData.Success(handle)
                    }
                    .onFailure {
                        resetHandleFlow.value = AsyncData.Failure(it)
                    }
            }

            resetHandleFlow
        }
    }

    suspend fun cancel() {
        handleJob?.cancelAndJoin()
        try {
            currentHandleFlow.value.dataOrNull()?.cancel()
            identityResetLifecycleRecorder.cancelExplicitReset().getOrThrow()
            resetHandleFlow.value = AsyncData.Uninitialized
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Unknown remote outcomes retain the durable checkpoint for reconciliation.
            // A navigation callback must not turn them into an uncaught session exception.
            resetHandleFlow.value = AsyncData.Failure(error)
        }
    }

    fun dismissError() {
        if (resetHandleFlow.value.isFailure()) resetHandleFlow.value = AsyncData.Uninitialized
    }

    private companion object {
        val COMPLETION_POLL_INTERVAL = 1_000.milliseconds
        val COMPLETION_TIMEOUT = 90.seconds
    }
}

private class IdentityResetCompletionTimeoutException : IllegalStateException(
    "Identity reset did not reach a verified local and server state before the timeout."
)
