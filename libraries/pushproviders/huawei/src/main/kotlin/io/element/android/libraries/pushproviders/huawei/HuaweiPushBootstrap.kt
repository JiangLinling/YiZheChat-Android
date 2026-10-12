/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.huawei

import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.pushproviders.api.VendorPushProvider
import timber.log.Timber

private val loggerTag = LoggerTag("HuaweiPushBootstrap", LoggerTag.PushLoggerTag)

@ContributesIntoSet(AppScope::class)
@SingleIn(AppScope::class)
class HuaweiPushBootstrap(
    private val registrationCoordinator: HuaweiPushRegistrationCoordinator,
    private val diagnostics: HuaweiPushDiagnostics,
) : VendorPushProvider {
    override val id = "huawei"
    override val priority = 100

    override fun isAvailable(): Boolean = isHuaweiPushTarget(Build.MANUFACTURER, Build.BRAND)

    override fun start() {
        val isTarget = isAvailable()
        diagnostics.recordTarget(Build.MANUFACTURER, Build.BRAND, Build.MODEL, isTarget)
        if (!isTarget) return
        Timber.tag(loggerTag.value).i("Starting Huawei Push registration coordinator")
        registrationCoordinator.start()
    }
}
