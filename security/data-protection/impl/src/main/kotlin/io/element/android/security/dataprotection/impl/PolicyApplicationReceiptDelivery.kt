/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes durable R05 receipt delivery inside one service instance. The receipt stays pending
 * across transport ambiguity, process death, server rejection, or acknowledgement validation
 * failure. It is retired only after [sendAndValidate] has accepted the exact server projection.
 */
internal class PolicyApplicationReceiptDelivery(
    private val loadPending: () -> PolicyApplicationReceiptWire?,
    private val sendAndValidate: suspend (PolicyApplicationReceiptWire) -> Unit,
    private val markDelivered: (nonce: String) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun flush(): Boolean = mutex.withLock {
        val receipt = loadPending() ?: return@withLock false
        sendAndValidate(receipt)
        markDelivered(receipt.nonce)
        true
    }
}
