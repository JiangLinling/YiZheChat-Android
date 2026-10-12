/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.ui

import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.impl.ui.acceptCallUiToggleSpeaker
import io.element.android.features.call.impl.ui.acceptCallUiHangup
import io.element.android.features.call.impl.ui.acceptCallUiTerminalError
import io.element.android.features.call.impl.ui.callOrigin
import io.element.android.features.call.impl.utils.injectPromiseWithResolversPolyfill
import io.element.android.features.call.impl.utils.isSameOrigin
import io.element.android.features.call.impl.utils.needsHuaweiLegacyCallPolyfill
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallWebSecurityTest {
    @Test
    fun `speaker route accepts only the embedded main frame action`() {
        val origin = "https://appassets.androidplatform.net"
        assertThat(acceptCallUiToggleSpeaker("toggle-speaker", origin, origin, true)).isTrue()
        assertThat(acceptCallUiToggleSpeaker("toggle-speaker", origin, origin, false)).isFalse()
        assertThat(acceptCallUiToggleSpeaker("toggle-speaker", "https://evil.example", origin, true)).isFalse()
        assertThat(acceptCallUiToggleSpeaker("hangup", origin, origin, true)).isFalse()
        assertThat(acceptCallUiToggleSpeaker(null, origin, origin, true)).isFalse()
    }

    @Test
    fun `terminal presentation accepts only the embedded main frame and cannot request hangup`() {
        val origin = "https://appassets.androidplatform.net"
        assertThat(acceptCallUiTerminalError("terminal-error", origin, origin, true)).isTrue()
        assertThat(acceptCallUiTerminalError("terminal-error", origin, origin, false)).isFalse()
        assertThat(acceptCallUiTerminalError("terminal-error", "http://appassets.androidplatform.net", origin, true)).isFalse()
        assertThat(acceptCallUiTerminalError("terminal-error", "https://appassets.androidplatform.net.evil.example", origin, true)).isFalse()
        assertThat(acceptCallUiTerminalError("hangup", origin, origin, true)).isFalse()
        assertThat(acceptCallUiHangup("terminal-error", origin, origin, true)).isFalse()
        assertThat(acceptCallUiTerminalError(null, origin, origin, true)).isFalse()
    }

    @Test
    fun `native hangup accepts only the exact action from the trusted main frame`() {
        val origin = "https://appassets.androidplatform.net"
        assertThat(acceptCallUiHangup("hangup", origin, origin, true)).isTrue()
        assertThat(acceptCallUiHangup("hangup", origin, origin, false)).isFalse()
        assertThat(acceptCallUiHangup("hangup", "http://appassets.androidplatform.net", origin, true)).isFalse()
        assertThat(acceptCallUiHangup("hangup", "https://appassets.androidplatform.net.evil.example", origin, true)).isFalse()
        assertThat(acceptCallUiHangup("hangup", "https://user@appassets.androidplatform.net", origin, true)).isFalse()
        assertThat(acceptCallUiHangup("other", origin, origin, true)).isFalse()
        assertThat(acceptCallUiHangup(null, origin, origin, true)).isFalse()
    }

    @Test
    fun `same origin accepts trusted paths and rejects origin changes`() {
        val trustedOrigin = "https://appassets.androidplatform.net"

        assertThat(isSameOrigin("https://appassets.androidplatform.net/element-call/index.html", trustedOrigin)).isTrue()
        assertThat(isSameOrigin("https://appassets.androidplatform.net.evil.example/element-call", trustedOrigin)).isFalse()
        assertThat(isSameOrigin("http://appassets.androidplatform.net/element-call", trustedOrigin)).isFalse()
        assertThat(isSameOrigin("https://user@appassets.androidplatform.net/element-call", trustedOrigin)).isFalse()
    }

    @Test
    fun `call origin rejects non HTTPS and user info`() {
        assertThat(callOrigin("https://appassets.androidplatform.net/element-call/index.html"))
            .isEqualTo("https://appassets.androidplatform.net")
        assertThat(callOrigin("http://appassets.androidplatform.net/element-call/index.html")).isNull()
        assertThat(callOrigin("https://user@appassets.androidplatform.net/element-call/index.html")).isNull()
    }

    @Test
    fun `legacy compatibility applies only to Huawei WebView older than Chromium 119`() {
        assertThat(needsHuaweiLegacyCallPolyfill("HUAWEI", 114)).isTrue()
        assertThat(needsHuaweiLegacyCallPolyfill("huawei", 0)).isTrue()
        assertThat(needsHuaweiLegacyCallPolyfill("HUAWEI", 119)).isFalse()
        assertThat(needsHuaweiLegacyCallPolyfill("Xiaomi", 114)).isFalse()
    }

    @Test
    fun `promise compatibility script is inserted before the module script`() {
        val html = "<html><head><script type=\"module\" src=\"call.js\"></script></head></html>"

        val patched = injectPromiseWithResolversPolyfill(html)

        assertThat(patched).contains("if (typeof Promise.withResolvers !== 'function')")
        assertThat(patched!!.indexOf("Promise.withResolvers")).isLessThan(patched.indexOf("<script type=\"module\""))
    }

    @Test
    fun `promise compatibility script is not duplicated`() {
        val html = "<html><head><script type=\"module\" src=\"call.js\"></script></head></html>"
        val firstPass = injectPromiseWithResolversPolyfill(html)!!

        val secondPass = injectPromiseWithResolversPolyfill(firstPass)

        assertThat(secondPass).isEqualTo(firstPass)
    }
}
