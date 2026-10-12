/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.x

/**
 * A separate, resizable notification task. Reuses the main navigation, account/device admission,
 * application lock and protected-content gates rather than implementing a second chat client.
 * The OS owns notification drag gestures, freeform placement and conversation bubbles.
 */
class NotificationChatActivity : MainActivity()
