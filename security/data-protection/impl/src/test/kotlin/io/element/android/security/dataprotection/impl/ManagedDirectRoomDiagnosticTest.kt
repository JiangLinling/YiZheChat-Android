/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ManagedDirectRoomDiagnosticTest {
    @Test fun `safe code and correlation retain the failed request boundary`() {
        assertThat(managedDirectRoomFailureDiagnostic(503, "LC11_DIRECT_ORIGINAL_RECONCILIATION_REQUIRED", "0752c640-581b-49e3-b05c-c0e91f0a6401"))
            .isEqualTo("LC11_DIRECT_HTTP_FAILED status=503 code=LC11_DIRECT_ORIGINAL_RECONCILIATION_REQUIRED request=0752c640-581b-49e3-b05c-c0e91f0a6401")
    }
    @Test fun `untrusted body and header cannot become diagnostics`() {
        for (detail in listOf("@person:matrix.test", "Bearer secret", "LC11_peer_secret", "https://secret.example", "LC11_" + "A".repeat(120))) {
            assertThat(managedDirectRoomFailureDiagnostic(999, detail, "secret-header"))
                .isEqualTo("LC11_DIRECT_HTTP_FAILED status=0 code=UNKNOWN request=absent")
        }
    }
}
