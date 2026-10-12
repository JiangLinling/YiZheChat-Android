/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.huawei

import dev.zacsweers.metro.Inject
import io.element.android.libraries.core.extensions.flatMap
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.pusher.UnsetHttpPusherData
import io.element.android.libraries.pushproviders.unifiedpush.embedded.EmbeddedNtfyManager
import io.element.android.libraries.pushproviders.unifiedpush.embedded.EmbeddedNtfyRegistrar
import io.element.android.libraries.pushproviders.unifiedpush.embedded.EmbeddedNtfyRegistrationStore
import io.element.android.libraries.pushstore.api.clientsecret.PushClientSecret
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.api.toUserList
import timber.log.Timber

private val loggerTag = LoggerTag("HuaweiPushNewTokenHandler", LoggerTag.PushLoggerTag)

@Inject
class HuaweiPushNewTokenHandler(
    private val sessionStore: SessionStore,
    private val matrixClientProvider: MatrixClientProvider,
    private val pushClientSecret: PushClientSecret,
    private val embeddedNtfyManager: EmbeddedNtfyManager,
    private val embeddedNtfyRegistrationStore: EmbeddedNtfyRegistrationStore,
    private val embeddedNtfyRegistrar: EmbeddedNtfyRegistrar,
) {
    suspend fun handle(token: String): Result<Unit> {
        if (token.isBlank()) return Result.failure(IllegalArgumentException("Huawei Push token is blank"))
        val validSessions = sessionStore.getAllSessions()
            .filter { it.isTokenValid }
            .toUserList()
        if (validSessions.isEmpty()) {
            return Result.failure(IllegalStateException("No valid Matrix session is available for Huawei Push registration"))
        }
        val failures = validSessions
            .map(::SessionId)
            .mapNotNull { sessionId ->
                matrixClientProvider.getOrRestore(sessionId)
                    .onFailure {
                        Timber.tag(loggerTag.value).e(it, "Failed to restore a session for Huawei Push registration")
                    }
                    .flatMap { matrixClient -> register(matrixClient, token) }
                    .onFailure {
                        Timber.tag(loggerTag.value).e(it, "Failed to register the Huawei Push pusher")
                    }
                    .exceptionOrNull()
            }
        return failures.firstOrNull()?.let(Result.Companion::failure) ?: Result.success(Unit)
    }

    suspend fun restoreUniversalFallback(): Result<Unit> {
        val validSessions = sessionStore.getAllSessions()
            .filter { it.isTokenValid }
            .toUserList()
        val failures = validSessions
            .map(::SessionId)
            .mapNotNull { sessionId ->
                matrixClientProvider.getOrRestore(sessionId)
                    .flatMap { matrixClient ->
                        val clientSecret = pushClientSecret.getSecretForUser(matrixClient.sessionId)
                        embeddedNtfyManager.register(matrixClient, clientSecret)
                    }
                    .exceptionOrNull()
            }
        return failures.firstOrNull()?.let(Result.Companion::failure) ?: Result.success(Unit)
    }

    private suspend fun register(matrixClient: MatrixClient, token: String): Result<Unit> {
        val clientSecret = pushClientSecret.getSecretForUser(matrixClient.sessionId)
        val ntfyRegistration = embeddedNtfyManager.register(matrixClient, clientSecret)
        if (ntfyRegistration.isFailure) return ntfyRegistration

        val registration = runCatching { embeddedNtfyRegistrationStore.get(clientSecret) }
            .getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("Built-in push registration is unavailable"))
        val vendorRegistration = embeddedNtfyRegistrar.registerVendor(
            managementToken = registration.managementToken,
            provider = "huawei",
            token = token,
        )
        if (vendorRegistration.isFailure) return vendorRegistration

        // Older builds registered Huawei as a second Matrix pusher. Remove that legacy route only
        // after ntfy and the server-side fallback token are both ready, otherwise one Matrix event
        // can reach the device through both channels.
        return matrixClient.pushersService.unsetHttpPusher(
            UnsetHttpPusherData(
                pushKey = token,
                appId = HuaweiPushConfig.PUSHER_APP_ID,
            )
        ).onFailure { failure ->
            Timber.tag(loggerTag.value).e(failure, "Failed to retire the legacy direct Huawei pusher")
        }
    }
}
