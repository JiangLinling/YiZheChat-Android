/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.security.securestorage.api.VaultDestroyReason
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.security.securestorage.test.FakeVaultKeyService
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AccountSessionSecretVaultTest {
    @Test
    fun `storage representation contains only vault envelopes and restores runtime values`() = runTest {
        val service = FakeVaultKeyService()
        val sut = AccountSessionSecretVault(service, FakeFeatureFlagService())
        val original = aSessionData(
            accessToken = "access-token-secret-value",
            refreshToken = "refresh-token-secret-value",
        ).copy(
            oAuthData = "oauth-data-secret-value",
            passphrase = "x'00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff'",
        )

        val protected = sut.protectForStorage(original)

        assertThat(protected.accessToken).startsWith("yzvault1:")
        assertThat(protected.refreshToken).startsWith("yzvault1:")
        assertThat(protected.oAuthData).startsWith("yzvault1:")
        assertThat(protected.passphrase).startsWith("yzvault1:")
        assertThat(protected.toString()).doesNotContain("secret-value")
        assertThat(sut.exposeForRuntime(protected)).isEqualTo(original)
    }

    @Test
    fun `an envelope cannot be moved between credential fields`() = runTest {
        val sut = AccountSessionSecretVault(FakeVaultKeyService(), FakeFeatureFlagService())
        val original = aSessionData(
            accessToken = "access-token-secret-value",
            refreshToken = "refresh-token-secret-value",
        )
        val protected = sut.protectForStorage(original)

        val failure = runCatchingExceptions {
            sut.exposeForRuntime(protected.copy(refreshToken = protected.accessToken))
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
    }

    @Test
    fun `destroying an account vault makes stored session credentials unreadable`() = runTest {
        val service = FakeVaultKeyService()
        val sut = AccountSessionSecretVault(service, FakeFeatureFlagService())
        val original = aSessionData(accessToken = "access-token-secret-value")
        val protected = sut.protectForStorage(original)
        val vaultId = deriveAccountVaultId(original.userId, original.deviceId)

        assertThat(service.destroy(vaultId, VaultDestroyReason.ACCOUNT_LOGOUT)).isTrue()
        val failure = runCatchingExceptions { sut.exposeForRuntime(protected) }.exceptionOrNull()

        assertThat(failure).isNotNull()
    }
}
