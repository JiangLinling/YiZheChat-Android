/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.office

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.OfficePreviewGrantRequest
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectNotFoundException
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import io.element.android.security.securestorage.api.SecureVaultDestroyedException
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

internal const val MAX_OFFICE_PREVIEW_BYTES = 20L * 1024L * 1024L
private const val OFFICE_PREVIEW_BASE_URL_META = "io.element.android.yizhe.officepreview.BASE_URL"
private const val OFFICE_PREVIEW_PIN_META = "io.element.android.yizhe.officepreview.CERTIFICATE_PIN"
private const val OFFICE_IO_BUFFER_SIZE = 64 * 1024
private const val OFFICE_PREVIEW_CACHE_NAME = "encrypted_office_preview_cache_v1"
private const val OFFICE_PREVIEW_CACHE_FORMAT = 1
private const val OFFICE_PREVIEW_API_VERSION = 2
private const val MAX_RENDERED_PDF_BYTES = 64L * 1024L * 1024L
private val pdfMagic = "%PDF-".encodeToByteArray()
private val officePreviewLeaseTtl = 24.hours

internal enum class OfficePreviewPhase {
    HASHING,
    AUTHORIZING,
    UPLOADING,
    OPENING,
}

internal data class OfficePreviewProgress(
    val phase: OfficePreviewPhase,
    val fraction: Float? = null,
)

internal class OfficePreviewSession(
    val sessionId: String,
    val sessionToken: String,
    val viewerPath: String,
    val renderedPath: String,
) {
    override fun toString(): String = "OfficePreviewSession(redacted)"
}

internal sealed interface OfficePreviewDocument : AutoCloseable {
    class Paginated(
        val localMedia: LocalMedia,
        private val lease: SecureMediaLease,
    ) : OfficePreviewDocument {
        override fun close() = lease.close()
    }

    class Spreadsheet(
        val viewerUrl: String,
        val allowedOrigin: String,
        private val closeAction: () -> Unit,
    ) : OfficePreviewDocument {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) closeAction()
        }

        override fun toString(): String = "OfficePreviewDocument.Spreadsheet(redacted)"
    }
}

@Inject
@SingleIn(AppScope::class)
class OfficePreviewService(
    @ApplicationContext private val context: Context,
    private val managementControlService: ManagementControlService,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val sensitiveFileStore: SensitiveFileStore,
    private val secureMediaLeaseManager: SecureMediaLeaseManager,
) {
    private val configuration by lazy { OfficePreviewConfiguration.read(context) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cacheMutex = Mutex()
    private val cachePreferences by lazy {
        context.getSharedPreferences(OFFICE_PREVIEW_CACHE_NAME, Context.MODE_PRIVATE)
    }
    private val client by lazy {
        OkHttpClient.Builder()
            // This client is deliberately isolated from the application's HTTP
            // logging stack because it carries decrypted document bytes.
            .certificatePinner(
                CertificatePinner.Builder()
                    .add(configuration.baseUrl.host, configuration.certificatePin)
                    .build()
            )
            .connectTimeout(20, TimeUnit.SECONDS)
            // The server may spend up to three minutes converting and fetching a
            // rendered PDF. Keep the end-to-end deadline slightly above that
            // bound so the client does not abandon a conversion that is still
            // valid, while coroutine cancellation continues to cancel the call.
            .readTimeout(190, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(210, TimeUnit.SECONDS)
            .build()
    }

    internal suspend fun prepare(
        localMedia: LocalMedia,
        accountId: String,
        deviceId: String,
        onProgress: (OfficePreviewProgress) -> Unit,
    ): OfficePreviewDocument = appOperationalStateCoordinator.performOfficePreview(
        lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_16,
            trigger = "preview",
            accountIdHash = sha256Hex(accountId.encodeToByteArray()),
            matrixDeviceIdHash = sha256Hex(deviceId.encodeToByteArray()),
            roomIdHash = localMedia.lifecycleRoomIdHash,
        )
    ) {
        prepareWithinOperation(localMedia, accountId, deviceId, onProgress)
    }

    private suspend fun prepareWithinOperation(
        localMedia: LocalMedia,
        accountId: String,
        deviceId: String,
        onProgress: (OfficePreviewProgress) -> Unit,
    ): OfficePreviewDocument {
        val extension = localMedia.officePreviewExtension()
            ?: throw OfficePreviewException(OfficePreviewFailure.UNSUPPORTED_FORMAT)
        val isSpreadsheet = extension.isOfficeSpreadsheetExtension()
        notifyProgress(onProgress, OfficePreviewProgress(OfficePreviewPhase.HASHING, 0f))
        val digest = hashSource(localMedia, onProgress)
        val vaultId = deriveAccountVaultId(accountId, deviceId)
        val cacheIdentity = previewCacheIdentity(extension, digest.sha256)
        // A cached preview reference was authorized for an older operation. Reusing it here would
        // leave the current operation without an OFFICE_PREVIEW authority receipt. Until the
        // preview authority exposes an idempotent validation endpoint, every operation obtains a
        // fresh signed grant and service receipt; the cache remains available for cleanup only.
        notifyProgress(onProgress, OfficePreviewProgress(OfficePreviewPhase.AUTHORIZING))
        val grant = managementControlService.createOfficePreviewGrant(
            OfficePreviewGrantRequest(
                accountIdHash = sha256Hex(accountId.encodeToByteArray()),
                fileSha256 = digest.sha256,
                fileSizeBytes = digest.sizeBytes,
                fileExtension = extension,
            )
        )
        if (grant.expiresAtEpochMillis <= System.currentTimeMillis()) {
            throw OfficePreviewException(OfficePreviewFailure.AUTHORIZATION_FAILED)
        }
        notifyProgress(onProgress, OfficePreviewProgress(OfficePreviewPhase.UPLOADING, 0f))
        val uploadBody = UriRequestBody(
            context = context,
            uri = localMedia.uri,
            contentType = localMedia.info.mimeType.toMediaTypeOrNull(),
            contentLength = digest.sizeBytes,
            onProgress = { sent ->
                notifyProgress(
                    onProgress,
                    OfficePreviewProgress(
                        phase = OfficePreviewPhase.UPLOADING,
                        fraction = (sent.toDouble() / digest.sizeBytes.toDouble()).toFloat().coerceIn(0f, 1f),
                    )
                )
            },
        )
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("grant", grant.envelopeJson)
            .addFormDataPart("file", localMedia.info.filename, uploadBody)
            .build()
        val request = Request.Builder()
            .url(configuration.baseUrl)
            .header("Accept", "application/json")
            .post(requestBody)
            .build()
        val responseText = client.newCall(request).awaitSuccessfulBody()
        notifyProgress(onProgress, OfficePreviewProgress(OfficePreviewPhase.OPENING))
        val session = parseSession(responseText)
        if (isSpreadsheet) {
            return OfficePreviewDocument.Spreadsheet(
                viewerUrl = viewerUrl(session),
                allowedOrigin = configuration.origin,
                closeAction = { closeSessionAsync(session) },
            )
        }
        return try {
            val reference = downloadAndCachePdf(
                session = session,
                vaultId = vaultId,
                cacheIdentity = cacheIdentity,
                localMedia = localMedia,
                onProgress = onProgress,
            )
            documentFrom(reference, localMedia)
        } finally {
            closeSession(session)
        }
    }

    private suspend fun closeSession(session: OfficePreviewSession) = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(configuration.baseUrl.newBuilder().addPathSegment(session.sessionId).build())
                .header("Authorization", "Bearer ${session.sessionToken}")
                .delete()
                .build()
            client.newCall(request).execute().use { }
        }
    }

    private fun closeSessionAsync(session: OfficePreviewSession) {
        val url = configuration.baseUrl.newBuilder()
            .encodedPath("/api/mobile/office-previews/${session.sessionId}")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${session.sessionToken}")
            .delete()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private fun viewerUrl(session: OfficePreviewSession): String = configuration.baseUrl.newBuilder()
        .encodedPath(session.viewerPath)
        // Keep the bearer capability in the URL fragment. Fragments are not sent in HTTP requests;
        // the self-hosted viewer consumes it and immediately removes it from browser history.
        .fragment("session=${session.sessionToken}")
        .build()
        .toString()

    private suspend fun hashSource(
        localMedia: LocalMedia,
        onProgress: (OfficePreviewProgress) -> Unit,
    ): SourceDigest = withContext(Dispatchers.IO) {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val expected = localMedia.info.fileSize?.takeIf { it > 0 }
        val input = context.contentResolver.openInputStream(localMedia.uri)
            ?: throw OfficePreviewException(OfficePreviewFailure.SOURCE_UNAVAILABLE)
        input.use { source ->
            val buffer = ByteArray(OFFICE_IO_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > MAX_OFFICE_PREVIEW_BYTES) {
                    throw OfficePreviewException(OfficePreviewFailure.FILE_TOO_LARGE)
                }
                messageDigest.update(buffer, 0, count)
                expected?.let {
                    notifyProgress(
                        onProgress,
                        OfficePreviewProgress(
                            OfficePreviewPhase.HASHING,
                            (total.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f),
                        )
                    )
                }
            }
        }
        if (total <= 0) throw OfficePreviewException(OfficePreviewFailure.SOURCE_UNAVAILABLE)
        SourceDigest(sha256 = messageDigest.digest().toHex(), sizeBytes = total)
    }

    private suspend fun downloadAndCachePdf(
        session: OfficePreviewSession,
        vaultId: AccountVaultId,
        cacheIdentity: String,
        localMedia: LocalMedia,
        onProgress: (OfficePreviewProgress) -> Unit,
    ): SecureObjectReference = withContext(Dispatchers.IO) {
        val renderedUrl = configuration.baseUrl.newBuilder()
            .encodedPath(session.renderedPath)
            .build()
        val request = Request.Builder()
            .url(renderedUrl)
            .header("Accept", MimeTypes.Pdf)
            .header("Authorization", "Bearer ${session.sessionToken}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body.contentType()?.toString()?.substringBefore(';') != MimeTypes.Pdf) {
                throw OfficePreviewException(OfficePreviewFailure.SERVER_REJECTED)
            }
            val declaredLength = response.body.contentLength().takeIf { it in 1..MAX_RENDERED_PDF_BYTES }
            if (response.body.contentLength() > MAX_RENDERED_PDF_BYTES) {
                throw OfficePreviewException(OfficePreviewFailure.FILE_TOO_LARGE)
            }
            val cacheKey = cacheKey(vaultId, cacheIdentity)
            val associatedData = cacheAssociatedData(cacheIdentity)
            val reference = sensitiveFileStore.write(
                SecureObjectWriteRequest(
                    objectId = SecureObjectId("office-preview-pdf-$cacheIdentity"),
                    vaultId = vaultId,
                    purpose = SecureObjectPurpose.DOCUMENT_PREVIEW,
                    associatedData = associatedData,
                    expectedPlaintextLength = declaredLength,
                )
            ) { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(OFFICE_IO_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        if (copied > MAX_RENDERED_PDF_BYTES) {
                            throw OfficePreviewException(OfficePreviewFailure.FILE_TOO_LARGE)
                        }
                        output.write(buffer, 0, count)
                        notifyProgress(
                            onProgress,
                            OfficePreviewProgress(
                                OfficePreviewPhase.OPENING,
                                declaredLength?.let { (copied.toDouble() / it).toFloat().coerceIn(0f, 1f) },
                            )
                        )
                    }
                }
            }
            val prefix = try {
                sensitiveFileStore.readRange(reference, 0, pdfMagic.size)
            } catch (failure: Exception) {
                sensitiveFileStore.delete(reference)
                throw failure
            }
            val valid = try {
                prefix.contentEquals(pdfMagic)
            } finally {
                prefix.fill(0)
            }
            if (!valid) {
                sensitiveFileStore.delete(reference)
                throw OfficePreviewException(OfficePreviewFailure.SERVER_REJECTED)
            }
            val previous = decodeReference(cachePreferences.getString(cacheKey, null), vaultId, cacheIdentity)
            if (!cachePreferences.edit().putString(cacheKey, encodeReference(reference)).commit()) {
                sensitiveFileStore.delete(reference)
                throw OfficePreviewException(OfficePreviewFailure.SERVER_REJECTED)
            }
            if (previous != null && previous.keyEnvelopeId != reference.keyEnvelopeId) {
                sensitiveFileStore.delete(previous)
            }
            reference
        }
    }

    private suspend fun readCachedReference(vaultId: AccountVaultId, cacheIdentity: String): SecureObjectReference? {
        val key = cacheKey(vaultId, cacheIdentity)
        val encodedReference = cachePreferences.getString(key, null)
        val reference = decodeReference(encodedReference, vaultId, cacheIdentity)
        if (reference == null) {
            if (encodedReference != null) cachePreferences.edit().remove(key).commit()
            return null
        }
        val prefix = try {
            sensitiveFileStore.readRange(reference, 0, pdfMagic.size)
        } catch (failure: SecureObjectNotFoundException) {
            cachePreferences.edit().remove(key).commit()
            return null
        } catch (failure: SecureObjectAuthenticationException) {
            cachePreferences.edit().remove(key).commit()
            runCatching { sensitiveFileStore.delete(reference) }
            return null
        } catch (failure: SecureStorageKeyUnavailableException) {
            throw OfficePreviewException(OfficePreviewFailure.AUTHORIZATION_FAILED, failure)
        } catch (failure: SecureVaultDestroyedException) {
            throw OfficePreviewException(OfficePreviewFailure.AUTHORIZATION_FAILED, failure)
        } catch (failure: Exception) {
            throw OfficePreviewException(OfficePreviewFailure.SERVER_REJECTED, failure)
        }
        val valid = try {
            prefix.contentEquals(pdfMagic)
        } finally {
            prefix.fill(0)
        }
        if (!valid) {
            cachePreferences.edit().remove(key).commit()
            sensitiveFileStore.delete(reference)
            return null
        }
        return reference
    }

    private fun documentFrom(reference: SecureObjectReference, source: LocalMedia): OfficePreviewDocument {
        val lease = secureMediaLeaseManager.issue(
            SecureMediaLeaseRequest(
                reference = reference,
                mimeType = MimeTypes.Pdf,
                displayName = source.info.filename.substringBeforeLast('.', source.info.filename) + ".pdf",
                timeToLive = officePreviewLeaseTtl,
                requireSeekableDescriptor = true,
                deleteObjectOnRevoke = false,
            )
        )
        return OfficePreviewDocument.Paginated(
            localMedia = LocalMedia(
                uri = lease.uri,
                info = source.info.copy(
                    mimeType = MimeTypes.Pdf,
                    fileExtension = "pdf",
                    fileSize = reference.plaintextLength,
                ),
                lifecycleOperationId = source.lifecycleOperationId,
                lifecycleRoomIdHash = source.lifecycleRoomIdHash,
            ),
            lease = lease,
        )
    }

    private fun previewCacheIdentity(extension: String, sourceSha256: String): String =
        sha256Hex("office-preview-source-v1\u0000$extension\u0000$sourceSha256".encodeToByteArray())

    private fun cacheKey(vaultId: AccountVaultId, cacheIdentity: String): String =
        sha256Hex("office-preview-cache-key-v1\u0000${vaultId.value}\u0000$cacheIdentity".encodeToByteArray())

    private fun cacheAssociatedData(cacheIdentity: String): ByteArray =
        "office-preview-pdf-v1\u0000$cacheIdentity".encodeToByteArray()

    private fun encodeReference(reference: SecureObjectReference): String = JSONObject()
        .put("version", OFFICE_PREVIEW_CACHE_FORMAT)
        .put("object_id", reference.objectId.value)
        .put("vault_id", reference.vaultId.value)
        .put("purpose", reference.purpose.name)
        .put("key_envelope_id", reference.keyEnvelopeId.value)
        .put("format_version", reference.formatVersion)
        .put("plaintext_length", reference.plaintextLength)
        .put("ciphertext_length", reference.ciphertextLength)
        .put("associated_data_sha256", reference.associatedDataSha256)
        .toString()

    private fun decodeReference(raw: String?, vaultId: AccountVaultId, cacheIdentity: String): SecureObjectReference? = try {
        val value = JSONObject(raw ?: return null)
        val expectedObjectId = "office-preview-pdf-$cacheIdentity"
        if (value.getInt("version") != OFFICE_PREVIEW_CACHE_FORMAT ||
            value.getString("object_id") != expectedObjectId ||
            value.getString("vault_id") != vaultId.value ||
            value.getString("purpose") != SecureObjectPurpose.DOCUMENT_PREVIEW.name
        ) {
            return null
        }
        SecureObjectReference(
            objectId = SecureObjectId(expectedObjectId),
            vaultId = vaultId,
            purpose = SecureObjectPurpose.DOCUMENT_PREVIEW,
            keyEnvelopeId = KeyEnvelopeId(value.getString("key_envelope_id")),
            formatVersion = value.getInt("format_version"),
            plaintextLength = value.getLong("plaintext_length"),
            ciphertextLength = value.getLong("ciphertext_length"),
            associatedDataSha256 = value.getString("associated_data_sha256"),
        ).takeIf {
            it.formatVersion > 0 &&
                it.plaintextLength in pdfMagic.size.toLong()..MAX_RENDERED_PDF_BYTES &&
                it.ciphertextLength > it.plaintextLength &&
                it.associatedDataSha256 == sha256Hex(cacheAssociatedData(cacheIdentity))
        }
    } catch (_: Exception) {
        null
    }

    private fun parseSession(responseText: String): OfficePreviewSession {
        try {
            val root = JSONObject(responseText)
            if (!root.optBoolean("is_success", false)) throw IOException("Preview upload was rejected")
            val data = root.getJSONObject("data")
            val sessionId = data.getString("session_id")
            val sessionToken = data.getString("session_token")
            val viewerPath = data.getString("viewer_path")
            val apiVersion = data.getInt("api_version")
            val renderedPath = data.getString("rendered_path")
            val expiresAt = data.getLong("expires_at_epoch_millis")
            val expectedRenderedPath = "/api/mobile/office-previews/$sessionId/rendered"
            if (!sessionId.matches(Regex("[A-Za-z0-9_-]{24,128}")) ||
                sessionToken.length !in 20..4096 ||
                viewerPath != "/api/mobile/office-previews/viewer" ||
                apiVersion != OFFICE_PREVIEW_API_VERSION ||
                renderedPath != expectedRenderedPath ||
                expiresAt <= System.currentTimeMillis()
            ) {
                throw IOException("Invalid preview session")
            }
            return OfficePreviewSession(sessionId, sessionToken, viewerPath, renderedPath)
        } catch (failure: OfficePreviewException) {
            throw failure
        } catch (failure: Exception) {
            throw OfficePreviewException(OfficePreviewFailure.SERVER_REJECTED, failure)
        }
    }

    private fun notifyProgress(callback: (OfficePreviewProgress) -> Unit, progress: OfficePreviewProgress) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback(progress)
        } else {
            mainHandler.post { callback(progress) }
        }
    }

}

internal enum class OfficePreviewFailure {
    UNSUPPORTED_FORMAT,
    FILE_TOO_LARGE,
    SOURCE_UNAVAILABLE,
    AUTHORIZATION_FAILED,
    SERVER_REJECTED,
}

internal class OfficePreviewException(
    val failure: OfficePreviewFailure,
    cause: Throwable? = null,
) : IOException(failure.name, cause)

private data class SourceDigest(
    val sha256: String,
    val sizeBytes: Long,
)

private class UriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val contentType: MediaType?,
    private val contentLength: Long,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: BufferedSink) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Attachment source is unavailable")
        input.use { source ->
            val buffer = ByteArray(OFFICE_IO_BUFFER_SIZE)
            var sent = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                sink.write(buffer, 0, count)
                sent += count
                if (sent > contentLength) throw IOException("Attachment changed during upload")
                onProgress(sent)
            }
            if (sent != contentLength) throw IOException("Attachment changed during upload")
        }
    }
}

private data class OfficePreviewConfiguration(
    val baseUrl: HttpUrl,
    val certificatePin: String,
) {
    val origin: String
        get() = baseUrl.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')

    companion object {
        fun read(context: Context): OfficePreviewConfiguration {
            val applicationInfo = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            }
            val metadata = applicationInfo.metaData ?: throw OfficePreviewException(OfficePreviewFailure.SERVER_REJECTED)
            val baseUrl = metadata.getString(OFFICE_PREVIEW_BASE_URL_META)
                ?.trim()
                ?.trimEnd('/')
                ?.toHttpUrlOrNull()
                ?: throw OfficePreviewException(OfficePreviewFailure.SERVER_REJECTED)
            if (baseUrl.scheme != "https" || baseUrl.query != null || baseUrl.fragment != null ||
                baseUrl.encodedPath != "/api/mobile/office-previews"
            ) {
                throw OfficePreviewException(OfficePreviewFailure.SERVER_REJECTED)
            }
            val pin = metadata.getString(OFFICE_PREVIEW_PIN_META)?.trim().orEmpty()
            runCatching { CertificatePinner.Builder().add(baseUrl.host, pin).build() }
                .getOrElse { throw OfficePreviewException(OfficePreviewFailure.SERVER_REJECTED, it) }
            return OfficePreviewConfiguration(baseUrl, pin)
        }
    }
}

private suspend fun Call.awaitSuccessfulBody(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching {
                response.use {
                    if (!it.isSuccessful) throw IOException("Preview service rejected the request")
                    it.body.string()
                }
            }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    })
}

internal fun LocalMedia.officePreviewExtension(): String? {
    val extension = info.fileExtension.lowercase()
    if (extension in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx")) return extension
    return when (info.mimeType.lowercase()) {
        "application/msword" -> "doc"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/vnd.ms-excel" -> "xls"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "application/vnd.ms-powerpoint" -> "ppt"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
        else -> null
    }
}

internal fun String.isOfficeSpreadsheetExtension(): Boolean = lowercase() in setOf("xls", "xlsx")

private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).toHex()

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
