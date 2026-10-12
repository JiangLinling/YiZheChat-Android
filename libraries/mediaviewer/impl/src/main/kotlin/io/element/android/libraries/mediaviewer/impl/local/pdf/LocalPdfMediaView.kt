/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseState
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.R
import io.element.android.libraries.mediaviewer.impl.local.LocalMediaViewState
import io.element.android.libraries.mediaviewer.impl.local.office.rememberBoundFilePreviewLease

/** PDF-only adapter over the same exact host lease used by the local Office branch. */
@Composable
internal fun LocalPdfMediaView(
    localMediaViewState: LocalMediaViewState,
    localMedia: LocalMedia?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val expectedAuthority = "${context.packageName}.securemedia"
    val lease = localMedia?.let { rememberBoundFilePreviewLease(it, expectedAuthority) }
    val leaseIsValid = lease != null &&
        lease.verification.isSatisfied &&
        lease.carrierUri == localMedia?.uri.toString() &&
        lease.state.value == FilePreviewLeaseState.Available
    val validLease = lease?.takeIf { leaseIsValid }
    var failure by remember(localMedia?.uri) { mutableStateOf<Throwable?>(null) }
    var available by remember(validLease) { mutableStateOf(validLease != null) }

    LaunchedEffect(lease, validLease) {
        if (validLease == null) {
            lease?.release(FilePreviewLeaseReleaseReason.SECURITY_BLOCKED)
            available = false
            return@LaunchedEffect
        }
        validLease.state.collect { state -> available = state == FilePreviewLeaseState.Available }
    }

    LaunchedEffect(validLease, lifecycleOwner) {
        if (validLease == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (available) {
                val maintained = try {
                    withContext(Dispatchers.IO) { validLease.maintainReadAuthorization() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!maintained) {
                    failure = PdfSourceUnavailableException()
                    validLease.release(FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE)
                    available = false
                    break
                }
                delay(5_000L)
            }
        }
    }

    DisposableEffect(validLease, lifecycleOwner) {
        if (validLease == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                validLease.release(FilePreviewLeaseReleaseReason.APP_LOCKED)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            validLease.release(FilePreviewLeaseReleaseReason.HOST_DISPOSED)
        }
    }

    if (available && validLease != null) {
        MediaPdfView(
            localMediaViewState = localMediaViewState,
            localMedia = localMedia,
            onClick = onClick,
            onSourceFailure = { cause ->
                failure = cause
                validLease.release(cause.toFilePreviewLeaseReleaseReason())
                available = false
            },
            runtimeFactory = validLease.takeIf { it.tracksRuntimeCleanup }?.let { it::beginRuntime },
            showPageIndicator = true,
            modifier = modifier,
        )
    } else {
        localMediaViewState.isReady = false
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = stringResource(failure.pdfFailureMessageResource()))
        }
    }
}

internal fun Throwable.toFilePreviewLeaseReleaseReason(): FilePreviewLeaseReleaseReason =
    if (this is PdfPreviewPolicyException) {
        FilePreviewLeaseReleaseReason.SECURITY_BLOCKED
    } else {
        FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE
    }

internal fun Throwable?.pdfFailureMessageResource(): Int = when (this) {
    is PdfPreviewPolicyException.EncodedSizeExceeded -> R.string.local_pdf_input_size_limit
    is PdfPreviewPolicyException.PageCountExceeded -> R.string.local_pdf_page_count_limit
    is PdfPreviewPolicyException.InvalidPageGeometry -> R.string.local_pdf_page_geometry_invalid
    is PdfPreviewPolicyException.MemoryUnavailable -> R.string.local_pdf_memory_unavailable
    is PdfSourceUnavailableException -> R.string.local_pdf_source_unavailable
    null -> R.string.local_pdf_source_unavailable
    else -> R.string.local_pdf_render_failed
}
