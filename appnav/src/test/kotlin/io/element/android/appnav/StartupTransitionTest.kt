package io.element.android.appnav

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StartupTransitionTest {
    @Test fun `home skeleton cannot release the startup curtain`() {
        assertThat(shouldRevealLoggedInStartup(StartupDestination.HOME, true, false)).isFalse()
        assertThat(shouldRevealLoggedInStartup(StartupDestination.HOME, true, false, loadingGraceExpired = true)).isTrue()
        assertThat(shouldRevealLoggedInStartup(StartupDestination.HOME, true, true)).isTrue()
    }
    @Test fun `restored room and deletion recovery do not wait for hidden home list`() {
        assertThat(shouldRevealLoggedInStartup(StartupDestination.HOME, false, false)).isTrue()
        assertThat(shouldRevealLoggedInStartup(StartupDestination.DELETION_RECOVERY, false, false)).isTrue()
        assertThat(shouldRevealLoggedInStartup(StartupDestination.WAIT, false, true)).isFalse()
    }

    @Test fun `startup replacements have no intermediate animated screen`() {
        listOf(LoggedInFlowNode.NavTarget.Placeholder, LoggedInFlowNode.NavTarget.SecuritySetup,
            LoggedInFlowNode.NavTarget.StartupFailure("TEST_FAILURE")).forEach {
            assertThat(isStartupGate(it)).isTrue()
        }
    }

    @Test fun `ordinary home navigation retains its transition`() {
        assertThat(isStartupGate(LoggedInFlowNode.NavTarget.Home)).isFalse()
        assertThat(isStartupGate(LoggedInFlowNode.NavTarget.VerifyCurrentSession)).isFalse()
    }
}
