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

    /**
     * Complete through whichever trustworthy signal arrives first: the SDK reset call itself,
     * or an independently refreshed proof that the reset's security state is fully active.
     */
    suspend fun completeReset(reset: suspend () -> Result<Unit>): Result<Unit> = supervisorScope {
        val resetJob = async { reset() }
        val completionProofJob = async { awaitCompletionProof() }
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

    private suspend fun awaitCompletionProof(): Result<Unit> {
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
                    if (state?.isComplete == true) {
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

            sessionCoroutineScope.launch {
                encryptionService.startIdentityReset()
                    .onSuccess { handle ->
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
        currentHandleFlow.value.dataOrNull()?.cancel()
        resetHandleFlow.value = AsyncData.Uninitialized
    }

    private companion object {
        val COMPLETION_POLL_INTERVAL = 1_000.milliseconds
        val COMPLETION_TIMEOUT = 90.seconds
    }
}

private class IdentityResetCompletionTimeoutException : IllegalStateException(
    "Identity reset did not reach a verified local and server state before the timeout."
)
