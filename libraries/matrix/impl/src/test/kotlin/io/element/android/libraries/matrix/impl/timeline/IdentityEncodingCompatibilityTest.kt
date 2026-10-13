/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.security.securestorage.api.lowercaseHex
import java.security.MessageDigest
import java.util.Locale
import org.junit.Test

class IdentityEncodingCompatibilityTest {
    @Test fun `every byte retains exact legacy persistent identity encoding`() {
        val values = ByteArray(256) { it.toByte() }
        val expected = values.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
        assertThat(values.lowercaseHex()).isEqualTo(expected)
        assertThat(byteArrayOf().lowercaseHex()).isEmpty()
        assertThat(byteArrayOf(0, 1, 15, 16, 127, -128, -1).lowercaseHex()).isEqualTo("00010f107f80ff")
    }

    @Test fun `published SHA256 vector and existing account and vault identities remain unchanged`() {
        assertThat(accountIdHash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val account = "@alice:example.org"
        val device = "EXISTING_DEVICE"
        fun legacy(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
        assertThat(accountIdHash(account)).isEqualTo(legacy(account))
        assertThat(deriveAccountVaultId(account, device).value).isEqualTo("matrix-device-v1:${legacy("$account\u0000$device")}")
    }
}
