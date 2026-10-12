/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.localofficeviewer.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FilePreviewLeaseTest {
    @Test
    fun `carrier verification alone never satisfies protected plaintext authorization`() {
        val carrierOnly = FilePreviewVerification(
            authority = FilePreviewVerificationAuthority.SECURE_MATRIX_MEDIA_LOADER,
            carrierIntegrityVerified = true,
            requiredPlaintextAuthenticationSatisfied = false,
        )

        assertThat(carrierOnly.isSatisfied).isFalse()
    }

    @Test
    fun `both verification layers are required`() {
        val verified = FilePreviewVerification(
            authority = FilePreviewVerificationAuthority.SECURE_MATRIX_MEDIA_LOADER,
            carrierIntegrityVerified = true,
            requiredPlaintextAuthenticationSatisfied = true,
        )

        assertThat(verified.isSatisfied).isTrue()
    }
}
