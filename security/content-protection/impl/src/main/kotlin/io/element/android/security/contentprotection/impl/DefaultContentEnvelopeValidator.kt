/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.security.contentprotection.api.ContentEnvelopeValidator
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.ContentProtectionHeader
import io.element.android.security.contentprotection.api.MessageLifecycleMode

@ContributesBinding(AppScope::class)
class DefaultContentEnvelopeValidator : ContentEnvelopeValidator {
    override fun validate(header: ContentProtectionHeader): Result<Unit> {
        return try {
            require(
                header.formatVersion in ContentProtectionFormat.LEGACY_DEVICE_ENVELOPE_VERSION..ContentProtectionFormat.CURRENT_VERSION
            ) { "Unsupported content format version." }
            require(header.contentId.value.isNotBlank()) { "Content id must not be blank." }
            require(header.policy.policyId.value.isNotBlank()) { "Policy id must not be blank." }
            require(header.policy.policyVersion > 0) { "Policy version must be positive." }
            require(header.policy.expiresAtEpochMillis > header.createdAtEpochMillis) { "Content must expire after it is created." }
            require(header.senderInstallationId.isNotBlank()) { "Sender installation id must not be blank." }
            require(header.immutableContextSha256.size == ContentProtectionFormat.SHA256_SIZE_BYTES) {
                "Immutable context digest has an invalid length."
            }
            require(header.nonce.size == ContentProtectionFormat.GCM_NONCE_SIZE_BYTES) { "Content nonce has an invalid length." }
            if (header.formatVersion == ContentProtectionFormat.LEGACY_DEVICE_ENVELOPE_VERSION) {
                require(header.roomEpoch == null) { "Legacy content must not contain a room epoch." }
                require(header.audienceAccountIdHashes.isNotEmpty()) { "At least one audience account is required." }
                require(header.audienceAccountIdHashes.size <= MAX_AUDIENCE_ACCOUNTS) { "Too many audience accounts." }
                require(header.audienceAccountIdHashes.all { ACCOUNT_HASH.matches(it) }) { "Audience account hash is malformed." }
                require(header.deviceShareEnvelopes.isNotEmpty()) { "At least one device share envelope is required." }
                require(header.deviceShareEnvelopes.size <= MAX_RECIPIENTS) { "Too many device share envelopes." }
                require(
                    header.deviceShareEnvelopes.map { it.recipientInstallationId }.distinct().size ==
                        header.deviceShareEnvelopes.size
                ) { "Recipient installation ids must be unique." }
                header.deviceShareEnvelopes.forEach { envelope ->
                    require(envelope.recipientInstallationId.isNotBlank()) { "Recipient installation id must not be blank." }
                    require(envelope.recipientKeyId.isNotBlank()) { "Recipient key id must not be blank." }
                    require(envelope.algorithm == ContentProtectionFormat.DEVICE_SHARE_ALGORITHM) {
                        "Unsupported device share algorithm."
                    }
                    require(envelope.ciphertext.isNotEmpty()) { "Device share ciphertext must not be empty." }
                    require(envelope.ciphertext.size <= MAX_DEVICE_SHARE_CIPHERTEXT_BYTES) {
                        "Device share ciphertext is too large."
                    }
                }
            } else {
                val epoch = requireNotNull(header.roomEpoch) { "Room epoch reference is required." }
                require(header.audienceAccountIdHashes.isEmpty()) { "Room-epoch headers must not expose the audience list." }
                require(header.deviceShareEnvelopes.isEmpty()) { "Room-epoch headers must have fixed recipient size." }
                require(epoch.epochId.length in 24..128) { "Room epoch id is malformed." }
                require(epoch.generation > 0) { "Room epoch generation must be positive." }
                require(epoch.audienceDigestSha256.size == ContentProtectionFormat.SHA256_SIZE_BYTES) {
                    "Room audience digest has an invalid length."
                }
            }
            require(header.kmsShareReference.value.isNotBlank()) { "KMS share reference must not be blank." }
            if (header.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) {
                require(header.lifecycle.conversationKind != null) { "Burn-after-read conversation kind is required." }
                require(header.offlineLeaseMode == io.element.android.security.contentprotection.api.OfflineLeaseMode.ONLINE_REQUIRED) {
                    "Burn-after-read must not carry an offline license."
                }
                require(header.kind in BURN_AFTER_READ_KINDS) { "This content kind does not support burn-after-read." }
            } else {
                require(header.lifecycle.conversationKind == null) { "Room-following content must not carry a conversation kind." }
            }
            Result.success(Unit)
        } catch (failure: IllegalArgumentException) {
            Result.failure(failure)
        }
    }

    private companion object {
        val ACCOUNT_HASH = Regex("[0-9a-f]{64}")
        const val MAX_AUDIENCE_ACCOUNTS = 512
        const val MAX_RECIPIENTS = 512
        const val MAX_DEVICE_SHARE_CIPHERTEXT_BYTES = 1024
        val BURN_AFTER_READ_KINDS = setOf(
            io.element.android.security.contentprotection.api.ContentKind.TEXT,
            io.element.android.security.contentprotection.api.ContentKind.IMAGE,
            io.element.android.security.contentprotection.api.ContentKind.VOICE_MESSAGE,
            io.element.android.security.contentprotection.api.ContentKind.VIDEO,
            io.element.android.security.contentprotection.api.ContentKind.ATTACHMENT,
            io.element.android.security.contentprotection.api.ContentKind.LOCATION,
        )
    }
}
