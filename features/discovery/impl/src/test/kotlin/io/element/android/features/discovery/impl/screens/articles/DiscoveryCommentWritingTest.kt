/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import androidx.compose.runtime.mutableStateOf
import io.element.android.features.discovery.impl.actions.*
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import io.element.android.libraries.architecture.Presenter
import io.element.android.tests.testutils.test
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal class DiscoveryCommentWritingTest {
    private class Fixture {
        val repository = mockk<DiscoveryArticlesRepository>()
        val actions = mockk<DiscoveryInteractions>()
        var pending: DiscoveryArticleCommentCommand? = null
        val article = DiscoveryArticle(5, "Article", "", commentEnabled = true)
        init {
            coEvery { repository.detail(5) } returns article
            coEvery { actions.pending() } answers { pending != null }
            coEvery { actions.pendingComment(5) } answers { pending }
        }
        fun presenter() = DiscoveryArticlesPresenter(repository, actions)
    }

    @Test
    fun `removed article still permits recovery of its previous comment result`() = runTest {
        val f = Fixture()
        f.pending = DiscoveryArticleCommentCommand(UUID.randomUUID().toString(), 5, "待确认", 7)
        coEvery { f.repository.detail(5) } throws DiscoveryFailure(404)
        coEvery { f.actions.recover() } answers { f.pending = null; DiscoveryInteractionOutcome.REJECTED }
        val presenter = f.presenter()
        Presenter { presenter.detail(5) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.missing && state.commentPending)
            state.eventSink(DiscoveryArticleDetailEvent.Recover)
            do { state = awaitItem() } while (!state.interactionRejected || state.interactionBusy)
            assertFalse(state.interactionPending || state.commentSent)
            coVerify(exactly = 1) { f.actions.recover() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `successful comment clears draft before refresh failure and double send is blocked`() = runTest {
        val f = Fixture()
        val release = CompletableDeferred<DiscoveryInteractionOutcome>()
        coEvery { f.actions.comment(5, "正文", null) } coAnswers { release.await() }
        val presenter = f.presenter()
        Presenter { presenter.detail(5) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryArticleDetailEvent.CommentInput(" 正文 "))
            do { state = awaitItem() } while (state.commentText.isBlank())
            state.eventSink(DiscoveryArticleDetailEvent.SendComment)
            state.eventSink(DiscoveryArticleDetailEvent.SendComment)
            do { state = awaitItem() } while (!state.interactionBusy)
            coEvery { f.repository.detail(5) } throws DiscoveryFailure(503)
            release.complete(DiscoveryInteractionOutcome.APPLIED)
            do { state = awaitItem() } while (state.interactionBusy)
            assertTrue(state.commentSent && state.failed)
            assertEquals("", state.commentText)
            assertFalse(state.interactionFailed || state.interactionPending)
            state.eventSink(DiscoveryArticleDetailEvent.CommentInput("不能使用旧权限再发送"))
            state.eventSink(DiscoveryArticleDetailEvent.Like)
            state.eventSink(DiscoveryArticleDetailEvent.SendComment)
            coVerify(exactly = 0) { f.actions.interact(any(), any(), any()) }
            coVerify(exactly = 1) { f.actions.comment(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unknown reply locks original text and restores it after route recreation`() = runTest {
        val f = Fixture()
        f.pending = DiscoveryArticleCommentCommand(UUID.randomUUID().toString(), 5, "恢复正文", 7)
        coEvery { f.actions.recover() } answers { f.pending = null; DiscoveryInteractionOutcome.APPLIED }
        val presenter = f.presenter()
        Presenter { presenter.detail(5) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals("恢复正文", state.commentText)
            assertEquals(7L, state.commentReply?.id)
            assertTrue(state.commentPending)
            state.eventSink(DiscoveryArticleDetailEvent.CommentInput("另一个正文"))
            state.eventSink(DiscoveryArticleDetailEvent.SendComment)
            coVerify(exactly = 0) { f.actions.comment(any(), any(), any()) }
            state.eventSink(DiscoveryArticleDetailEvent.Recover)
            do { state = awaitItem() } while (state.interactionBusy || state.interactionPending)
            assertEquals("", state.commentText)
            assertTrue(state.commentSent)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `explicit rejection retains editable draft and does not report sent`() = runTest {
        val f = Fixture()
        coEvery { f.actions.comment(5, "回复正文", 7) } returns DiscoveryInteractionOutcome.REJECTED
        val presenter = f.presenter()
        Presenter { presenter.detail(5) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryArticleDetailEvent.Reply(DiscoveryArticleComment(7, 5, "Reader", "Original", "")))
            state.eventSink(DiscoveryArticleDetailEvent.CommentInput("回复正文"))
            do { state = awaitItem() } while (state.commentText.isBlank())
            state.eventSink(DiscoveryArticleDetailEvent.SendComment)
            do { state = awaitItem() } while (!state.interactionRejected || state.interactionBusy)
            assertEquals("回复正文", state.commentText)
            assertFalse(state.commentSent || state.interactionPending)
            assertEquals(7L, state.commentReply?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hidden route drops unsent private draft and disabled comments cannot send`() = runTest {
        val f = Fixture()
        val active = mutableStateOf(true)
        val presenter = f.presenter()
        Presenter { presenter.detail(5, active.value) }.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            state.eventSink(DiscoveryArticleDetailEvent.CommentInput("未发送的草稿"))
            do { state = awaitItem() } while (state.commentText.isEmpty())
            active.value = false
            do { state = awaitItem() } while (state.commentText.isNotEmpty())
            assertNull(state.article)
            coEvery { f.repository.detail(5) } returns f.article.copy(commentEnabled = false)
            active.value = true
            do { state = awaitItem() } while (state.loading)
            state.eventSink(DiscoveryArticleDetailEvent.CommentInput("禁止发送"))
            state.eventSink(DiscoveryArticleDetailEvent.SendComment)
            coVerify(exactly = 0) { f.actions.comment(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
