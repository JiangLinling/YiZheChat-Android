/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.ContentProtectionException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProtectedReceiveLifecycleTest {
    @Test
    fun `unconfirmed sender echo creates no receive and uses only its pending payload`() = runTest {
        var receives = 0
        var releases = 0
        val pending = byteArrayOf(7)
        repeat(3) {
            val result = withProtectedReceiveLifecycle(null, true, false,
                receive = { receives++; error("Unconfirmed event admitted") },
                pendingLocalEcho = { protectedReceivePayload(pending, true) { releases++; error("Unexpected release") } },
            )
            assertThat(result).isSameInstanceAs(pending)
        }
        assertThat(receives).isEqualTo(0)
        assertThat(releases).isEqualTo(0)
    }

    @Test
    fun `sender echo without cached payload fails closed without authority release`() = runTest {
        var releases = 0
        val failure = runCatching {
            withProtectedReceiveLifecycle(null, true, false,
                receive = { error("Unconfirmed event admitted") },
                pendingLocalEcho = { protectedReceivePayload(null, true) { releases++; byteArrayOf(9) } },
            )
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        assertThat(releases).isEqualTo(0)
    }

    @Test
    fun `unconfirmed foreign or remote item cannot use sender echo path`() = runTest {
        for ((own, remote) in listOf(false to false, false to true, true to true)) {
            var called = false
            val failure = runCatching {
                withProtectedReceiveLifecycle(null, own, remote,
                    receive = { called = true }, pendingLocalEcho = { called = true },
                )
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
            assertThat(called).isFalse()
        }
    }

    @Test
    fun `server confirmation admits original event and normal receiver release`() = runTest {
        val confirmed = mutableListOf<String>()
        var releases = 0
        for (own in listOf(true, false)) {
            val result = withProtectedReceiveLifecycle("\$confirmed", own, true,
                receive = { eventId -> confirmed += eventId; protectedReceivePayload(null, false) { releases++; byteArrayOf(9) } },
                pendingLocalEcho = { error("Confirmed event used local path") },
            )
            assertThat(result.toList()).containsExactly(9.toByte())
        }
        assertThat(confirmed).containsExactly("\$confirmed", "\$confirmed")
        assertThat(releases).isEqualTo(2)
    }
}
