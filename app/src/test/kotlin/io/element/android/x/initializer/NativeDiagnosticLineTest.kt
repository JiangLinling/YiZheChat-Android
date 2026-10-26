/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.x.initializer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NativeDiagnosticLineTest {
    @Test fun `real forwarded app event and its continuation cannot echo back through native collector`() {
        val real = "10-26 08:13:00.000 1000 1001 D org.matrix.rust.sdk: elementx: [YiZheTimelinePerf] duration_ms=12"
        val parser = NativeDiagnosticLine()
        assertThat(parser.parse(real)).isNull()
        assertThat(parser.parse("10-26 08:13:00.001 1000 1001 D org.matrix.rust.sdk:  at DeviceRootKey.unwrap(DeviceRootKey.kt:42)")).isNull()
        assertThat(parser.lastWasForwardedApp).isTrue()
        val native = parser.parse("10-26 08:13:00.002 1000 1001 E org.matrix.rust.sdk: matrix_sdk::http_client: SocketTimeoutException")
        assertThat(native?.priority).isEqualTo(6)
        assertThat(native?.message).contains("SocketTimeoutException")
        assertThat(parser.parse("10-26 08:13:00.003 1000 1001 E org.matrix.rust.sdk: new_sdk_crate::transport: HTTP 503")).isNotNull()
        assertThat(parser.parse("10-26 08:13:00.004 1000 1001 E org.matrix.rust.sdk:  at transport.retry(Transport.rs:42)")).isNotNull()
        assertThat(parser.parse("10-26 08:13:00.005 1000 1001 E org.matrix.rust.sdk: thread 'worker' panicked at src/transport.rs:42")).isNotNull()
        assertThat(parser.parse("10-26 08:13:00.006 1000 1001 E org.matrix.rust.sdk: stack backtrace:")).isNotNull()
        assertThat(parser.parse("10-26 08:13:00.007 1000 1001 E org.matrix.rust.sdk: 0: new_sdk_crate::transport::run")).isNotNull()
    }

    @Test fun `fixed direct app logs keep actual sources while command excludes unknown sources and other processes`() {
        val command = NativeDiagnosticLine.command(123, 456)
        assertThat(command).containsAtLeast("--pid=123", "--uid=456", "*:S", "YiZheAuth:V", "YiZheSecuritySetup:V")
        assertThat(command).doesNotContain("*:V")
        val parser = NativeDiagnosticLine()
        listOf(
            "YiZheManagement",
            "YiZheAuth",
            "YiZheStartup",
            "YiZheSecureMedia",
            "YiZheUpdateGate",
            "YiZheSecuritySetup",
            "rustls-platform-verifier-android"
        ).forEach { source ->
            val event = parser.parse("10-26 08:13:00.000 123 124 W $source: YZMGMT_RELEASE_FAILURE=ENVELOPE_DECODE")
            assertThat(event?.source).isEqualTo(source)
            assertThat(event?.priority).isEqualTo(5)
        }
        assertThat(parser.parse("10-26 08:13:00.000 123 124 W UnknownThirdParty: PRIVATE")).isNull()
    }
}
