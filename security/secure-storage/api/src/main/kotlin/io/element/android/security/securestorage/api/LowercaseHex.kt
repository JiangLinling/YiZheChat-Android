/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.api

/** Exact byte encoding only; does not retain the input or alter any cryptographic operation. */
fun ByteArray.lowercaseHex(): String {
    val digits = "0123456789abcdef"
    val encoded = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        encoded[index * 2] = digits[value ushr 4]
        encoded[index * 2 + 1] = digits[value and 0x0f]
    }
    return encoded.concatToString()
}
