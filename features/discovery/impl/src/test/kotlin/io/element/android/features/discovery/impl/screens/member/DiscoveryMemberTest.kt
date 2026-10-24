/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.compose.runtime.mutableStateOf
import io.element.android.libraries.architecture.Presenter
import io.element.android.tests.testutils.test
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryMemberTest {
    @Test fun `all course services retain selected enrollment and session`() {
        val course = MemberCourse(1, 10, "enrollment-2", "Course", "Child", "Date", "Confirmed", "Venue")
        MemberServiceKind.entries.forEach { kind ->
            assertEquals(MemberRoute.Service(10, "enrollment-2", kind), course.service(kind))
        }
    }

    @Test fun `same order number in different domains remains distinct and cannot satisfy wrong route`() {
        val course = MemberOrderSummary(MemberOrderSource.COURSE, "1", "N1", "Course", "Person", "Pending", 100, "Date")
        val mall = course.copy(source = MemberOrderSource.MALL)
        assertNotEquals(course.key, mall.key)
        val page = MemberPage.Order(1, course, emptyList(), null, emptyList())
        assertTrue(page.matches(course.route, 1))
        assertFalse(page.matches(mall.route, 1))
        assertFalse(page.matches(course.route, 2))
    }

    @Test fun `identity lists reject missing active person and duplicated children`() {
        val self = MemberIdentity(1, "Self", false, "", 80)
        assertThrows(IllegalArgumentException::class.java) { MemberPage.Identities(2, listOf(self)) }
        val child = MemberIdentity(2, "Child", true, "", null)
        assertThrows(IllegalArgumentException::class.java) { MemberPage.Identities(1, listOf(self, child, child)) }
    }

    @Test fun `identity and visibility changes clear old data before reloading`() = runTest {
        val reader = mockk<DiscoveryMemberReader>()
        coEvery { reader.load(MemberRoute.Services, any(), null) } answers { MemberPage.Services(secondArg(), emptyList()) }
        val identity = mutableStateOf(1L)
        val active = mutableStateOf(true)
        val presenter = DiscoveryMemberPresenter(reader)
        Presenter { presenter.present(MemberRoute.Services, identity.value, active.value, 0) }.test {
            var state = awaitItem(); while (state.loading) state = awaitItem()
            assertEquals(1L, state.page?.personId)
            identity.value = 2
            do { state = awaitItem() } while (state.loading)
            assertEquals(2L, state.page?.personId)
            active.value = false
            do { state = awaitItem() } while (state.page != null)
            state.reload()
            coVerify(exactly = 2) { reader.load(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `wrong identity projection is an error and never renders stale content`() = runTest {
        val reader = mockk<DiscoveryMemberReader>()
        coEvery { reader.load(any(), any(), any()) } returns MemberPage.Services(2, emptyList())
        val presenter = DiscoveryMemberPresenter(reader)
        Presenter { presenter.present(MemberRoute.Services, 1, true, 0) }.test {
            var state = awaitItem(); while (state.loading) state = awaitItem()
            assertTrue(state.failed); assertNull(state.page)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
