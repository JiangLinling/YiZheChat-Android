/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.startchat.impl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class FriendScannerState(private val onScanned: (ByteArray) -> FriendQrScanResult) {
    var scanning by mutableStateOf(true)
        private set
    var result by mutableStateOf<FriendQrScanResult?>(null)
        private set

    fun pause() {
        scanning = false
    }

    fun scanned(bytes: ByteArray?) {
        scanning = false
        result = bytes?.let(onScanned) ?: FriendQrScanResult.Invalid
    }

    fun scanAgain() {
        result = null
        scanning = true
    }
}
