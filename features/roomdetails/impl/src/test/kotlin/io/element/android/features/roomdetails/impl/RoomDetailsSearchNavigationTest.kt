/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.node
import com.bumble.appyx.navmodel.backstack.activeElement
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import com.bumble.appyx.testing.junit4.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.api.MessagesEntryPoint
import io.element.android.features.roomdetails.api.RoomDetailsEntryPoint
import io.element.android.features.roomdetails.impl.RoomDetailsFlowNode.NavTarget
import io.element.android.libraries.architecture.AssistedNodeFactory
import io.element.android.libraries.architecture.NodeFactoriesBindings
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.security.dataprotection.api.ProtectedPreviewAccessState
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.services.appnavstate.api.AppForegroundStateService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomDetailsSearchNavigationTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = BuildContext.root(savedStateMap = null)
    private val outerCallback = mockk<RoomDetailsEntryPoint.Callback>(relaxed = true)
    private val messagesCallback = slot<MessagesEntryPoint.Callback>()
    private val messagesParams = slot<MessagesEntryPoint.Params>()
    private lateinit var detailsCallback: RoomDetailsNode.Callback
    private val eventId = EventId("\$search-result")

    @Before
    fun setUp() {
        // Keep the real flow and Appyx backstack; replace only child-screen construction.
        mockkStatic("io.element.android.libraries.architecture.BindingsKt")
        val factories = NodeFactoriesBindings {
            mapOf(RoomDetailsNode::class to AssistedNodeFactory { _: BuildContext, plugins ->
                detailsCallback = plugins.filterIsInstance<RoomDetailsNode.Callback>().single()
                mockk<RoomDetailsNode>()
            })
        }
        every { any<Node>().bindings(NodeFactoriesBindings::class.java) } returns factories
    }

    @After
    fun tearDown() {
        unmockkStatic("io.element.android.libraries.architecture.BindingsKt")
    }

    @Test
    fun `group search header opens settings without losing the highlighted event`() {
        val flow = openSearchResult(isDirect = false)
        assertThat(messagesParams.captured.initialTarget).isEqualTo(MessagesEntryPoint.InitialTarget.Messages(eventId))
        messagesCallback.captured.navigateToRoomDetails()
        assertThat(flow.backstack.activeElement).isEqualTo(NavTarget.ChatSearchRoomDetails)
        verify(exactly = 0) { outerCallback.onDone() }
    }

    @Test
    fun `direct room search settings return to the same event`() {
        val flow = openSearchResult(isDirect = true)
        messagesCallback.captured.navigateToRoomDetails()
        flow.resolve(requireNotNull(flow.backstack.activeElement), context)
        detailsCallback.navigateBack()
        assertThat(flow.backstack.activeElement).isEqualTo(NavTarget.ChatSearchResult(eventId))
        verify(exactly = 0) { outerCallback.onDone() }
    }

    @Test
    fun `toolbar back returns through highlighted message search and original settings`() {
        val flow = openSearchResult()
        messagesCallback.captured.navigateToRoomDetails()
        flow.resolve(requireNotNull(flow.backstack.activeElement), context)
        detailsCallback.navigateBack()
        assertThat(flow.backstack.activeElement).isEqualTo(NavTarget.ChatSearchResult(eventId))
        flow.backstack.pop()
        assertThat(flow.backstack.activeElement).isEqualTo(NavTarget.FindChatContent)
        flow.backstack.pop()
        assertThat(flow.backstack.activeElement).isEqualTo(NavTarget.RoomDetails)
        flow.resolve(requireNotNull(flow.backstack.activeElement), context)
        detailsCallback.navigateBack()
        verify(exactly = 1) { outerCallback.onDone() }
    }

    @Test
    fun `backstack back from search settings retains the search result and original settings`() {
        val flow = openSearchResult()
        messagesCallback.captured.navigateToRoomDetails()
        flow.backstack.pop()
        assertThat(flow.backstack.activeElement).isEqualTo(NavTarget.ChatSearchResult(eventId))
        flow.backstack.pop()
        assertThat(flow.backstack.activeElement).isEqualTo(NavTarget.FindChatContent)
        flow.backstack.pop()
        assertThat(flow.backstack.activeElement).isEqualTo(NavTarget.RoomDetails)
        verify(exactly = 0) { outerCallback.onDone() }
    }

    @Test
    fun `settings child returns to search settings and reopening preserves the result`() {
        val flow = openSearchResult()
        repeat(2) {
            messagesCallback.captured.navigateToRoomDetails()
            flow.resolve(requireNotNull(flow.backstack.activeElement), context)
            detailsCallback.navigateToRoomMemberList()
            assertThat(flow.backstack.activeElement).isEqualTo(NavTarget.RoomMemberList)
            flow.backstack.pop()
            assertThat(flow.backstack.activeElement).isEqualTo(NavTarget.ChatSearchRoomDetails)
            detailsCallback.navigateBack()
            assertThat(flow.backstack.activeElement).isEqualTo(NavTarget.ChatSearchResult(eventId))
        }
        verify(exactly = 0) { outerCallback.onDone() }
    }

    private fun openSearchResult(isDirect: Boolean = false): RoomDetailsFlowNode {
        val messages = mockk<MessagesEntryPoint>()
        val previewGate = mockk<ProtectedPreviewAuthorizationGate> {
            every { authorizationRevision } returns MutableStateFlow(1L)
            every { accessState } returns MutableStateFlow(ProtectedPreviewAccessState(true, true, true))
            every { canAttemptPreview() } returns true
        }
        val foregroundService = mockk<AppForegroundStateService> {
            every { isInForeground } returns MutableStateFlow(true)
        }
        every { messages.createNode(any(), any(), capture(messagesParams), capture(messagesCallback)) } answers { node(context) {} }
        val flow = RoomDetailsFlowNode(
            buildContext = context,
            plugins = listOf(RoomDetailsEntryPoint.Params(RoomDetailsEntryPoint.InitialTarget.RoomDetails), outerCallback),
            pollHistoryEntryPoint = mockk(),
            elementCallEntryPoint = mockk(),
            room = FakeJoinedRoom(baseRoom = FakeBaseRoom(initialRoomInfo = aRoomInfo(isDirect = isDirect))),
            messageReferences = mockk(relaxed = true),
            analyticsService = mockk(),
            messagesEntryPoint = messages,
            knockRequestsListEntryPoint = mockk(),
            mediaViewerEntryPoint = mockk(),
            mediaGalleryEntryPoint = mockk(),
            outgoingVerificationEntryPoint = mockk(),
            reportRoomEntryPoint = mockk(),
            changeRoomMemberRolesEntryPoint = mockk(),
            rolesAndPermissionsEntryPoint = mockk(),
            securityAndPrivacyEntryPoint = mockk(),
            roomDetailsEditEntryPoint = mockk(),
            friendProfileRepository = mockk(),
            previewAuthorizationGate = previewGate,
            appForegroundStateService = foregroundService,
        )
        flow.resolve(NavTarget.RoomDetails, context)
        detailsCallback.navigateToFindChatContent()
        assertThat(flow.backstack.activeElement).isEqualTo(NavTarget.FindChatContent)
        flow.backstack.push(NavTarget.ChatSearchResult(eventId))
        flow.resolve(requireNotNull(flow.backstack.activeElement), context)
        return flow
    }
}
