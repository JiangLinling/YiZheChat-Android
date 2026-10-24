/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import androidx.compose.runtime.mutableStateOf
import io.element.android.libraries.architecture.Presenter
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import io.element.android.tests.testutils.test
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryPersonalPresenterTest {
    private val kind = DiscoveryPersonalKind.FAVORITES
    private fun row(id: Long) = DiscoveryPersonalItem.Article(DiscoveryArticle(id, "Article", ""))

    @Test
    fun `double more does not skip a page and failed request retries that same page`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        val second = CompletableDeferred<DiscoveryPersonalPage>()
        coEvery { repository.personal(kind, 1) } returns DiscoveryPersonalPage(listOf(row(1)), 2)
        coEvery { repository.personal(kind, 2) } coAnswers { second.await() }
        val presenter = DiscoveryPersonalPresenter(repository)
        Presenter { presenter.present(kind) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryPersonalEvent.More)
            state.eventSink(DiscoveryPersonalEvent.More)
            do { state = awaitItem() } while (!state.loading)
            second.completeExceptionally(IllegalStateException("test network failure"))
            do { state = awaitItem() } while (!state.failed)
            assertEquals(listOf(1L), state.items.map { it.id })
            coEvery { repository.personal(kind, 2) } returns DiscoveryPersonalPage(listOf(row(2)), 2)
            state.eventSink(DiscoveryPersonalEvent.Retry)
            do { state = awaitItem() } while (state.loading || state.failed)
            assertEquals(listOf(1L, 2L), state.items.map { it.id })
            coVerify(exactly = 2) { repository.personal(kind, 2) }
            coVerify(exactly = 0) { repository.personal(kind, 3) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hidden route clears rows and activation or activity resume rechecks current list`() = runTest {
        val kind = DiscoveryPersonalKind.HISTORY
        val repository = mockk<DiscoveryArticlesRepository>()
        var rows = listOf(row(1))
        coEvery { repository.personal(kind, 1) } answers { DiscoveryPersonalPage(rows, rows.size) }
        val active = mutableStateOf(true)
        val revision = mutableStateOf(1L)
        val presenter = DiscoveryPersonalPresenter(repository)
        Presenter { presenter.present(kind, active.value, revision.value) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(1, state.items.size)
            active.value = false
            do { state = awaitItem() } while (state.items.isNotEmpty())
            state.eventSink(DiscoveryPersonalEvent.Refresh)
            coVerify(exactly = 1) { repository.personal(any(), any()) }
            rows = emptyList()
            active.value = true
            do { state = awaitItem() } while (state.loading)
            assertTrue(state.items.isEmpty())
            rows = listOf(row(2))
            revision.value++
            do { state = awaitItem() } while (state.loading || state.items.isEmpty())
            assertEquals(2L, state.items.single().id)
            coVerify(exactly = 3) { repository.personal(kind, 1) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `authorization loss on a later page removes retained private rows`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        coEvery { repository.personal(kind, 1) } returns DiscoveryPersonalPage(listOf(row(1)), 2)
        coEvery { repository.personal(kind, 2) } throws DiscoveryFailure(403)
        val presenter = DiscoveryPersonalPresenter(repository)
        Presenter { presenter.present(kind) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryPersonalEvent.More)
            do { state = awaitItem() } while (!state.failed)
            assertTrue(state.items.isEmpty())
            state.eventSink(DiscoveryPersonalEvent.Retry)
            do { state = awaitItem() } while (state.loading || state.failed)
            assertEquals(listOf(1L), state.items.map { it.id })
            coVerify(exactly = 2) { repository.personal(kind, 1) }
            coVerify(exactly = 1) { repository.personal(kind, 2) }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
