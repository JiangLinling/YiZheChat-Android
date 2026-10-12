/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.security.dataprotection.api.AccountHistoryAccessBoundary
import io.element.android.security.dataprotection.api.AccountHistoryAccessBoundaryProvider
import io.element.android.security.dataprotection.api.AccountIdentityContinuity
import io.element.android.security.dataprotection.api.AccountReactivationCheckpointState
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.accountIdHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultAccountHistoryAccessBoundaryProvider(
    private val matrixClient: MatrixClient,
    private val deviceTrustService: DeviceTrustService,
    private val accountReactivationScopeStore: AccountReactivationScopeStore,
) : AccountHistoryAccessBoundaryProvider {
    private val mutex = Mutex()
    private var loaded = false
    private var cached: AccountHistoryAccessBoundary? = null

    override suspend fun current(): AccountHistoryAccessBoundary? = mutex.withLock {
        if (loaded) return@withLock cached
        val trust = deviceTrustService.current()
        val checkpoint = accountReactivationScopeStore.current(
            accountIdHash = accountIdHash(matrixClient.sessionId.value),
            installationId = trust.installationId,
            matrixDeviceIdHash = sha256Hex(matrixClient.deviceId.value),
        )
        cached = checkpoint
            ?.takeIf { it.state == AccountReactivationCheckpointState.COMMITTED }
            ?.receipt
            ?.takeIf { it.identityContinuity == AccountIdentityContinuity.RESET_CONFIRMED }
            ?.let { receipt -> AccountHistoryAccessBoundary(receipt.committedAtEpochMillis) }
        loaded = true
        cached
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
