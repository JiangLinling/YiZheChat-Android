/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import io.element.android.features.discovery.impl.actions.DiscoveryBusinessActions
import io.element.android.features.discovery.impl.actions.DiscoveryEnrollmentResultLocator
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DefaultDiscoveryEnrollmentPortTest {
    private class Fixture {
        val reader = mockk<DiscoveryEnrollmentReader>()
        val actions = mockk<DiscoveryBusinessActions>()
        val locator = DiscoveryEnrollmentResultLocator("11111111-1111-4111-8111-111111111111", 1, 10, listOf(2), 4)
        val current = EnrollmentCurrentWire(1, 10, listOf(EnrollmentItemWire(2, 3, "pending_payment", 100, 4, "Student")),
            EnrollmentOrderWire(4, "pending", 100, null, "unavailable"), emptySet(), "Session", EnrollmentPaymentChannel.UNAVAILABLE)
        init {
            coEvery { actions.execute<EnrollmentRecovery>(null, any(), any(), any(), any(), any(), any()) } returns null
            coEvery { actions.enrollmentResult(1, 10) } returns locator
            coEvery { reader.current(1, 10, 2) } returns current
        }
        fun port() = DefaultDiscoveryEnrollmentPort(reader, actions)
    }

    @Test fun `retired operation resumes through stored locator and fresh authorized group`() = runTest {
        val f = Fixture()
        val recovered = f.port().recover(1, 10) as EnrollmentRecovery.Confirmed
        assertEquals(f.locator, recovered.locator)
        assertEquals("2", recovered.receipt.enrollmentId)
        assertEquals(EnrollmentReceiptStatus.PENDING_PAYMENT, recovered.receipt.status)
        coVerify(exactly = 1) { f.reader.current(1, 10, 2) }
        coVerify(exactly = 0) { f.reader.options(any(), any()) }
    }

    @Test fun `failed fresh query retains locator and retry never returns new enrollment form`() = runTest {
        val f = Fixture()
        coEvery { f.reader.current(1, 10, 2) } throws IllegalStateException("query unavailable")
        assertTrue(runCatching { f.port().recover(1, 10) }.isFailure)
        coEvery { f.reader.current(1, 10, 2) } returns f.current
        assertTrue(f.port().recover(1, 10) is EnrollmentRecovery.Confirmed)
        coVerify(exactly = 0) { f.actions.dismissEnrollmentResult(any(), any(), any()) }
        coVerify(exactly = 0) { f.reader.options(any(), any()) }
    }

    @Test fun `changed group or order cannot replace the confirmed result`() = runTest {
        val f = Fixture()
        coEvery { f.reader.current(1, 10, 2) } returns f.current.copy(
            enrollments = f.current.enrollments.map { it.copy(enrollmentId = 9) },
        )
        assertTrue(runCatching { f.port().recover(1, 10) }.isFailure)
        coEvery { f.reader.current(1, 10, 2) } returns f.current.copy(order = f.current.order!!.copy(orderId = 9))
        assertTrue(runCatching { f.port().recover(1, 10) }.isFailure)
        coVerify(exactly = 0) { f.actions.dismissEnrollmentResult(any(), any(), any()) }
    }
}
