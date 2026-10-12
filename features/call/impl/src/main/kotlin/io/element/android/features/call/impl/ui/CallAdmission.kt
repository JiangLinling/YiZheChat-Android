/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.call.impl.ui

import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.dataprotection.api.performForComponent
import io.element.android.security.dataprotection.api.withOperation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/** Native facts exist first. Only a registration requiring both clients permits concurrent submission. */
internal suspend fun <T> AppOperationalStateCoordinator.performCallAdmission(
    request: LifecycleOperationRequest,
    attach: suspend () -> T,
): T = withOperation(request) { id ->
    val result = attach()
    val clients = listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND)
    val admitted = operations.value[id]
    val parallel = request.lifecycleId == FullAppLifecycleId.LC_20 && request.trigger == "join" &&
        admitted?.lifecycleId == request.lifecycleId && !admitted.status.isTerminal &&
        admitted.steps.filter { it.required }.map { it.component }.containsAll(clients)
    if (parallel) {
        // Each component uses the existing durable receipt path; failure of one never
        // cancels the other's submission. Neither response releases the media gate alone.
        val confirmations = supervisorScope {
            clients.map { client -> async { runCatching { performForComponent(request, client) { Unit } } } }.awaitAll()
        }
        confirmations.forEach { it.getOrThrow() }
    } else {
        perform(request) { Unit }
    }
    result
}
