/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class PolicyApplicationReceiptDeliveryTest {
    @Test
    fun `ambiguous transport failure preserves exact signed receipt for retry`() = runTest {
        val receipt = aReceipt()
        var pending: PolicyApplicationReceiptWire? = receipt
        val sent = mutableListOf<PolicyApplicationReceiptWire>()
        var attempts = 0
        var delivered = 0
        val delivery = PolicyApplicationReceiptDelivery(
            loadPending = { pending },
            sendAndValidate = { candidate ->
                sent += candidate
                attempts += 1
                if (attempts == 1) throw IOException("response lost after request write")
            },
            markDelivered = { nonce ->
                assertThat(nonce).isEqualTo(receipt.nonce)
                delivered += 1
                pending = null
            },
        )

        val firstFailure = runCatching { delivery.flush() }.exceptionOrNull()

        assertThat(firstFailure).isInstanceOf(IOException::class.java)
        assertThat(pending).isEqualTo(receipt)
        assertThat(delivered).isEqualTo(0)
        assertThat(delivery.flush()).isTrue()
        assertThat(sent).containsExactly(receipt, receipt).inOrder()
        assertThat(delivered).isEqualTo(1)
        assertThat(pending).isNull()
    }

    @Test
    fun `server rejection or invalid acknowledgement cannot mark receipt delivered`() = runTest {
        val receipt = aReceipt()
        var delivered = false
        val delivery = PolicyApplicationReceiptDelivery(
            loadPending = { receipt },
            sendAndValidate = { throw SecurityException("acknowledgement rejected") },
            markDelivered = { delivered = true },
        )

        val failure = runCatching { delivery.flush() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(delivered).isFalse()
    }

    @Test
    fun `concurrent duplicate flushes post and mark the pending receipt once`() = runTest {
        val receipt = aReceipt()
        var pending: PolicyApplicationReceiptWire? = receipt
        var posts = 0
        var delivered = 0
        val postStarted = CompletableDeferred<Unit>()
        val releasePost = CompletableDeferred<Unit>()
        val delivery = PolicyApplicationReceiptDelivery(
            loadPending = { pending },
            sendAndValidate = {
                posts += 1
                postStarted.complete(Unit)
                releasePost.await()
            },
            markDelivered = {
                delivered += 1
                pending = null
            },
        )

        val first = async { delivery.flush() }
        postStarted.await()
        val duplicate = async { delivery.flush() }
        releasePost.complete(Unit)

        assertThat(first.await()).isTrue()
        assertThat(duplicate.await()).isFalse()
        assertThat(posts).isEqualTo(1)
        assertThat(delivered).isEqualTo(1)
    }

    @Test
    fun `only non transient 4xx receipt responses are terminal rejections`() {
        listOf(400, 401, 403, 404, 409, 422, 499).forEach { status ->
            assertThat(isTerminalPolicyApplicationReceiptServerRejection(status)).isTrue()
        }
        listOf(200, 307, 408, 429, 500, 503).forEach { status ->
            assertThat(isTerminalPolicyApplicationReceiptServerRejection(status)).isFalse()
        }
    }

    private fun aReceipt(): PolicyApplicationReceiptWire = PolicyApplicationReceiptWire(
        operationId = "operation-policy-application-0001",
        installationId = "installation-policy-device-0001",
        bootId = "42",
        policyDomain = "GLOBAL_POLICY",
        version = 7,
        digest = "a".repeat(64),
        appliedAt = 2_000_000L,
        enforcedCapabilities = listOf("CAPTURE_BLOCK"),
        refusedCapabilities = emptyList(),
        cleanupOperationIds = listOf("cleanup-policy-0001"),
        result = "APPLIED_CLEANUP_PENDING",
        nonce = "receipt-nonce-policy-application-0001",
        deviceSignature = "device-signature-policy-application-0001",
    )
}
