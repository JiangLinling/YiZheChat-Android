package io.element.android.appnav.securitysetup
import com.google.common.truth.Truth.assertThat
import io.element.android.features.securebackup.api.RecoverySessionState
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import org.junit.Test
class RecoveryRefreshActionTest {
    @Test fun `retired intent query permits new key without asserting identity verification`() {
        assertThat(recoveryActionAfterRefresh(Result.success(Unit), RecoverySessionState.None, SessionVerifiedStatus.NotVerified)).isEqualTo(AsyncAction.Uninitialized)
    }
    @Test fun `verified session with no pending recovery completes action`() {
        assertThat(recoveryActionAfterRefresh(Result.success(Unit), RecoverySessionState.None, SessionVerifiedStatus.Verified)).isEqualTo(AsyncAction.Success(Unit))
    }
    @Test fun `verified session cannot bypass waiting authority`() {
        assertThat(recoveryActionAfterRefresh(Result.success(Unit), RecoverySessionState.Waiting("operation", "recovery", 1000), SessionVerifiedStatus.Verified)).isEqualTo(AsyncAction.Uninitialized)
    }
    @Test fun `pending intent stays gated without false completion`() {
        assertThat(recoveryActionAfterRefresh(Result.success(Unit), RecoverySessionState.Pending("operation"), SessionVerifiedStatus.NotVerified)).isEqualTo(AsyncAction.Uninitialized)
    }
    @Test fun `authority failure preserves original error`() {
        val e = java.io.IOException("TEST_AUTHORITY_UNAVAILABLE")
        assertThat(recoveryActionAfterRefresh(Result.failure(e), RecoverySessionState.Rejected("RECOVERY_RECHECK_REQUIRED"), SessionVerifiedStatus.NotVerified)).isEqualTo(AsyncAction.Failure(e))
    }
}
