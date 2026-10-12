/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.media

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * A wrapper around a media file on the disk.
 * When closed the file will be removed from the disk unless [persist] has been used.
 */
interface MediaFile : Closeable {
    /** Secure implementations revalidate their exact source and may acquire a fresh issuer-owned grant. */
    suspend fun maintainReadAuthorization(): Boolean = true

    /** Original lease observation: revoked and all readers/buffers released. Null means unobservable.
     * This is not backing-object deletion or a lifecycle completion receipt.
     */
    val readQuiescence: StateFlow<Boolean>? get() = null

    fun path(): String

    /**
     * Returns a URI suitable for in-app viewers and players. Secure implementations return a short-lived content URI.
     */
    fun uri(): Uri = Uri.parse(uriString())

    /**
     * String form of [uri]. This Android-free accessor keeps local unit tests and media engines from having to invoke
     * the Android [Uri] implementation just to pass the location through.
     */
    fun uriString(): String = File(path()).toURI().toString()

    /**
     * Opens the media without requiring callers to materialize a second plaintext file.
     */
    fun openInputStream(): InputStream = FileInputStream(path())

    /**
     * Persists the temp file to the given path. The file will be moved to
     * the given path and won't be deleted anymore when closing the handle.
     */
    fun persist(path: String): Boolean
}

fun MediaFile.toFile(): File {
    return File(path())
}
