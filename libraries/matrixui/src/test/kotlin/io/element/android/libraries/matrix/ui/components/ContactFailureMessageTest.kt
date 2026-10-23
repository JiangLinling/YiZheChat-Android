/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.ui.components

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.ui.strings.CommonStrings
import org.junit.Test

class ContactFailureMessageTest {
    @Test
    fun `expired direct response has a retry message distinct from network failure`() {
        assertThat(IllegalStateException("LC11_DIRECT_RESPONSE_EXPIRED_RETRY").contactFailureMessageResource())
            .isEqualTo(CommonStrings.contact_request_expired_retry)
        assertThat(IllegalStateException("network failed").contactFailureMessageResource()).isNull()
        assertThat(IllegalStateException("CONTACT_BLOCKED_BY_YOU").contactFailureMessageResource())
            .isEqualTo(CommonStrings.contact_blocked_by_you)
    }
}
