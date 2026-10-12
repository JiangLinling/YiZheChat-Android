/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.embedded

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmbeddedNtfyRegistrationTest {
    @Test
    fun `plain ntfy message is returned as UTF-8 bytes`() {
        val envelope = NtfyEnvelope(event = "message", message = "hello")

        assertThat(envelope.messageBytes()).isEqualTo("hello".encodeToByteArray())
    }

    @Test
    fun `base64 ntfy message is decoded before UnifiedPush handling`() {
        val envelope = NtfyEnvelope(event = "message", message = "AAEC_w==", encoding = "base64")

        assertThat(envelope.messageBytes()).isEqualTo(byteArrayOf(0, 1, 2, -1))
    }

    @Test
    fun `invalid or unsupported encodings are rejected`() {
        assertThat(NtfyEnvelope(event = "message", message = "not base64!", encoding = "base64").messageBytes()).isNull()
        assertThat(NtfyEnvelope(event = "message", message = "hello", encoding = "gzip").messageBytes()).isNull()
    }
}
