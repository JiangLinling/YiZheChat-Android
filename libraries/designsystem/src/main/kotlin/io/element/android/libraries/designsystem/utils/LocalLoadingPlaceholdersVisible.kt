/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.utils

import androidx.compose.runtime.compositionLocalOf

/** Presentation only: omit loading decoration while an opaque system splash covers the window. */
val LocalLoadingPlaceholdersVisible = compositionLocalOf { true }
