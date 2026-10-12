/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Timeline-scoped TalkBack state.
 *
 * [TimelineView] owns the single AccessibilityManager subscription. Message rows only read this
 * value, avoiding a listener registration for every item entering the lazy-list viewport.
 */
internal val LocalTimelineIsTalkbackActive = staticCompositionLocalOf { false }
