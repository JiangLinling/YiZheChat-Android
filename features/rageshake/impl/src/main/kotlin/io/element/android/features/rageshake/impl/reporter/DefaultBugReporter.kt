/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.rageshake.impl.reporter

import android.content.Context
import android.os.Build
import androidx.core.net.toFile
import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.appconfig.RageshakeConfig
import io.element.android.features.rageshake.api.reporter.BugReportAttachment
import io.element.android.features.rageshake.api.reporter.BugReporter
import io.element.android.features.rageshake.api.reporter.BugReporterListener
import io.element.android.features.rageshake.impl.crash.CrashDataStore
import io.element.android.features.rageshake.impl.screenshot.ScreenshotHolder
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.SdkMetadata
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.tracing.TracingService
import io.element.android.libraries.matrix.api.tracing.WriteToFilesConfiguration
import io.element.android.libraries.network.useragent.UserAgentProvider
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.api.sessionIdFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * BugReporter creates and sends the bug reports.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultBugReporter(
    @ApplicationContext private val context: Context,
    @AppCoroutineScope
    private val appCoroutineScope: CoroutineScope,
    private val screenshotHolder: ScreenshotHolder,
    private val crashDataStore: CrashDataStore,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val okHttpClient: () -> OkHttpClient,
    private val userAgentProvider: UserAgentProvider,
    private val sessionStore: SessionStore,
    private val buildMeta: BuildMeta,
    private val bugReporterUrlProvider: BugReporterUrlProvider,
    private val sdkMetadata: SdkMetadata,
    private val matrixClientProvider: MatrixClientProvider,
    private val tracingService: TracingService,
    private val diagnosticSnapshots: DiagnosticSnapshotProvider,
) : BugReporter {
    companion object {
        // filenames
        private const val LOG_CAT_FILENAME = "logcat.log"
        private const val LOG_DIRECTORY_NAME = "logs"
        private const val MULTIPART_SIZE_RESERVE_BYTES = 1024 * 1024L
    }

    private var currentTracingLogLevel: String? = null

    private val baseLogDirectory = File(context.cacheDir, LOG_DIRECTORY_NAME)
    private var currentLogDirectory: File = baseLogDirectory

    init {
        if (buildMeta.isEnterpriseBuild) {
            val logSubfolder = runBlocking {
                sessionStore.getLatestSession()
            }?.userId?.let(::UserId)?.domainName
            setCurrentLogDirectory(logSubfolder)
            sessionStore.sessionIdFlow()
                .map {
                    it?.let(::UserId)?.domainName
                }
                .distinctUntilChanged()
                .onEach { logSubfolder ->
                    setCurrentLogDirectory(logSubfolder)
                    tracingService.updateWriteToFilesConfiguration(WriteToFilesConfiguration.Disabled)
                }
                .launchIn(appCoroutineScope)
        }
    }

    override suspend fun sendBugReport(
        withDevicesLogs: Boolean,
        withCrashLogs: Boolean,
        withScreenshot: Boolean,
        problemDescription: String,
        canContact: Boolean,
        sendPushRules: Boolean,
        attachments: List<BugReportAttachment>,
        listener: BugReporterListener,
    ) {
        val url = bugReporterUrlProvider.provide().first()
        if (url == null) {
            // It should not happen, but if the URL is null, we cannot proceed
            Timber.e("## sendBugReport() : bug report URL is null")
            error("Bug report URL is null, cannot send bug report")
        }
        // enumerate files to delete
        val bugReportFiles: MutableList<File> = ArrayList()
        var response: Response? = null

        // Start at something like 1000 lines to have some 'buffer' in case unexpected lines were added

        try {
            var serverError: String? = null
            withContext(coroutineDispatchers.io) {
                val bugDescription = problemDescription.take(MAX_BUG_DESCRIPTION_CHARS)
                val diagnostics = if (withDevicesLogs || withCrashLogs) diagnosticSnapshots.get() else null
                if (diagnostics != null && !diagnostics.canUpload) {
                    serverError = diagnostics.summary
                    return@withContext
                }
                val sessionData = sessionStore.getLatestSession()
                val numberOfAccounts = sessionStore.numberOfSessions()
                val deviceId = sessionData?.deviceId ?: "undefined"
                val userId = sessionData?.userId?.let { UserId(it) }
                // build the multi part request
                val builder = BugReporterMultipartBody.Builder()
                    .addFormDataPart("text", bugDescription)
                    .addFormDataPart("logs_requested", (withDevicesLogs || withCrashLogs).toString())
                    .addFormDataPart("app", RageshakeConfig.BUG_REPORT_APP_NAME)
                    .addFormDataPart("user_agent", userAgentProvider.provide())
                    .addFormDataPart("user_id", userId?.toString() ?: "undefined")
                    .addFormDataPart("number_of_accounts", numberOfAccounts.toString())
                    .addFormDataPart("can_contact", canContact.toString())
                    .addFormDataPart("device_id", deviceId)
                    .addFormDataPart("device", Build.MODEL.trim())
                    .addFormDataPart("locale", Locale.getDefault().toString())
                    .addFormDataPart("sdk_sha", sdkMetadata.sdkGitSha)
                    .addFormDataPart("local_time", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME))
                    .addFormDataPart("utc_time", LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME))
                    .addFormDataPart("app_id", buildMeta.applicationId)
                    // Nightly versions have a custom version name suffix that we should remove for the bug report
                    .addFormDataPart("Version", buildMeta.versionName.replace("-nightly", ""))
                    .addFormDataPart("label", buildMeta.versionName)
                    .addFormDataPart("label", buildMeta.flavorDescription)
                    .addFormDataPart("branch_name", buildMeta.gitBranchName)

                if (sendPushRules && userId != null) {
                    val rawRules = matrixClientProvider.getOrNull(userId)?.notificationSettingsService?.getRawPushRules()?.getOrNull()
                    // Only collection status and structural count; never upload rule patterns, users, rooms or bodies.
                    val ruleCount = rawRules?.let { Regex("\"enabled\"\\s*:").findAll(it).count() }
                    builder.addFormDataPart("notification_diagnostics", JSONObject()
                        .put("collected", ruleCount != null).put("rule_count", ruleCount ?: JSONObject.NULL).toString())
                }
                userId?.let { matrixClientProvider.getOrNull(it)?.encryptionService }?.let { encryption ->
                    val curve = encryption.deviceCurve25519()
                    val ed = encryption.deviceEd25519()
                    if (curve != null && ed != null) builder.addFormDataPart("device_keys", "curve25519:$curve, ed25519:$ed")
                }
                if (withCrashLogs) builder.addFormDataPart("label", "crash")

                currentTracingLogLevel?.let {
                    builder.addFormDataPart("tracing_log_level", it)
                }
                if (buildMeta.isEnterpriseBuild) {
                    builder.addFormDataPart("label", "Enterprise")
                }
                if (diagnostics != null) {
                    val attachmentBytes = attachments.sumOf { it.file.length() }
                    if (diagnostics.compressed.size + attachmentBytes + MULTIPART_SIZE_RESERVE_BYTES > RageshakeConfig.MAX_LOG_UPLOAD_SIZE) {
                        serverError = "日志与附件超过上传限制，请减少附件后重试"
                        return@withContext
                    }
                    builder.addFormDataPart("diagnostics", diagnostics.manifest)
                    builder.addFormDataPart(
                        "compressed-log",
                        "diagnostics-${diagnostics.snapshot.id}.jsonl.gz",
                        diagnostics.compressed.toRequestBody(MimeTypes.OctetStream.toMediaTypeOrNull())
                    )
                }
                if (withScreenshot) {
                    screenshotHolder.getFileUri()
                        ?.toUri()
                        ?.toFile()
                        ?.let { screenshotFile ->
                            try {
                                builder.addFormDataPart(
                                    "file",
                                    screenshotFile.name,
                                    screenshotFile.asRequestBody(MimeTypes.OctetStream.toMediaTypeOrNull())
                                )
                            } catch (e: Exception) {
                                Timber.e(e, "## sendBugReport() : fail to write screenshot")
                            }
                        }
                }
                attachments.forEach { attachment ->
                    builder.addFormDataPart(
                        "file",
                        attachment.fileName,
                        attachment.file.asRequestBody(attachment.mimeType.toMediaTypeOrNull()),
                    )
                }
                val requestBody = builder.build()
                // add a progress listener
                requestBody.setWriteListener { totalWritten, contentLength ->
                    val percentage = if (-1L != contentLength) {
                        if (totalWritten > contentLength) {
                            100
                        } else {
                            (totalWritten * 100 / contentLength).toInt()
                        }
                    } else {
                        0
                    }
                    Timber.v("## onWrite() : $percentage%")
                    listener.onProgress(percentage)
                }
                // build the request
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()
                var errorMessage: String? = null
                // trigger the request
                try {
                    response = okHttpClient()
                        .newCall(request)
                        .execute()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error executing the request")
                    errorMessage = "网络连接失败，请稍后重试"
                }
                val responseCode = response?.code
                // if the upload failed, try to retrieve the reason
                if (responseCode == HttpURLConnection.HTTP_OK && diagnostics != null) {
                    val receipt = runCatching { JSONObject(response?.body?.string().orEmpty()) }.getOrNull()
                    val stored = receipt?.optJSONObject("diagnostics")
                    val expected = JSONObject(diagnostics.manifest)
                    if (receipt?.optString("report_id")?.matches(Regex("bug-[0-9a-f]{24}")) != true || stored == null ||
                        stored.optString("snapshot_id") != diagnostics.snapshot.id ||
                        stored.optLong("record_count", -1) != diagnostics.snapshot.recordCount.toLong() ||
                        stored.optLong("uncompressed_bytes", -1) != expected.getLong("uncompressed_bytes")) {
                        serverError = "服务器未确认收到这份运行日志，请刷新日志后重试"
                    }
                } else if (responseCode != HttpURLConnection.HTTP_OK) {
                    serverError = if (errorMessage != null) {
                        errorMessage
                    } else {
                        val responseBody = response?.body
                        if (responseBody == null) {
                            bugReportFallback(responseCode)
                        } else {
                            try {
                                val inputStream = responseBody.byteStream()
                                val serverErrorJson = inputStream.use {
                                    it.readBytes().toString(Charsets.UTF_8)
                                }
                                try {
                                    parseBugReportErrorMessage(serverErrorJson, responseCode)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: JSONException) {
                                    Timber.e(e, "Json conversion failed")
                                    bugReportFallback(responseCode)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.e(e, "## sendBugReport() : failed to parse error")
                                bugReportFallback(responseCode)
                            }
                        }
                    }
                }
            }
            if (serverError == null) {
                listener.onUploadSucceed()
            } else {
                listener.onUploadFailed(serverError)
            }
        } finally {
            withContext(coroutineDispatchers.io) {
                // delete the generated files when the bug report process has finished
                for (file in bugReportFiles) {
                    file.safeDelete()
                }
                response?.close()
            }
        }
    }

    override fun logDirectory(): File {
        return currentLogDirectory.apply {
            mkdirs()
        }
    }

    private fun setCurrentLogDirectory(subfolderName: String?) {
        currentLogDirectory = if (subfolderName == null) {
            baseLogDirectory
        } else {
            File(baseLogDirectory, subfolderName)
        }
    }

    suspend fun deleteAllFiles(predicate: (File) -> Boolean) {
        withContext(coroutineDispatchers.io) {
            deleteAllFilesRecursive(baseLogDirectory, predicate)
        }
    }

    private fun deleteAllFilesRecursive(
        directory: File,
        predicate: (File) -> Boolean,
    ) {
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleteAllFilesRecursive(file, predicate)
            } else {
                if (predicate(file)) {
                    file.safeDelete()
                }
            }
        }
    }

    override fun setCurrentTracingLogLevel(logLevel: String) {
        currentTracingLogLevel = logLevel
    }

    // The viewer and upload read the installation-bound encrypted snapshot. Never dump raw logcat.
    override fun saveLogCat(): File? = null
}

internal fun parseBugReportErrorMessage(serverErrorJson: String, responseCode: Int?): String {
    val responseJSON = JSONObject(serverErrorJson)
    val message = when (val error = responseJSON.opt("error")) {
        is JSONObject -> error.optString("message")
            .takeIf { it.isNotBlank() }
            .orEmpty()
        is String -> error
        else -> responseJSON.optString("detail").takeIf { responseJSON.opt("detail") is String }.orEmpty()
    }
    return message.take(300).takeIf { it.any { character -> character in '\u4e00'..'\u9fff' } } ?: bugReportFallback(responseCode)
}

internal fun bugReportFallback(code: Int?): String = when (code) {
    413 -> "日志与附件过大，请减少附件后重试"
    429 -> "提交过于频繁，请稍后重试"
    422 -> "反馈或日志内容不完整，请刷新日志后重试"
    401, 403 -> "反馈提交未获授权，请重新登录后重试"
    500, 502, 503, 504 -> "反馈服务暂时不可用，请稍后重试"
    else -> "反馈提交失败，请稍后重试"
}

internal fun buildBugDescription(
    problemDescription: String,
    crashCallStack: String,
    includeCrashLogs: Boolean,
): String {
    val userDescription = problemDescription.take(MAX_BUG_DESCRIPTION_CHARS)
    if (!includeCrashLogs || crashCallStack.isEmpty() || userDescription.length >= MAX_BUG_DESCRIPTION_CHARS) {
        return userDescription
    }
    val separator = "\n\n\n\n--------------------------------- crash call stack ---------------------------------\n"
    return buildString(MAX_BUG_DESCRIPTION_CHARS) {
        append(userDescription)
        append(separator.take(MAX_BUG_DESCRIPTION_CHARS - length))
        append(crashCallStack.take(MAX_BUG_DESCRIPTION_CHARS - length))
    }
}

private const val MAX_BUG_DESCRIPTION_CHARS = 512_000
