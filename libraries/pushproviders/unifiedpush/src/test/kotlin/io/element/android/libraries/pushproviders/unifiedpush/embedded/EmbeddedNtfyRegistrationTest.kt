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
    fun `registration from an earlier deployment cannot open a current deployment socket`() {
        val current = requireNotNull(embeddedPushDeploymentBinding(true, "https://security.yizhesy.cn/push/registrar", "https://matrix.yizhesy.cn"))
        val old = requireNotNull(embeddedPushDeploymentBinding(true, "https://next.yizhesy.cn:18443/push/registrar", "https://matrix-next.yizhesy.cn:18443"))
        val registration = EmbeddedNtfyRegistration("test-client", "https://push.example", "topic", "user", "test-password",
            "https://push.example/topic", "https://push.example/gateway", "test-token", Long.MAX_VALUE)

        assertThat(registration.matchesPushDeployment(current)).isFalse()
        assertThat(registration.copy(deploymentBinding = old).matchesPushDeployment(current)).isFalse()
        assertThat(registration.copy(deploymentBinding = current).matchesPushDeployment(current)).isTrue()
        assertThat(registration.matchesPushDeployment(null)).isTrue()
    }

    @Test
    fun `deployment cache binding tolerates trailing slashes but changes with the registrar or homeserver`() {
        val current = embeddedPushDeploymentBinding(true, "https://security.example/registrar", "https://matrix.example")
        assertThat(embeddedPushDeploymentBinding(true, "https://security.example/registrar/", "https://matrix.example/"))
            .isEqualTo(current)
        assertThat(embeddedPushDeploymentBinding(true, "https://other.example/registrar", "https://matrix.example"))
            .isNotEqualTo(current)
        assertThat(embeddedPushDeploymentBinding(true, "https://security.example/registrar", "https://other.example"))
            .isNotEqualTo(current)
    }

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
