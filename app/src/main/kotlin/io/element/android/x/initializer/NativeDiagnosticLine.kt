/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.x.initializer

/** Own UID/PID bridge for the SDK and existing fixed direct-Log producers. Never a second sink. */
internal class NativeDiagnosticLine {
    private val header = Regex("\\s([0-9]+)\\s+([0-9]+)\\s+([VDIWEF])\\s+([A-Za-z0-9_.-]+)\\s*:\\s*(.*)")
    private val nativeModule = Regex("^[A-Za-z_][A-Za-z0-9_]*(?:::[A-Za-z0-9_]+)*:")
    private val origins = linkedMapOf<String, Boolean>()
    var lastWasForwardedApp: Boolean = false
        private set
    fun parse(line: String): CapturedDiagnosticLine? {
        lastWasForwardedApp = false
        if (line.length > 128 * 1024) return null
        val match = header.find(line) ?: return null
        val source = match.groupValues[4]
        val priority = when (match.groupValues[3]) {
            "V" -> 2
            "D" -> 3
            "W" -> 5
            "E" -> 6
            "F" -> 7
            else -> 4
        }
        val payload = match.groupValues[5]
        if (source in DIRECT_APP_TAGS) return CapturedDiagnosticLine(priority, source, payload)
        if (source != SDK_TAG) return null
        val producer = match.groupValues[1] + ":" + match.groupValues[2]
        val previousNative = origins[producer] == true
        if (origins.size > 64) origins.remove(origins.keys.first())
        lastWasForwardedApp = payload.startsWith("elementx:") || (origins[producer] == false && payload.trimStart().startsWith("at "))
        if (payload.startsWith("elementx:")) {
            origins[producer] = false
            return null
        }
        val continuation = payload.trimStart().let {
            it.startsWith("at ") || it.startsWith("#") || it.matches(Regex("[0-9]+:.*")) || it == "stack backtrace:"
        }
        val native = nativeModule.containsMatchIn(payload) || payload.contains("panicked at") || (previousNative && continuation)
        if (!native) {
            origins[producer] = false
            return null
        }
        origins[producer] = true
        return CapturedDiagnosticLine(priority, source, payload)
    }

    companion object {
        private const val SDK_TAG = "org.matrix.rust.sdk"
        private val DIRECT_APP_TAGS = setOf(
            "YiZheUpdateGate",
            "YiZheManagement",
            "YiZheStartup",
            "YiZheAuth",
            "YiZheSecureMedia",
            "YiZheSecuritySetup",
            "rustls-platform-verifier-android",
        )

        fun command(pid: Int, uid: Int): List<String> {
            require(pid > 0 && uid >= 0)
            return listOf("logcat", "--pid=$pid", "--uid=$uid", "-v", "threadtime") +
                (DIRECT_APP_TAGS + SDK_TAG).map { "$it:V" } + "*:S"
        }
    }
}

internal data class CapturedDiagnosticLine(val priority: Int, val source: String, val message: String)
