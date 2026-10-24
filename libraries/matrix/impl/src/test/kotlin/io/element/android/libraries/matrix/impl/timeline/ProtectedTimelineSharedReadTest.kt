/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.item.event.EventContent
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefresh
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.contentprotection.api.MessageLifecycleMode
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.TimelineContentReleaseClass
import io.element.android.security.contentprotection.api.TimelineContentReadPurpose
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectedTimelineSharedReadTest {
    @Test
    fun `late reader after lifecycle wait reuses completed authorized projection`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.release.complete(Unit)
        fixture.read(this).await().getOrThrow()
        // The in-flight registry has retired, but this exact projection remains authorized.
        assertThat(fixture.cache.completedProjectionForRead(fixture.identity, { fixture.allowed }, { true })).isEqualTo(fixture.content)
        assertThat(fixture.decrypts).isEqualTo(1)
        assertThat(fixture.cache.completedProjectionForRead(fixture.identity.copy(operationId = "other-operation"), { true }, { true })).isNull()
        assertThat(fixture.cache.completedProjectionForRead(fixture.identity.copy(releaseClass = TimelineContentReleaseClass.HISTORY_PREFETCH),
            { true }, { true })).isNull()
        assertThat(fixture.cache.completedProjectionForRead(fixture.identity.copy(readPurpose = TimelineContentReadPurpose.ROOM_PREVIEW),
            { true }, { true })).isNull()
        fixture.allowed = false
        assertThat(fixture.read(this).await().isFailure).isTrue()
        assertThat(fixture.decrypts).isEqualTo(1)
    }

    @Test
    fun `recent room projection survives more than one thousand history admissions`() = runTest {
        val fixture = Fixture(backgroundScope)
        val key = fixture.identity.projection
        fixture.cache.put(key, fixture.content)
        fixture.cache.protectRecentEvents(key.sessionId, key.roomId, listOf(key.eventId))
        repeat(1100) { index -> fixture.cache.put(key.copy(eventId = "history-$index"), fixture.content) }
        assertThat(fixture.cache.get(key)).isEqualTo(fixture.content)
        assertThat(fixture.cache.get(key.copy(eventId = "history-0"))).isNull()
        fixture.cache.clear()
        assertThat(fixture.cache.get(key)).isNull()
    }

    @Test
    fun `protected cache still expires and content destruction removes every projection`() = runTest {
        val fixture = Fixture(backgroundScope)
        val key = fixture.identity.projection
        fixture.cache.protectRecentEvents(key.sessionId, key.roomId, listOf(key.eventId))
        fixture.cache.put(key, fixture.content)
        fixture.now = key.expiresAtEpochMillis
        assertThat(fixture.cache.get(key)).isNull()
        fixture.now = 1000L
        fixture.cache.put(key, fixture.content)
        fixture.runtime.clearContent(key.contentId)
        assertThat(fixture.cache.get(key)).isNull()
    }

    @Test
    fun `recent protection never applies to a different account or room`() = runTest {
        val fixture = Fixture(backgroundScope)
        val key = fixture.identity.projection
        fixture.cache.protectRecentEvents(key.sessionId, key.roomId, listOf(key.eventId))
        val other = key.copy(sessionId = "another-account")
        fixture.cache.put(other, fixture.content)
        repeat(1024) { index -> fixture.cache.put(key.copy(eventId = "history-$index"), fixture.content) }
        assertThat(fixture.cache.get(other)).isNull()
    }

    @Test
    fun `oversized projection is not retained and does not flush recent small projections`() = runTest {
        val fixture = Fixture(backgroundScope)
        val key = fixture.identity.projection
        fixture.cache.put(key, fixture.content)
        fixture.cache.protectRecentEvents(key.sessionId, key.roomId, listOf(key.eventId))
        val hugeKey = key.copy(eventId = "huge")
        fixture.cache.put(hugeKey, aMessageContent(body = "x".repeat(5 * 1024 * 1024)))
        assertThat(fixture.cache.get(hugeKey)).isNull()
        assertThat(fixture.cache.get(key)).isEqualTo(fixture.content)
    }

    @Test
    fun `sender echo projection keeps SEND ownership without starting or receipting RECEIVE`() = runTest {
        val fixture = Fixture(backgroundScope)
        val pending = PendingProtectedPayloadCache({ fixture.now })
        val key = PendingProtectedPayloadCache.Key(ContentId("content"), "account", "room", "digest")
        val echoIdentity = PendingProtectedPayloadCache.Identity("device", "installation", "full-envelope", 0, 0)
        val operation = LifecycleOperationContext("originating-send-operation", FullAppLifecycleId.LC_13)
        pending.put(key, "echo".encodeToByteArray(), PendingProtectedPayloadCache.Owner(operation, echoIdentity, 2_000L))
        val ticket = checkNotNull(pending.ownership(key, echoIdentity))
        val coordinator = FakeAppOperationalStateCoordinator()
        val readIdentity = fixture.identity.copy(operationId = operation.operationId)
        val readers = List(10) { fixture.read(this, readIdentity, canRead = { pending.isCurrent(ticket) }) {
            assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(operation.operationId)
            val bytes = checkNotNull(pending.get(ticket))
            try { fixture.release.await(); fixture.content } finally { bytes.fill(0) }
        } }
        runCurrent()
        assertThat(fixture.decrypts).isEqualTo(1)
        fixture.release.complete(Unit)
        readers.awaitAll().forEach { it.getOrThrow() }
        assertThat(coordinator.startedRequests).isEmpty()
        assertThat(coordinator.clientReceipts).isEmpty()
    }

    @Test
    fun `target destruction during sender projection rejects late local plaintext`() = runTest {
        val fixture = Fixture(backgroundScope)
        val pending = PendingProtectedPayloadCache({ fixture.now })
        val key = PendingProtectedPayloadCache.Key(ContentId("content"), "account", "room", "digest")
        val echoIdentity = PendingProtectedPayloadCache.Identity("device", "installation", "full-envelope", 0, 0)
        val operation = LifecycleOperationContext("originating-send-operation", FullAppLifecycleId.LC_13)
        pending.put(key, "echo".encodeToByteArray(), PendingProtectedPayloadCache.Owner(operation, echoIdentity, 2_000L))
        val ticket = checkNotNull(pending.ownership(key, echoIdentity))
        val read = fixture.read(this, fixture.identity.copy(operationId = operation.operationId), canRead = { pending.isCurrent(ticket) }) {
            withContext(NonCancellable) { fixture.release.await() }
            fixture.content
        }
        runCurrent()
        pending.remove(key.contentId)
        fixture.runtime.clearContent(key.contentId)
        fixture.release.complete(Unit)
        assertThat(read.await().isFailure).isTrue()
        assertThat(fixture.cache.get(fixture.identity.projection)).isNull()
    }

    @Test
    fun `optional identity failure falls back without authorizing or decrypting`() = runTest {
        val fixture = Fixture(backgroundScope)
        assertThat(optionalCarrierIdentity<String> { throw IllegalStateException("identity unavailable") }).isNull()
        assertThat(fixture.decrypts).isEqualTo(0)
    }

    @Test
    fun `optional identity cancellation stays cancelled`() = runTest {
        val cancelled = CancellationException("page closed")
        assertThat(runCatching { optionalCarrierIdentity<String> { throw cancelled } }.exceptionOrNull()).isSameInstanceAs(cancelled)
    }

    @Test
    fun `optional identity retains a successfully validated identity`() = runTest {
        assertThat(optionalCarrierIdentity { "validated installation" }).isEqualTo("validated installation")
    }

    @Test
    fun `one hundred concurrent consumers run one authorized decrypt and retain LC13 context`() = runTest {
        val fixture = Fixture(backgroundScope)
        val readers = List(100) { fixture.read(this) }
        runCurrent()
        assertThat(fixture.decrypts).isEqualTo(1)
        fixture.release.complete(Unit)
        assertThat(readers.awaitAll().all { it.getOrThrow() === fixture.content }).isTrue()
        assertThat(fixture.cache.get(fixture.identity.projection)).isSameInstanceAs(fixture.content)
    }

    @Test
    fun `completed flight is not a second authorization cache`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.release.complete(Unit)
        fixture.read(this).await().getOrThrow()
        fixture.read(this).await().getOrThrow()
        assertThat(fixture.decrypts).isEqualTo(2)
    }

    @Test
    fun `shared decrypt preserves one receive operation and its existing receipt ownership`() = runTest {
        val fixture = Fixture(backgroundScope)
        val coordinator = FakeAppOperationalStateCoordinator()
        val work = TimelineLifecycleWork { System.currentTimeMillis() + testScheduler.currentTime }
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_13, "receive", operationId = fixture.identity.operationId)
        val readers = List(10) { async {
            work.receive(coordinator, request) {
                fixture.cache.readOnce(fixture.identity, { fixture.allowed }) {
                    fixture.decrypts++
                    fixture.release.await()
                    fixture.content
                }
            }
        } }
        runCurrent()
        assertThat(fixture.decrypts).isEqualTo(1)
        fixture.release.complete(Unit)
        readers.awaitAll()
        assertThat(coordinator.startedRequests).hasSize(1)
        assertThat(coordinator.clientReceipts).hasSize(2)
        fixture.allowed = false
        val denied = runCatching { work.receive(coordinator, request) {
            fixture.cache.readOnce(fixture.identity, { fixture.allowed }) { error("Closed gate must not decrypt") }
        } }
        assertThat(denied.exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        assertThat(coordinator.clientReceipts).hasSize(2)
    }

    @Test
    fun `mismatched lifecycle operation cannot join protected work`() = runTest {
        val fixture = Fixture(backgroundScope)
        val result = runCatching { withContext(LifecycleOperationContext("wrong-operation", FullAppLifecycleId.LC_13)) {
            fixture.cache.readOnce(fixture.identity, { true }) { error("Must not decrypt") }
        } }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fixture.decrypts).isEqualTo(0)
    }

    @Test
    fun `operation device installation envelope and outer metadata isolate concurrent reads`() = runTest {
        val fixture = Fixture(backgroundScope)
        val original = fixture.identity
        val identities = listOf(
            original,
            original.copy(operationId = "other-operation"),
            original.copy(matrixDeviceId = "other-device"),
            original.copy(installationId = "other-installation"),
            original.copy(previewRevision = 1L),
            original.copy(readPurpose = TimelineContentReadPurpose.ROOM_PREVIEW),
            original.copy(encryptedMessage = original.encryptedMessage.copy(body = "different-encrypted-header")),
            original.copy(encryptedMessage = original.encryptedMessage.copy(isEdited = true)),
            original.copy(projection = original.projection.copy(sessionId = "other-account")),
            original.copy(projection = original.projection.copy(roomId = "other-room")),
            original.copy(projection = original.projection.copy(ciphertextHash = "other-version")),
        )
        val readers = identities.map { fixture.read(this, it) }
        runCurrent()
        assertThat(fixture.decrypts).isEqualTo(identities.size)
        fixture.release.complete(Unit)
        readers.awaitAll().forEach { it.getOrThrow() }
    }

    @Test
    fun `foreground does not join blocked history or unscoped preview`() = runTest {
        val fixture = Fixture(backgroundScope)
        val blocked = CompletableDeferred<Unit>()
        val history = fixture.read(this, fixture.identity.copy(releaseClass = TimelineContentReleaseClass.HISTORY_PREFETCH)) {
            blocked.await()
            fixture.content
        }
        val preview = fixture.read(this, fixture.identity.copy(releaseClass = null)) {
            blocked.await()
            fixture.content
        }
        runCurrent()
        fixture.release.complete(Unit)
        val visible = fixture.read(this)
        runCurrent()
        assertThat(visible.isCompleted).isTrue()
        visible.await().getOrThrow()
        assertThat(history.isCompleted).isFalse()
        assertThat(preview.isCompleted).isFalse()
        blocked.complete(Unit)
        history.await().getOrThrow()
        preview.await().getOrThrow()
    }

    @Test
    fun `each consumer independently rejects a result after its display gate closes`() = runTest {
        val fixture = Fixture(backgroundScope)
        var secondAllowed = true
        val first = fixture.read(this)
        val second = fixture.read(this, canRead = { secondAllowed })
        runCurrent()
        secondAllowed = false
        fixture.release.complete(Unit)
        assertThat(first.await().isSuccess).isTrue()
        assertThat(second.await().exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        assertThat(fixture.decrypts).isEqualTo(1)
    }

    @Test
    fun `closed gate cannot join or initiate protected work`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.allowed = false
        assertThat(fixture.read(this).await().exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        assertThat(fixture.decrypts).isEqualTo(0)
    }

    @Test
    fun `cancelling one waiter preserves another waiter and the shared read`() = runTest {
        val fixture = Fixture(backgroundScope)
        val first = fixture.read(this)
        val second = fixture.read(this)
        runCurrent()
        first.cancel()
        runCurrent()
        fixture.release.complete(Unit)
        assertThat(second.await().isSuccess).isTrue()
        assertThat(fixture.decrypts).isEqualTo(1)
    }

    @Test
    fun `closed joining page cannot start or join a protected read`() = runTest {
        val fixture = Fixture(backgroundScope)
        assertThat(fixture.read(this, canConsume = { false }).await().exceptionOrNull())
            .isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        assertThat(fixture.decrypts).isEqualTo(0)
    }

    @Test
    fun `closed producing page cannot consume a result but another live page can`() = runTest {
        val fixture = Fixture(backgroundScope)
        var ownerScopeLive = true
        val owner = fixture.read(this, canConsume = { ownerScopeLive })
        val follower = fixture.read(this)
        runCurrent()
        ownerScopeLive = false
        fixture.release.complete(Unit)
        assertThat(owner.await().exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        assertThat(follower.await().isSuccess).isTrue()
        assertThat(fixture.decrypts).isEqualTo(1)
    }

    @Test
    fun `producer viewport cancellation lets live followers share one fresh authorized retry`() = runTest {
        val fixture = Fixture(backgroundScope)
        var ownerScopeLive = true
        val owner = fixture.read(this, canConsume = { ownerScopeLive }) {
            fixture.release.await()
            throw CancellationException("Test producing viewport ended")
        }
        val followers = List(2) { fixture.read(this) }
        runCurrent()
        ownerScopeLive = false
        fixture.release.complete(Unit)
        assertThat(owner.await().exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        followers.awaitAll().forEach { it.getOrThrow() }
        assertThat(fixture.decrypts).isEqualTo(2)
    }

    @Test
    fun `repeated producer cancellation has a bounded retry and does not cancel application work`() = runTest {
        val fixture = Fixture(backgroundScope)
        val reader = fixture.read(this) { throw CancellationException("Test source cancellation") }
        assertThat(reader.await().exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
        assertThat(fixture.decrypts).isEqualTo(2)
    }

    @Test
    fun `last waiter cancellation fences a noncancellable late result and permits fresh retry`() = runTest {
        val fixture = Fixture(backgroundScope)
        val first = fixture.read(this) {
            withContext(NonCancellable) { fixture.release.await() }
            fixture.content
        }
        runCurrent()
        first.cancel()
        runCurrent()
        fixture.release.complete(Unit)
        runCurrent()
        assertThat(fixture.cache.get(fixture.identity.projection)).isNull()
        assertThat(fixture.read(this).await().isSuccess).isTrue()
    }

    @Test
    fun `lock or logout clear cannot be repopulated by a noncancellable late result`() = runTest {
        val fixture = Fixture(backgroundScope)
        val readers = List(2) { fixture.read(this) {
            withContext(NonCancellable) { fixture.release.await() }
            fixture.content
        } }
        runCurrent()
        fixture.runtime.clearSensitiveCaches()
        fixture.release.complete(Unit)
        readers.awaitAll().forEach {
            assertThat(it.exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        }
        assertThat(fixture.cache.get(fixture.identity.projection)).isNull()
    }

    @Test
    fun `content destruction fences all target flights without cancelling unrelated history`() = runTest {
        val fixture = Fixture(backgroundScope)
        val target = fixture.read(this)
        val otherIdentity = fixture.identity.copy(projection = fixture.identity.projection.copy(contentId = ContentId("other")))
        val other = fixture.read(this, otherIdentity)
        runCurrent()
        fixture.runtime.clearContent(fixture.identity.projection.contentId)
        fixture.release.complete(Unit)
        assertThat(target.await().exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        assertThat(other.await().isSuccess).isTrue()
        assertThat(fixture.cache.get(fixture.identity.projection)).isNull()
        assertThat(fixture.cache.get(otherIdentity.projection)).isNotNull()
    }

    @Test
    fun `event eviction fences the pending projection`() = runTest {
        val fixture = Fixture(backgroundScope)
        val reader = fixture.read(this)
        runCurrent()
        fixture.cache.remove("room", "event")
        fixture.release.complete(Unit)
        assertThat(reader.await().exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        assertThat(fixture.cache.get(fixture.identity.projection)).isNull()
    }

    @Test
    fun `authorization revision change fences the pending read`() = runTest {
        val fixture = Fixture(backgroundScope)
        val reader = fixture.read(this)
        runCurrent()
        fixture.runtime.revision++
        fixture.release.complete(Unit)
        assertThat(reader.await().exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        assertThat(fixture.cache.get(fixture.identity.projection)).isNull()
    }

    @Test
    fun `runtime refresh interrupts waiters before a slow network completes`() = runTest {
        val fixture = Fixture(backgroundScope)
        val reader = fixture.read(this)
        runCurrent()
        fixture.runtime.requestAuthorizationRefresh()
        runCurrent()
        assertThat(reader.isCompleted).isTrue()
        assertThat(reader.await().exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        assertThat(fixture.cache.get(fixture.identity.projection)).isNull()
    }

    @Test
    fun `expiry interrupts pending reads at the immutable content deadline`() = runTest {
        val fixture = Fixture(backgroundScope)
        val reader = fixture.read(this)
        runCurrent()
        fixture.now = 2_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertThat(reader.await().exceptionOrNull()).isInstanceOf(ContentProtectionException.Expired::class.java)
        fixture.release.complete(Unit)
        runCurrent()
        assertThat(fixture.cache.get(fixture.identity.projection)).isNull()
    }

    @Test
    fun `transient failure is shared but not retained and retry decrypts again`() = runTest {
        val fixture = Fixture(backgroundScope)
        var fail = true
        val decrypt: suspend () -> EventContent = {
            fixture.release.await()
            if (fail) throw ContentProtectionException.KmsUnavailable()
            fixture.content
        }
        val readers = List(2) { fixture.read(this, decrypt = decrypt) }
        runCurrent()
        fixture.release.complete(Unit)
        readers.awaitAll().forEach { assertThat(it.exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java) }
        fail = false
        assertThat(fixture.read(this, decrypt = decrypt).await().isSuccess).isTrue()
        assertThat(fixture.decrypts).isEqualTo(2)
    }

    @Test
    fun `history and bootstrap registry capacity reserve slots for actual new messages`() = runTest {
        for (releaseClass in listOf(TimelineContentReleaseClass.HISTORY_PREFETCH, TimelineContentReleaseClass.FOREGROUND_BOOTSTRAP)) {
            val fixture = Fixture(backgroundScope)
            val history = List(33) { index -> fixture.read(this, fixture.identity.copy(
                projection = fixture.identity.projection.copy(eventId = "history-$index"), releaseClass = releaseClass,
            )) }
            runCurrent()
            assertThat(fixture.decrypts).isEqualTo(32)
            assertThat(history.last().await().exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
            val fresh = fixture.read(this)
            runCurrent()
            assertThat(fixture.decrypts).isEqualTo(33)
            fixture.release.complete(Unit)
            history.dropLast(1).awaitAll().forEach { it.getOrThrow() }
            fresh.await().getOrThrow()
        }
    }

    @Test
    fun `preview capacity also reserves slots for new messages`() = runTest {
        val fixture = Fixture(backgroundScope)
        val previews = List(33) { index -> fixture.read(this, fixture.identity.copy(
            projection = fixture.identity.projection.copy(eventId = "preview-$index"), releaseClass = null,
        )) }
        runCurrent()
        assertThat(previews.last().await().exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
        val fresh = fixture.read(this)
        runCurrent()
        assertThat(fixture.decrypts).isEqualTo(33)
        fixture.release.complete(Unit)
        previews.dropLast(1).awaitAll().forEach { it.getOrThrow() }
        fresh.await().getOrThrow()
    }

    @Test
    fun `total pending capacity is bounded and duplicate joins still work at capacity`() = runTest {
        val fixture = Fixture(backgroundScope)
        val readers = List(65) { index -> fixture.read(this, fixture.identity.copy(
            projection = fixture.identity.projection.copy(eventId = "visible-$index"),
        )) }
        runCurrent()
        assertThat(fixture.decrypts).isEqualTo(64)
        assertThat(readers.last().await().exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
        val duplicate = fixture.read(this, fixture.identity.copy(projection = fixture.identity.projection.copy(eventId = "visible-0")))
        runCurrent()
        assertThat(fixture.decrypts).isEqualTo(64)
        fixture.release.complete(Unit)
        readers.dropLast(1).awaitAll().forEach { it.getOrThrow() }
        duplicate.await().getOrThrow()
    }

    @Test
    fun `unverified or burn marked results never enter shared projection cache`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.release.complete(Unit)
        listOf(fixture.content.copy(isContentProtectionAuthorized = false), fixture.content.copy(isBurnAfterRead = true)).forEach { content ->
            assertThat(fixture.read(this) { content }.await().exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
        }
        assertThat(fixture.cache.get(fixture.identity.projection)).isNull()
    }

    @Test
    fun `flash media offline quota and sender echo cannot enter shared ordinary text path`() {
        fun eligible(
            kind: ContentKind = ContentKind.TEXT,
            lifecycle: MessageLifecycleMode = MessageLifecycleMode.FOLLOW_ROOM,
            lease: OfflineLeaseMode = OfflineLeaseMode.ONLINE_REQUIRED,
            local: Boolean = false,
            burn: Boolean = false,
        ) = canShareProtectedTextRead(kind, lifecycle, lease, local, burn)
        assertThat(eligible()).isTrue()
        ContentKind.entries.filterNot { it == ContentKind.TEXT }.forEach { assertThat(eligible(kind = it)).isFalse() }
        assertThat(eligible(lifecycle = MessageLifecycleMode.BURN_AFTER_READ)).isFalse()
        assertThat(eligible(lease = OfflineLeaseMode.BOUNDED_OFFLINE)).isFalse()
        assertThat(eligible(local = true)).isFalse()
        assertThat(eligible(burn = true)).isFalse()
    }

    @Test
    fun `room summary reuses only an exact observed Matrix carrier and does not decrypt`() = runTest {
        val fixture = Fixture(backgroundScope)
        val scope = ProtectedTimelineDecryptionCache.CarrierScope("account", "device", "installation", "room", "live#1")
        fixture.openCarrierScope(scope)
        val carrier = ProtectedTimelineDecryptionCache.ObservedCarrier(fixture.identity.projection, 12L, "sender", fixture.identity.encryptedMessage, 0L)
        fixture.cache.updateObservedCarrier(scope, carrier)
        assertThat(fixture.cache.observedEventIdentity(scope.copy(handle = "summary"), 12L, "sender", carrier.encryptedMessage)).isEqualTo("event")
        assertThat(fixture.decrypts).isEqualTo(0)
        listOf(scope.copy(sessionId = "other"), scope.copy(deviceId = "other"), scope.copy(installationId = "other"), scope.copy(roomId = "other"))
            .forEach { assertThat(fixture.cache.observedEventIdentity(it, 12L, "sender", carrier.encryptedMessage)).isNull() }
        assertThat(fixture.cache.observedEventIdentity(scope, 13L, "sender", carrier.encryptedMessage)).isNull()
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "other", carrier.encryptedMessage)).isNull()
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage.copy(isEdited = true))).isNull()
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage.copy(body = "other carrier"))).isNull()
    }

    @Test
    fun `ambiguous Matrix carrier never picks an event arbitrarily`() = runTest {
        val fixture = Fixture(backgroundScope)
        val scope = ProtectedTimelineDecryptionCache.CarrierScope("account", "device", "installation", "room", "live#1")
        fixture.openCarrierScope(scope)
        fixture.openCarrierScope(scope.copy(handle = "live#2"))
        val carrier = ProtectedTimelineDecryptionCache.ObservedCarrier(fixture.identity.projection, 12L, "sender", fixture.identity.encryptedMessage, 0L)
        fixture.cache.updateObservedCarrier(scope, carrier)
        fixture.cache.updateObservedCarrier(scope.copy(handle = "live#2"), carrier.copy(projection = carrier.projection.copy(eventId = "other-event")))
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage)).isNull()
        fixture.cache.updateObservedCarrier(scope.copy(handle = "live#2"), null)
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage)).isEqualTo("event")
        fixture.cache.updateObservedCarrier(scope, null)
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage)).isNull()
    }

    @Test
    fun `carrier snapshots invalidate on expiry revoke refresh and runtime clear`() = runTest {
        val fixture = Fixture(backgroundScope)
        val scope = ProtectedTimelineDecryptionCache.CarrierScope("account", "device", "installation", "room", "live#1")
        fixture.openCarrierScope(scope)
        val carrier = ProtectedTimelineDecryptionCache.ObservedCarrier(fixture.identity.projection, 12L, "sender", fixture.identity.encryptedMessage, 0L)
        val clearers = listOf<() -> Unit>(
            { fixture.cache.remove("room", "event") }, { fixture.runtime.clearContent(carrier.projection.contentId) },
            { fixture.runtime.clearSensitiveCaches() }, { fixture.runtime.requestAuthorizationRefresh(MatrixAuthorizationRefreshMode.HARD_INVALIDATION) },
        )
        clearers.forEach { clear ->
            fixture.runtime.revision = 0L
            fixture.cache.updateObservedCarrier(scope, carrier)
            clear()
            assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage)).isNull()
        }
        fixture.runtime.revision = 0L
        fixture.cache.updateObservedCarrier(scope, carrier)
        fixture.now = 2_000L
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage)).isNull()
    }

    @Test
    fun `closed and replaced latest snapshot cannot resurrect the previous identity`() = runTest {
        val fixture = Fixture(backgroundScope)
        val scope = ProtectedTimelineDecryptionCache.CarrierScope("account", "device", "installation", "room", "live#1")
        fixture.openCarrierScope(scope)
        val carrier = ProtectedTimelineDecryptionCache.ObservedCarrier(fixture.identity.projection, 12L, "sender", fixture.identity.encryptedMessage, 0L)
        fixture.cache.updateObservedCarrier(scope, carrier)
        fixture.cache.updateObservedCarrier(scope, carrier.copy(timestamp = 13L))
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage)).isNull()
        fixture.runtime.revision = 1L
        assertThat(fixture.cache.observedEventIdentity(scope, 13L, "sender", carrier.encryptedMessage)).isNull()
        fixture.cache.updateObservedCarrier(scope, carrier)
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage)).isNull()
    }

    @Test
    fun `carrier snapshots are bounded and scope cleanup is exact`() = runTest {
        val fixture = Fixture(backgroundScope)
        val scope = ProtectedTimelineDecryptionCache.CarrierScope("account", "device", "installation", "room", "live#0")
        val carrier = ProtectedTimelineDecryptionCache.ObservedCarrier(fixture.identity.projection, 0L, "sender", fixture.identity.encryptedMessage, 0L)
        repeat(65) { index ->
            val current = scope.copy(handle = "live#$index")
            fixture.openCarrierScope(current)
            fixture.cache.updateObservedCarrier(current, carrier.copy(timestamp = index.toLong()))
        }
        assertThat(fixture.cache.observedEventIdentity(scope, 0L, "sender", carrier.encryptedMessage)).isNull()
        assertThat(fixture.cache.observedEventIdentity(scope, 64L, "sender", carrier.encryptedMessage)).isEqualTo("event")
        fixture.cache.removeObservedCarrierScope("account", "device", "room", "live#63")
        assertThat(fixture.cache.observedEventIdentity(scope, 63L, "sender", carrier.encryptedMessage)).isNull()
        assertThat(fixture.cache.observedEventIdentity(scope, 64L, "sender", carrier.encryptedMessage)).isEqualTo("event")
    }

    @Test
    fun `late carrier publication cannot reopen a closed subscription`() = runTest {
        val fixture = Fixture(backgroundScope)
        val scope = ProtectedTimelineDecryptionCache.CarrierScope("account", "device", "installation", "room", "live#1")
        val carrier = ProtectedTimelineDecryptionCache.ObservedCarrier(fixture.identity.projection, 12L, "sender", fixture.identity.encryptedMessage, 0L)
        fixture.openCarrierScope(scope)
        fixture.cache.removeObservedCarrierScope("account", "device", "room", scope.handle)
        fixture.cache.updateObservedCarrier(scope, carrier)
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage)).isNull()
        fixture.openCarrierScope(scope.copy(handle = "live#2"))
        fixture.cache.updateObservedCarrier(scope, carrier)
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage)).isNull()
    }

    @Test
    fun `replacement owner retires old metadata and ignores late old close and publish`() = runTest {
        val fixture = Fixture(backgroundScope)
        val scope = ProtectedTimelineDecryptionCache.CarrierScope("account", "device", "installation", "room", "live#1")
        val carrier = ProtectedTimelineDecryptionCache.ObservedCarrier(fixture.identity.projection, 12L, "sender", fixture.identity.encryptedMessage, 0L)
        fixture.openCarrierScope(scope, "same-raw-scope")
        fixture.cache.updateObservedCarrier(scope, carrier)
        val replacement = scope.copy(handle = "live#2")
        fixture.openCarrierScope(replacement, "same-raw-scope")
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage)).isNull()
        fixture.cache.updateObservedCarrier(replacement, carrier.copy(timestamp = 13L))
        fixture.cache.removeObservedCarrierScope("account", "device", "room", scope.handle)
        fixture.cache.updateObservedCarrier(scope, carrier)
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage)).isNull()
        assertThat(fixture.cache.observedEventIdentity(scope, 13L, "sender", carrier.encryptedMessage)).isEqualTo("event")
    }

    @Test
    fun `clearing a noneligible latest carrier does not close its valid owner`() = runTest {
        val fixture = Fixture(backgroundScope)
        val scope = ProtectedTimelineDecryptionCache.CarrierScope("account", "device", "installation", "room", "live#1")
        val carrier = ProtectedTimelineDecryptionCache.ObservedCarrier(fixture.identity.projection, 12L, "sender", fixture.identity.encryptedMessage, 0L)
        fixture.openCarrierScope(scope)
        fixture.cache.updateObservedCarrier(scope, carrier)
        fixture.cache.clearObservedCarrierValue("account", "device", "room", scope.handle)
        assertThat(fixture.cache.observedEventIdentity(scope, 12L, "sender", carrier.encryptedMessage)).isNull()
        fixture.cache.updateObservedCarrier(scope, carrier.copy(timestamp = 13L))
        assertThat(fixture.cache.observedEventIdentity(scope, 13L, "sender", carrier.encryptedMessage)).isEqualTo("event")
    }

    private class Fixture(scope: CoroutineScope) {
        var now = 1_000L
        var allowed = true
        var decrypts = 0
        val runtime = Runtime()
        val cache = ProtectedTimelineDecryptionCache(runtime, scope, SystemClock { now })
        val content = aMessageContent(body = "authenticated test projection").copy(isContentProtectionAuthorized = true)
        val release = CompletableDeferred<Unit>()
        val identity = ProtectedTimelineDecryptionCache.ReadIdentity(
            projection = ProtectedTimelineDecryptionCache.Key("event", ContentId("content"), "digest", 7, 3, "account", "room", 2_000L),
            operationId = "lc13-receive-operation", matrixDeviceId = "device", installationId = "installation",
            authorizationRevision = 0, previewRevision = 0, releaseClass = TimelineContentReleaseClass.FOREGROUND_VISIBLE,
            encryptedMessage = aMessageContent(body = "encrypted test carrier"),
        )

        fun openCarrierScope(scope: ProtectedTimelineDecryptionCache.CarrierScope, rawScopeId: String = scope.handle) {
            cache.openObservedCarrierScope(scope.sessionId, scope.deviceId, scope.roomId, rawScopeId, scope.handle)
        }

        fun read(
            scope: CoroutineScope,
            identity: ProtectedTimelineDecryptionCache.ReadIdentity = this.identity,
            canRead: () -> Boolean = { allowed },
            canConsume: () -> Boolean = { true },
            decrypt: suspend () -> EventContent = { release.await(); content },
        ) = scope.async {
            runCatching {
                withContext(LifecycleOperationContext(identity.operationId, FullAppLifecycleId.LC_13)) {
                    cache.readOnce(identity, canRead, canConsume) {
                        assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(identity.operationId)
                        decrypts++
                        decrypt()
                    }
                }
            }
        }
    }

    private class Runtime : MatrixContentProtectionRuntime {
        var revision = 0L
        private var clearer: (() -> Unit)? = null
        private var contentClearer: ((ContentId) -> Unit)? = null
        private var refreshListener: ((MatrixAuthorizationRefresh) -> Unit)? = null
        override fun attach() = Unit
        override fun detach() = Unit
        override fun isConnected() = true
        override fun authorizationRevision() = revision
        override fun registerSensitiveCacheClearer(clearer: () -> Unit) { this.clearer = clearer }
        override fun registerSensitiveContentClearer(clearer: (ContentId) -> Unit) { contentClearer = clearer }
        override fun clearSensitiveCaches() { clearer?.invoke() }
        override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
            refreshListener = listener
            return AutoCloseable { refreshListener = null }
        }
        override fun requestAuthorizationRefresh(mode: MatrixAuthorizationRefreshMode) {
            revision++
            refreshListener?.invoke(MatrixAuthorizationRefresh(revision, mode, revision))
        }
        fun clearContent(contentId: ContentId) { contentClearer?.invoke(contentId) }
    }
}
