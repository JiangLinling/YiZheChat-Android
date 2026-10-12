/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.clipboard

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.dataprotection.api.RuntimeDataProtectionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidClipboardHelper(
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
    private val matrixContentProtectionRuntime: MatrixContentProtectionRuntime,
) : ClipboardHelper {
    private var expiryJob: Job? = null
    private val invalidatedContentIds = mutableSetOf<String>()

    init {
        matrixContentProtectionRuntime.registerSensitiveContentClearer { contentId ->
            synchronized(this) {
                invalidatedContentIds += contentId.value
                InAppClipboard.clearForContent(contentId.value)
            }
        }
        matrixContentProtectionRuntime.registerAuthorizationRefreshListener { refresh ->
            if (refresh.mode == MatrixAuthorizationRefreshMode.HARD_INVALIDATION) {
                if (RuntimeDataProtectionPolicy.allowsClipboard()) {
                    InAppClipboard.clearProtectedBeforeAuthorizationRevision(refresh.revision)
                } else {
                    InAppClipboard.clear()
                }
            }
        }
    }

    @Synchronized
    override fun copyPlainText(text: String): Boolean {
        val token = InAppClipboard.commitPlainText(
            text = text,
            enforceRuntimePolicyOnRead = true,
        ) ?: return false
        scheduleExpiry(token)
        return true
    }

    @Synchronized
    override fun copyProtectedPlainText(text: String, source: ProtectedClipboardSource): Boolean {
        if (source.contentIds.any(invalidatedContentIds::contains)) return false
        val token = InAppClipboard.commitPlainText(
            text = text,
            protectedSource = source,
            authorizationRevision = matrixContentProtectionRuntime::authorizationRevision,
            enforceRuntimePolicyOnRead = true,
        ) ?: return false
        scheduleExpiry(token)
        return true
    }

    @Synchronized
    internal fun scheduleExpiry(token: ClipboardWriteToken) {
        if (!InAppClipboard.isCurrent(token)) return
        expiryJob?.cancel()
        expiryJob = appCoroutineScope.launch {
            val remainingNanos = (token.expiresAtNanos - System.nanoTime()).coerceAtLeast(0L)
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
            delay(remainingMillis)
            InAppClipboard.clear(token)
        }
    }
}
