/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.impl

import io.element.android.security.securestorage.api.SecureDiagnosticEvent
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/** Arbitrary formatted messages are not safe to persist. Retain typed telemetry and code frames only. */
internal object DiagnosticLogRedactor {
    private val telemetrySources = setOf("YiZheMessageStage", "YiZheTimelinePerf", "YiZheDeviceTrust", "YiZheDiagnostics")
    private val directSources = setOf("YiZheUpdateGate", "YiZheManagement", "YiZheStartup", "YiZheAuth", "YiZheSecureMedia", "YiZheSecuritySetup")
    private val diagnosticKey = Regex("YZ(?:MGMT|UPDATE|SETUP)_[A-Z0-9_]{1,80}")
    private val diagnosticReason =
        Regex(
            "(?:DEVICE|HARDWARE|ATTESTATION|ACCESS|OFFLINE|REGISTRATION|MATRIX|KMS|POLICY|SIGNATURE|AUTH|LICENSE|NETWORK|SECURITY|ACCOUNT|INSTALLATION|ENVELOPE|SIGNED|RELEASE|LOCATION)_[A-Z0-9_]{1,80}"
        )
    private val diagnosticEnums = setOf("TIMEOUT", "DEBUG_BUILD", "READY", "PENDING", "CORE_READY", "RETRYABLE_FAILURE", "ACTIVE", "INACTIVE")
    private val field = Regex("(?:^|\\s)([a-zA-Z_]+)=([^\\s]+)")
    private val number = Regex("-?[0-9]{1,19}(?:\\.[0-9]{1,6})?")
    private val stage = Regex("[a-z][a-z0-9_]{0,79}")
    private val operation = Regex("(?:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|lc[0-9]{2}-[a-z-]{1,40}-[0-9a-f]{16,64}|unbound)")
    private val frameName = Regex("[A-Za-z_$][A-Za-z0-9_.$<>-]{0,220}")
    private val fileName = Regex("[A-Za-z0-9_.$-]{1,100}\\.(?:kt|java|rs|js|so)")
    private val numericFields = setOf(
        "duration_ms",
        "duration_us",
        "elapsed_ms",
        "epoch_ms",
        "span",
        "parent",
        "item_count",
        "batch_size",
        "changed_count",
        "applied_count",
        "visible_count",
        "history_count",
        "diff_count",
        "decrypt_queue_depth",
        "decrypt_in_flight",
        "generation",
        "http_status",
        "status_code",
        "retry_count",
        "bytes",
        "count",
        "reason",
        "pss",
        "rss",
        "range_start",
        "range_end",
        "range_total",
        "resume_offset",
        "bytes_per_second",
    )
    private val enumValues = setOf(
        "start",
        "end",
        "point",
        "observed",
        "success",
        "failure",
        "failed",
        "cancelled",
        "true",
        "false",
        "null",
        "VISIBLE",
        "HISTORY",
        "BOOTSTRAP",
        "cache_hit",
        "cache_miss",
        "hit",
        "miss",
        "allowed",
        "denied",
        "unavailable",
        "CDN",
        "ORIGIN_COMPAT",
        "ORIGIN_FALLBACK",
        "UNKNOWN",
    )

    private val componentName = Regex("[A-Za-z][A-Za-z0-9_.:$-]{0,95}")
    private val opaqueSource = Regex("[A-Za-z0-9_-]{40,}")
    private val enumFields = setOf("phase", "outcome", "priority", "cache", "recent", "projection_recent", "authority", "result", "protected_frame")
    private val operationFields = setOf("operation", "operationId", "operation_id")
    private val failureSite = Regex("([A-Za-z_$][A-Za-z0-9_.$]*(?:Exception|Error))@([A-Za-z0-9_.$-]{1,100}):(-?[0-9]{1,8})")
    private val locationName = Regex("[A-Za-z0-9_.$<>:/-]{1,240}")
    private val sensitiveField = Regex(
        "(?i)\\b(?:password|passwd|passphrase|recovery[_ -]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|" +
            "authorization|cookie|private[_-]?key|api[_-]?key|session[_-]?key|secret|body|content|text|payload|plaintext)\\b[\"']?\\s*[:=]\\s*",
    )
    private val technicalCode = Regex(
        "\\b(?:CERT|TLS|SSL|HTTP|KMS|DEVICE|REGISTRATION|REG|ONBOARDING|M|RTC|CALL|PUSH|MEDIA|NETWORK|CLIENT|SERVER|" +
            "SECURITY|ACCESS|OPERATION|LIFECYCLE|OFFLINE|VAULT|SDK|DOWNLOAD|ERR|ERROR|QUOTA|MATRIX|YZMGMT|YZUPDATE|YZSETUP)(?:_[A-Z0-9]+){1,9}\\b",
    )
    private val exceptionName = Regex(
        "\\b(?:SocketTimeoutException|SSLHandshakeException|SSLPeerUnverifiedException|ConnectException|UnknownHostException|" +
            "IOException|SecurityException|IllegalStateException|KmsUnavailable|PanicException|OutOfMemoryError|TimeoutCancellationException|" +
            "CertificateException|CertPathValidatorException|CertificateExpiredException|CertificateNotYetValidException)\\b",
    )
    private val url = Regex("https?://[^\\s\\\"'<>]+")
    private val httpStatus = Regex("(?i)\\b(?:HTTP(?:/[0-9.]+)?[ _:=]+|(?:http_status|status_code|statusCode)[=:]\\s*)([1-5][0-9]{2})\\b")
    private val javaFrame = Regex("\\bat\\s+([A-Za-z0-9_.$<>]+)\\(([^:()]+):([0-9]{1,8})\\)")
    private val nativeFrame = Regex("#([0-9]{1,3})\\s+pc\\s+([0-9a-fA-F]{1,16})\\s+[^\\s]*[/]([^/\\s()]+\\.so)")
    private val rustSymbolFrame = Regex("^\\s*([0-9]{1,3}):\\s+([A-Za-z_][A-Za-z0-9_:.$<>-]{1,220})\\s*$")
    private val rustFileFrame = Regex("^\\s*at\\s+(?:[A-Za-z0-9_./-]*/)?([A-Za-z0-9_.-]+\\.rs):([0-9]{1,8})(?::[0-9]{1,6})?\\s*$")
    private val pathLiterals = setOf(
        "v1",
        "v3",
        "r0",
        "public",
        "app",
        "releases",
        "latest",
        "signing-key",
        "account",
        "onboarding",
        "registration",
        "register",
        "login",
        "invite",
        "devices",
        "device",
        "challenge",
        "attest",
        "license",
        "kms",
        "shares",
        "release",
        "release-batch",
        "room-epochs",
        "content-shares",
        "receipts",
        "sync",
        "send",
        "messages",
        "join",
        "rooms",
        "_matrix",
        "client",
        "media",
        "download",
        "upload",
        "versions",
        "turnServer",
        "_synapse",
        "admin",
        "users",
        "push",
        "notifications",
        "bug-reports",
        "_id_",
    )

    fun redact(
        time: Long,
        priority: Int,
        source: String?,
        message: String,
        throwable: Throwable?,
        location: String? = null,
    ): SecureDiagnosticEvent {
        val safeSource = source?.takeIf { componentName.matches(it) && !opaqueSource.containsMatchIn(it) }
            ?: "UnknownComponent"
        val safeLocation = location?.takeIf(locationName::matches).orEmpty()
        val (scrubbed, redactions) = scrub(message.take(MAX_INPUT_CHARACTERS))
        val fields = field.findAll(scrubbed).mapNotNull { match ->
                val (key, value) = match.destructured
                when {
                    key in numericFields && number.matches(value) -> "$key=$value"
                    key == "stage" && (safeSource in telemetrySources || safeSource in directSources) && stage.matches(value) -> "$key=$value"
                    key in enumFields &&
                        value in enumValues -> "$key=$value"
                    safeSource in directSources && diagnosticKey.matches(key) &&
                        (diagnosticReason.matches(value) || value in diagnosticEnums || value in enumValues) -> "$key=$value"
                    key in operationFields && operation.matches(value) -> "$key=$value"
                    else -> null
                }
        }
            .take(32).toList() + sourceFacts(safeSource, scrubbed)
        val codes = technicalCode.findAll(scrubbed).map { it.value }.filter { it.length <= 96 }.distinct().take(12).toList()
        val exceptions = exceptionName.findAll(scrubbed).map { it.value }.distinct().take(6).toList()
        val status = httpStatus.findAll(scrubbed).map { "http_status=${it.groupValues[1]}" }.distinct().take(3).toList()
        val endpoints = url.findAll(scrubbed).map { "endpoint=${safeEndpoint(it.value)}" }.distinct().take(3).toList()
        val category = when {
            safeSource == "org.matrix.rust.sdk" && scrubbed.contains("panicked at") -> "RUST_PANIC"
            "SocketTimeoutException" in exceptions || scrubbed.contains("timed out", ignoreCase = true) -> "NETWORK_TIMEOUT"
            "ConnectException" in exceptions || scrubbed.contains("connection refused", ignoreCase = true) -> "CONNECTION_FAILED"
            codes.any { it.startsWith("CERT_") || it.startsWith("TLS_") || it.startsWith("SSL_") } -> "CERTIFICATE_VALIDATION"
            status.isNotEmpty() -> "HTTP_RESULT"
            exceptions.isNotEmpty() || throwable != null -> "EXCEPTION"
            else -> "EVENT"
        }
        // The ID hashes safe code identity/categories only, never original user text or credential material.
        val template = "$safeSource|$safeLocation|$category|${fields.map { it.substringBefore('=') }}|$codes|$exceptions"
        val templateId = MessageDigest.getInstance("SHA-256").digest(template.encodeToByteArray())
            .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
        val details = (listOf("template=$templateId", "category=$category") + fields + codes.map { "code=$it" } +
            exceptions.map { "exception=$it" } + status + endpoints).distinct().joinToString(" ")
        val rustFrame = if (safeSource == "org.matrix.rust.sdk") {
            rustSymbolFrame.matchEntire(scrubbed)?.let { " #${it.groupValues[1]} ${it.groupValues[2]}" }
                ?: rustFileFrame.matchEntire(scrubbed)?.let { " at native_code(${it.groupValues[1]}:${it.groupValues[2]})" }.orEmpty()
        } else {
            ""
        }
        val frames = stack(throwable).ifEmpty {
            (javaFrame.findAll(scrubbed).mapNotNull { match ->
                val (method, file, line) = match.destructured
                if (frameName.matches(method) && fileName.matches(file)) " at $method($file:$line)" else null
            } + nativeFrame.findAll(scrubbed).mapNotNull { match ->
                val (index, pc, file) = match.destructured
                file.takeIf(fileName::matches)?.let { " #$index pc $pc $it" }
            }).take(512).joinToString("\n")
        }.ifEmpty { rustFrame }
        return SecureDiagnosticEvent(
            time.coerceAtLeast(0),
            priority.coerceIn(2, 7),
            safeSource,
            details.take(4096),
            frames,
            safeLocation,
            redactions + 1,
        )
    }

    private fun sourceFacts(source: String, message: String): List<String> = when (source) {
        "org.matrix.rust.sdk" -> if (message.contains("panicked at")) listOf("code=SDK_NATIVE_PANIC") else emptyList()
        "YiZheManagement" -> failureSite.findAll(message).take(8).map { "failure_site=${it.value}" }.toList()
        "YiZheAuth" -> listOf("client-create", "observer-notification", "session-persistence")
            .firstOrNull { message.contains("Login finalization failed at $it:") }?.let { listOf("auth_stage=$it") }.orEmpty()
        "YiZheStartup" -> Regex("\\[Startup] \\+([0-9]{1,12})ms ((?:Application|MainActivity|Startup|LoggedInFlowNode|Background)\\.[A-Za-z0-9_.]{1,70})")
            .find(message)?.let { listOf("elapsed_ms=${it.groupValues[1]}", "startup_stage=${it.groupValues[2]}") }.orEmpty()
        "rustls-platform-verifier-android" -> if (message.contains("TrustManager", true)) listOf("code=TLS_TRUST_MANAGER") else emptyList()
        else -> emptyList()
    }

    private fun scrub(message: String): Pair<String, Int> {
        var result = message
        var count = 0
        // Process from the end so original ranges remain stable. Quoted/nested values never become diagnostic fields.
        sensitiveField.findAll(message).toList().asReversed().forEach { match ->
            val start = match.range.last + 1
            if (start >= result.length) return@forEach
            var end = start
            val first = result[start]
            if (first == '"' || first == '\'') {
                end++
                var escaped = false
                while (end < result.length) {
                    val c = result[end++]
                    if (!escaped && c == first) break
                    escaped = !escaped && c == '\\'
                }
            } else if (first == '{' || first == '[') {
                // Conservatively remove nested/free content through the end of this line.
                end = result.indexOf('\n', start).takeIf { it >= 0 } ?: result.length
            } else {
                end = result.indexOf('\n', start).takeIf { it >= 0 } ?: result.length
            }
            result = result.replaceRange(start, end, "[REDACTED]")
            count++
        }
        result = result.replace(Regex("(?i)\\bBearer\\s+[^\\s]+")) {
            count++
            "Bearer [REDACTED]"
        }
        result = result.replace(url) { match ->
            count++
            safeEndpoint(match.value)
        }
        return result to count
    }

    private fun safeEndpoint(value: String): String = runCatching {
        val uri = URI(value.trimEnd(',', ')', ';'))
        val host = uri.host?.lowercase().orEmpty()
        val safeHost = host.takeIf { it == "yizhesy.cn" || it.endsWith(".yizhesy.cn") } ?: "external-host"
        val path = uri.path.orEmpty().split('/').filter(String::isNotEmpty).take(12)
            .joinToString("/") { if (it in pathLiterals) it else "_id_" }
        "https://$safeHost/$path"
    }.getOrDefault("https://external-host/_id_")

    private fun stack(throwable: Throwable?): String {
        if (throwable == null) return ""
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val output = StringBuilder()
        fun append(error: Throwable, label: String, depth: Int) {
            if (depth >= 16 || !seen.add(error)) return
            output.append(label).append(error.javaClass.name.takeIf(frameName::matches) ?: "Throwable").append('\n')
            error.stackTrace.take(512).forEach { frame ->
                val owner = frame.className.takeIf(frameName::matches) ?: "class"
                val method = frame.methodName.takeIf(frameName::matches) ?: "method"
                val file = frame.fileName?.takeIf(fileName::matches) ?: "source"
                output.append(" at ").append(owner).append('.').append(method).append('(').append(file)
                    .append(':').append(frame.lineNumber.coerceAtLeast(0)).append(")\n")
            }
            if (error.stackTrace.size > 512) output.append(" frames_truncated=true\n")
            error.suppressed.take(16).forEach { append(it, "Suppressed: ", depth + 1) }
            error.cause?.let { append(it, "Caused by: ", depth + 1) }
        }
        append(throwable, "", 0)
        return output.toString().take(MAX_STACK_CHARACTERS)
    }

    private const val MAX_INPUT_CHARACTERS = 128 * 1024
    private const val MAX_STACK_CHARACTERS = 128 * 1024
}
