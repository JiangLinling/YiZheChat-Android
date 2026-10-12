/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.element.android.libraries.core.extensions.runCatchingExceptions
import java.io.File

class ParcelFileDescriptorFactory(private val context: Context) {
    /** Metadata query reuses the existing provider admission; it neither reads payload nor issues a lease. */
    internal fun sourceAccessCheck(model: Any?, encodedSize: () -> Long): (() -> Unit)? {
        val uri = (model as? Uri)?.takeIf {
            it.scheme == "content" && it.authority == "${context.packageName}.securemedia"
        } ?: return null
        val guard = PdfProtectedSourceAccess(encodedSize) {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (!cursor.moveToFirst() || column < 0 || cursor.isNull(column)) -1L else cursor.getLong(column)
            } ?: -1L
        }
        return guard::check
    }

    fun create(model: Any?) = runCatchingExceptions {
        when (model) {
            is File -> ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY)
            is Uri -> context.contentResolver.openFileDescriptor(model, "r")!!
            else -> error(RuntimeException("Can't handle this model"))
        }
    }
}
