/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.impl

import android.content.pm.ProviderInfo
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRejection
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

/** Same-process provider integration. This does not assert Android target UID grants or OEM lifecycle timing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecureMediaProviderAdmissionTest {
    @Test
    fun `live leased source is readable and the same uri is rejected after explicit close`() = runTest {
        val memoryStore = InMemorySensitiveFileStore()
        val streamed = CountDownLatch(1)
        val reads = AtomicInteger()
        val store = object : SensitiveFileStore by memoryStore {
            override suspend fun <T> read(reference: SecureObjectReference, readPlaintext: suspend (InputStream) -> T): T {
                val result = memoryStore.read(reference, readPlaintext)
                if (reads.incrementAndGet() == 2) streamed.countDown()
                return result
            }
        }
        val reference = store.write(request()) { it.write(byteArrayOf(1, 2, 3)) }
        val context = RuntimeEnvironment.getApplication()
        val manager = DefaultSecureMediaLeaseManager(context, backgroundScope, store)
        val lease = manager.issue(SecureMediaLeaseRequest(reference, "application/octet-stream", null, 5.minutes))
        val provider = provider()

        assertThat(manager.readAuthorizationRejection(lease.uri)).isNull()
        val descriptor = provider.openFile(lease.uri, "r")
        // Robolectric represents this pipe with a non-blocking regular file. Await the real provider worker's writes.
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            assertThat(streamed.await(10, TimeUnit.SECONDS)).isTrue()
            assertThat(it.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        }
        lease.close()
        assertThat(manager.readAuthorizationRejection(lease.uri)).isEqualTo(SecureMediaAuthenticatedReadRejection.REVOKED)
        assertThrows(FileNotFoundException::class.java) { provider.openFile(lease.uri, "r") }
    }

    @Test
    fun `live guard denial cannot be repaired by reusing uri or restoring the guard`() = runTest {
        val store = InMemorySensitiveFileStore()
        val reference = store.write(request()) { it.write(byteArrayOf(1)) }
        val manager = DefaultSecureMediaLeaseManager(RuntimeEnvironment.getApplication(), backgroundScope, store)
        var authorized = true
        val lease = manager.issue(SecureMediaLeaseRequest(reference, null, null, 5.minutes, isReadAuthorized = { authorized }))

        authorized = false
        assertThat(manager.readAuthorizationRejection(lease.uri)).isEqualTo(SecureMediaAuthenticatedReadRejection.AUTHORITY_UNAVAILABLE)
        authorized = true
        assertThat(manager.readAuthorizationRejection(lease.uri)).isEqualTo(SecureMediaAuthenticatedReadRejection.REVOKED)
        assertThrows(FileNotFoundException::class.java) { provider().openFile(lease.uri, "r") }
    }

    @Test
    fun `malformed or foreign source fails binding without consuming valid source`() = runTest {
        val store = InMemorySensitiveFileStore()
        val reference = store.write(request()) { it.write(byteArrayOf(1)) }
        val manager = DefaultSecureMediaLeaseManager(RuntimeEnvironment.getApplication(), backgroundScope, store)
        val lease = manager.issue(SecureMediaLeaseRequest(reference, null, null, 5.minutes))

        assertThat(manager.readAuthorizationRejection(lease.uri.buildUpon().appendQueryParameter("test", "1").build()))
            .isEqualTo(SecureMediaAuthenticatedReadRejection.BINDING_INVALID)
        assertThat(manager.readAuthorizationRejection(lease.uri.buildUpon().authority("foreign.securemedia").build()))
            .isEqualTo(SecureMediaAuthenticatedReadRejection.BINDING_INVALID)
        assertThat(manager.readAuthorizationRejection(lease.uri)).isNull()
        lease.close()
    }

    private fun provider() = SecureMediaContentProvider().apply {
        attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply {
            authority = "${RuntimeEnvironment.getApplication().packageName}.securemedia"
            exported = false
            grantUriPermissions = true
        })
    }

    private fun request() = SecureObjectWriteRequest(
        objectId = SecureObjectId("provider-qa"), vaultId = AccountVaultId("provider-qa-vault"),
        purpose = SecureObjectPurpose.ATTACHMENT, associatedData = "provider-qa".encodeToByteArray(),
    )
}
