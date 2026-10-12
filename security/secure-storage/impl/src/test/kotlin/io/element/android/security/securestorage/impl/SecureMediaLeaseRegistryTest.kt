/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import org.junit.Test
import java.util.UUID

class SecureMediaLeaseRegistryTest {
    @Test
    fun `live authorization denial permanently revokes lease`() {
        val token = UUID.randomUUID().toString()
        var authorized = true
        var revoked = false
        SecureMediaLeaseRegistry.register(
            token,
            SecureMediaLeaseRegistry.Entry(
                reference = SecureObjectReference(
                    objectId = SecureObjectId("authorization-bound"),
                    vaultId = AccountVaultId("test-vault"),
                    purpose = SecureObjectPurpose.VOICE_MESSAGE,
                    keyEnvelopeId = KeyEnvelopeId("test-key"),
                    formatVersion = 1,
                    plaintextLength = 10,
                    ciphertextLength = 42,
                    associatedDataSha256 = "a".repeat(64),
                ),
                mimeType = "audio/ogg",
                displayName = null,
                expiresAtMillis = Long.MAX_VALUE,
                requireSeekableDescriptor = true,
                isReadAuthorized = { authorized },
                sensitiveFileStore = InMemorySensitiveFileStore(),
                onRevoked = { revoked = true },
            )
        )

        assertThat(SecureMediaLeaseRegistry.acquire(token)).isNotNull()
        authorized = false
        assertThat(SecureMediaLeaseRegistry.acquire(token)).isNull()
        assertThat(revoked).isTrue()
        authorized = true
        assertThat(SecureMediaLeaseRegistry.acquire(token)).isNull()
    }
}
