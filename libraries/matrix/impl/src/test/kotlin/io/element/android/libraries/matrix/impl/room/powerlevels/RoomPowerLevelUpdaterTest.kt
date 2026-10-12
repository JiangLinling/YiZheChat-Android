/*
 * Copyright 2026 YiZheChat.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.room.powerlevels

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomPowerLevelUpdaterTest {
    private val original = Json.parseToJsonElement("""
        {"ban":75,"kick":60,"users":{"@admin:server":100,"@other:server":7},
         "events":{"m.call.member":0,"org.matrix.msc3401.call.member":0,"com.example.secret":90},
         "invite":50,"redact":80,"historical":100,"state_default":65,"users_default":10,"events_default":3,
         "notifications":{"room":60,"custom":12},"com.example.future":{"array":[null,true,"value"],"nested":{"level":4}}}
    """).jsonObjectValue()

    @Test
    fun `grant administrator moderator and withdraw edit only target users including custom default`() = runTest {
        val transport = RecordingTransport(original)
        val updater = RoomPowerLevelUpdater(transport, Mutex())
        for (level in listOf(100L, 50L, 0L)) {
            updater.updateUsers(mapOf("@target:server" to level))
            assertThat(transport.current - "users").isEqualTo(original - "users")
            val users = transport.current.getValue("users").jsonObjectValue()
            assertThat(users.getValue("@target:server")).isEqualTo(JsonPrimitive(level))
            assertThat(users - "@target:server").isEqualTo(original.getValue("users").jsonObjectValue())
        }
        assertThat(transport.sent).hasSize(3)
    }

    @Test
    fun `real r63 sparse content preserves stable unstable and historical defaults`() = runTest {
        val r63 = Json.parseToJsonElement("""
            {"ban":50,"kick":50,"users":{"@admin:server":100},
             "events":{"m.call.member":0,"org.matrix.msc3401.call.member":0},
             "invite":50,"redact":50,"historical":100,"state_default":50,"users_default":0,"events_default":0}
        """).jsonObjectValue()
        val transport = RecordingTransport(r63)
        val updater = RoomPowerLevelUpdater(transport, Mutex())
        updater.updateUsers(mapOf("@target:server" to 50))
        updater.updateUsers(mapOf("@target:server" to 0))
        transport.sent.forEach { assertThat(it - "users").isEqualTo(r63 - "users") }
    }

    @Test
    fun `empty and already applied changes send no event`() = runTest {
        val transport = RecordingTransport(original)
        val updater = RoomPowerLevelUpdater(transport, Mutex())
        updater.updateUsers(emptyMap())
        assertThat(transport.reads).isEqualTo(0)
        updater.updateUsers(mapOf("@admin:server" to 100))
        assertThat(transport.sent).isEmpty()
    }

    @Test
    fun `missing users does not materialize unrelated defaults`() = runTest {
        val minimal = Json.parseToJsonElement("""{"events":{"custom":11},"unknown":null}""").jsonObjectValue()
        val transport = RecordingTransport(minimal)
        RoomPowerLevelUpdater(transport, Mutex()).updateUsers(mapOf("@target:server" to 0))
        assertThat(transport.current - "users").isEqualTo(minimal)
    }

    @Test
    fun `malformed users and unavailable fresh authority fail without writing`() = runTest {
        for (bad in listOf("null", "[]", "1", "\"bad\"")) {
            val transport = RecordingTransport(Json.parseToJsonElement("""{"users":$bad}""").jsonObjectValue())
            assertThat(runCatching { RoomPowerLevelUpdater(transport, Mutex()).updateUsers(mapOf("@target:server" to 50)) }.isFailure).isTrue()
            assertThat(transport.sent).isEmpty()
        }
        val transport = RecordingTransport(original).apply { readFailure = true }
        assertThat(runCatching { RoomPowerLevelUpdater(transport, Mutex()).updateUsers(mapOf("@target:server" to 50)) }.isFailure).isTrue()
        assertThat(transport.sent).isEmpty()
    }

    @Test
    fun `remote change before put is a conflict and is never overwritten or replayed`() = runTest {
        val remote = JsonObject(original + ("events_default" to JsonPrimitive(91)))
        val transport = RecordingTransport(original).apply { beforeRead = { if (reads == 2) current = remote } }
        val result = runCatching { RoomPowerLevelUpdater(transport, Mutex()).updateUsers(mapOf("@target:server" to 50)) }
        assertThat(result.isFailure).isTrue()
        assertThat(transport.current).isEqualTo(remote)
        assertThat(transport.sent).isEmpty()
    }

    @Test
    fun `latest authority before first read is merged preserving remote user and unknown changes`() = runTest {
        val remote = JsonObject(original + ("new_unknown" to JsonPrimitive(true)))
        val transport = RecordingTransport(remote)
        RoomPowerLevelUpdater(transport, Mutex()).updateUsers(mapOf("@target:server" to 50, "@second:server" to 100))
        assertThat(transport.current - "users").isEqualTo(remote - "users")
        assertThat(transport.sent).hasSize(1)
    }

    @Test
    fun `different room wrappers serialize through shared mutex and merge preceding user update`() = runTest {
        val enteredSend = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val transport = RecordingTransport(original).apply {
            beforeSend = { if (sent.isEmpty()) { enteredSend.complete(Unit); releaseSend.await() } }
        }
        val mutex = Mutex()
        val first = async { RoomPowerLevelUpdater(transport, mutex).updateUsers(mapOf("@one:server" to 50)) }
        enteredSend.await()
        val second = async { RoomPowerLevelUpdater(transport, mutex).updateUsers(mapOf("@two:server" to 100)) }
        runCurrent()
        assertThat(transport.reads).isEqualTo(2)
        releaseSend.complete(Unit)
        first.await()
        second.await()
        val users = transport.current.getValue("users").jsonObjectValue()
        assertThat(users.getValue("@one:server")).isEqualTo(JsonPrimitive(50))
        assertThat(users.getValue("@two:server")).isEqualTo(JsonPrimitive(100))
        assertThat(transport.sent).hasSize(2)
    }

    @Test
    fun `post write divergence remains failure with no automatic repair`() = runTest {
        val transport = RecordingTransport(original).apply {
            beforeRead = { if (reads == 3) current = original }
        }
        assertThat(runCatching { RoomPowerLevelUpdater(transport, Mutex()).updateUsers(mapOf("@target:server" to 50)) }.isFailure).isTrue()
        assertThat(transport.sent).hasSize(1)
        assertThat(transport.current).isEqualTo(original)
    }

    @Test
    fun `uncertain transport completion never repeats the put`() = runTest {
        val transport = RecordingTransport(original).apply { sendFailure = true }
        assertThat(runCatching { RoomPowerLevelUpdater(transport, Mutex()).updateUsers(mapOf("@target:server" to 50)) }.isFailure).isTrue()
        assertThat(transport.sent).hasSize(1)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectValue() = this as JsonObject

    @Test
    fun `legacy string levels and damaged event maps are preserved without inventing call policy`() = runTest {
        val legacy = Json.parseToJsonElement("""
            {"users":{"@other:server":"100"},"users_default":"0",
             "events":{"org.matrix.msc3401.call.member":"0","custom":"75"},"unknown":null}
        """).jsonObjectValue()
        val transport = RecordingTransport(legacy)
        RoomPowerLevelUpdater(transport, Mutex()).updateUsers(mapOf("@target:server" to 50))
        assertThat(transport.current - "users").isEqualTo(legacy - "users")
        assertThat(transport.current.getValue("users").jsonObjectValue().getValue("@other:server"))
            .isEqualTo(JsonPrimitive("100"))
    }

    @Test
    fun `remote write in final read to put gap is explicitly not an atomic merge`() = runTest {
        val remote = JsonObject(original + ("remote_policy" to JsonPrimitive(99)))
        val transport = RecordingTransport(original).apply { beforeSend = { current = remote } }
        RoomPowerLevelUpdater(transport, Mutex()).updateUsers(mapOf("@target:server" to 50))
        // Documents the protocol limit: double reads cannot detect an overwritten intervening
        // remote write. This test must never be reported as cross-device atomicity acceptance.
        assertThat(transport.current.containsKey("remote_policy")).isFalse()
        assertThat(transport.sent).hasSize(1)
    }

    private class RecordingTransport(var current: JsonObject) : PowerLevelEventTransport {
        val sent = mutableListOf<JsonObject>()
        var reads = 0
        var beforeRead: suspend RecordingTransport.() -> Unit = {}
        var beforeSend: suspend RecordingTransport.() -> Unit = {}
        var readFailure = false
        var sendFailure = false
        override suspend fun read(): JsonObject {
            reads++
            beforeRead()
            check(!readFailure) { "Authority unavailable" }
            return current
        }
        override suspend fun send(content: JsonObject): String {
            beforeSend()
            sent += content
            current = content
            check(!sendFailure) { "Unknown PUT completion" }
            return "\$event${sent.size}"
        }
    }
}
