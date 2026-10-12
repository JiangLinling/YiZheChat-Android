/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

internal data class AttachmentLibraryState(
    val index: AttachmentIndex? = null,
    val updating: Boolean = false,
    val sharedAccess: Boolean = false,
    val error: Boolean = false,
    val watchedDirectories: List<String> = emptyList(),
)

internal interface AttachmentIndexBackend {
    fun hasSharedAccess(): Boolean
    fun allowedSources(sources: List<AttachmentSource>): List<AttachmentSource>
    suspend fun load(): AttachmentIndexSnapshot?
    suspend fun save(snapshot: AttachmentIndexSnapshot)
    suspend fun sources(sources: List<AttachmentSource>): AttachmentIndex
    suspend fun mediaStamp(): String
    suspend fun media(): AttachmentIndex
    suspend fun baseline(directories: MutableMap<String, Long>, onUpdate: suspend (AttachmentIndex) -> Unit): AttachmentIndex
    suspend fun reconcile(snapshot: AttachmentIndexSnapshot, dirty: Set<String>, force: Boolean): AttachmentIndexSnapshot
    fun watchDirectories(snapshot: AttachmentIndexSnapshot): List<String>
}

/** Lives with the existing picker owner, so hiding/reopening the page does not restart discovery. */
internal class AttachmentIndexedLibrary(private val backend: AttachmentIndexBackend, scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(AttachmentLibraryState())
    val state = mutableState.asStateFlow()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val requestLock = Any()
    private var pending: RefreshRequest? = null
    private var snapshot: AttachmentIndexSnapshot? = null
    private val worker = scope.launch(Dispatchers.IO) {
        for (signal in signals) {
            val request = synchronized(requestLock) { pending.also { pending = null } } ?: continue
            update(request)
        }
    }

    fun refresh(sources: List<AttachmentSource>, force: Boolean = false, sourcesChanged: Boolean = false, dirty: Set<String> = emptySet()) {
        synchronized(requestLock) {
            pending = RefreshRequest(
                sources,
                force || pending?.force == true,
                sourcesChanged || pending?.sourcesChanged == true,
                dirty + pending?.dirty.orEmpty()
            )
        }
        signals.trySend(Unit)
    }

    fun close() {
        signals.close()
        worker.cancel()
        snapshot = null
        mutableState.value = AttachmentLibraryState()
    }

    private suspend fun update(request: RefreshRequest) {
        val started = System.nanoTime()
        mutableState.value = mutableState.value.copy(updating = true, error = false)
        try {
            val allowed = backend.allowedSources(request.sources)
            val shared = backend.hasSharedAccess()
            val key = attachmentSourcesKey(allowed)
            var current = snapshot ?: backend.load().also {
                Timber.tag(TAG).d("event=cache_load hit=%s elapsed_ms=%d", it != null, elapsed(started))
            } ?: AttachmentIndexSnapshot()
            // Current OS authority gates a cached projection before it reaches the page.
            if (!shared) {
                current = current.copy(
                    disk = EMPTY_ATTACHMENT_INDEX,
                    media = EMPTY_ATTACHMENT_INDEX,
                directories = emptyMap(),
                    baselineComplete = false,
                    mediaStamp = "",
                    sharedAccess = false
                )
            }
            if (current.sourcesKey != key) current = current.copy(added = EMPTY_ATTACHMENT_INDEX)
            current = current.copy(sharedAccess = shared)
            publish(current, updating = true)
            if (current.sourcesKey != key || request.force || request.sourcesChanged) {
                val sourceIndex = backend.sources(allowed)
                current =
                    current.copy(added = sourceIndex.copy(failedSources = sourceIndex.failedSources + request.sources.size - allowed.size), sourcesKey = key)
                Timber.tag(TAG).d("event=source_refresh count=%d", allowed.size)
                publish(current, updating = true)
            }
            if (shared) {
                val stamp = backend.mediaStamp()
                if (current.mediaStamp != stamp || request.force) {
                    current = current.copy(media = backend.media(), mediaStamp = stamp)
                    Timber.tag(TAG).d("event=media_query rows=%d", current.media.files.size)
                    publish(current, updating = true)
                }
                if (!current.baselineComplete) {
                    // Save the fast projection before the one-time supplementation begins.
                    backend.save(current)
                    val directories = mutableMapOf<String, Long>()
                    Timber.tag(TAG).d("event=baseline_start")
                    val prefix = current
                    val disk = backend.baseline(directories) { partial ->
                        if (!backend.hasSharedAccess()) throw SecurityException("Shared attachment access revoked")
                        publish(prefix.copy(disk = partial, directories = directories.toMap()), updating = true)
                    }
                    current = current.copy(disk = disk, directories = directories, baselineComplete = true)
                    Timber.tag(TAG).d("event=baseline_finish rows=%d directories=%d", disk.files.size, directories.size)
                } else {
                    current = backend.reconcile(current, request.dirty, request.force)
                }
            }
            if (shared && !backend.hasSharedAccess()) {
                current = current.copy(
                    disk = EMPTY_ATTACHMENT_INDEX,
                    media = EMPTY_ATTACHMENT_INDEX,
                    directories = emptyMap(),
                    baselineComplete = false,
                    mediaStamp = "",
                    sharedAccess = false
                )
            }
            val changed = current != snapshot
            publish(current, updating = false)
            if (changed) backend.save(current)
            snapshot = current
            Timber.tag(TAG).d("event=projection_ready elapsed_ms=%d rows=%d force=%s", elapsed(started), current.projection().files.size, request.force)
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            // An interrupted scan never establishes a completed baseline or an OS grant.
            if (!backend.hasSharedAccess()) {
                snapshot = snapshot?.copy(
                    disk = EMPTY_ATTACHMENT_INDEX,
                    media = EMPTY_ATTACHMENT_INDEX,
                    directories = emptyMap(),
                    baselineComplete = false,
                    mediaStamp = "",
                    sharedAccess = false
                )
                snapshot?.let { publish(it, updating = false) }
            }
            mutableState.value = mutableState.value.copy(
                updating = false,
                error = true,
                sharedAccess = backend.hasSharedAccess(),
                index = if (backend.hasSharedAccess()) mutableState.value.index else null
            )
            Timber.tag(TAG).w("event=index_update_failed type=%s", failure.javaClass.simpleName)
        }
    }

    private fun publish(snapshot: AttachmentIndexSnapshot, updating: Boolean) {
        mutableState.value = AttachmentLibraryState(
        snapshot.projection(),
        updating,
        snapshot.sharedAccess,
            watchedDirectories = backend.watchDirectories(snapshot)
    )
    }

    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000
    private data class RefreshRequest(val sources: List<AttachmentSource>, val force: Boolean, val sourcesChanged: Boolean, val dirty: Set<String>)
    private companion object {
        const val TAG = "AttachmentIndex"
    }
}
