/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.timeline

/** Decrypted only while the account Vault is available; never save this in a UI Bundle. */
data class RelayEditRecovery(
    val operationId: String,
    val targetEventId: String,
    val itemId: String,
    val expectedRevision: Long,
    val entry: String,
) {
    init {
        require(operationId.startsWith("relay-edit-"))
        val suffix = operationId.removePrefix("relay-edit-")
        require(java.util.UUID.fromString(suffix).toString() == suffix)
        require(targetEventId.startsWith('$') && targetEventId.length in 2..512 && targetEventId.none(Char::isWhitespace))
        require(itemId.length in 1..512 && itemId.none(Char::isWhitespace))
        require(expectedRevision in 0 until Long.MAX_VALUE && entry.isNotBlank() && entry.trim().length <= 2_000)
    }
}
