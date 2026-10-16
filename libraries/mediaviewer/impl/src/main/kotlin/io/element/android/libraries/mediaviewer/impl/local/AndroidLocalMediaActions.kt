/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.content.PermissionChecker
import androidx.core.net.toFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.element.android.libraries.androidutils.system.startInstallFromSourceIntent
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.local.office.FilePreviewLeaseRegistry
import io.element.android.libraries.mediaviewer.impl.local.office.filePreviewLeaseBinding
import io.element.android.security.dataprotection.api.DataProtectionActionDeniedException
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.securestorage.api.SecureMediaExternalRecipient
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@ContributesBinding(AppScope::class)
class AndroidLocalMediaActions internal constructor(
    @ApplicationContext private val context: Context,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val buildMeta: BuildMeta,
    private val policyProvider: DataProtectionPolicyProvider,
    private val secureMediaLeaseManager: SecureMediaLeaseManager,
    private val externalHandoff: ExternalReadHandoff? = null,
    @Suppress("UNUSED_PARAMETER") testConstructor: Unit = Unit,
) : LocalMediaActions {
    @Inject constructor(@ApplicationContext context: Context, coroutineDispatchers: CoroutineDispatchers,
        buildMeta: BuildMeta, policyProvider: DataProtectionPolicyProvider, secureMediaLeaseManager: SecureMediaLeaseManager,
        externalHandoff: ExternalReadHandoff) : this(
            context,
            coroutineDispatchers,
            buildMeta,
            policyProvider,
            secureMediaLeaseManager,
            externalHandoff as ExternalReadHandoff?,
            Unit
        )
    private var activityContext: Context? = null
    private var apkInstallLauncher: ManagedActivityResultLauncher<Intent, ActivityResult>? = null
    private var pendingMedia: LocalMedia? = null
    private var externalLauncher: ManagedActivityResultLauncher<Intent, ActivityResult>? = null
    private var pendingExternalOperation: String? = null
    private val selection = mutableStateOf<ExternalReadSelection?>(null)

    @Composable
    override fun Configure() {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        externalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            pendingExternalOperation?.let { externalHandoff?.returned(it) }
            pendingExternalOperation = null
        }
        selection.value?.let { request ->
            ExternalReadAppChooser(
                request.options,
                onSelect = {
                    request.answer.complete(it)
                    selection.value = null
                },
                onDismiss = {
                    request.answer.complete(null)
                    selection.value = null
                }
            )
        }
        apkInstallLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { activityResult ->
            if (activityResult.resultCode == Activity.RESULT_OK) {
                pendingMedia?.let {
                    coroutineScope.launch {
                        openFile(it)
                    }
                }
            } else {
                // User cancelled
            }
            pendingMedia = null
        }
        return DisposableEffect(Unit) {
            activityContext = context
            onDispose {
                activityContext = null
                externalLauncher = null
                selection.value?.answer?.complete(null)
                selection.value = null
            }
        }
    }

    override suspend fun saveOnDisk(localMedia: LocalMedia): Result<Unit> = withContext(coroutineDispatchers.io) {
        runCatchingExceptions {
            if (!policyProvider.current().policy.allowContentExport) throw DataProtectionActionDeniedException.ContentExport()
            val source = localMedia.materializeForAction()
            require(source.uri.scheme in SUPPORTED_LOCAL_SCHEMES)
            ensureSourceReadAuthorized(source)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveOnDiskUsingMediaStore(source)
            } else {
                saveOnDiskUsingExternalStorageApi(source)
            }
        }.onSuccess {
            Timber.v("Save on disk succeed")
        }.onFailure {
            Timber.e(it, "Save on disk failed")
        }
    }

    override suspend fun share(localMedia: LocalMedia): Result<Unit> = withContext(coroutineDispatchers.io) {
        runCatchingExceptions {
            if (!policyProvider.current().policy.allowExternalShare) throw DataProtectionActionDeniedException.ExternalShare()
            val source = localMedia.materializeForAction()
            require(source.uri.scheme in SUPPORTED_LOCAL_SCHEMES)
            ensureSourceReadAuthorized(source)
            val shareableUri = source.toShareableUri()
            val shareMediaIntent = Intent(Intent.ACTION_SEND)
                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM, shareableUri)
                .setTypeAndNormalize(localMedia.info.mimeType)
                .apply {
                    // createChooser propagates this URI and its read grant to the sharesheet.
                    // Keep the capability opaque; the provider supplies DISPLAY_NAME to recipients.
                    clipData = ClipData(source.info.filename, arrayOf(type), ClipData.Item(shareableUri))
                }
            withContext(coroutineDispatchers.main) {
                if (!policyProvider.current().policy.allowExternalShare) throw DataProtectionActionDeniedException.ExternalShare()
                ensureSourceReadAuthorized(source)
                val intent = Intent.createChooser(shareMediaIntent, null)
                activityContext!!.startActivity(intent)
            }
        }.onSuccess {
            Timber.v("Share media succeed")
        }.onFailure {
            Timber.e(it, "Share media failed")
        }
    }

    override suspend fun open(localMedia: LocalMedia): Result<Unit> = withContext(coroutineDispatchers.io) {
        if (localMedia.progressiveVideo != null) {
            return@withContext runCatchingExceptions {
                if (!policyProvider.current().policy.allowContentExport) throw DataProtectionActionDeniedException.ContentExport()
                val source = localMedia.materializeForAction()
                ensureSourceReadAuthorized(source)
                openFile(source)
            }
        }
        require(localMedia.uri.scheme in SUPPORTED_LOCAL_SCHEMES)
        runCatchingExceptions {
            if (!policyProvider.current().policy.allowContentExport) throw DataProtectionActionDeniedException.ContentExport()
            ensureSourceReadAuthorized(localMedia)
            when (localMedia.info.mimeType) {
                MimeTypes.Apk -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (PermissionChecker.checkPermission(
                                context,
                                Manifest.permission.REQUEST_INSTALL_PACKAGES,
                                -1,
                                -1,
                                context.packageName
                            ) == PermissionChecker.PERMISSION_GRANTED &&
                            activityContext?.packageManager?.canRequestPackageInstalls() == false) {
                            pendingMedia = localMedia
                            activityContext?.startInstallFromSourceIntent(apkInstallLauncher!!).let { }
                        } else {
                            openFile(localMedia)
                        }
                    } else {
                        openFile(localMedia)
                    }
                }
                else -> if (localMedia.filePreviewLeaseToken != null && isProtectedSecureMedia(localMedia)) {
                    openProtectedFile(localMedia)
                } else {
                    openFile(localMedia)
                }
            }
        }.onSuccess {
            Timber.v("Open media handoff dispatched; external read remains unverified")
        }.onFailure {
            Timber.e(it, "Open media failed")
        }
    }

    private suspend fun LocalMedia.materializeForAction(): LocalMedia {
        val progressive = progressiveVideo ?: return this
        progressive.requireAuthorized()
        val file = progressive.materialize()
        progressive.requireAuthorized()
        return copy(uri = file.uri(), progressiveVideo = null)
    }

    private suspend fun openFile(localMedia: LocalMedia) {
        val openMediaIntent = Intent(Intent.ACTION_VIEW)
            .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setDataAndType(localMedia.toShareableUri(), localMedia.info.mimeType)
        withContext(coroutineDispatchers.main) {
            // The policy can change while resolving a URI or switching dispatchers.
            // Recheck at the irreversible handoff to another application.
            ensureContentExportAllowed()
            ensureSourceReadAuthorized(localMedia)
            requireNotNull(activityContext) { "Media viewer activity is unavailable." }
                .startActivity(openMediaIntent)
        }
    }

    private suspend fun openProtectedFile(localMedia: LocalMedia) {
        withContext(coroutineDispatchers.main) {
            ensureContentExportAllowed()
            ensureSourceReadAuthorized(localMedia)
        }
        val handoff = requireNotNull(externalHandoff) { "External protected read authority is unavailable." }
        val options = handoff.recipients(localMedia)
        check(options.isNotEmpty()) { "No compatible external application is installed." }
        val request = ExternalReadSelection(options, CompletableDeferred())
        val recipient = try {
            withContext(coroutineDispatchers.main) {
                check(selection.value == null && externalLauncher != null)
                selection.value = request
            }
            request.answer.await() ?: throw CancellationException("External application selection was cancelled.")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + coroutineDispatchers.main) {
                if (selection.value === request) selection.value = null
            }
        }
        // Selection can outlive the original read authorization. Report its actual rejection
        // before preparing any external operation or grant; never renew the selected source.
        withContext(coroutineDispatchers.main) {
            ensureContentExportAllowed()
            ensureSourceReadAuthorized(localMedia)
        }
        val launch = handoff.prepare(localMedia, recipient)
        try {
            withContext(coroutineDispatchers.main) {
                ensureContentExportAllowed()
                pendingExternalOperation = launch.operationId
                handoff.dispatched(launch.operationId)
                requireNotNull(externalLauncher).launch(launch.intent)
            }
        } catch (failure: Throwable) {
            handoff.failed(launch.operationId)
            throw failure
        }
    }

    private data class ExternalReadSelection(val options: List<ExternalReadRecipientOption>,
        val answer: CompletableDeferred<SecureMediaExternalRecipient?>)

    private fun LocalMedia.toShareableUri(): Uri {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) return uri
        val mediaAsFile = this.toFile()
        val authority = "${buildMeta.applicationId}.fileprovider"
        return FileProvider.getUriForFile(context, authority, mediaAsFile).normalizeScheme()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveOnDiskUsingMediaStore(localMedia: LocalMedia) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, localMedia.info.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, localMedia.info.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val outputUri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)) {
            "Unable to create a pending media export."
        }
        try {
            requireNotNull(localMedia.openStream()) { "Export source is unavailable." }.use { input ->
                requireNotNull(resolver.openOutputStream(outputUri)) { "Export destination is unavailable." }.use { output ->
                    copyCancellable(input, output)
                }
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            ensureContentExportAllowed()
            ensureSourceReadAuthorized(localMedia)
            val published = resolver.update(
                outputUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            check(published == 1) { "Unable to publish the completed media export." }
        } catch (failure: Throwable) {
            try {
                check(resolver.delete(outputUri, null, null) == 1) { "Unable to delete failed pending media export." }
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private suspend fun saveOnDiskUsingExternalStorageApi(localMedia: LocalMedia) {
        check(!isProtectedSecureMedia(localMedia)) {
            "Protected media export requires Android 10 or newer pending-media isolation."
        }
        val target = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            localMedia.info.filename
        )
        check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Export directory is unavailable." }
        val staging = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.pending")
        try {
            requireNotNull(localMedia.openStream()) { "Export source is unavailable." }.use { input ->
                FileOutputStream(staging).use { output ->
                    copyCancellable(input, output)
                    output.fd.sync()
                }
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            ensureContentExportAllowed()
            ensureSourceReadAuthorized(localMedia)
            Os.rename(staging.absolutePath, target.absolutePath)
        } catch (failure: Throwable) {
            try {
                check(!staging.exists() || staging.delete()) { "Unable to delete failed media export staging." }
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun ensureContentExportAllowed() {
        if (!policyProvider.current().policy.allowContentExport) {
            throw DataProtectionActionDeniedException.ContentExport()
        }
    }

    private fun ensureSourceReadAuthorized(localMedia: LocalMedia) {
        val uri = localMedia.uri
        if (uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority?.endsWith(SECURE_MEDIA_AUTHORITY_SUFFIX) == true) {
            val rejection = secureMediaLeaseManager.readAuthorizationRejection(uri)
            if (rejection != null) {
                Timber.d("Protected media action source rejected reason=%s", rejection)
                val failure = ProtectedMediaReadUnavailableException(rejection)
                // Retire only the exact source whose real authority just rejected admission.
                // Propagate loss to the renderer; a cached rendered body is not current authority.
                try {
                    localMedia.filePreviewLeaseToken?.let { token ->
                        FilePreviewLeaseRegistry.acquire(token, localMedia.filePreviewLeaseBinding(), "${context.packageName}.securemedia")
                            ?.release(FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE)
                    }
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }
    }

    private fun isProtectedSecureMedia(localMedia: LocalMedia): Boolean =
        localMedia.uri.scheme == ContentResolver.SCHEME_CONTENT &&
            localMedia.uri.authority?.endsWith(SECURE_MEDIA_AUTHORITY_SUFFIX) == true

    private fun LocalMedia.openStream(): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }

    /**
     * Tries to extract a file from the uri.
     */
    private fun LocalMedia.toFile(): File {
        return uri.toFile()
    }

    private companion object {
        val SUPPORTED_LOCAL_SCHEMES = setOf(ContentResolver.SCHEME_FILE, ContentResolver.SCHEME_CONTENT)
        const val SECURE_MEDIA_AUTHORITY_SUFFIX = ".securemedia"
    }
}

internal suspend fun copyCancellable(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    try {
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val read = input.read(buffer)
            kotlin.coroutines.coroutineContext.ensureActive()
            if (read < 0) return
            output.write(buffer, 0, read)
        }
    } finally {
        buffer.fill(0)
    }
}
