/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.appnav

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.PermanentAccountDeletionHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class DeletionRecoveryGateTest {
    @Test fun `429 on foreground becomes retry required instead of escaping or opening the gate`() = runTest {
        val failure = IOException("HTTP 429")
        val gate = checkDeletionRecoveryGate { throw failure }
        assertThat(gate).isEqualTo(DeletionRecoveryGate.RetryRequired(failure))
    }
    @Test fun `only an authoritative absence opens the gate`() = runTest {
        assertThat(checkDeletionRecoveryGate { null }).isEqualTo(DeletionRecoveryGate.Clear)
        val handle = PermanentAccountDeletionHandle("existing-deletion-operation")
        assertThat(checkDeletionRecoveryGate { handle }).isEqualTo(DeletionRecoveryGate.Pending(handle))
    }
    @Test fun `cancellation is preserved`() = runTest {
        val failure = CancellationException("leaving screen")
        assertThat(runCatching { checkDeletionRecoveryGate { throw failure } }.exceptionOrNull()).isSameInstanceAs(failure)
    }
}
