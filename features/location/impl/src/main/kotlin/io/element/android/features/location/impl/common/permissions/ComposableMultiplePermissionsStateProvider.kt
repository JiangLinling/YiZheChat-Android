/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl.common.permissions

import androidx.compose.runtime.Composable
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

@OptIn(ExperimentalPermissionsApi::class)
interface ComposableMultiplePermissionsStateProvider {
    @Composable
    fun provide(permissions: List<String>, onPermissionsResult: (Map<String, Boolean>) -> Unit): MultiplePermissionsState
}

@Inject
@ContributesBinding(AppScope::class)
class DefaultComposableMultiplePermissionsStateProvider : ComposableMultiplePermissionsStateProvider {
    @OptIn(ExperimentalPermissionsApi::class)
    @Composable
    override fun provide(permissions: List<String>, onPermissionsResult: (Map<String, Boolean>) -> Unit): MultiplePermissionsState =
        rememberMultiplePermissionsState(permissions, onPermissionsResult)
}
