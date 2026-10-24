/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.home.impl.roomlist

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.test.FakeProtectedPreviewAuthorizationGate
import io.element.android.security.securestorage.api.SecureVaultDestroyedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProtectedNotificationProjectionTest {
    @Test
    fun `destroyed vault remains a failed update`() = runTest {
        val result = updateProtectedNotificationProjection(FakeProtectedPreviewAuthorizationGate()) {
            throw SecureVaultDestroyedException()
        }
        assertThat(result).isFalse()
    }

    @Test
    fun `cancellation of the owning screen is not swallowed`() = runTest {
        val cancellation = CancellationException("screen retired")
        val failure = runCatching {
            updateProtectedNotificationProjection(FakeProtectedPreviewAuthorizationGate()) { throw cancellation }
        }.exceptionOrNull()
        assertThat(failure).isSameInstanceAs(cancellation)
    }
}
