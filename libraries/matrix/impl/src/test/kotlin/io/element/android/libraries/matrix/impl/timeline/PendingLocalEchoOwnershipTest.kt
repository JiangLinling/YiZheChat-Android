/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import org.junit.Test

class PendingLocalEchoOwnershipTest {
    private var now = 1_000L
    private val cache = PendingProtectedPayloadCache({ now }, maximumEntries = 2, ttlMillis = 100L)
    private val key = PendingProtectedPayloadCache.Key(ContentId("content"), "account", "room", "ciphertext")
    private val identity = PendingProtectedPayloadCache.Identity("device", "installation", "full-envelope", 7L, 9L)
    private val operation = LifecycleOperationContext("original-send-operation", FullAppLifecycleId.LC_13)
    private val owner = PendingProtectedPayloadCache.Owner(operation, identity, 2_000L)

    @Test
    fun `owned echo retains the originating send operation and independent zeroable bytes`() {
        val input = "echo".encodeToByteArray()
        cache.put(key, input, owner)
        input.fill(0)
        val first = checkNotNull(cache.ownership(key, identity))
        val second = checkNotNull(cache.ownership(key, identity))
        assertThat(first.owner.operation).isSameInstanceAs(operation)
        val bytes = checkNotNull(cache.get(first))
        bytes.fill(0)
        assertThat(cache.get(second)?.decodeToString()).isEqualTo("echo")
    }

    @Test
    fun `other account room or ciphertext cannot use sender ownership`() {
        cache.put(key, "echo".encodeToByteArray(), owner)
        listOf(key.copy(accountIdHash = "other"), key.copy(roomId = "other"), key.copy(ciphertextHash = "other"),
            key.copy(contentId = ContentId("other"))).forEach {
            assertThat(cache.ownership(it, identity)).isNull()
        }
    }

    @Test
    fun `device installation full header and authorization revisions are exact matches`() {
        cache.put(key, "echo".encodeToByteArray(), owner)
        listOf(identity.copy(matrixDeviceIdHash = "other"), identity.copy(installationId = "other"),
            identity.copy(envelopeHash = "tampered-header"), identity.copy(authorizationRevision = 8L),
            identity.copy(previewRevision = 10L)).forEach {
            assertThat(cache.ownership(key, it)).isNull()
        }
    }

    @Test
    fun `unowned local bytes must fall back to a genuine authorized receive`() {
        cache.put(key, "echo".encodeToByteArray())
        assertThat(cache.ownership(key, identity)).isNull()
        assertThat(cache.get(key)?.decodeToString()).isEqualTo("echo")
    }

    @Test
    fun `other lifecycles cannot masquerade as ordinary send ownership`() {
        cache.put(key, "echo".encodeToByteArray(), owner.copy(operation = LifecycleOperationContext("other-lifecycle-operation", FullAppLifecycleId.LC_21)))
        assertThat(cache.ownership(key, identity)).isNull()
    }

    @Test
    fun `interaction echoes retain their original operation and reject stale identity or expiry`() {
        val interaction = LifecycleOperationContext("original-interaction-send", FullAppLifecycleId.LC_15)
        for (kind in listOf(ContentKind.MEDIA_CAPTION, ContentKind.REACTION, ContentKind.POLL, ContentKind.POLL_OPERATION, ContentKind.RELAY, ContentKind.RELAY_OPERATION)) {
            val interactionOwner = owner.copy(operation = interaction, contentKind = kind, expiresAtEpochMillis = 1_050L)
            cache.put(key, "echo".encodeToByteArray(), interactionOwner)
            val ticket = checkNotNull(cache.ownership(key, identity))
            assertThat(ticket.owner.operation).isSameInstanceAs(interaction)
            assertThat(cache.ownership(key, identity.copy(authorizationRevision = 8L))).isNull()
            assertThat(cache.ownership(key, identity.copy(envelopeHash = "altered-kind"))).isNull()
            cache.clear()
            assertThat(cache.get(ticket)).isNull()
            cache.put(key, "echo".encodeToByteArray(), interactionOwner)
            val expiring = checkNotNull(cache.ownership(key, identity))
            now = 1_050L
            assertThat(cache.ownership(key, identity)).isNull()
            assertThat(cache.get(expiring)).isNull()
            now = 1_000L
            cache.clear()
        }
    }

    @Test
    fun `interaction ownership cannot cover ordinary text or media bodies`() {
        val interaction = LifecycleOperationContext("interaction", FullAppLifecycleId.LC_15)
        for (kind in listOf(ContentKind.TEXT, ContentKind.IMAGE, ContentKind.VOICE_MESSAGE)) {
            cache.put(key, "echo".encodeToByteArray(), owner.copy(operation = interaction, contentKind = kind))
            assertThat(cache.ownership(key, identity)).isNull()
        }
        cache.put(key, "echo".encodeToByteArray(), owner.copy(contentKind = ContentKind.RELAY))
        assertThat(cache.ownership(key, identity)).isNull()
    }

    @Test
    fun `signed content expiry prevents local echo before memory TTL expires`() {
        cache.put(key, "echo".encodeToByteArray(), owner.copy(expiresAtEpochMillis = 1_050L))
        val ticket = checkNotNull(cache.ownership(key, identity))
        now = 1_050L
        assertThat(cache.ownership(key, identity)).isNull()
        assertThat(cache.isCurrent(ticket)).isFalse()
        assertThat(cache.get(ticket)).isNull()
    }

    @Test
    fun `memory TTL invalidates an already issued ownership ticket`() {
        cache.put(key, "echo".encodeToByteArray(), owner)
        val ticket = checkNotNull(cache.ownership(key, identity))
        now = 1_100L
        assertThat(cache.get(ticket)).isNull()
        assertThat(cache.ownership(key, identity)).isNull()
    }

    @Test
    fun `target destroy invalidates tickets without affecting another message`() {
        cache.put(key, "echo".encodeToByteArray(), owner)
        val other = key.copy(contentId = ContentId("other"))
        cache.put(other, "other".encodeToByteArray(), owner)
        val ticket = checkNotNull(cache.ownership(key, identity))
        cache.remove(key.contentId)
        assertThat(cache.get(ticket)).isNull()
        assertThat(cache.ownership(other, identity)).isNotNull()
    }

    @Test
    fun `lock logout and replacement cannot resurrect an old ticket`() {
        cache.put(key, "echo".encodeToByteArray(), owner)
        val ticket = checkNotNull(cache.ownership(key, identity))
        cache.clear()
        cache.put(key, "echo".encodeToByteArray(), owner)
        assertThat(cache.isCurrent(ticket)).isFalse()
        assertThat(cache.get(ticket)).isNull()
        assertThat(cache.ownership(key, identity)).isNotNull()
    }

    @Test
    fun `bounded eviction invalidates its outstanding sender ticket`() {
        cache.put(key, "echo".encodeToByteArray(), owner)
        val ticket = checkNotNull(cache.ownership(key, identity))
        cache.put(key.copy(contentId = ContentId("second")), "two".encodeToByteArray(), owner)
        cache.put(key.copy(contentId = ContentId("third")), "three".encodeToByteArray(), owner)
        assertThat(cache.get(ticket)).isNull()
    }
}
