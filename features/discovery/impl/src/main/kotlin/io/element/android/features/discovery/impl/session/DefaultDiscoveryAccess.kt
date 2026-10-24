/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.session

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.discovery.api.DiscoveryAccess
import io.element.android.features.discovery.api.DiscoveryAccessState
import io.element.android.features.discovery.impl.network.DiscoveryCodeClient
import io.element.android.features.discovery.impl.network.DiscoveryConfiguration
import io.element.android.features.discovery.impl.network.HttpDiscoveryGatewayClient
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultDiscoveryAccess internal constructor(
    configuration: DiscoveryConfiguration,
    gate: AndroidDiscoveryIdentityGate,
    authority: DiscoveryCodeClient,
    transport: PinnedDiscoveryTransport,
    clock: AndroidDiscoveryClock,
    @SessionCoroutineScope coroutineScope: CoroutineScope,
) : DiscoveryAccess {
    override val configured = configuration.endpoints != null
    private val manager = if (configuration.endpoints == null) null else DiscoverySessionManager(
        gate, authority, HttpDiscoveryGatewayClient(transport, clock), clock, coroutineScope,
    )
    override val state = manager?.state ?: MutableStateFlow(DiscoveryAccessState())
    override suspend fun refreshAccess() { manager?.refreshAccess() }
    override suspend fun enter(): Boolean = manager?.enter() ?: false
    override suspend fun leave() { manager?.leave() }
    internal suspend fun <T> withSession(block: suspend (DiscoverySession) -> T): T =
        (manager ?: throw DiscoveryFailure(503)).withSession(block)
}
