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
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
class DefaultSecureMediaLeaseManagerTest {
    @Test
    fun `closing a persistent cache lease keeps the encrypted object`() = runTest {
        val store = InMemorySensitiveFileStore()
        val reference = store.write(
            io.element.android.security.securestorage.api.SecureObjectWriteRequest(
                objectId = SecureObjectId("cached-preview"),
                vaultId = AccountVaultId("test-vault"),
                purpose = SecureObjectPurpose.DOCUMENT_PREVIEW,
                associatedData = "preview".encodeToByteArray(),
            )
        ) { it.write("%PDF-test".encodeToByteArray()) }
        val manager = DefaultSecureMediaLeaseManager(
            context = RuntimeEnvironment.getApplication(),
            appCoroutineScope = this,
            sensitiveFileStore = store,
        )

        manager.issue(
            SecureMediaLeaseRequest(
                reference = reference,
                mimeType = "application/pdf",
                displayName = "preview.pdf",
                timeToLive = 5.minutes,
                requireSeekableDescriptor = true,
                deleteObjectOnRevoke = false,
            )
        ).close()

        assertThat(store.read(reference) { it.readBytes().decodeToString() }).isEqualTo("%PDF-test")
    }

    @Test
    fun `revoking a scope removes every media capability in that scope only`() = runTest {
        val manager = DefaultSecureMediaLeaseManager(
            context = RuntimeEnvironment.getApplication(),
            appCoroutineScope = this,
            sensitiveFileStore = InMemorySensitiveFileStore(),
        )
        val first = manager.issue(aRequest("first", "burn-session"))
        val second = manager.issue(aRequest("second", "burn-session"))
        val unrelated = manager.issue(aRequest("third", "other-session"))

        manager.revokeScope("burn-session")

        assertThat(SecureMediaLeaseRegistry.acquire(first.token())).isNull()
        assertThat(SecureMediaLeaseRegistry.acquire(second.token())).isNull()
        assertThat(SecureMediaLeaseRegistry.acquire(unrelated.token())).isNotNull()
        unrelated.close()
    }

    @Test
    fun `revoking a vault removes only capabilities backed by that account vault`() = runTest {
        val manager = DefaultSecureMediaLeaseManager(
            context = RuntimeEnvironment.getApplication(),
            appCoroutineScope = this,
            sensitiveFileStore = InMemorySensitiveFileStore(),
        )
        val deletedAccount = manager.issue(aRequest("deleted", "viewer", "deleted-vault"))
        val otherAccount = manager.issue(aRequest("other", "viewer", "other-vault"))

        manager.revokeVault(AccountVaultId("deleted-vault"))

        assertThat(SecureMediaLeaseRegistry.acquire(deletedAccount.token())).isNull()
        assertThat(SecureMediaLeaseRegistry.acquire(otherAccount.token())).isNotNull()
        otherAccount.close()
    }

    @Test
    fun `authorization validation rejects malformed foreign and revoked lease uris`() = runTest {
        val manager = DefaultSecureMediaLeaseManager(
            context = RuntimeEnvironment.getApplication(),
            appCoroutineScope = this,
            sensitiveFileStore = InMemorySensitiveFileStore(),
        )
        val lease = manager.issue(aRequest("authorized", "viewer"))

        assertThat(manager.isReadAuthorized(lease.uri)).isTrue()
        assertThat(manager.isReadAuthorized(lease.uri.buildUpon().appendQueryParameter("reuse", "1").build())).isFalse()
        assertThat(manager.isReadAuthorized(lease.uri.buildUpon().authority("foreign.securemedia").build())).isFalse()

        lease.close()
        assertThat(manager.isReadAuthorized(lease.uri)).isFalse()
    }

    @Test
    fun `authorization validation permanently revokes a lease denied by its live authority`() = runTest {
        val manager = DefaultSecureMediaLeaseManager(
            context = RuntimeEnvironment.getApplication(),
            appCoroutineScope = this,
            sensitiveFileStore = InMemorySensitiveFileStore(),
        )
        var authorized = true
        val lease = manager.issue(aRequest("live-authority", "viewer").copy(isReadAuthorized = { authorized }))
        assertThat(manager.isReadAuthorized(lease.uri)).isTrue()

        authorized = false
        assertThat(manager.isReadAuthorized(lease.uri)).isFalse()

        authorized = true
        assertThat(manager.isReadAuthorized(lease.uri)).isFalse()
    }

    private fun aRequest(objectId: String, scope: String, vaultId: String = "test-vault") = SecureMediaLeaseRequest(
        reference = SecureObjectReference(
            objectId = SecureObjectId(objectId),
            vaultId = AccountVaultId(vaultId),
            purpose = SecureObjectPurpose.IMAGE,
            keyEnvelopeId = KeyEnvelopeId("key-$objectId"),
            formatVersion = 1,
            plaintextLength = 10,
            ciphertextLength = 42,
            associatedDataSha256 = "a".repeat(64),
        ),
        mimeType = "image/jpeg",
        displayName = "$objectId.jpg",
        timeToLive = 5.minutes,
        revocationScope = scope,
    )

    private fun io.element.android.security.securestorage.api.SecureMediaLease.token(): String =
        checkNotNull(uri.lastPathSegment)
}
