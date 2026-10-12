/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.impl

import io.element.android.security.securestorage.api.SecureMediaExternalReadObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only the registry/provider and the object's owner can publish these local facts. */
internal class ExternalReadObserver {
    private val lock = Any()
    private val mutable = MutableStateFlow(SecureMediaExternalReadObservation())
    val state = mutable.asStateFlow()

    fun descriptorOpened() = synchronized(lock) {
        mutable.value = mutable.value.copy(descriptorOpens = increment(mutable.value.descriptorOpens, 1))
    }

    fun copied(count: Int) = synchronized(lock) {
        val previous = mutable.value
        mutable.value = previous.copy(
            readCalls = increment(previous.readCalls, 1),
            bytesCopied = increment(previous.bytesCopied, count.toLong()),
            firstReadAtEpochMillis = previous.firstReadAtEpochMillis ?: System.currentTimeMillis().takeIf { count > 0 }
        )
    }

    fun quiescent() = synchronized(lock) {
        mutable.value = mutable.value.copy(revoked = true, readsQuiescent = true)
    }

    fun cleanup(complete: Boolean) = synchronized(lock) {
        mutable.value = mutable.value.copy(cleanupComplete = complete, cleanupPending = !complete)
    }

    private fun increment(value: Long, amount: Long) = if (Long.MAX_VALUE - value < amount) Long.MAX_VALUE else value + amount
}
