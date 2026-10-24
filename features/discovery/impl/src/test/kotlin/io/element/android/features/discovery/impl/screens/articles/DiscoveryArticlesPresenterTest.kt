/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import androidx.compose.runtime.mutableStateOf
import io.element.android.libraries.architecture.Presenter
import io.element.android.features.discovery.impl.actions.DiscoveryInteractions
import io.element.android.features.discovery.impl.actions.DiscoveryInteractionKind
import io.element.android.features.discovery.impl.actions.DiscoveryInteractionOutcome
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import io.element.android.tests.testutils.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryArticlesPresenterTest {
    @Test fun `hidden collection drops private search state ignores captured actions and reloads on return`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        val interactions = mockk<DiscoveryInteractions>()
        val active = mutableStateOf(true)
        val revision = mutableStateOf(0L)
        val target = ArticleCollection(ArticleCollectionKind.AUTHOR, 7)
        coEvery { repository.collection(target) } returns ArticleCollectionHeader("Author", "", 1, followed = false, followEnabled = true)
        coEvery { repository.feed("", ArticleSort.RECOMMEND, 1, target) } returns DiscoveryArticlePage(listOf(article(1)), 1)
        coEvery { interactions.pending() } returns false
        val presenter = DiscoveryArticlesPresenter(repository, interactions)
        Presenter { presenter.present(false, target, active.value, revision.value) }.test {
            var state = awaitItem(); while (state.loading) state = awaitItem()
            val oldSink = state.eventSink
            active.value = false
            do { state = awaitItem() } while (state.items.isNotEmpty())
            assertNull(state.header)
            oldSink(DiscoveryArticlesEvent.Follow)
            state.eventSink(DiscoveryArticlesEvent.Search("hidden"))
            coVerify(exactly = 0) { interactions.interact(any(), any(), any()) }
            coVerify(exactly = 1) { repository.feed(any(), any(), any(), any()) }
            coEvery { repository.collection(target) } returns ArticleCollectionHeader("Author", "", 1, followed = true, followEnabled = true)
            active.value = true
            do { state = awaitItem() } while (state.loading)
            assertTrue(state.header!!.followed)
            assertEquals("", state.keyword)
            revision.value++
            do { state = awaitItem() } while (state.loading)
            coVerify(exactly = 3) { repository.collection(target) }
            coVerify(exactly = 0) { repository.feed("hidden", any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `follow blocks double taps and refresh during submission then reads current author state`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        val interactions = mockk<DiscoveryInteractions>()
        val target = ArticleCollection(ArticleCollectionKind.AUTHOR, 7)
        val release = CompletableDeferred<DiscoveryInteractionOutcome>()
        val header = ArticleCollectionHeader("作者", "", 0, 5, followed = false, followEnabled = true)
        coEvery { repository.collection(target) } returns header
        coEvery { repository.feed("", ArticleSort.RECOMMEND, 1, target) } returns DiscoveryArticlePage(emptyList(), 0)
        coEvery { interactions.pending() } returns false
        coEvery { interactions.interact(7, DiscoveryInteractionKind.FOLLOW, true) } coAnswers { release.await() }
        val presenter = DiscoveryArticlesPresenter(repository, interactions)
        Presenter { presenter.present(false, target) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryArticlesEvent.Follow)
            state.eventSink(DiscoveryArticlesEvent.Follow)
            state.eventSink(DiscoveryArticlesEvent.Reload)
            do { state = awaitItem() } while (!state.interactionBusy)
            assertFalse(requireNotNull(state.header).followed)
            release.complete(DiscoveryInteractionOutcome.APPLIED)
            do { state = awaitItem() } while (state.interactionBusy)
            // A newer unfollow can already be current; the historical receipt must not turn this on.
            assertEquals(header, state.header)
            coVerify(exactly = 1) { interactions.interact(7, DiscoveryInteractionKind.FOLLOW, true) }
            coVerify(exactly = 2) { repository.collection(target) }
            coVerify(exactly = 1) { repository.feed(any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unknown follow blocks new intent and explicit rejected recovery is not displayed as success`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        val interactions = mockk<DiscoveryInteractions>()
        val target = ArticleCollection(ArticleCollectionKind.AUTHOR, 7)
        var pending = false
        var header = ArticleCollectionHeader("作者", "", 0, 0, followEnabled = true)
        coEvery { repository.collection(target) } answers { header }
        coEvery { repository.feed("", ArticleSort.RECOMMEND, 1, target) } returns DiscoveryArticlePage(emptyList(), 0)
        coEvery { interactions.pending() } answers { pending }
        coEvery { interactions.interact(any(), any(), any()) } answers { pending = true; error("test timeout") }
        coEvery { interactions.recover() } answers { pending = false; DiscoveryInteractionOutcome.REJECTED }
        val presenter = DiscoveryArticlesPresenter(repository, interactions)
        Presenter { presenter.present(false, target) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryArticlesEvent.Follow)
            do { state = awaitItem() } while (!state.interactionFailed)
            assertTrue(state.interactionPending)
            state.eventSink(DiscoveryArticlesEvent.Follow)
            coVerify(exactly = 1) { interactions.interact(any(), any(), any()) }
            header = header.copy(followEnabled = false)
            state.eventSink(DiscoveryArticlesEvent.ConfirmInteraction)
            do { state = awaitItem() } while (state.interactionBusy || state.interactionFailed)
            assertTrue(state.interactionRejected)
            assertFalse(state.interactionPending)
            assertFalse(requireNotNull(state.header).followed)
            assertFalse(requireNotNull(state.header).followEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `collection paging retains its filter and refresh reloads metadata and first page`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        val collection = ArticleCollection(ArticleCollectionKind.TAG, 7)
        coEvery { repository.collection(collection) } returns ArticleCollectionHeader("共读", "", 2)
        coEvery { repository.feed("", ArticleSort.RECOMMEND, 1, collection) } returns DiscoveryArticlePage(listOf(article(1)), 2)
        coEvery { repository.feed("", ArticleSort.RECOMMEND, 2, collection) } returns DiscoveryArticlePage(listOf(article(2)), 2)
        val presenter = DiscoveryArticlesPresenter(repository, mockk(relaxed = true))
        Presenter { presenter.present(false, collection) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals("共读", state.header?.title)
            state.eventSink(DiscoveryArticlesEvent.More)
            do { state = awaitItem() } while (state.loading || state.items.size != 2)
            assertEquals(listOf(1L, 2L), state.items.map { it.id })
            coVerify(exactly = 1) { repository.collection(collection) }
            coEvery { repository.collection(collection) } returns ArticleCollectionHeader("更新后的话题", "", 2)
            state.eventSink(DiscoveryArticlesEvent.Search(""))
            do { state = awaitItem() } while (state.loading || state.header?.title != "更新后的话题")
            assertEquals(listOf(1L), state.items.map { it.id })
            coVerify(exactly = 2) { repository.feed("", ArticleSort.RECOMMEND, 1, collection) }
            coVerify(exactly = 0) { repository.feed(any(), any(), any(), null) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `missing author never falls back to an unfiltered feed`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        val collection = ArticleCollection(ArticleCollectionKind.AUTHOR, 9)
        coEvery { repository.collection(collection) } throws DiscoveryFailure(404)
        val presenter = DiscoveryArticlesPresenter(repository, mockk(relaxed = true))
        Presenter { presenter.present(false, collection) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.failed && state.missing)
            assertTrue(state.items.isEmpty())
            coVerify(exactly = 0) { repository.feed(any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `confirmed refusal followed by removed article hides stale content without an endless confirmation loop`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        val interactions = mockk<DiscoveryInteractions>()
        coEvery { repository.detail(1) } returns article(1)
        coEvery { interactions.pending() } returns false
        coEvery { interactions.interact(1, DiscoveryInteractionKind.LIKE, true) } answers {
            coEvery { repository.detail(1) } throws DiscoveryFailure(404)
            DiscoveryInteractionOutcome.REJECTED
        }
        val presenter = DiscoveryArticlesPresenter(repository, interactions)
        Presenter { presenter.detail(1) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryArticleDetailEvent.Like)
            do { state = awaitItem() } while (!state.missing)
            assertNull(state.article)
            assertTrue(state.failed)
            assertFalse(state.interactionPending || state.interactionFailed || state.interactionBusy)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `article interaction blocks double tap and refreshes current state instead of toggling from a receipt`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        val interactions = mockk<DiscoveryInteractions>()
        val release = CompletableDeferred<Unit>()
        val current = article(1).copy(liked = false, likes = 8)
        coEvery { repository.detail(1) } returns current
        coEvery { interactions.pending() } returns false
        coEvery { interactions.interact(1, DiscoveryInteractionKind.LIKE, true) } coAnswers { release.await(); DiscoveryInteractionOutcome.APPLIED }
        val presenter = DiscoveryArticlesPresenter(repository, interactions)
        Presenter { presenter.detail(1) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryArticleDetailEvent.Like)
            state.eventSink(DiscoveryArticleDetailEvent.Like)
            do { state = awaitItem() } while (!state.interactionBusy)
            assertFalse(requireNotNull(state.article).liked)
            release.complete(Unit)
            do { state = awaitItem() } while (state.interactionBusy)
            assertEquals(current, state.article)
            coVerify(exactly = 1) { interactions.interact(any(), any(), any()) }
            coVerify(exactly = 2) { repository.detail(1) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unknown result requires recovery before another mutation and reloads authoritative detail`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        val interactions = mockk<DiscoveryInteractions>()
        var pending = false
        coEvery { repository.detail(1) } returns article(1)
        coEvery { interactions.pending() } answers { pending }
        coEvery { interactions.interact(any(), any(), any()) } answers { pending = true; error("test timeout") }
        coEvery { interactions.recover() } answers { pending = false; DiscoveryInteractionOutcome.APPLIED }
        val presenter = DiscoveryArticlesPresenter(repository, interactions)
        Presenter { presenter.detail(1) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryArticleDetailEvent.Favorite)
            do { state = awaitItem() } while (!state.interactionFailed)
            assertTrue(state.interactionPending)
            state.eventSink(DiscoveryArticleDetailEvent.Like)
            coVerify(exactly = 1) { interactions.interact(any(), any(), any()) }
            coEvery { repository.detail(1) } returns article(1).copy(favorited = true)
            state.eventSink(DiscoveryArticleDetailEvent.Recover)
            do { state = awaitItem() } while (state.interactionBusy || state.interactionFailed)
            assertTrue(requireNotNull(state.article).favorited)
            assertFalse(state.interactionPending)
            coVerify(exactly = 1) { interactions.recover() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `double load more cannot skip a page and a failed page retries without losing rows`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        val second = CompletableDeferred<DiscoveryArticlePage>()
        coEvery { repository.feed("", ArticleSort.RECOMMEND, 1) } returns DiscoveryArticlePage(listOf(article(1)), 3)
        coEvery { repository.feed("", ArticleSort.RECOMMEND, 2) } coAnswers { second.await() }
        val presenter = DiscoveryArticlesPresenter(repository, mockk(relaxed = true))
        Presenter { presenter.present(false) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(listOf(1L), state.items.map { it.id })
            state.eventSink(DiscoveryArticlesEvent.More)
            state.eventSink(DiscoveryArticlesEvent.More)
            second.completeExceptionally(IllegalStateException("test offline"))
            do { state = awaitItem() } while (!state.failed)
            assertEquals(listOf(1L), state.items.map { it.id })
            coEvery { repository.feed("", ArticleSort.RECOMMEND, 2) } returns DiscoveryArticlePage(listOf(article(1), article(2)), 3)
            state.eventSink(DiscoveryArticlesEvent.Reload)
            do { state = awaitItem() } while (state.loading || state.failed)
            assertEquals(listOf(1L, 2L), state.items.map { it.id })
            coVerify(exactly = 0) { repository.feed(any(), any(), 3) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a slow prior search cannot replace new results and clear returns to popular feed`() = runTest {
        val repository = mockk<DiscoveryArticlesRepository>()
        coEvery { repository.words() } returns listOf("经典")
        coEvery { repository.feed("", ArticleSort.VIEWS, 1) } returns DiscoveryArticlePage(listOf(article(1)), 1)
        val slow = CompletableDeferred<DiscoveryArticlePage>()
        coEvery { repository.feed("old", ArticleSort.RECOMMEND, 1) } coAnswers { slow.await() }
        coEvery { repository.feed("new", ArticleSort.RECOMMEND, 1) } returns DiscoveryArticlePage(listOf(article(3)), 1)
        val presenter = DiscoveryArticlesPresenter(repository, mockk(relaxed = true))
        Presenter { presenter.present(true) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryArticlesEvent.Search("old"))
            do { state = awaitItem() } while (!state.loading || state.keyword != "old")
            state.eventSink(DiscoveryArticlesEvent.Search("new"))
            do { state = awaitItem() } while (state.loading || state.keyword != "new")
            assertEquals(listOf(3L), state.items.map { it.id })
            slow.complete(DiscoveryArticlePage(listOf(article(2)), 1))
            state.eventSink(DiscoveryArticlesEvent.Clear)
            do { state = awaitItem() } while (state.loading || state.keyword.isNotEmpty())
            assertEquals(listOf(1L), state.items.map { it.id })
            assertEquals(listOf("new", "old"), state.history)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun article(id: Long) = DiscoveryArticle(id, "Article $id", "")
}
