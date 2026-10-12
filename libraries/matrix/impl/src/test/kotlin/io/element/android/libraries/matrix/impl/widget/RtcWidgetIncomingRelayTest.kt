/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RtcWidgetIncomingRelayTest {
    private val event = """{"api":"toWidget","action":"send_event","data":{
        "type":"org.matrix.msc4143.rtc.member","content":{"msc4354_sticky_key":"member"}}}"""
    private val response = """{"api":"fromWidget","action":"org.matrix.msc4157.update_delayed_event","response":{}}"""

    @Test
    fun `slow metadata lookup allows renewal response through immediately`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val output = mutableListOf<String>()
        val relay = RtcWidgetIncomingRelay(backgroundScope, { gate.await(); "restored" }, { output.add(it) })
        relay.deliver(event)
        runCurrent()
        relay.deliver(response)
        assertThat(output).containsExactly(response)
        gate.complete(Unit)
        runCurrent()
        assertThat(output).containsExactly(response, "restored").inOrder()
    }

    @Test
    fun `closing receive scope cancels late event delivery`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val output = mutableListOf<String>()
        val relay = RtcWidgetIncomingRelay(backgroundScope, { gate.await(); "late" }, { output.add(it) })
        relay.deliver(event)
        runCurrent()
        backgroundScope.cancel()
        gate.complete(Unit)
        runCurrent()
        assertThat(output).isEmpty()
    }

    @Test
    fun `complete snapshot and protocol messages bypass metadata lookup`() = runTest {
        val snapshot = event.replace("\"type\":", "\"msc4354_sticky\":{\"duration_ms\":60000},\"type\":")
        val output = mutableListOf<String>()
        val relay = RtcWidgetIncomingRelay(backgroundScope, { error("unexpected lookup") }, { output.add(it) })
        relay.deliver(snapshot)
        relay.deliver(response)
        assertThat(output).containsExactly(snapshot, response).inOrder()
    }

    @Test
    fun `queued snapshot retains absolute lifetime instead of restarting sync TTL`() = runTest {
        val snapshot = event.replace("\"type\":", "\"origin_server_ts\":1000,\"unsigned\":{\"msc4354_sticky_duration_ttl_ms\":55000},\"msc4354_sticky\":{\"duration_ms\":60000},\"type\":")
        val output = mutableListOf<String>()
        val relay = RtcWidgetIncomingRelay(backgroundScope, { error("unexpected lookup") }, { output.add(it) })
        relay.deliver(snapshot)
        val original = Json.parseToJsonElement(snapshot).jsonObject.getValue("data").jsonObject
        val normalized = Json.parseToJsonElement(output.single()).jsonObject.getValue("data").jsonObject
        assertThat(normalized["origin_server_ts"]).isEqualTo(original["origin_server_ts"])
        assertThat(normalized["msc4354_sticky"]).isEqualTo(original["msc4354_sticky"])
        assertThat(normalized.getValue("unsigned").jsonObject).isEmpty()
    }
}
