/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ReleasedRoomEpoch
import io.element.android.security.contentprotection.api.RoomEpochReference
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomEpochMemoryCacheTest {
    private var now = 1_000L
    private val reference = RoomEpochReference("test-epoch", 1L, ByteArray(32) { 2 })
    private val cache = RoomEpochMemoryCache(SystemClock { now })

    @Test
    fun `concurrent misses load one validated epoch and return independently owned copies`() = runTest {
        val ready = CompletableDeferred<Unit>()
        var loads = 0
        val readers = List(4) {
            async { cache.releaseOrLoad(reference, "account") { loads++; ready.await(); released() } }
        }
        runCurrent()
        assertThat(loads).isEqualTo(1)
        ready.complete(Unit)
        val epochs = readers.awaitAll()
        assertThat(loads).isEqualTo(1)
        epochs.first().epochSecret.fill(0)
        epochs.drop(1).forEach { assertThat(it.epochSecret.all { byte -> byte == 7.toByte() }).isTrue() }
        assertThat(cache.forRelease(reference, "account")?.epochSecret?.all { it == 7.toByte() }).isTrue()
    }

    @Test
    fun `lock invalidation denies late loader and queued old generation without repopulating secrets`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val value = released()
        var loads = 0
        val admittedGeneration = cache.generation()
        val readers = List(2) {
            async { runCatching { cache.releaseOrLoad(reference, "account", admittedGeneration) { loads++; ready.await(); value } } }
        }
        runCurrent()
        cache.clear()
        ready.complete(Unit)
        readers.awaitAll().forEach { assertThat(it.exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java) }
        assertThat(loads).isEqualTo(1)
        assertThat(value.epochSecret.all { it == 0.toByte() }).isTrue()
        assertThat(cache.forRelease(reference, "account")).isNull()
        assertThat(cache.releaseOrLoad(reference, "account") { released() }.epochSecret.all { it == 7.toByte() }).isTrue()
    }

    @Test
    fun `failed loader leaves no cached epoch and next caller performs a genuine retry`() = runTest {
        val first = runCatching { cache.releaseOrLoad(reference, "account") { throw ContentProtectionException.KmsUnavailable() } }
        assertThat(first.exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
        assertThat(cache.forRelease(reference, "account")).isNull()
        var loads = 0
        cache.releaseOrLoad(reference, "account") { loads++; released() }
        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `different account or epoch cannot reuse a release and rejected secret is zeroed`() = runTest {
        cache.releaseOrLoad(reference, "account") { released() }
        val otherAccount = released()
        val deniedAccount = runCatching { cache.releaseOrLoad(reference, "other-account") { otherAccount } }
        assertThat(deniedAccount.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
        assertThat(otherAccount.epochSecret.all { it == 0.toByte() }).isTrue()
        val otherEpoch = released()
        val deniedEpoch = runCatching { cache.releaseOrLoad(reference.copy(generation = 2L), "account") { otherEpoch } }
        assertThat(deniedEpoch.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
        assertThat(otherEpoch.epochSecret.all { it == 0.toByte() }).isTrue()
        assertThat(cache.forRelease(reference, "account")?.epochSecret?.all { it == 7.toByte() }).isTrue()
    }

    @Test
    fun `existing five minute cache deadline still forces a fresh release`() = runTest {
        var loads = 0
        cache.releaseOrLoad(reference, "account") { loads++; released() }
        now += 5 * 60_000L
        cache.releaseOrLoad(reference, "account") { loads++; released() }
        assertThat(loads).isEqualTo(2)
    }

    private fun released() = ReleasedRoomEpoch(
        reference = reference, roomIdSha256 = ByteArray(32) { 3 }, accountIdHash = "account", installationId = "installation",
        recipientKeyId = "key", requestNonce = ByteArray(24), activeUntilEpochMillis = 1_000_000L,
        releaseUntilEpochMillis = 1_000_000L, serverTimeEpochMillis = now, epochSecret = ByteArray(32) { 7 },
    )
}
