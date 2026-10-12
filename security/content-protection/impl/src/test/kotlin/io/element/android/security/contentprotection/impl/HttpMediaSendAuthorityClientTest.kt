/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.MediaSendAuthorityConfigurationProvider
import io.element.android.security.contentprotection.api.MediaSendBindRequest
import io.element.android.security.contentprotection.api.MediaSendTrustedClock
import io.element.android.security.contentprotection.api.OfflineLicenseClock
import io.element.android.security.securestorage.api.DeviceTrustService
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HttpMediaSendAuthorityClientTest {
    @Test fun `missing immutable production configuration fails before network`() = runTest {
        val client = client(MediaSendAuthorityConfigurationProvider { null })
        assertThat(client.issue("op-1", "req-1").isFailure).isTrue()
        assertThat(client.fetchIssuerRegistry().isFailure).isTrue()
    }

    @Test fun `noncanonical identifiers and bind digests fail before challenge bootstrap`() = runTest {
        val client = client(MediaSendAuthorityConfigurationProvider { null })
        assertThat(client.issue("../op", "req-1").isFailure).isTrue()
        assertThat(client.bind(MediaSendBindRequest("op-1", "req-1", "nonce-1", "bad", "txn-1", "0".repeat(64))).isFailure).isTrue()
        assertThat(client.recover("op%2f1", "req-1").isFailure).isTrue()
    }

    private fun client(configuration: MediaSendAuthorityConfigurationProvider) = HttpMediaSendAuthorityClient(
        configuration, mockk<SessionStore>(relaxed = true), mockk<DeviceTrustService>(relaxed = true),
        DeviceRuntimeBindingProvider { 1 }, mockk<OfflineLicenseClock>(relaxed = true), mockk<MediaSendTrustedClock>(relaxed = true),
    )
}
