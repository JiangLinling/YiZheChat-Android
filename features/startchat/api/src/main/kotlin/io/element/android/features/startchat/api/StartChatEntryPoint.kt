/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.startchat.api

import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import io.element.android.libraries.architecture.FeatureEntryPoint
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.matrix.api.core.RoomIdOrAlias
import io.element.android.libraries.matrix.api.core.UserId

interface StartChatEntryPoint : FeatureEntryPoint {
    enum class InitialTarget { SEARCH, SCANNER, MY_QR_CODE }
    data class Params(val initialTarget: InitialTarget = InitialTarget.SEARCH) : NodeInputs

    fun createNode(
        parentNode: Node,
        buildContext: BuildContext,
        callback: Callback,
        params: Params = Params(),
    ): Node

    interface Callback : Plugin {
        fun onRoomCreated(roomIdOrAlias: RoomIdOrAlias, serverNames: List<String>)
        fun navigateToRoomDirectory()
        fun navigateToUserProfile(userId: UserId)
    }
}
