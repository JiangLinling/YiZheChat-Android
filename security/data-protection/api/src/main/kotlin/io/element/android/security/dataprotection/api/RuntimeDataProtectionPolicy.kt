/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

import java.util.concurrent.atomic.AtomicReference

/**
 * Fail-closed bridge for platform utility code which is instantiated outside dependency injection.
 * The verified provider is installed once by the application-scoped managed policy provider.
 */
object RuntimeDataProtectionPolicy {
    private val provider = AtomicReference<DataProtectionPolicyProvider?>()

    fun install(policyProvider: DataProtectionPolicyProvider) {
        val existing = provider.get()
        if (existing === policyProvider || existing == null && provider.compareAndSet(null, policyProvider)) return
        check(provider.get() === policyProvider) { "The process data protection policy provider cannot be replaced." }
    }

    fun allowsClipboard(): Boolean = provider.get()?.current()?.policy?.allowClipboard == true

    fun allowsExternalShare(): Boolean = provider.get()?.current()?.policy?.allowExternalShare == true
}
