/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.media

import java.io.File
import java.io.InputStream

/**
 * One-shot authorized plaintext stream whose bytes must enter protected-content encryption without
 * first being copied to a filesystem staging file.
 */
class ProtectedMediaSource(
    val plaintextSize: Long,
    val encryptedWorkingDirectory: File,
    private val inputStreamFactory: () -> InputStream,
) {
    init {
        require(plaintextSize >= 0L)
        require(encryptedWorkingDirectory.isDirectory)
    }

    fun openInputStream(): InputStream = inputStreamFactory()
}
