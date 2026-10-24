/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.home.impl

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.element.android.features.discovery.api.DiscoveryAccess
import io.element.android.features.discovery.api.observeForeground

/** The activity boundary stays alive across discovery child navigation, but stops in background. */
@Composable
internal fun ObserveHomeDiscovery(access: DiscoveryAccess) {
    if (!access.configured) return
    val owner = LocalActivity.current as? LifecycleOwner ?: return
    LaunchedEffect(access, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { access.observeForeground() }
    }
}
