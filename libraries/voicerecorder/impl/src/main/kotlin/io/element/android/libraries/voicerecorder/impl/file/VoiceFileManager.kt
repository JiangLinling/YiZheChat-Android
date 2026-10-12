/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.voicerecorder.impl.file

import java.io.File

interface VoiceFileManager {
    /** Removes scratch files left by a reclaimed process before a new capture can start. */
    fun recoverAbandonedFiles()

    fun createFile(): File

    fun createFile(operationId: String): File = createFile()

    fun discardPreparedCapture(operationId: String) = Unit

    fun pendingCaptureCleanups(): Set<String> = emptySet()

    fun captureCleanupResult(operationId: String): String = "VOICE_CAPTURE_CANCELLED_AND_CLEANED"

    fun markCaptureCleanup(file: File, resultCode: String) = Unit

    fun acknowledgeCaptureCleanup(operationId: String) = Unit

    fun deleteFile(file: File)
}
