/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.api

/**
 * Optional manufacturer-specific wake-up channel used as a fallback to the universal push provider.
 *
 * Implementations must only claim devices they support. The app starts at most one eligible vendor provider,
 * while the universal pusher remains registered. Delivery coordination and event-id deduplication ensure the
 * vendor channel is only used when the app-owned channel cannot acknowledge the event.
 */
interface VendorPushProvider {
    val id: String
    val priority: Int

    fun isAvailable(): Boolean
    fun start()
}
