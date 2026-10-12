/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.qrcode

import android.graphics.Bitmap
import zxingcpp.BarcodeReader

/** Decode the first barcode payload found in a bitmap. */
fun decodeQrCodeBitmap(bitmap: Bitmap): ByteArray? = runCatching {
    BarcodeReader().read(bitmap).firstOrNull()?.bytes
}.getOrNull()
