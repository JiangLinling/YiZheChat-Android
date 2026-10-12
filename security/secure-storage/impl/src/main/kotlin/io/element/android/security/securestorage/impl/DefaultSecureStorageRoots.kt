/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.securestorage.api.SecureStorageRoots
import java.io.File

@ContributesBinding(AppScope::class)
class DefaultSecureStorageRoots(
    @ApplicationContext private val context: Context,
) : SecureStorageRoots {
    override fun persistentRoot(): File = File(context.noBackupFilesDir, PERSISTENT_ROOT)

    override fun runtimeRoot(): File = File(context.cacheDir, RUNTIME_ROOT)

    private companion object {
        const val PERSISTENT_ROOT = "secure/v1"
        const val RUNTIME_ROOT = "secure-runtime/v1"
    }
}
