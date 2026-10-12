/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.localofficeviewer.api

import java.io.InputStream

enum class LocalOfficeFormat(val extension: String) {
    DOC("doc"),
    DOCX("docx"),
    DOCM("docm"),
    XLS("xls"),
    XLSX("xlsx"),
    XLSM("xlsm"),
    PPT("ppt"),
    PPTX("pptx"),
    CSV("csv"),
}

enum class LocalOfficeCapabilityStatus {
    VERIFIED,
    EXPERIMENTAL,
    UNSUPPORTED,
}

data class LocalOfficeFormatCapability(
    val format: LocalOfficeFormat,
    val status: LocalOfficeCapabilityStatus,
    val knownDegradations: List<String> = emptyList(),
)

fun interface LocalOfficeContentSource {
    fun openInputStream(): InputStream
}

data class LocalOfficePreviewRequest(
    val operationId: String,
    val attachmentId: String,
    val displayName: String,
    val declaredMimeType: String?,
    val declaredSize: Long?,
    /** Optional digest supplied by the LC-16 host. When present it is verified before parsing. */
    val expectedSha256: String? = null,
    val contentSource: LocalOfficeContentSource,
) {
    init {
        require(operationId.length in 16..256)
        require(attachmentId.isNotBlank())
        require(displayName.isNotBlank())
        require(declaredSize == null || declaredSize >= 0)
        require(expectedSha256 == null || SHA_256_REGEX.matches(expectedSha256))
    }

    private companion object {
        val SHA_256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    }
}

enum class LocalOfficePreviewState {
    PREPARING,
    VALIDATING,
    RENDERING,
    READY,
    UNSUPPORTED,
    SECURITY_BLOCKED,
    VALIDATION_FAILED,
    RENDER_FAILED,
    CLOSED,
    RESOURCES_RELEASED,
}

enum class LocalOfficeCloseReason {
    USER_CLOSE,
    CANCELLED,
    ATTACHMENT_SWITCHED,
    APP_LOCKED,
    ACCESS_REVOKED,
    ACCOUNT_EXITED,
    UNSUPPORTED,
    SECURITY_BLOCKED,
    VALIDATION_FAILED,
    RENDER_TIMEOUT,
    RENDER_FAILED,
    HOST_DISPOSED,
}

data class LocalOfficeCleanupResult(
    val webRuntimeReleased: Boolean,
    /** Viewer-owned provider/renderer streams are closed; this is not the host MediaFile lease. */
    val sourceClosed: Boolean,
    val validatedPayloadCleared: Boolean,
    /** True only when the exact projected host handle was matched and MediaFile.close returned. */
    val hostLeaseReleased: Boolean,
    val hostLeaseTokenMatched: Boolean,
    val hostActiveStreamsClosed: Boolean,
    val hostBackingCleanupStatus: FilePreviewBackingCleanupStatus,
    val temporaryPlaintextRemoved: Boolean,
    val pendingRecovery: Boolean,
)

sealed interface LocalOfficeFallbackResult {
    data object NotConnected : LocalOfficeFallbackResult
}

fun interface LocalOfficeFallback {
    suspend fun requestFallback(request: LocalOfficePreviewRequest): LocalOfficeFallbackResult
}

object NotConnectedLocalOfficeFallback : LocalOfficeFallback {
    override suspend fun requestFallback(request: LocalOfficePreviewRequest): LocalOfficeFallbackResult =
        LocalOfficeFallbackResult.NotConnected
}

object LocalOfficePocCapabilities {
    val formats: List<LocalOfficeFormatCapability> = LocalOfficeFormat.entries.map {
        LocalOfficeFormatCapability(format = it, status = LocalOfficeCapabilityStatus.EXPERIMENTAL)
    }
}
