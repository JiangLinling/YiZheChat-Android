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

internal class DiscoveryCommentsPresenterTest {
    private val articleId = 5L
    private fun row(id: Long) = DiscoveryArticleComment(id, articleId, "Reader", "Comment", "")

    @Test
    fun `double more does not skip a page and failed request retries that same page`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        val second = CompletableDeferred<DiscoveryCommentsPage>()
        coEvery { repository.comments(articleId, 1) } returns DiscoveryCommentsPage(listOf(row(1)), 2)
        coEvery { repository.comments(articleId, 2) } coAnswers { second.await() }
        val presenter = DiscoveryCommentsPresenter(repository)
        Presenter { presenter.present(articleId) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryPersonalEvent.More)
            state.eventSink(DiscoveryPersonalEvent.More)
            do { state = awaitItem() } while (!state.loading)
            second.completeExceptionally(IllegalStateException("test network failure"))
            do { state = awaitItem() } while (!state.failed)
            assertEquals(listOf(1L), state.items.map { it.id })
            coEvery { repository.comments(articleId, 2) } returns DiscoveryCommentsPage(listOf(row(2)), 2)
            state.eventSink(DiscoveryPersonalEvent.Retry)
            do { state = awaitItem() } while (state.loading || state.failed)
            assertEquals(listOf(1L, 2L), state.items.map { it.id })
            coVerify(exactly = 2) { repository.comments(articleId, 2) }
            coVerify(exactly = 0) { repository.comments(articleId, 3) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hidden route clears rows and activation or activity resume rechecks current list`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        var rows = listOf(row(1))
        coEvery { repository.comments(articleId, 1) } answers { DiscoveryCommentsPage(rows, rows.size) }
        val active = mutableStateOf(true)
        val revision = mutableStateOf(1L)
        val presenter = DiscoveryCommentsPresenter(repository)
        Presenter { presenter.present(articleId, active.value, revision.value) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(1, state.items.size)
            active.value = false
            do { state = awaitItem() } while (state.items.isNotEmpty())
            state.eventSink(DiscoveryPersonalEvent.Refresh)
            coVerify(exactly = 1) { repository.comments(any(), any()) }
            rows = emptyList()
            active.value = true
            do { state = awaitItem() } while (state.loading)
            assertTrue(state.items.isEmpty())
            rows = listOf(row(2))
            revision.value++
            do { state = awaitItem() } while (state.loading || state.items.isEmpty())
            assertEquals(2L, state.items.single().id)
            coVerify(exactly = 3) { repository.comments(articleId, 1) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unpublished article on a later page removes retained comments`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        coEvery { repository.comments(articleId, 1) } returns DiscoveryCommentsPage(listOf(row(1)), 2)
        coEvery { repository.comments(articleId, 2) } throws DiscoveryFailure(404)
        val presenter = DiscoveryCommentsPresenter(repository)
        Presenter { presenter.present(articleId) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryPersonalEvent.More)
            do { state = awaitItem() } while (!state.failed)
            assertTrue(state.items.isEmpty())
            assertTrue(state.missing)
            state.eventSink(DiscoveryPersonalEvent.Retry)
            do { state = awaitItem() } while (state.loading || state.failed)
            assertEquals(listOf(1L), state.items.map { it.id })
            coVerify(exactly = 2) { repository.comments(articleId, 1) }
            coVerify(exactly = 1) { repository.comments(articleId, 2) }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
