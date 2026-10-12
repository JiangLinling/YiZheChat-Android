/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.security.securestorage.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.*
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SecureMediaFreshReadAdmissionTest {
    @Test fun `fresh grant keeps descriptor resource provider revision and original process TTL`() = runTest {
        val f = fixture()
        val provider = checkNotNull(SecureMediaLeaseRegistry.beginProviderRead(f.token))
        val original = checkNotNull(f.entry.authorizedState)
        repeat(9) { index ->
            f.validator.elapsed = (index + 1) * 40_000L
            assertThat(SecureMediaLeaseRegistry.maintainReadAuthorization(f.token)).isTrue()
            val fresh = checkNotNull(f.entry.authorizedState)
            assertThat(fresh.processTtlStartElapsedRealtimeMillis).isEqualTo(original.processTtlStartElapsedRealtimeMillis)
            assertThat(fresh.processTtlDeadlineElapsedRealtimeMillis).isEqualTo(original.processTtlDeadlineElapsedRealtimeMillis)
            assertThat(fresh.logicalResource).isEqualTo(original.logicalResource)
            assertThat(f.entry.revision).isEqualTo(0)
            val destination = ByteArray(3)
            assertThat(SecureMediaLeaseRegistry.copyAuthenticatedRangeInto(f.token,
                SecureMediaAuthenticatedReadRequest(0, 3, SecureMediaReadPurpose.LEASE_STREAM), destination, destinationOffset = 0))
                .isEqualTo(SecureMediaAuthenticatedReadResult.Copied(3))
            assertThat(destination).isEqualTo(byteArrayOf(1, 2, 3))
        }
        val latest = checkNotNull(f.entry.authorizedState).authority as Authority
        SecureMediaLeaseRegistry.revoke(f.token)
        assertThat(f.cleanups).isEqualTo(0)
        assertThat(latest.closed).isFalse()
        provider.close()
        assertThat(f.cleanups).isEqualTo(1)
        assertThat(latest.closed).isTrue()
    }

    @Test fun `fresh permission cannot reset expired process TTL`() = runTest {
        val f = fixture(processDeadline = 50_000)
        f.validator.elapsed = 40_000
        assertThat(SecureMediaLeaseRegistry.maintainReadAuthorization(f.token)).isTrue()
        f.validator.elapsed = 50_000
        assertThat(SecureMediaLeaseRegistry.maintainReadAuthorization(f.token)).isFalse()
        assertThat(f.validator.renewCalls).isEqualTo(1)
        assertThat(f.cleanups).isEqualTo(1)
    }

    @Test fun `expired old grant is never sent for fresh admission`() = runTest {
        val f = fixture()
        f.validator.elapsed = 60_000
        assertThat(SecureMediaLeaseRegistry.maintainReadAuthorization(f.token)).isFalse()
        assertThat(f.validator.renewCalls).isEqualTo(0)
    }

    @Test fun `revocation while fresh request suspended closes returned candidate without resurrection`() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        f.validator.onRenew = { entered.complete(Unit); proceed.await() }
        val pending = async { SecureMediaLeaseRegistry.maintainReadAuthorization(f.token) }
        entered.await()
        SecureMediaLeaseRegistry.revoke(f.token)
        proceed.complete(Unit)
        assertThat(pending.await()).isFalse()
        assertThat(f.validator.lastCandidate?.closed).isTrue()
        assertThat(SecureMediaLeaseRegistry.acquire(f.token)).isNull()
        assertThat(f.cleanups).isEqualTo(1)
    }

    @Test fun `old expiry during fresh request closes candidate and leaves caller bytes unchanged`() = runTest {
        val f = fixture()
        f.validator.onRenew = { f.validator.elapsed = 60_000 }
        assertThat(SecureMediaLeaseRegistry.maintainReadAuthorization(f.token)).isFalse()
        assertThat(f.validator.lastCandidate?.closed).isTrue()
        val destination = ByteArray(3) { 77 }
        assertThat(SecureMediaLeaseRegistry.copyAuthenticatedRangeInto(f.token,
            SecureMediaAuthenticatedReadRequest(0, 3, SecureMediaReadPurpose.LEASE_STREAM), destination, destinationOffset = 0))
            .isInstanceOf(SecureMediaAuthenticatedReadResult.Rejected::class.java)
        assertThat(destination).isEqualTo(ByteArray(3) { 77 })
    }

    @Test fun `binding revision purpose and clock mismatch retire source`() = runTest {
        val changes: List<(SecureMediaVerifiedAuthorization) -> SecureMediaVerifiedAuthorization> = listOf(
            { it.copy(contentId = "different") }, { it.copy(immutableContextSha256Hex = "f".repeat(64)) },
            { it.copy(authorizationRevision = 8) }, { it.copy(revocationScope = "different") },
            { it.copy(localAuthorityId = "external") },
        )
        changes.forEach { change ->
            val f = fixture()
            f.validator.transform = change
            assertThat(SecureMediaLeaseRegistry.maintainReadAuthorization(f.token)).isFalse()
            assertThat(f.validator.lastCandidate?.closed).isTrue()
            assertThat(f.cleanups).isEqualTo(1)
        }
        val f = fixture()
        f.validator.onRenew = { f.validator.boot = 2 }
        assertThat(SecureMediaLeaseRegistry.maintainReadAuthorization(f.token)).isFalse()
        assertThat(f.validator.lastCandidate?.closed).isTrue()
    }

    @Test fun `plaintext read slot delays replacement until safe without invalidating descriptor`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val f = fixture(beforeRange = { entered.complete(Unit); proceed.await() })
        val destination = ByteArray(3)
        val read = async { SecureMediaLeaseRegistry.copyAuthenticatedRangeInto(f.token,
            SecureMediaAuthenticatedReadRequest(0, 3, SecureMediaReadPurpose.LEASE_STREAM), destination, destinationOffset = 0) }
        entered.await()
        val original = f.entry.authorizedState
        val pending = async { SecureMediaLeaseRegistry.maintainReadAuthorization(f.token) }
        runCurrent()
        assertThat(pending.isCompleted).isFalse()
        assertThat(f.entry.authorizedState).isSameInstanceAs(original)
        proceed.complete(Unit)
        assertThat(read.await()).isEqualTo(SecureMediaAuthenticatedReadResult.Copied(3))
        assertThat(destination).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(pending.await()).isTrue()
        SecureMediaLeaseRegistry.revoke(f.token)
    }

    @Test fun `cancellation of fresh request retires source and closes once`() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        f.validator.onRenew = { entered.complete(Unit); awaitCancellation() }
        val pending = async { SecureMediaLeaseRegistry.maintainReadAuthorization(f.token) }
        entered.await()
        pending.cancel()
        pending.join()
        assertThat(SecureMediaLeaseRegistry.acquire(f.token)).isNull()
        assertThat(f.cleanups).isEqualTo(1)
    }

    @Test fun `external purpose is never refreshed`() = runTest {
        val f = fixture()
        f.entry.authorizedState = checkNotNull(f.entry.authorizedState).copy(allowedPurpose = SecureMediaReadPurpose.EXTERNAL_OPEN)
        assertThat(SecureMediaLeaseRegistry.maintainReadAuthorization(f.token)).isTrue()
        assertThat(f.validator.renewCalls).isEqualTo(0)
        SecureMediaLeaseRegistry.revoke(f.token)
    }

    private suspend fun fixture(processDeadline: Long = 3_600_000, beforeRange: suspend () -> Unit = {}): Fixture {
        val store = InMemorySensitiveFileStore()
        val descriptor = SecureMediaLogicalResourceDescriptor(assetId = "asset", mediaVersion = 1,
            roomIdSha256Hex = "1".repeat(64), contentContractId = "contract", resourceId = "resource", contentId = "content",
            kind = "ATTACHMENT", index = 0, expectedPlaintextLength = 3, immutableContextSha256Hex = "2".repeat(64),
            matrixLocatorSha256Hex = "3".repeat(64), outerFrameSha256Hex = "4".repeat(64), outerFrameLength = 100,
            vaultId = AccountVaultId("vault"), objectId = SecureObjectId(UUID.randomUUID().toString()), purpose = SecureObjectPurpose.ATTACHMENT)
        val reference = store.write(SecureObjectWriteRequest(descriptor.objectId, descriptor.vaultId, descriptor.purpose,
            descriptor.canonicalBytes(), expectedPlaintextLength = 3)) { it.write(byteArrayOf(1, 2, 3)) }
        val validator = Validator()
        val original = Authority(validator.base)
        val f = Fixture(UUID.randomUUID().toString(), validator)
        val guardedStore = object : SensitiveFileStore by store {
            override suspend fun readRangeInto(reference: SecureObjectReference, offset: Long, byteCount: Int,
                destination: ByteArray, destinationOffset: Int): Int {
                beforeRange()
                val bytes = store.readRange(reference, offset, byteCount)
                try {
                    bytes.copyInto(destination, destinationOffset)
                    return bytes.size
                } finally {
                    bytes.fill(0)
                }
            }
        }
        f.entry = SecureMediaLeaseRegistry.Entry(reference, "application/pdf", null, Long.MAX_VALUE, true, guardedStore,
            onRevoked = { f.cleanups++; original.close() }, authorizedState = SecureMediaLeaseRegistry.AuthorizedState(
                original, validator, "content", "2".repeat(64), validator.base, 1, 0, processDeadline,
                descriptor.digestSha256Hex(), SecureMediaReadPurpose.LEASE_STREAM, descriptor))
        SecureMediaLeaseRegistry.register(f.token, f.entry)
        return f
    }

    private class Fixture(val token: String, val validator: Validator) {
        lateinit var entry: SecureMediaLeaseRegistry.Entry
        var cleanups = 0
    }
    private class Authority(val authorization: SecureMediaVerifiedAuthorization) : SecureMediaReleaseAuthority {
        var closed = false
        override fun close() { closed = true }
    }
    private class Validator : SecureMediaAuthorizationValidator {
        val base = SecureMediaVerifiedAuthorization("initial", "content", "2".repeat(64), 60_000, 7, "content:content")
        var elapsed = 40_000L
        var boot = 1L
        var renewCalls = 0
        var onRenew: suspend () -> Unit = {}
        var transform: (SecureMediaVerifiedAuthorization) -> SecureMediaVerifiedAuthorization = { it }
        var lastCandidate: Authority? = null
        override suspend fun renewReadAuthority(authority: SecureMediaReleaseAuthority): SecureMediaReleaseAuthority {
            renewCalls++
            onRenew()
            return Authority(transform(base.copy(authorityId = "fresh-$renewCalls", authorizationDeadlineEpochMillis = elapsed + 60_000)))
                .also { lastCandidate = it }
        }
        override fun validate(authority: SecureMediaReleaseAuthority, expectedContentId: String,
            expectedImmutableContextSha256Hex: String, expectedAuthorizationRevision: Long?): SecureMediaAuthorizationValidation {
            val issued = authority as Authority
            return when {
                issued.closed -> SecureMediaAuthorizationValidation.Rejected(SecureMediaAuthorizationRejection.CLOSED)
                elapsed >= issued.authorization.authorizationDeadlineEpochMillis ->
                    SecureMediaAuthorizationValidation.Rejected(SecureMediaAuthorizationRejection.EXPIRED)
                else -> SecureMediaAuthorizationValidation.Verified(issued.authorization, SecureMediaValidationTime(boot, elapsed))
            }
        }
    }
}
