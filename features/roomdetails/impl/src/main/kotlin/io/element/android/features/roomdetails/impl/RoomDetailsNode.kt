/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.bumble.appyx.core.lifecycle.subscribe
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import im.vector.app.features.analytics.plan.MobileScreen
import io.element.android.annotations.ContributesNode
import io.element.android.features.leaveroom.api.LeaveRoomRenderer
import io.element.android.libraries.androidutils.system.startSharePlainTextIntent
import io.element.android.libraries.architecture.appyx.launchMolecule
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.designsystem.utils.snackbar.LocalSnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.room.BaseRoom
import io.element.android.security.dataprotection.api.perform
import io.element.android.services.analytics.api.AnalyticsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import io.element.android.libraries.androidutils.R as AndroidUtilsR

@ContributesNode(RoomScope::class)
@AssistedInject
class RoomDetailsNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    presenterFactory: RoomDetailsPresenter.Factory,
    private val room: BaseRoom,
    private val matrixClient: io.element.android.libraries.matrix.api.MatrixClient,
    private val managementControlService: io.element.android.security.dataprotection.api.ManagementControlService,
    private val operationalCoordinator: io.element.android.security.dataprotection.api.AppOperationalStateCoordinator,
    private val analyticsService: AnalyticsService,
    private val leaveRoomRenderer: LeaveRoomRenderer,
) : Node(buildContext, plugins = plugins), RoomDetailsNavigator {
    interface Callback : Plugin {
        fun navigateBack()
        fun navigateToRoomMemberList()
        fun navigateToInviteMembers()
        fun navigateToRoomDetailsEdit()
        fun navigateToRoomNotificationSettings()
        fun navigateToAvatarPreview(name: String, url: String)
        fun navigateToPollHistory()
        fun navigateToMediaGallery()
        fun navigateToFindChatContent()
        fun navigateToAdminSettings()
        fun navigateToPinnedMessagesList()
        fun navigateToKnockRequestsList()
        fun navigateToSecurityAndPrivacy()
        fun navigateToRoomMemberDetails(userId: UserId)
        fun navigateToRoomCall(callIntent: CallIntent)
        fun navigateToReportRoom()
        fun navigateToSelectNewOwnersWhenLeaving()
    }

    private val snackbarDispatcher = SnackbarDispatcher()
    private val presenter = presenterFactory.create(navigator = this, snackbarDispatcher = snackbarDispatcher)
    private val callback: Callback = callback()

    init {
        lifecycle.subscribe(
            onResume = {
                analyticsService.screen(MobileScreen(screenName = MobileScreen.ScreenName.RoomDetails))
            }
        )
    }

    private fun shareCard(context: Context, link: String) {
        val card = io.element.android.libraries.matrix.api.permalink.SharedIdentityCard(true, stateFlow.value.roomName, link)
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            setClassName(context.packageName, "io.element.android.x.MainActivity")
            putExtra(android.content.Intent.EXTRA_TEXT, card.body())
        })
    }

    private val stateFlow = launchMolecule { presenter.present() }

    fun onNewOwnersSelected() {
        stateFlow.value.eventSink(RoomDetailsEvent.LeaveRoom(needsConfirmation = false))
    }

    @Composable
    override fun View(modifier: Modifier) = CompositionLocalProvider(LocalSnackbarDispatcher provides snackbarDispatcher) {
        val context = LocalContext.current
        val state by stateFlow.collectAsState()
        var qrCapability by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<io.element.android.security.dataprotection.api.GroupShareCapability?>(null) }
        var sharing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

        fun onShareRoom() {
            if (sharing) return
            android.app.AlertDialog.Builder(context).setTitle(context.getString(R.string.screen_group_share_title))
                .setItems(arrayOf(context.getString(R.string.screen_group_share_card),
                    context.getString(R.string.screen_group_share_link), context.getString(R.string.screen_group_share_qr))) { _, choice ->
                    sharing = true
                    lifecycleScope.launch {
                        try {
                            val capability = operationalCoordinator.perform(
                                io.element.android.security.dataprotection.api.lifecycleOperationRequest(
                                    lifecycleId = io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_04,
                                    trigger = "share_intent",
                                    accountIdHash = io.element.android.security.securestorage.api.accountIdHash(matrixClient.sessionId.value),
                                    matrixDeviceIdHash = io.element.android.security.securestorage.api.accountIdHash(matrixClient.deviceId.value),
                                    roomIdHash = io.element.android.security.securestorage.api.accountIdHash(state.roomId.value),
                                )) { managementControlService.issueGroupShare(matrixClient.sessionId.value, state.roomId.value) }
                            when (choice) {
                                0 -> shareCard(context, capability.link())
                                1 -> context.startSharePlainTextIntent(null, context.getString(R.string.screen_group_share_link), capability.link(),
                                    noActivityFoundMessage = context.getString(AndroidUtilsR.string.error_no_compatible_app_found))
                                else -> qrCapability = capability
                            }
                        } catch (failure: kotlin.coroutines.cancellation.CancellationException) { throw failure
                        } catch (_: io.element.android.security.dataprotection.api.GroupSharePermissionException) {
                            android.widget.Toast.makeText(context, R.string.screen_group_share_permission_required, android.widget.Toast.LENGTH_LONG).show()
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(context, R.string.screen_group_share_error, android.widget.Toast.LENGTH_LONG).show()
                        } finally { sharing = false }
                    }
                }.setNegativeButton(context.getString(io.element.android.libraries.ui.strings.CommonStrings.action_cancel), null).show()
        }

        fun onActionClick(action: RoomDetailsAction) {
            when (action) {
                RoomDetailsAction.Edit -> {
                    callback.navigateToRoomDetailsEdit()
                }
                RoomDetailsAction.AddTopic -> {
                    callback.navigateToRoomDetailsEdit()
                }
            }
        }

        qrCapability?.let { capability ->
            GroupShareQrDialog(capability, state.roomName, onDismiss = { qrCapability = null }, onSendCard = {
                qrCapability = null
                shareCard(context, capability.link())
            })
        }
        RoomDetailsView(
            state = state,
            modifier = modifier,
            goBack = ::navigateUp,
            onActionClick = ::onActionClick,
            onShareRoom = ::onShareRoom,
            openRoomMemberList = callback::navigateToRoomMemberList,
            openRoomNotificationSettings = callback::navigateToRoomNotificationSettings,
            invitePeople = callback::navigateToInviteMembers,
            openAvatarPreview = callback::navigateToAvatarPreview,
            openPollHistory = callback::navigateToPollHistory,
            openMediaGallery = callback::navigateToMediaGallery,
            onFindChatContentClick = callback::navigateToFindChatContent,
            openAdminSettings = callback::navigateToAdminSettings,
            onJoinCallClick = callback::navigateToRoomCall,
            onPinnedMessagesClick = callback::navigateToPinnedMessagesList,
            onKnockRequestsClick = callback::navigateToKnockRequestsList,
            onSecurityAndPrivacyClick = callback::navigateToSecurityAndPrivacy,
            onProfileClick = callback::navigateToRoomMemberDetails,
            onReportRoomClick = callback::navigateToReportRoom,
            leaveRoomView = {
                leaveRoomRenderer.Render(
                    state = state.leaveRoomState,
                    onSelectNewOwners = { callback.navigateToSelectNewOwnersWhenLeaving() },
                    modifier = Modifier
                )
            }
        )
    }

    override fun onDone() {
        callback.navigateBack()
    }
}
