/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.home.impl.components

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import io.element.android.libraries.fullscreenintent.api.FullScreenIntentPermissionsState
import io.element.android.libraries.fullscreenintent.api.MissingCallPermission

/** Local presentation only. Unknown and unsupported must never be recorded as grants. */
internal enum class ReachabilityReadiness { Ready, NeedsUser, Unknown, Unsupported }

internal fun channelReadiness(importance: Int?): ReachabilityReadiness = when {
    importance == null || importance < NotificationManager.IMPORTANCE_NONE -> ReachabilityReadiness.Unknown
    importance < NotificationManager.IMPORTANCE_HIGH -> ReachabilityReadiness.NeedsUser
    else -> ReachabilityReadiness.Ready
}

internal fun Context.channelReadiness(channelId: String?): ReachabilityReadiness {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ReachabilityReadiness.Unsupported
    if (channelId == null) return ReachabilityReadiness.Unknown
    return runCatching {
        channelReadiness(getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId)?.importance)
    }.getOrDefault(ReachabilityReadiness.Unknown)
}

internal fun shouldGuide(readiness: ReachabilityReadiness, deferred: Boolean): Boolean =
    readiness == ReachabilityReadiness.NeedsUser && !deferred

internal val FullScreenIntentPermissionsState.shouldDisplayHomeBanner: Boolean
    get() = shouldDisplayBanner && missingPermission != null && missingPermission != MissingCallPermission.DoNotDisturbAccess
