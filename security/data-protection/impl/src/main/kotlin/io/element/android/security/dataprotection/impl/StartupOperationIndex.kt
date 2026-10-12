/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** All indexed operations must be verified before reconciliation can apply any of them. */
internal suspend fun <T> readStartupOperationIndex(
    operationIds: List<String>,
    readAndVerify: suspend (String) -> T,
): List<T> = coroutineScope {
    require(operationIds.size <= 128 && operationIds.distinct().size == operationIds.size)
    val slots = Semaphore(4)
    operationIds.map { id ->
        async { slots.withPermit { readAndVerify(id) } }
    }.awaitAll()
}
