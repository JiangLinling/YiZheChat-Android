/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

import java.security.MessageDigest

/** One existing LC13 receive identity for timeline and notification projections of the same event. */
fun messageReceiveOperationId(
    accountIdHash: String,
    matrixDeviceIdHash: String,
    roomIdHash: String,
    eventIdHash: String,
): String {
    val identity = listOf(
        FullAppLifecycleId.LC_13.wireValue,
        "receive",
        accountIdHash,
        matrixDeviceIdHash,
        roomIdHash,
        eventIdHash,
    ).joinToString("\u0000").encodeToByteArray()
    val digest = MessageDigest.getInstance("SHA-256").digest(identity).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "lc13-receive-$digest"
}
