/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.startchat.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FriendScannerStateTest {
    @Test
    fun `camera and album share self rejection and explicit retry`() {
        for (album in listOf(false, true)) {
            val scanned = mutableListOf<String>()
            val scanner = FriendScannerState {
                scanned += it.decodeToString()
                if (it.decodeToString() == "self") FriendQrScanResult.Self else FriendQrScanResult.Handled
            }
            assertThat(scanner.scanning).isTrue()
            if (album) scanner.pause()
            scanner.scanned("self".encodeToByteArray())
            assertThat(scanner.result).isEqualTo(FriendQrScanResult.Self)
            assertThat(scanner.scanning).isFalse()
            scanner.scanAgain()
            assertThat(scanner.result).isNull()
            assertThat(scanner.scanning).isTrue()
            scanner.scanned("friend".encodeToByteArray())
            assertThat(scanner.result).isEqualTo(FriendQrScanResult.Handled)
            assertThat(scanner.scanning).isFalse()
            assertThat(scanned).containsExactly("self", "friend").inOrder()
        }
    }

    @Test
    fun `album with no decoded QR shows invalid without routing`() {
        val scanner = FriendScannerState { error("No bytes to route") }
        scanner.pause()
        scanner.scanned(null)
        assertThat(scanner.result).isEqualTo(FriendQrScanResult.Invalid)
        assertThat(scanner.scanning).isFalse()
        scanner.scanAgain()
        assertThat(scanner.result).isNull()
        assertThat(scanner.scanning).isTrue()
    }

    @Test
    fun `invalid camera QR can be retried`() {
        val scanner = FriendScannerState { FriendQrScanResult.Invalid }
        scanner.scanned("invalid".encodeToByteArray())
        assertThat(scanner.result).isEqualTo(FriendQrScanResult.Invalid)
        assertThat(scanner.scanning).isFalse()
        scanner.scanAgain()
        assertThat(scanner.scanning).isTrue()
        assertThat(scanner.result).isNull()
    }
}
