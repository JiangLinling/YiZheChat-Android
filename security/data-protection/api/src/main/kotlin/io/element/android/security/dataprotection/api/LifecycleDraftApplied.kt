/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** An actual committed local storage version, without draft content. */
data class LifecycleDraftApplied(
    val operationId: String,
    val accountIdHash: String,
    val matrixDeviceIdHash: String,
    val roomIdHash: String,
    val storageVersionHash: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(operationId.length in 16..256 && occurredAtEpochMillis > 0)
        require(listOf(accountIdHash, matrixDeviceIdHash, roomIdHash, storageVersionHash).all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    fun receipts() = listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND).map { component ->
        LifecycleClientReceipt(operationId, component, LifecyclePhase.CLIENT_APPLIED, "${component.name}_APPLIED",
            "${component.name.lowercase().replace('_', '-')}:$operationId:version:$storageVersionHash", occurredAtEpochMillis)
    }
}

/** The encrypted draft store owns its crash-durable result outbox for this invocation. */
class LifecycleDraftWriteContext(val operationId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LifecycleDraftWriteContext>
    var hasDurableResultOwner: Boolean = false
        private set

    fun claimResultOwnership(originalOperationId: String) {
        require(originalOperationId == operationId)
        hasDurableResultOwner = true
    }
}
