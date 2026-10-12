package io.element.android.appnav.securitysetup

import com.google.common.truth.Truth.assertThat
import io.element.android.appnav.R
import io.element.android.features.securebackup.api.RecoverySessionState
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.dataprotection.api.RecoveryIntentRejected
import org.junit.Test

class RecoveryFailureMessageTest {
    @Test fun `expired refresh preserves expiry wording when action becomes a failure`() {
        val error = RecoveryIntentRejected("RECOVERY_OPERATION_EXPIRED")
        val action = recoveryActionAfterRefresh(Result.failure(error), RecoverySessionState.Rejected(error.reasonCode), SessionVerifiedStatus.NotVerified)
        assertThat(action).isInstanceOf(AsyncAction.Failure::class.java)
        assertThat(recoveryFailureMessage((action as AsyncAction.Failure).error)).isEqualTo(R.string.screen_security_setup_recovery_expired)
        assertThat(recoveryRejectionMessage(error.reasonCode)).isEqualTo(recoveryFailureMessage(error))
    }
    @Test fun `typed wrong key rejection uses key error`() {
        assertThat(recoveryFailureMessage(RecoveryIntentRejected("RECOVERY_SECRET_STORAGE_REJECTED")))
            .isEqualTo(R.string.screen_security_identity_recovery_key_error)
    }
    @Test fun `authority scope rejection is security blocked`() {
        assertThat(recoveryFailureMessage(RecoveryIntentRejected("RECOVERY_AUTHORITY_SCOPE_REJECTED")))
            .isEqualTo(R.string.screen_security_setup_security_blocked)
    }
    @Test fun `transport failure cannot impersonate an expiry reason from message text`() {
        assertThat(recoveryFailureMessage(java.io.IOException("RECOVERY_OPERATION_EXPIRED")))
            .isEqualTo(R.string.screen_security_setup_service_unavailable)
    }
    @Test fun `content lease expiry retains existing expiry wording`() {
        assertThat(recoveryFailureMessage(ContentProtectionException.Expired())).isEqualTo(R.string.screen_security_setup_recovery_expired)
    }
    @Test fun `restored transport uncertainty retains service unavailable wording`() {
        assertThat(recoveryRejectionMessage("RECOVERY_RECHECK_REQUIRED")).isEqualTo(R.string.screen_security_setup_service_unavailable)
    }
}
