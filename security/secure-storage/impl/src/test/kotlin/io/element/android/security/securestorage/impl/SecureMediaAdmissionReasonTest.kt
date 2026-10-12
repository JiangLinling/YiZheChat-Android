/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRejection
import io.element.android.security.securestorage.api.SecureMediaAuthorizationRejection
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidation
import io.element.android.security.securestorage.api.SecureMediaAuthorizationValidator
import io.element.android.security.securestorage.api.SecureMediaReadPurpose
import io.element.android.security.securestorage.api.SecureMediaReleaseAuthority
import io.element.android.security.securestorage.api.SecureMediaVerifiedAuthorization
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import org.junit.Test
import java.util.UUID

class SecureMediaAdmissionReasonTest {
    @Test
    fun `validator rejection preserves exact reason and retires source only once`() {
        for (reason in SecureMediaAuthorizationRejection.entries) {
            val token = UUID.randomUUID().toString()
            var closed = 0
            val validator = object : SecureMediaAuthorizationValidator {
                override fun validate(authority: SecureMediaReleaseAuthority, expectedContentId: String,
                    expectedImmutableContextSha256Hex: String, expectedAuthorizationRevision: Long?) =
                    SecureMediaAuthorizationValidation.Rejected(reason)
            }
            val entry = entry(onRevoked = { closed++ }).copyWithAuthority(validator)
            SecureMediaLeaseRegistry.register(token, entry)
            var observed: SecureMediaAuthenticatedReadRejection? = null
            assertThat(SecureMediaLeaseRegistry.acquire(token) { observed = it }).isNull()
            assertThat(observed?.name).isEqualTo(reason.name)
            assertThat(closed).isEqualTo(1)
            assertThat(SecureMediaLeaseRegistry.acquire(token) { observed = it }).isNull()
            assertThat(observed).isEqualTo(SecureMediaAuthenticatedReadRejection.REVOKED)
            assertThat(closed).isEqualTo(1)
        }
    }

    @Test
    fun `expired source reports expiry on first check and revoked after retirement`() {
        val token = UUID.randomUUID().toString()
        SecureMediaLeaseRegistry.register(token, entry(expiresAt = 0))
        var observed: SecureMediaAuthenticatedReadRejection? = null
        assertThat(SecureMediaLeaseRegistry.acquire(token) { observed = it }).isNull()
        assertThat(observed).isEqualTo(SecureMediaAuthenticatedReadRejection.EXPIRED)
        assertThat(SecureMediaLeaseRegistry.acquire(token) { observed = it }).isNull()
        assertThat(observed).isEqualTo(SecureMediaAuthenticatedReadRejection.REVOKED)
    }

    @Test
    fun `authorized check neither calls rejection observer nor extends deadline`() {
        val token = UUID.randomUUID().toString()
        val entry = entry()
        SecureMediaLeaseRegistry.register(token, entry)
        var rejections = 0
        assertThat(SecureMediaLeaseRegistry.acquire(token) { rejections++ }).isSameInstanceAs(entry)
        assertThat(rejections).isEqualTo(0)
        assertThat(entry.expiresAtMillis).isEqualTo(Long.MAX_VALUE)
        SecureMediaLeaseRegistry.revoke(token)
    }

    @Test
    fun `diagnostic failure cannot skip retirement or cleanup`() {
        val failure = IllegalStateException("synthetic diagnostic failure")
        val validator = object : SecureMediaAuthorizationValidator {
            override fun validate(authority: SecureMediaReleaseAuthority, expectedContentId: String,
                expectedImmutableContextSha256Hex: String, expectedAuthorizationRevision: Long?) =
                SecureMediaAuthorizationValidation.Rejected(SecureMediaAuthorizationRejection.REVISION_MISMATCH)
        }
        for (mode in listOf("expired", "authority", "legacy")) {
            val token = UUID.randomUUID().toString()
            var closed = 0
            val base = entry(expiresAt = if (mode == "expired") 0 else Long.MAX_VALUE, onRevoked = { closed++ })
            val rejectedEntry = when (mode) {
                "authority" -> base.copyWithAuthority(validator)
                "legacy" -> SecureMediaLeaseRegistry.Entry(
                    reference = base.reference, mimeType = base.mimeType, displayName = base.displayName,
                    expiresAtMillis = base.expiresAtMillis, requireSeekableDescriptor = false,
                    sensitiveFileStore = base.sensitiveFileStore, isReadAuthorized = { false }, onRevoked = base.onRevoked,
                )
                else -> base
            }
            SecureMediaLeaseRegistry.register(token, rejectedEntry)
            val thrown = runCatching { SecureMediaLeaseRegistry.acquire(token) { throw failure } }.exceptionOrNull()
            assertThat(thrown).isSameInstanceAs(failure)
            assertThat(closed).isEqualTo(1)
            assertThat(SecureMediaLeaseRegistry.acquire(token)).isNull()
            assertThat(closed).isEqualTo(1)
        }
    }

    private fun entry(expiresAt: Long = Long.MAX_VALUE, onRevoked: () -> Unit = {}) = SecureMediaLeaseRegistry.Entry(
        reference = reference(), mimeType = "application/octet-stream", displayName = null,
        expiresAtMillis = expiresAt, requireSeekableDescriptor = false,
        sensitiveFileStore = InMemorySensitiveFileStore(), onRevoked = onRevoked,
    )

    private fun SecureMediaLeaseRegistry.Entry.copyWithAuthority(validator: SecureMediaAuthorizationValidator) = SecureMediaLeaseRegistry.Entry(
        reference = reference, mimeType = mimeType, displayName = displayName, expiresAtMillis = expiresAtMillis,
        requireSeekableDescriptor = requireSeekableDescriptor, sensitiveFileStore = sensitiveFileStore, onRevoked = onRevoked,
        authorizedState = SecureMediaLeaseRegistry.AuthorizedState(
            authority = object : SecureMediaReleaseAuthority { override fun close() = Unit }, validator = validator,
            expectedContentId = "synthetic-content", expectedImmutableContextSha256Hex = "b".repeat(64),
            authorization = SecureMediaVerifiedAuthorization("synthetic-authority", "synthetic-content", "b".repeat(64), Long.MAX_VALUE, 1, null),
            bootCount = 1, processTtlStartElapsedRealtimeMillis = 0, processTtlDeadlineElapsedRealtimeMillis = Long.MAX_VALUE,
            logicalResourceDigestSha256Hex = reference.associatedDataSha256, allowedPurpose = SecureMediaReadPurpose.LEASE_STREAM,
        ),
    )

    private fun reference() = SecureObjectReference(
        objectId = SecureObjectId("synthetic-object"), vaultId = AccountVaultId("synthetic-vault"),
        purpose = SecureObjectPurpose.ATTACHMENT, keyEnvelopeId = KeyEnvelopeId("synthetic-key"),
        formatVersion = 1, plaintextLength = 1, ciphertextLength = 33, associatedDataSha256 = "a".repeat(64),
    )
}
