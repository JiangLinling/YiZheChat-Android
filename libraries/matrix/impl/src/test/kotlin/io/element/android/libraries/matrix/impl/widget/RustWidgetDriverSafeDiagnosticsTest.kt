/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RustWidgetDriverSafeDiagnosticsTest {
    @Test
    fun `RTC membership diagnostics expose schema but never values`() {
        val diagnostic = """
            {
              "action":"send_event",
              "api":"fromWidget",
              "data":{
                "type":"org.matrix.msc4143.rtc.member",
                "content":{
                  "msc4354_sticky_key":"SECRET_STICKY_VALUE",
                  "slot_id":"SECRET_SLOT_VALUE",
                  "member":{"user_id":"SECRET_USER","device_id":"SECRET_DEVICE","id":"SECRET_MEMBER"},
                  "application":{"type":"m.call","m.call.intent":"room"},
                  "versions":["1"],
                  "rtc_transports":[{"type":"livekit"}]
                }
              }
            }
        """.trimIndent().safeProtocolIdentity()

        assertThat(diagnostic).contains("rtcShape=root[application,member,msc4354_sticky_key,rtc_transports,slot_id,versions]")
        assertThat(diagnostic).contains("member[device_id,id,user_id]")
        assertThat(diagnostic).contains("application[m.call.intent,type]")
        assertThat(diagnostic).contains("versionsType=array versionsCount=1")
        assertThat(diagnostic).contains("transportsType=array transportsCount=1")
        assertThat(diagnostic).doesNotContain("SECRET_")
        assertThat(diagnostic).doesNotContain("livekit")
    }
}
