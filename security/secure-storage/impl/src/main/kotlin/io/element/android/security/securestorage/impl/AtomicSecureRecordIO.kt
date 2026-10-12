/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

internal object AtomicSecureRecordIO {
    private const val PARTIAL_PREFIX = ".partial-"
    private const val BACKUP_SUFFIX = ".replace-backup"

    fun read(destination: File, maximumLength: Long): ByteArray? {
        recover(destination)
        if (!destination.isFile) return null
        if (destination.length() !in 1..maximumLength) throw IOException("Secure record has an invalid length.")
        return destination.readBytes()
    }

    fun create(destination: File, bytes: ByteArray): Boolean {
        recover(destination)
        if (destination.exists()) return false
        val partial = writePartial(destination, bytes)
        return try {
            if (!partial.renameTo(destination)) throw IOException("Unable to publish secure record.")
            true
        } finally {
            partial.delete()
        }
    }

    fun replace(destination: File, bytes: ByteArray) {
        recover(destination)
        val partial = writePartial(destination, bytes)
        val backup = backupFor(destination)
        try {
            if (destination.exists() && !destination.renameTo(backup)) throw IOException("Unable to stage secure record replacement.")
            if (!partial.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                throw IOException("Unable to publish secure record replacement.")
            }
            if (backup.exists() && !backup.delete()) throw IOException("Unable to remove replaced secure record.")
        } finally {
            partial.delete()
            if (!destination.exists() && backup.exists()) backup.renameTo(destination)
        }
    }

    fun delete(destination: File): Boolean {
        recover(destination)
        val destinationDeleted = !destination.exists() || destination.delete()
        val backup = backupFor(destination)
        val backupDeleted = !backup.exists() || backup.delete()
        return destinationDeleted && backupDeleted
    }

    private fun recover(destination: File) {
        val backup = backupFor(destination)
        if (!backup.exists()) return
        if (destination.exists()) {
            if (!backup.delete()) throw IOException("Unable to finish secure record recovery.")
        } else if (!backup.renameTo(destination)) {
            throw IOException("Unable to restore secure record.")
        }
    }

    private fun writePartial(destination: File, bytes: ByteArray): File {
        val directory = requireNotNull(destination.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to create secure record directory.")
        val partial = File(directory, "$PARTIAL_PREFIX${destination.name}-${UUID.randomUUID()}")
        FileOutputStream(partial).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        return partial
    }

    private fun backupFor(destination: File): File = File(destination.parentFile, destination.name + BACKUP_SUFFIX)
}
