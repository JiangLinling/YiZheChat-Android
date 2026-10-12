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
import io.element.android.security.dataprotection.api.AccountIdentityResetLifecycleRecorder
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.accountIdHash
import java.security.MessageDigest

/**
 * Persists Matrix's completed, user-confirmed identity reset before FTUE can leave the reset flow.
 *
 * The marker contains no key material. The later DAK-signed PREPARE request consumes it and the
 * control plane decides whether the new identity may become CORE_READY.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultAccountIdentityResetLifecycleRecorder(
    private val matrixClient: MatrixClient,
    private val deviceTrustService: DeviceTrustService,
    private val accountReactivationScopeStore: AccountReactivationScopeStore,
) : AccountIdentityResetLifecycleRecorder {
    override suspend fun recordExplicitReset(): Result<Unit> = runCatching {
        val trust = deviceTrustService.current()
        check(trust.installationId.isNotBlank())
        check(
            accountReactivationScopeStore.markExplicitIdentityReset(
                accountIdHash = accountIdHash(matrixClient.sessionId.value),
                installationId = trust.installationId,
                matrixDeviceIdHash = sha256Hex(matrixClient.deviceId.value),
            )
        ) { "The identity reset lifecycle checkpoint was not persisted." }
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
