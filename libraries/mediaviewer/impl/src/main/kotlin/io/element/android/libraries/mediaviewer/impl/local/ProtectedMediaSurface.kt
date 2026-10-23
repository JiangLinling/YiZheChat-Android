/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.mediaviewer.impl.R
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.local.office.rememberBoundFilePreviewLease
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** The existing preview authority owns every decoded surface, including an already-open attachment. */
@Composable
internal fun ProtectedMediaSurface(
    gate: ProtectedPreviewAuthorizationGate,
    leaseManager: SecureMediaLeaseManager,
    localMedia: LocalMedia?,
    content: @Composable () -> Unit,
) {
    val access by gate.accessState.collectAsState()
    val revision by gate.authorizationRevision.collectAsState()
    val context = LocalContext.current
    val uri = localMedia?.uri
    val protected = uri?.scheme == "content" && uri.authority?.endsWith(".securemedia") == true
    val hostLease = localMedia?.let { rememberBoundFilePreviewLease(it, "${context.packageName}.securemedia") }
    var sourceAuthorized by remember(uri, revision) { mutableStateOf(!protected) }
    LaunchedEffect(uri, revision, access.allowsPreviewAttempt, hostLease) {
        if (!access.allowsPreviewAttempt) {
            sourceAuthorized = false
            return@LaunchedEffect
        }
        if (!protected || uri == null) {
            sourceAuthorized = true
            return@LaunchedEffect
        }
        var checks = 0
        while (isActive) {
            val valid = try {
                withContext(Dispatchers.IO) {
                    if (localMedia?.filePreviewLeaseToken != null && hostLease == null) false
                    else if (hostLease != null && checks % 10 == 0) hostLease.maintainReadAuthorization() && leaseManager.isReadAuthorized(uri)
                    else leaseManager.isReadAuthorized(uri)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            sourceAuthorized = valid
            if (!valid) {
                hostLease?.release(FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE)
                break // A revoked capability cannot be revived by a later successful poll.
            }
            checks++
            delay(500L)
        }
    }
    if (access.allowsPreviewAttempt && sourceAuthorized) {
        // Disposal releases players, renderers and text state before a new authorization generation reads again.
        key(revision) { content() }
    } else {
        Box(
            Modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(if (access.allowsPreviewAttempt) R.string.error_protected_media_read_unavailable else R.string.error_preview_access_locked),
                color = ElementTheme.colors.textPrimary,
            )
        }
    }
}
