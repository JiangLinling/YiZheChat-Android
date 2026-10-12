/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.ContentCipherSuite
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentPolicyReference
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.ContentProtectionHeader
import io.element.android.security.contentprotection.api.ContentLifecycle
import io.element.android.security.contentprotection.api.ConversationKind
import io.element.android.security.contentprotection.api.DeviceShareEnvelope
import io.element.android.security.contentprotection.api.KmsShareReference
import io.element.android.security.contentprotection.api.MessageLifecycleMode
import io.element.android.security.contentprotection.api.PolicyId
import io.element.android.security.contentprotection.api.RoomEpochReference
import org.junit.Test

class ContentEnvelopeValidatorTest {
    private val validator = DefaultContentEnvelopeValidator()

    @Test
    fun `valid legacy device envelope is accepted`() {
        assertThat(validator.validate(aHeader()).isSuccess).isTrue()
    }

    @Test
    fun `valid room epoch envelope is accepted`() {
        val header = aHeader().copy(
            formatVersion = ContentProtectionFormat.CURRENT_VERSION,
            audienceAccountIdHashes = emptySet(),
            deviceShareEnvelopes = emptyList(),
            roomEpoch = RoomEpochReference(
                epochId = "room-epoch-0000000000000001",
                generation = 1,
                audienceDigestSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 2 },
            ),
        )

        assertThat(validator.validate(header).isSuccess).isTrue()
    }

    @Test
    fun `duplicate recipient device is rejected`() {
        val envelope = aDeviceShareEnvelope()
        val result = validator.validate(aHeader().copy(deviceShareEnvelopes = listOf(envelope, envelope)))

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `unsupported format version is rejected`() {
        val result = validator.validate(aHeader().copy(formatVersion = ContentProtectionFormat.CURRENT_VERSION + 1))

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `legacy envelope without authenticated audience is rejected`() {
        val result = validator.validate(aHeader().copy(formatVersion = 1, audienceAccountIdHashes = emptySet()))

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `malformed audience account hash is rejected`() {
        val result = validator.validate(aHeader().copy(audienceAccountIdHashes = setOf("not-a-sha256")))

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `burn after read static location is accepted`() {
        val result = validator.validate(
            aHeader().copy(
                kind = ContentKind.LOCATION,
                lifecycle = ContentLifecycle(MessageLifecycleMode.BURN_AFTER_READ, ConversationKind.DIRECT),
            )
        )

        assertThat(result.isSuccess).isTrue()
    }

    private fun aHeader() = ContentProtectionHeader(
        formatVersion = ContentProtectionFormat.LEGACY_DEVICE_ENVELOPE_VERSION,
        contentId = ContentId("content-1"),
        kind = ContentKind.TEXT,
        cipherSuite = ContentCipherSuite.AES_256_GCM_HKDF_SHA256,
        policy = ContentPolicyReference(
            policyId = PolicyId("policy-1"),
            policyVersion = 1,
            expiresAtEpochMillis = 2,
        ),
        senderInstallationId = "sender-installation",
        audienceAccountIdHashes = setOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        createdAtEpochMillis = 1,
        immutableContextSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 1 },
        nonce = ByteArray(ContentProtectionFormat.GCM_NONCE_SIZE_BYTES) { 1 },
        deviceShareEnvelopes = listOf(aDeviceShareEnvelope()),
        kmsShareReference = KmsShareReference("kms-share-1"),
        offlineLeaseMode = io.element.android.security.contentprotection.api.OfflineLeaseMode.ONLINE_REQUIRED,
    )

    private fun aDeviceShareEnvelope() = DeviceShareEnvelope(
        recipientInstallationId = "recipient-installation",
        recipientKeyId = "recipient-key",
        algorithm = ContentProtectionFormat.DEVICE_SHARE_ALGORITHM,
        ciphertext = byteArrayOf(1),
    )
}
