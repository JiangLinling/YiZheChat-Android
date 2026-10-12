/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.test

import java.nio.charset.StandardCharsets

enum class SensitiveCanaryKind {
    TEXT,
    IMAGE,
    VOICE,
    VIDEO,
    ATTACHMENT,
    LOCATION,
    NOTIFICATION,
    LOG,
}

data class SensitiveDataCanary(
    val runId: String,
    val markers: Map<SensitiveCanaryKind, String>,
) {
    fun marker(kind: SensitiveCanaryKind): String = requireNotNull(markers[kind])

    fun bytes(kind: SensitiveCanaryKind): ByteArray = marker(kind).toByteArray(StandardCharsets.UTF_8)

    fun fileName(kind: SensitiveCanaryKind): String = "yizhe-security-canary-$runId-${kind.name.lowercase()}.bin"
}

object SensitiveDataCanaryFactory {
    fun create(runId: String): SensitiveDataCanary {
        require(runId.matches(Regex("[A-Za-z0-9_-]{8,64}"))) {
            "Canary run id must be an opaque 8-64 character identifier."
        }
        return SensitiveDataCanary(
            runId = runId,
            markers = SensitiveCanaryKind.entries.associateWith { kind ->
                "YIZHE_SECURITY_CANARY::$runId::${kind.name}"
            },
        )
    }
}
