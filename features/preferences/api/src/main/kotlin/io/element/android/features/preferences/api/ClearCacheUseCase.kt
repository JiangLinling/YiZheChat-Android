/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.preferences.api

/** Rebuilds the current session's non-cryptographic local caches and restarts its navigation graph. */
fun interface ClearCacheUseCase {
    suspend operator fun invoke()
}
