/*
 * Copyright (c) 2026 YiZhe Chat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.roomlist

import org.matrix.rustcomponents.sdk.RoomListDynamicEntriesController

/** Keeps synchronous FFI operations and stream-owned destruction mutually exclusive. */
internal class RoomListControllerOwner {
    private val lock = Any()
    private var current: RoomListDynamicEntriesController? = null

    fun install(controller: RoomListDynamicEntriesController) = synchronized(lock) {
        current = controller
    }

    fun withController(block: (RoomListDynamicEntriesController) -> Unit) = synchronized(lock) {
        current?.let(block)
        Unit
    }

    fun release(controller: RoomListDynamicEntriesController) = synchronized(lock) {
        if (current === controller) current = null
        controller.destroy()
    }
}
