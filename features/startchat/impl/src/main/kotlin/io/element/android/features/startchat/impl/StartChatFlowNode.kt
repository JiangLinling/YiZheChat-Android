/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.startchat.impl

import android.os.Parcelable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.navigation.transition.JumpToEndTransitionHandler
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.node
import com.bumble.appyx.core.plugin.Plugin
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.replace
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.features.createroom.api.CreateRoomEntryPoint
import io.element.android.features.startchat.DefaultStartChatNavigator
import io.element.android.features.startchat.api.StartChatEntryPoint
import io.element.android.features.startchat.impl.joinbyaddress.JoinRoomByAddressNode
import io.element.android.features.startchat.impl.root.StartChatNode
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.architecture.BaseFlowNode
import io.element.android.libraries.architecture.OverlayView
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.architecture.inputs
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.permissions.api.PermissionsPresenter
import kotlinx.parcelize.Parcelize

@ContributesNode(SessionScope::class)
@AssistedInject
class StartChatFlowNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val createRoomEntryPoint: CreateRoomEntryPoint,
    private val matrixClient: MatrixClient,
    @io.element.android.libraries.di.annotations.ApplicationContext private val androidContext: android.content.Context,
    permissionsPresenterFactory: PermissionsPresenter.Factory,
) : BaseFlowNode<StartChatFlowNode.NavTarget>(
    backstack = BackStack(
        initialElement = NavTarget.Root,
        savedStateMap = buildContext.savedStateMap,
    ),
    buildContext = buildContext,
    plugins = plugins
) {
    sealed interface NavTarget : Parcelable {
        @Parcelize
        data object Root : NavTarget

        @Parcelize
        data object NewRoom : NavTarget

        @Parcelize
        data object JoinByAddress : NavTarget

        @Parcelize
        data object Scanner : NavTarget

        @Parcelize
        data object MyQrCode : NavTarget
    }

    private val callback: StartChatEntryPoint.Callback = callback()
    private val inputs = inputs<StartChatEntryPoint.Params>()
    private val cameraPermissionPresenter = permissionsPresenterFactory.create(android.Manifest.permission.CAMERA)
    private val navigator = DefaultStartChatNavigator(
        backstack = backstack,
        overlay = overlay,
        openRoom = callback::onRoomCreated,
        openRoomDirectory = callback::navigateToRoomDirectory,
        openUserProfile = callback::navigateToUserProfile,
    )

    override fun onBuilt() {
        super.onBuilt()
        when (inputs.initialTarget) {
            StartChatEntryPoint.InitialTarget.SEARCH -> Unit
            StartChatEntryPoint.InitialTarget.SCANNER -> backstack.replace(NavTarget.Scanner)
            StartChatEntryPoint.InitialTarget.MY_QR_CODE -> backstack.replace(NavTarget.MyQrCode)
        }
    }

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node {
        return when (navTarget) {
            NavTarget.Root -> {
                createNode<StartChatNode>(buildContext = buildContext, plugins = listOf(navigator))
            }
            NavTarget.NewRoom -> {
                val callback = object : CreateRoomEntryPoint.Callback {
                    override fun onRoomCreated(roomId: RoomId) {
                        navigator.onRoomCreated(roomId.toRoomIdOrAlias(), emptyList())
                    }
                }
                createRoomEntryPoint
                    .builder(
                        parentNode = this,
                        buildContext = buildContext,
                        callback = callback,
                    )
                    .setIsSpace(false)
                    .build()
            }
            NavTarget.JoinByAddress -> {
                createNode<JoinRoomByAddressNode>(buildContext = buildContext, plugins = listOf(navigator))
            }
            NavTarget.Scanner -> node(buildContext) { modifier ->
                val permissionState = cameraPermissionPresenter.present()
                FriendScannerView(
                    permissionState = permissionState,
                    onBack = this::navigateUp,
                    onMyQrCode = navigator::onShowMyQrCode,
                    onScanned = { data ->
                        val scanned = data.decodeToString()
                        val uri = android.net.Uri.parse(scanned)
                        if (uri.scheme == "yizhe" && uri.host == "group" && uri.path == "/v1" && !uri.getQueryParameter("token").isNullOrBlank()) {
                            val context = androidContext
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                                setClassName(context.packageName, "io.element.android.x.MainActivity")
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                            return@FriendScannerView true
                        }
                        val userId = FriendQrCode.decode(scanned)
                        if (userId == null || matrixClient.isMe(userId)) {
                            false
                        } else {
                            callback.navigateToUserProfile(userId)
                            true
                        }
                    },
                    modifier = modifier,
                )
            }
            NavTarget.MyQrCode -> node(buildContext) { modifier ->
                val user by matrixClient.userProfile.collectAsState()
                MyFriendQrCodeView(user = user, onBack = this::navigateUp, modifier = modifier)
            }
        }
    }

    @Composable
    override fun View(modifier: Modifier) {
        Box(modifier = modifier) {
            BackstackView()
            OverlayView(transitionHandler = remember { JumpToEndTransitionHandler() })
        }
    }
}
