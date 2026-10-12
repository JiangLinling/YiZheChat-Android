package io.element.android.appnav.securitysetup

import com.google.common.truth.Truth.assertThat
import io.element.android.features.securebackup.api.RecoverySessionState
import io.element.android.features.securebackup.api.requiresGate
import org.junit.Test

class StartupRecoveryRenderingTest {
    @Test fun `discovering blocks entry without asking for a recovery key`() {
        assertThat(RecoverySessionState.Discovering.requiresGate).isTrue()
        assertThat(recoveryNeedsUserAction(RecoverySessionState.Discovering)).isFalse()
    }

    @Test fun `owned pending recovery and authority failures still require user action`() {
        listOf(RecoverySessionState.Pending("operation"),
            RecoverySessionState.Waiting("operation", "recovery", 1000),
            RecoverySessionState.Rejected("RECOVERY_RECHECK_REQUIRED")).forEach {
            assertThat(it.requiresGate).isTrue()
            assertThat(recoveryNeedsUserAction(it)).isTrue()
        }
    }

    @Test fun `resolved empty recovery state does not show the recovery form`() {
        assertThat(recoveryNeedsUserAction(RecoverySessionState.None)).isFalse()
    }
}
