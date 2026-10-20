/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

/** Read-only routing evidence request. No lifecycle admission, content/key grant or client receipt. */
data class ProtectedEventOriginQuery(
    val accountIdHash: String,
    val matrixDeviceIdHash: String,
    val roomIdHash: String,
    val eventIdHash: String,
    val carrierSha256: String,
    val immutableContextSha256: String,
    val contentId: String,
    val contentKind: String,
    val kmsReference: String,
    val generation: Long?,
) {
    init {
        listOf(accountIdHash, matrixDeviceIdHash, roomIdHash, eventIdHash, carrierSha256, immutableContextSha256)
            .forEach { require(it.matches(Regex("[0-9a-f]{64}"))) }
        require(contentId.length in 1..256 && kmsReference.length in 1..256)
        require(contentKind.matches(Regex("[A-Z_]{2,64}")))
        require(generation == null || generation >= 0)
    }
}

/**
 * Verified routing metadata, deliberately not a coroutine SEND context.
 * A retired producer identifies historical content for a new, independently admitted receive.
 */
data class ProtectedEventOrigin(
    val operationId: String,
    val lifecycleId: FullAppLifecycleId,
    val previousMatrixDeviceIdHash: String? = null,
)

/** Original producer scope; missing key or carrier observations alone cannot authorize a terminal outcome. */
data class ProtectedRelayPreparationQuery(
    val accountIdHash: String,
    val matrixDeviceIdHash: String,
    val roomIdHash: String,
    val eventIdHash: String,
    val operationId: String,
    val roomId: String,
    val contentId: String,
) {
    init {
        listOf(accountIdHash, matrixDeviceIdHash, roomIdHash, eventIdHash)
            .forEach { require(it.matches(Regex("[0-9a-f]{64}"))) }
        require(operationId.matches(Regex("[A-Za-z0-9_-]{16,256}")))
        require(roomId.startsWith("!") && roomId.length in 4..512)
        val expected = java.util.UUID.nameUUIDFromBytes("relay-edit-v1\u0000$roomId\u0000$operationId".encodeToByteArray()).toString()
        require(contentId == expected)
        val roomHash = java.security.MessageDigest.getInstance("SHA-256").digest(roomId.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(roomIdHash == roomHash)
    }
}
