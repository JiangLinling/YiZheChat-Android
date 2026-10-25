/* Copyright (c) 2026 YiZheChat contributors. */
package io.element.android.features.messages.impl.attachments.preview.error

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.ui.strings.R
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.StageThreeGateDenied
import io.element.android.security.contentprotection.api.StageThreeDenialReason
import org.junit.Test
import java.io.IOException

class ProtectedSendErrorTest {
    @Test
    fun `a confirmed pre-send security rejection is never reported as an unknown network send`() {
        assertThat(protectedSendErrorResource(StageThreeGateDenied(StageThreeDenialReason.RETENTION_EXCEEDED)))
            .isEqualTo(R.string.error_share_security_rejected)
        assertThat(protectedSendErrorResource(ContentProtectionException.Expired())).isEqualTo(R.string.error_share_content_expired)
        assertThat(protectedSendErrorResource(ContentProtectionException.VersionEditNotSubmitted(IOException("prepare failed"))))
            .isEqualTo(R.string.error_text_edit_not_submitted)
    }

    @Test
    fun `missing edit history is distinct from unknown Matrix send and access denial`() {
        assertThat(protectedSendErrorResource(ContentProtectionException.VersionLineageUnavailable())).isEqualTo(R.string.error_text_version_unavailable)
        assertThat(protectedSendErrorResource(IllegalStateException(ContentProtectionException.VersionLineageUnavailable())))
            .isEqualTo(R.string.error_text_version_unavailable)
        assertThat(protectedSendErrorResource(IOException("response lost"))).isEqualTo(R.string.error_share_send_failed)
        assertThat(protectedSendErrorResource(ContentProtectionException.AccessDenied())).isEqualTo(R.string.error_share_security_access_denied)
        assertThat(protectedSendErrorResource(ContentProtectionException.SendBindingPending())).isEqualTo(R.string.error_send_confirmation_pending)
    }
}
