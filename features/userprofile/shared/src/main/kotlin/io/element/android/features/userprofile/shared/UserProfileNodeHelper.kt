/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.userprofile.shared

import android.content.Context
import io.element.android.libraries.androidutils.R
import io.element.android.libraries.androidutils.system.startSharePlainTextIntent
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.permalink.PermalinkBuilder
import io.element.android.libraries.ui.strings.CommonStrings
import timber.log.Timber

class UserProfileNodeHelper(
    private val userId: UserId,
) {
    interface Callback : NodeInputs {
        fun navigateToAvatarPreview(username: String, avatarUrl: String)
        fun navigateToRoom(roomId: RoomId)
        fun startCall(dmRoomId: RoomId, callIntent: CallIntent)
        fun startVerifyUserFlow(userId: UserId)
    }

    fun onShareUser(
        context: Context,
        permalinkBuilder: PermalinkBuilder,
        displayName: String? = null,
    ) {
        val permalinkResult = permalinkBuilder.permalinkForUser(userId)
        permalinkResult.onSuccess { permalink ->
            val link = io.element.android.libraries.matrix.api.permalink.SharedIdentityCard.userLink(userId.value)
            android.app.AlertDialog.Builder(context)
                .setTitle(context.getString(io.element.android.features.userprofile.shared.R.string.screen_identity_share_title))
                .setItems(arrayOf(
                    context.getString(io.element.android.features.userprofile.shared.R.string.screen_identity_share_card),
                    context.getString(io.element.android.features.userprofile.shared.R.string.screen_identity_share_link),
                )) { _, choice ->
                    if (choice == 0) {
                        val card = io.element.android.libraries.matrix.api.permalink.SharedIdentityCard(false, displayName ?: userId.value, link)
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            setClassName(context.packageName, "io.element.android.x.MainActivity")
                            putExtra(android.content.Intent.EXTRA_TEXT, card.body())
                        })
                    } else {
                        context.startSharePlainTextIntent(null, context.getString(CommonStrings.action_share), link,
                            noActivityFoundMessage = context.getString(R.string.error_no_compatible_app_found))
                    }
                }.setNegativeButton(context.getString(CommonStrings.action_cancel), null).show()
        }.onFailure {
            Timber.e(it)
        }
    }
}
