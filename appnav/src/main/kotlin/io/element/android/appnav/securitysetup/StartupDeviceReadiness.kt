/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.appnav.securitysetup

import io.element.android.security.dataprotection.api.ManagedDeviceSecurityState
import io.element.android.security.dataprotection.api.StartupSecurityReadiness
import io.element.android.security.dataprotection.api.StartupSecurityReadinessState

internal fun canReuseStartupDeviceReady(readiness: StartupSecurityReadiness?, now: Long = System.currentTimeMillis()): Boolean =
    readiness != null && readiness.state == StartupSecurityReadinessState.READY_FOR_LC01 &&
        readiness.isFresh(now) && readiness.expiresAtEpochMillis != null && readiness.observedAtEpochMillis <= now &&
        readiness.deviceSecurityState == ManagedDeviceSecurityState.READY &&
        readiness.policyVersion != null && !readiness.installationId.isNullOrBlank() && !readiness.offlineEligible
