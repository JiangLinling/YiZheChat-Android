/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

/** Removes old internal-test sessions before the Next control plane starts its first sync. */
interface NextInternalCutoverMigration {
    suspend fun migrate(nextHomeserverUrl: String)
}
