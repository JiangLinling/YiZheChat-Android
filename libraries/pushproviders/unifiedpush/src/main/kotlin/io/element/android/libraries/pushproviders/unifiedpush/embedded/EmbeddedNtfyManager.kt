/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.embedded

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.pushproviders.api.Distributor
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushNewGatewayHandler
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushStore
import io.element.android.libraries.pushproviders.unifiedpush.UnregisterUnifiedPushUseCase
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import timber.log.Timber
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration.Companion.days

interface EmbeddedNtfyManager {
    suspend fun register(matrixClient: MatrixClient, clientSecret: String): Result<Unit>
    suspend fun unregister(matrixClient: MatrixClient, clientSecret: String): Result<Unit>
    suspend fun unregisterAccount(matrixClient: MatrixClient, clientSecret: String): Result<Unit>
    suspend fun cleanup(clientSecret: String)

    companion object {
        const val DISTRIBUTOR_VALUE = "io.element.android.x.embedded.ntfy"
        val DISTRIBUTOR = Distributor(DISTRIBUTOR_VALUE, "YiZheChat built-in ntfy")
    }
}

@ContributesBinding(AppScope::class)
class DefaultEmbeddedNtfyManager(
    @ApplicationContext private val context: Context,
    private val registrationStore: EmbeddedNtfyRegistrationStore,
    private val registrar: EmbeddedNtfyRegistrar,
    private val unifiedPushStore: UnifiedPushStore,
    private val newGatewayHandler: UnifiedPushNewGatewayHandler,
    private val unregisterUnifiedPushUseCase: UnregisterUnifiedPushUseCase,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
) : EmbeddedNtfyManager {
    override suspend fun register(matrixClient: MatrixClient, clientSecret: String): Result<Unit> {
        return runCatching {
            appOperationalStateCoordinator.perform(
                lifecycleOperationRequest(
                    lifecycleId = FullAppLifecycleId.LC_19,
                    trigger = "push_register",
                    accountIdHash = matrixClient.sessionId.value.sha256(),
                    matrixDeviceIdHash = matrixClient.deviceId.value.sha256(),
                )
            ) {
                registerInternal(matrixClient, clientSecret).getOrThrow()
            }
        }
    }

    private suspend fun registerInternal(matrixClient: MatrixClient, clientSecret: String): Result<Unit> {
        val existing = try {
            registrationStore.get(clientSecret)
        } catch (failure: Exception) {
            return Result.failure(failure)
        }
        val reusable = existing?.takeIf { registration ->
            registration.expiresAt > Instant.now().epochSecond + RENEW_BEFORE_EXPIRY.inWholeSeconds &&
            registration.strategyVersion >= minimumCoordinatedStrategyVersion(NextDeploymentConfig.isNextDeployment)
        }
        val registration = reusable ?: provision(matrixClient, clientSecret, existing).getOrElse {
            return Result.failure(it)
        }
        val wasProvisioned = reusable == null
        val replacedCredentials = existing == null || !existing.hasSameNtfyIdentity(registration)
        unifiedPushStore.storeUpEndpoint(clientSecret, registration.endpoint)
        unifiedPushStore.storePushGateway(clientSecret, registration.gateway)
        return newGatewayHandler.handle(registration.endpoint, registration.gateway, clientSecret)
            .fold(
                onSuccess = {
                    registrar.confirm(registration.managementToken)
                },
                onFailure = { Result.failure(it) },
            )
            .onSuccess {
                if (existing != null && replacedCredentials) {
                    registrar.unregister(existing.managementToken)
                        .onFailure { Timber.w(it, "Unable to revoke superseded built-in push registration") }
                }
                EmbeddedNtfyConnectionService.start(context)
            }
            .onFailure { failure ->
                Timber.w(failure, "Unable to register Matrix pusher for built-in push")
                if (wasProvisioned && replacedCredentials) {
                    registrar.unregister(registration.managementToken).fold(
                        onSuccess = {
                            if (existing == null) {
                                registrationStore.remove(clientSecret)
                                unifiedPushStore.storeUpEndpoint(clientSecret, null)
                                unifiedPushStore.storePushGateway(clientSecret, null)
                            } else {
                                registrationStore.upsert(existing)
                                unifiedPushStore.storeUpEndpoint(clientSecret, existing.endpoint)
                                unifiedPushStore.storePushGateway(clientSecret, existing.gateway)
                            }
                        },
                        onFailure = { revocationFailure ->
                            // Provisioning stored the new encrypted management token before the
                            // Matrix pusher step. Keep it when rollback is not authoritative so a
                            // later retry can revoke the server lease instead of leaking it.
                            Timber.w(
                                revocationFailure,
                                "Built-in push registration rollback is pending; retaining retry credentials",
                            )
                        },
                    )
                }
            }
    }

    override suspend fun unregister(matrixClient: MatrixClient, clientSecret: String): Result<Unit> {
        return runCatching {
            appOperationalStateCoordinator.perform(
                lifecycleOperationRequest(
                    lifecycleId = FullAppLifecycleId.LC_19,
                    trigger = "device_revoke",
                    accountIdHash = matrixClient.sessionId.value.sha256(),
                    matrixDeviceIdHash = matrixClient.deviceId.value.sha256(),
                )
            ) {
                unregisterInternal(matrixClient, clientSecret, revokeAccount = false)
            }
        }
    }

    override suspend fun unregisterAccount(matrixClient: MatrixClient, clientSecret: String): Result<Unit> {
        return runCatching {
            check(currentLifecycleOperationContext()?.lifecycleId == FullAppLifecycleId.LC_22) {
                "Account-wide built-in push revocation requires an LC-22 operation."
            }
            unregisterInternal(matrixClient, clientSecret, revokeAccount = true)
        }
    }

    private suspend fun unregisterInternal(
        matrixClient: MatrixClient,
        clientSecret: String,
        revokeAccount: Boolean,
    ) {
        // Explicit revocation (including LC-22) is fail-closed: the Matrix pusher and
        // registrar registration must both confirm before local credentials disappear.
        val registration = registrationStore.get(clientSecret)
        unregisterUnifiedPushUseCase.unregister(
            matrixClient = matrixClient,
            clientSecret = clientSecret,
            unregisterUnifiedPush = false,
        ).getOrThrow()
        if (revokeAccount) {
            val openIdToken = matrixClient.requestOpenIdToken().getOrThrow()
            registrar.unregisterAccount(openIdToken).getOrThrow()
        } else if (registration != null) {
            registrar.unregister(registration.managementToken).getOrThrow()
        }
        cleanupLocal(clientSecret)
    }

    override suspend fun cleanup(clientSecret: String) {
        val registration = try {
            registrationStore.get(clientSecret)
        } catch (failure: Exception) {
            Timber.w(failure, "Unable to load built-in push registration during cleanup")
            null
        }
        if (registration != null) {
            registrar.unregister(registration.managementToken)
                .onFailure {
                    // Keep the encrypted management token. Session observation is asynchronous
                    // and cannot provide the operation context required to retry a revocation.
                    Timber.w(it, "Built-in push revocation is pending; retaining retry credentials")
                    return
                }
        }
        cleanupLocal(clientSecret)
    }

    private suspend fun cleanupLocal(clientSecret: String) {
        registrationStore.remove(clientSecret)
        unregisterUnifiedPushUseCase.cleanup(clientSecret, unregisterUnifiedPush = false)
        if (registrationStore.getAll().isEmpty()) {
            EmbeddedNtfyConnectionService.stop(context)
        } else {
            EmbeddedNtfyConnectionService.start(context)
        }
    }

    private suspend fun provision(
        matrixClient: MatrixClient,
        clientSecret: String,
        previous: EmbeddedNtfyRegistration?,
    ): Result<EmbeddedNtfyRegistration> {
        return matrixClient.requestOpenIdToken().fold(
            onSuccess = { token ->
                registrar.register(token, clientSecret, matrixClient.deviceId.value.sha256()).mapCatching { registration ->
                    val persisted = if (previous != null && previous.hasSameNtfyIdentity(registration)) {
                        registration.copy(lastMessageId = previous.lastMessageId)
                    } else {
                        registration
                    }
                    registrationStore.upsert(persisted)
                    persisted
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    private fun EmbeddedNtfyRegistration.hasSameNtfyIdentity(other: EmbeddedNtfyRegistration): Boolean {
        return server == other.server && topic == other.topic && username == other.username
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        val RENEW_BEFORE_EXPIRY = 7.days
    }
}

internal fun minimumCoordinatedStrategyVersion(isNextDeployment: Boolean): Int =
    if (isNextDeployment) 4 else 2
