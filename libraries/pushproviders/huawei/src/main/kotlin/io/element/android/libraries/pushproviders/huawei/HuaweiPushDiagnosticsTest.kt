/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.huawei

import android.os.Build
import dev.zacsweers.metro.ContributesIntoSet
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.troubleshoot.api.test.NotificationTroubleshootTest
import io.element.android.libraries.troubleshoot.api.test.NotificationTroubleshootTestDelegate
import io.element.android.libraries.troubleshoot.api.test.NotificationTroubleshootTestState
import io.element.android.libraries.troubleshoot.api.test.TestFilterData
import io.element.android.services.toolbox.api.strings.StringProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

@ContributesIntoSet(SessionScope::class)
class HuaweiPushDiagnosticsTest(
    private val diagnostics: HuaweiPushDiagnostics,
    private val stringProvider: StringProvider,
) : NotificationTroubleshootTest {
    override val order = 450
    private val delegate = NotificationTroubleshootTestDelegate(
        defaultName = stringProvider.getString(R.string.troubleshoot_notifications_huawei_push_title),
        defaultDescription = stringProvider.getString(R.string.troubleshoot_notifications_huawei_push_pending),
        visibleWhenIdle = false,
    )
    override val state: StateFlow<NotificationTroubleshootTestState> = delegate.state

    override fun isRelevant(data: TestFilterData): Boolean =
        isHuaweiPushTarget(Build.MANUFACTURER, Build.BRAND) || diagnostics.snapshot().isTarget

    override suspend fun run(coroutineScope: CoroutineScope) {
        delegate.start()
        val snapshot = diagnostics.snapshot()
        val device = listOf(snapshot.manufacturer, snapshot.brand, snapshot.model)
            .filter(String::isNotBlank)
            .joinToString(" / ")
            .ifBlank { "unknown" }
        val detail = buildString {
            append("device=")
            append(device)
            append(", stage=")
            append(snapshot.stage)
            snapshot.error?.let {
                append(", error=")
                append(it)
            }
        }
        delegate.updateState(
            description = detail,
            status = if (snapshot.stage == "REGISTERED") {
                NotificationTroubleshootTestState.Status.Success
            } else {
                NotificationTroubleshootTestState.Status.Failure(isCritical = true)
            },
        )
    }

    override suspend fun reset() = delegate.reset()
}
