/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.media

import dev.zacsweers.metro.Inject
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Local denial boundary only. Passing it never grants access to an object or authorizes a preview lease. */
@Inject
internal class DiscoveryMediaPreviewBoundary(private val gate: ProtectedPreviewAuthorizationGate) {
    suspend fun <T> whileCurrent(block: suspend (checkCurrent: () -> Unit) -> T): T = coroutineScope {
        val revision = gate.authorizationRevision.value
        fun checkCurrent() {
            if (gate.authorizationRevision.value != revision || !gate.accessState.value.allowsPreviewAttempt || !gate.canAttemptPreview()) {
                throw CancellationException("Discovery media preview boundary changed")
            }
        }
        checkCurrent()
        val operation = currentCoroutineContext().job
        val watcher = launch {
            combine(gate.accessState, gate.authorizationRevision) { access, current ->
                access.allowsPreviewAttempt && current == revision
            }.first { !it }
            operation.cancel(CancellationException("Discovery media preview boundary changed"))
        }
        try {
            block(::checkCurrent).also { checkCurrent() }
        } finally { watcher.cancel() }
    }
}
