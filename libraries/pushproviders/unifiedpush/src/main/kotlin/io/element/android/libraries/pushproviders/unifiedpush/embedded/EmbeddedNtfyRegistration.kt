/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.embedded

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.decodeBase64

@Serializable
data class EmbeddedNtfyRegistration(
    val clientSecret: String,
    val server: String,
    val topic: String,
    val username: String,
    val password: String,
    val endpoint: String,
    val gateway: String,
    val managementToken: String,
    val expiresAt: Long,
    val lastMessageId: String? = null,
    val strategyVersion: Int = 1,
)

@Serializable
internal data class EmbeddedNtfyRegistrationState(
    val registrations: List<EmbeddedNtfyRegistration> = emptyList(),
)

@Serializable
internal data class RegistrarRegistrationResponse(
    val server: String,
    val topic: String,
    val username: String,
    val password: String,
    val endpoint: String,
    val gateway: String,
    @SerialName("management_token") val managementToken: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("strategy_version") val strategyVersion: Int = 1,
)

enum class DeliveryClaimDecision {
    CLAIMED,
    PROCESS_UNCOORDINATED,
    SUPPRESS,
}

@Serializable
internal data class RegistrarDeliveryDecisionResponse(
    val decision: String,
)

@Serializable
internal data class NtfyEnvelope(
    val id: String? = null,
    val event: String,
    val message: String? = null,
    val encoding: String? = null,
)

internal fun NtfyEnvelope.messageBytes(): ByteArray? {
    val value = message ?: return null
    return when (encoding) {
        null, "" -> value.encodeToByteArray()
        "base64" -> value.decodeBase64()?.toByteArray()
        else -> null
    }
}
