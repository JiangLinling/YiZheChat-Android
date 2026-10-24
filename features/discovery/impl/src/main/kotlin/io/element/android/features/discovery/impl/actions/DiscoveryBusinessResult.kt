/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import kotlinx.serialization.json.*

/** A typed extension of the same Vault record. It is never a second cache or an operation authority. */
internal interface DiscoveryResultCommand : DiscoveryBusinessCommand { val resultKind: String }

internal sealed interface DiscoveryConfirmedResult {
    val operationId: String
    val kind: String
    fun matches(command: DiscoveryResultCommand): Boolean
    fun json(): JsonObject

    companion object {
        fun parse(value: JsonObject): DiscoveryConfirmedResult {
            require(value.keys == setOf("kind", "body"))
            return when (value.getValue("kind")) {
                JsonPrimitive("feedback") -> DiscoveryFeedbackResult.parse(value.getValue("body").jsonObject)
                else -> error("Unknown Discovery result kind")
            }
        }
    }
}
