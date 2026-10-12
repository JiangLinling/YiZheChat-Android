/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.huawei

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.sessionstorage.api.observer.SessionListener
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

private val loggerTag = LoggerTag("HuaweiPushRegistration", LoggerTag.PushLoggerTag)

@SingleIn(AppScope::class)
@Inject
class HuaweiPushRegistrationCoordinator(
    private val tokenSource: HuaweiPushTokenSource,
    private val newTokenHandler: HuaweiPushNewTokenHandler,
    private val sessionObserver: SessionObserver,
    private val diagnostics: HuaweiPushDiagnostics,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
) : SessionListener {
    private val started = AtomicBoolean(false)
    private val registrationMutex = Mutex()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        sessionObserver.addListener(this)
        requestRegistration("application startup")
    }

    override suspend fun onSessionCreated(userId: String) {
        // The token can arrive before the first Matrix session exists. Register again after
        // login/session restoration; the user id itself is deliberately not logged.
        requestRegistration("session created")
    }

    private fun requestRegistration(reason: String) {
        coroutineScope.launch {
            registrationMutex.withLock {
                registerWithRetry(reason)
            }
        }
    }

    private suspend fun registerWithRetry(reason: String) {
        diagnostics.recordAttempt()
        var lastFailure: Throwable? = null
        RETRY_DELAYS_MS.forEachIndexed { attempt, delayMs ->
            if (delayMs > 0) delay(delayMs)
            val result = tokenSource.getToken().fold(
                onSuccess = { token ->
                    newTokenHandler.handle(token).fold(
                        onSuccess = { Result.success(Unit) },
                        onFailure = { Result.failure(HuaweiPusherRegistrationException(it)) },
                    )
                },
                onFailure = { Result.failure(it) },
            )
            if (result.isSuccess) {
                diagnostics.recordSuccess()
                Timber.tag(loggerTag.value).i("Huawei Push registration completed after $reason")
                return
            }
            lastFailure = result.exceptionOrNull()
            Timber.tag(loggerTag.value).w(
                lastFailure,
                "Huawei Push registration attempt ${attempt + 1} failed after $reason",
            )
        }
        diagnostics.recordFailure(lastFailure)
        Timber.tag(loggerTag.value).e(lastFailure, "Huawei Push registration exhausted bounded retries")
        newTokenHandler.restoreUniversalFallback()
            .onSuccess {
                diagnostics.recordFallbackActive(lastFailure)
                Timber.tag(loggerTag.value).w("Huawei Push unavailable; universal push fallback restored")
            }
            .onFailure { fallbackFailure ->
                Timber.tag(loggerTag.value).e(fallbackFailure, "Unable to restore universal push fallback")
            }
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(0L, 1_000L, 3_000L)
    }
}

class HuaweiPusherRegistrationException(cause: Throwable) :
    IllegalStateException("Matrix pusher registration failed", cause)
