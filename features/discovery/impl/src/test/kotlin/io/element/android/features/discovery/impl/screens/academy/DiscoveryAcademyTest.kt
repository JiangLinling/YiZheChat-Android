/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.academy

import androidx.compose.runtime.mutableStateOf
import io.element.android.features.discovery.impl.screens.articles.*
import io.element.android.features.discovery.impl.screens.home.*
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import io.element.android.libraries.architecture.Presenter
import io.element.android.tests.testutils.test
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryAcademyTest {
    private fun entry(id: Long) = DiscoveryHomeCard(id.toString(), "Entry $id", "", "realm_articles", id)
    private fun article(id: Long) = DiscoveryArticle(id, "Article $id", "", "Body $id", typeId = "realm")

    @Test
    fun `four published entries select next by server order and last returns home`() = runTest {
        val home = mockk<DiscoveryHomeRepository>()
        val articles = mockk<DiscoveryArticlesRepository>()
        val entries = listOf(9L, 2L, 7L, 4L).map(::entry)
        coEvery { home.load() } returns DiscoveryHomeContent(emptyList(), entries, emptyList(), emptyList())
        coEvery { articles.detail(any()) } answers { article(firstArg()) }
        val repository = DiscoveryAcademyRepository(home, articles)
        for ((index, item) in entries.withIndex()) {
            val page = repository.read(requireNotNull(item.targetArticleId))
            assertEquals(item.targetArticleId, page.article.id)
            assertEquals(entries.getOrNull(index + 1), page.next)
        }
        // A still-published realm moved outside the first four remains readable, with no next link.
        assertNull(repository.read(55).next)
        coEvery { articles.detail(66) } returns article(66).copy(typeId = "news")
        assertEquals("news", repository.read(66).article.typeId)
        assertNull(repository.read(66).next)
    }

    @Test
    fun `legacy feature content id never becomes the next article identity`() = runTest {
        val home = mockk<DiscoveryHomeRepository>()
        val articles = mockk<DiscoveryArticlesRepository>()
        coEvery { home.load() } returns DiscoveryHomeContent(emptyList(), listOf(DiscoveryHomeCard("999", "Feature", "", "features")), emptyList(), emptyList())
        coEvery { articles.detail(9) } returns article(9)
        assertNull(DiscoveryAcademyRepository(home, articles).read(9).next)
        coVerify(exactly = 0) { articles.detail(999) }
    }

    @Test
    fun `hidden route clears content and resumed route rechecks current article and order`() = runTest {
        val repository = mockk<DiscoveryAcademyRepository>()
        coEvery { repository.read(9) } returns DiscoveryAcademyContent(article(9), entry(2))
        val active = mutableStateOf(true)
        val resumed = mutableStateOf(0L)
        val presenter = DiscoveryAcademyPresenter(repository)
        Presenter { presenter.present(9, active.value, resumed.value) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(2L, state.content?.next?.targetArticleId)
            active.value = false
            do { state = awaitItem() } while (state.content != null)
            state.reload()
            coVerify(exactly = 1) { repository.read(9) }
            coEvery { repository.read(9) } throws DiscoveryFailure(404)
            active.value = true
            do { state = awaitItem() } while (state.loading)
            assertTrue(state.missing && state.failed)
            assertNull(state.content)
            coEvery { repository.read(9) } returns DiscoveryAcademyContent(article(9), entry(7))
            state.reload()
            do { state = awaitItem() } while (state.loading || state.failed)
            assertEquals(7L, state.content?.next?.targetArticleId)
            resumed.value++
            do { state = awaitItem() } while (state.loading)
            coVerify(exactly = 4) { repository.read(9) }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
