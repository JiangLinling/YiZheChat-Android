/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

import androidx.compose.runtime.mutableStateOf
import io.element.android.libraries.architecture.Presenter
import io.element.android.tests.testutils.test
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryCoursesPresenterTest {
    @Test
    fun `status and course filters keep featured session selected from the entire catalog`() {
        val catalog=sampleCourseCatalog()
        val state=DiscoveryCoursesState(loading=false,catalog=catalog,eventSink={})
        assertEquals(listOf(10L,20L),state.copy(filter=CourseFilter.OPEN).items.map { it.id })
        assertEquals(listOf(20L),state.copy(filter=CourseFilter.OPEN,courseId=2).items.map { it.id })
        assertEquals(listOf(30L),state.copy(filter=CourseFilter.ENDED).items.map { it.id })
        assertEquals(listOf(20L),state.copy(query="文学").items.map { it.id })
        assertEquals(listOf(10L),state.copy(query="周末").items.map { it.id })
        assertEquals(10L,state.copy(filter=CourseFilter.ENDED,courseId=2).catalog?.current?.id)
        assertEquals("公益课",DiscoveryCourse(1,"公益体验课程","").label)
        assertEquals("配置简称",DiscoveryCourse(1,"公益体验课程","配置简称").label)
    }

    @Test
    fun `hiding clears catalog and resume or retry reloads before displaying filters`() = runTest {
        val repository=mockk<DiscoveryCoursesRepository>()
        coEvery { repository.load() } returns sampleCourseCatalog()
        val active=mutableStateOf(true);val resumed=mutableStateOf(0L)
        val presenter=DiscoveryCoursesPresenter(repository)
        Presenter { presenter.present(active.value,resumed.value) }.test {
            var state=awaitItem();while(state.loading) state=awaitItem()
            state.eventSink(DiscoveryCoursesEvent.Filter(CourseFilter.OPEN))
            state=awaitItem();assertEquals(2,state.items.size)
            state.eventSink(DiscoveryCoursesEvent.Course(2))
            state=awaitItem();assertEquals(20L,state.items.single().id)
            active.value=false
            do { state=awaitItem() } while(state.catalog!=null)
            state.eventSink(DiscoveryCoursesEvent.Reload)
            coVerify(exactly=1) { repository.load() }
            coEvery { repository.load() } throws IllegalStateException("unavailable")
            active.value=true
            do { state=awaitItem() } while(state.loading)
            assertTrue(state.failed);assertNull(state.catalog)
            coEvery { repository.load() } returns sampleCourseCatalog()
            state.eventSink(DiscoveryCoursesEvent.Reload)
            do { state=awaitItem() } while(state.loading||state.failed)
            assertEquals(3,state.items.size)
            resumed.value++
            do { state=awaitItem() } while(state.loading)
            coVerify(exactly=4) { repository.load() }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
