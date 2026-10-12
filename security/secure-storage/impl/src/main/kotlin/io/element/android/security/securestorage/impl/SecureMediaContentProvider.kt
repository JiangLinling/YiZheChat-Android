/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.RequiresApi
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.FileNotFoundException
import java.util.concurrent.Executors

/**
 * Streams authenticated plaintext through a reliable pipe. It never exposes the encrypted container's filesystem path
 * and never creates a plaintext staging file.
 */
class SecureMediaContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = entry(uri).mimeType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val lease = entry(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> lease.displayName.orEmpty()
                    OpenableColumns.SIZE -> lease.reference.plaintextLength
                    else -> null
                }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Secure media is read-only.")
        val token = token(uri)
        val callerUid = Binder.getCallingUid()
        val lease = entry(token, callerUid)
        Timber.d("Secure media provider open admitted callerUid=%s descriptorOnly=true", Binder.getCallingUid())
        if (lease.requireSeekableDescriptor) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                throw FileNotFoundException("Secure seekable preview requires Android 8.0 or later.")
            }
            if (lease.authorizedState?.authorization?.externalReadBinding != null) {
                val descriptor = requireNotNull(
                    context
                ).getSystemService(
                    StorageManager::class.java
                ).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, externalReadCallback(token, callerUid), PROXY_HANDLER)
                lease.externalObserver?.descriptorOpened()
                return descriptor
            }
            return openSeekableFile(token)
        }
        return openStreamingPipe(token, lease)
    }

    /** Caller identity was captured at Binder admission; callback-thread identity is not recipient evidence. */
    @RequiresApi(Build.VERSION_CODES.O)
    internal fun externalReadCallback(token: String, callerUid: Int): ProxyFileDescriptorCallback = object : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = SecureMediaLeaseRegistry.acquireForCaller(token, callerUid)?.reference?.plaintextLength
            ?: throw ErrnoException("secure external size", OsConstants.EACCES)

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            val reader = SecureMediaLeaseRegistry.beginProviderRead(token, callerUid = callerUid) ?: run {
                data.fill(0)
                throw ErrnoException("secure external lease", OsConstants.EACCES)
            }
            return reader.use {
                if (offset < 0 || size < 0) {
                    data.fill(0)
                    throw ErrnoException("secure external range", OsConstants.EINVAL)
                }
                // A file descriptor permits a short read at EOF. The authenticated range API
                // deliberately requires an exact in-bounds range, so adapt the OS request here.
                val length = reader.entry.reference.plaintextLength
                val readOffset = minOf(offset, length)
                val requested = minOf(size.toLong(), data.size.toLong(),
                    io.element.android.security.securestorage.api.SECURE_MEDIA_AUTHENTICATED_HANDOFF_MAX_BYTES.toLong(),
                    length - readOffset).toInt()
                when (val result = runBlocking {
                    SecureMediaLeaseRegistry.copyAuthenticatedRangeInto(
                        token,
                        io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRequest(
                            readOffset,
                            requested,
                            io.element.android.security.securestorage.api.SecureMediaReadPurpose.EXTERNAL_OPEN,
                            callerUid
                        ),
                        data,
                        0
                    )
                }) {
                    is io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadResult.Copied -> result.byteCount
                    is io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadResult.Rejected -> {
                        data.fill(0)
                        Timber.d("Secure media external read rejected callerUid=%s reason=%s", callerUid, result.reason)
                        throw ErrnoException("secure external read", OsConstants.EACCES)
                    }
                }
            }
        }

        override fun onRelease() = Unit
    }

    private fun openStreamingPipe(token: String, lease: SecureMediaLeaseRegistry.Entry): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val reader = SecureMediaLeaseRegistry.beginProviderRead(token, onRevoke = {
            writeSide.closeWithError("Secure media lease was revoked.")
        }) ?: run {
            readSide.close()
            writeSide.close()
            throw FileNotFoundException("Secure media lease was revoked.")
        }
        try {
            PIPE_EXECUTOR.execute {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                    runBlocking {
                        // Do not expose even a valid prefix of a truncated object to another thread/process.
                        // SensitiveFileStore authenticates the whole object after the callback, so perform a verification pass first.
                        lease.sensitiveFileStore.read(lease.reference) { }
                        lease.sensitiveFileStore.read(lease.reference) { input ->
                            val buffer = ByteArray(STREAM_BUFFER_BYTES)
                            try {
                                while (true) {
                                    if (SecureMediaLeaseRegistry.acquire(token) == null) {
                                        throw FileNotFoundException("Secure media lease was revoked.")
                                    }
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    buffer.fill(0, toIndex = count)
                                }
                            } finally {
                                buffer.fill(0)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                try {
                    writeSide.closeWithError("Secure media authentication failed.")
                } catch (_: Exception) {
                    // The pipe may already be closed by the consumer.
                }
            } finally {
                reader.close()
            }
        }
        } catch (failure: Throwable) {
            try {
                readSide.close()
            } finally {
                try {
                    writeSide.close()
                } finally {
                    reader.close()
                }
            }
            throw failure
        }
        return readSide
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openSeekableFile(token: String): ParcelFileDescriptor {
        val storageManager = requireNotNull(context).getSystemService(StorageManager::class.java)
        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = activeEntry(token).reference.plaintextLength

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    val reader = SecureMediaLeaseRegistry.beginProviderRead(token)
                        ?: throw ErrnoException("secure media lease", OsConstants.EACCES)
                    return reader.use {
                    val lease = reader.entry
                    val requested = minOf(size, data.size, MAX_PROXY_READ_BYTES)
                    val plaintext = try {
                        runBlocking {
                            lease.sensitiveFileStore.readRange(lease.reference, offset, requested)
                        }
                    } catch (failure: Exception) {
                        throw ErrnoException("secure media read", OsConstants.EIO, failure)
                    }
                    try {
                        if (SecureMediaLeaseRegistry.acquire(token) == null) {
                            throw ErrnoException("secure media lease", OsConstants.EACCES)
                        }
                        plaintext.copyInto(data)
                        plaintext.size
                    } finally {
                        plaintext.fill(0)
                    }
                    }
                }

                override fun onRelease() = Unit
            },
            PROXY_HANDLER,
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun entry(uri: Uri): SecureMediaLeaseRegistry.Entry = entry(token(uri))

    private fun token(uri: Uri): String {
        val expectedAuthority = "${requireNotNull(context).packageName}.$SECURE_MEDIA_AUTHORITY_SUFFIX"
        if (uri.authority != expectedAuthority || uri.pathSegments.size != 2 || uri.pathSegments[0] != SECURE_MEDIA_PATH_VERSION) {
            throw FileNotFoundException("Invalid secure media URI.")
        }
        return uri.pathSegments[1]
    }

    private fun entry(token: String, callerUid: Int = Binder.getCallingUid()): SecureMediaLeaseRegistry.Entry {
        val admitted = SecureMediaLeaseRegistry.acquireForCaller(token, callerUid) { reason ->
            Timber.d("Secure media provider admission rejected callerUid=%s reason=%s", Binder.getCallingUid(), reason)
        } ?: throw FileNotFoundException("Secure media lease is unavailable.")
        admitted.authorizedState?.authorization?.externalReadBinding?.recipient?.let { recipient ->
            val currentUid = runCatching { requireNotNull(context).packageManager.getPackageUid(recipient.packageName, 0) }.getOrNull()
            if (currentUid != recipient.uid) throw FileNotFoundException("Secure media recipient installation changed.")
        }
        return admitted
    }

    private fun activeEntry(token: String): SecureMediaLeaseRegistry.Entry =
        SecureMediaLeaseRegistry.acquire(token)
            ?: throw ErrnoException("secure media lease", OsConstants.EACCES)

    private companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val MAX_PROXY_READ_BYTES = 4 * 1024 * 1024
        val PIPE_EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "secure-media-pipe").apply { isDaemon = true }
        }
        val PROXY_THREAD = HandlerThread("secure-media-proxy").apply { start() }
        val PROXY_HANDLER = Handler(PROXY_THREAD.looper)
    }
}
