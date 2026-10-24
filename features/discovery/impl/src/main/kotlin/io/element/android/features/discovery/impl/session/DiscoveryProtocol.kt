/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.session

import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

internal data class DiscoveryAccountScope(
    val accountHash: String,
    val deviceHash: String,
    val installationId: String,
    val attestationVersion: Long,
    val loginTimestamp: Long,
)

/** In-process only; never placed in UI state, saved state, storage or logs. */
internal class DiscoverySecret(val value: String) {
    override fun toString(): String = "[discovery credential]"
}

internal class DiscoveryCode(
    val secret: DiscoverySecret,
    val exchangeId: String,
    val verifier: DiscoverySecret,
    val codeExpiresAtMillis: Long,
    val authorizationExpiresAtMillis: Long,
    val startedAtElapsedMillis: Long,
)

internal class DiscoverySession(
    val secret: DiscoverySecret,
    val exchangeId: String,
    val expiresAtMillis: Long,
    val expiresAtElapsedMillis: Long,
)

internal interface DiscoveryIdentityGate {
    val scope: StateFlow<DiscoveryAccountScope?>
    suspend fun current(): DiscoveryAccountScope?
}

internal interface DiscoveryAuthorityClient {
    suspend fun issue(scope: DiscoveryAccountScope): DiscoveryCode
    suspend fun issueAction(scope: DiscoveryAccountScope, target: DiscoveryActionTarget): DiscoveryCode = throw DiscoveryFailure(503)
}

/** Exact immutable target digest. Persist the business intent before requesting this short-lived proof. */
internal class DiscoveryActionTarget(
    val operationId: String,
    val bodySha256: String,
    val path: String = "/v1/discovery/business/articles/interactions",
) {

    init {
        require(UUID.fromString(operationId).toString() == operationId)
        require(bodySha256.matches(Regex("[a-f0-9]{64}")))
        require(path in setOf("/v1/discovery/business/articles/interactions",
            "/v1/discovery/business/course-quotes", "/v1/discovery/business/course-enrollments", "/v1/discovery/business/member-addresses",
            "/v1/discovery/business/member-profile", "/v1/discovery/business/member-children",
            "/v1/discovery/business/mall-orders", "/v1/discovery/business/mall-order-cancellations",
            "/v1/discovery/business/mall-order-receipts", "/v1/discovery/business/mall-after-sales", "/v1/discovery/business/course-feedback"))
    }
}

internal interface DiscoveryGatewayClient {
    suspend fun allowed(code: DiscoveryCode): Boolean
    suspend fun exchange(code: DiscoveryCode): DiscoverySession
    suspend fun revoke(session: DiscoverySession)
}

internal interface DiscoveryClock {
    fun wallMillis(): Long
    fun elapsedMillis(): Long
}

internal class DiscoveryFailure(val status: Int) : Exception("Discovery request failed ($status)")
