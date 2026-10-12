/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.sessionstorage.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.androidutils.crypto.ClientSecret
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.security.securestorage.test.FakeDeviceRootKeyService
import io.element.encrypteddb.passphrase.RandomDatabaseSecretProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceRootDatabaseSecretProviderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `database secret round trips only through device root envelope`() {
        val root = FakeDeviceRootKeyService()
        val envelope = temporaryFolder.newFile("session.yzdbk").apply { delete() }
        val legacy = temporaryFolder.newFile("session.key").apply { delete() }
        val provider = provider(root, envelope, legacy)

        val first = provider.getSecret() as ClientSecret.RawKey
        val second = provider.getSecret() as ClientSecret.RawKey

        assertThat(first.bytes).hasLength(32)
        assertThat(second.bytes).isEqualTo(first.bytes)
        assertThat(envelope.isFile).isTrue()
        assertThat(envelope.readBytes().containsSubsequence(first.bytes)).isFalse()
    }

    @Test
    fun `destroying device root makes persisted database secret unavailable`() = runTest {
        val root = FakeDeviceRootKeyService()
        val envelope = temporaryFolder.newFile("session.yzdbk").apply { delete() }
        val legacy = temporaryFolder.newFile("session.key").apply { delete() }
        val provider = provider(root, envelope, legacy)
        provider.getSecret()

        root.destroy()

        assertThat(runCatchingExceptions { provider.getSecret() }.isFailure).isTrue()
    }

    @Test
    fun `legacy key retires only after device root envelope is committed`() {
        val root = FakeDeviceRootKeyService()
        val envelope = temporaryFolder.newFile("session.yzdbk").apply { delete() }
        val legacy = temporaryFolder.newFile("session.key")
        val legacyBytes = ByteArray(32) { it.toByte() }
        val legacyProvider = mockk<RandomDatabaseSecretProvider>()
        every { legacyProvider.getSecret() } returns ClientSecret.RawKey(legacyBytes)
        every { legacyProvider.reset() } answers { legacy.delete() }
        val provider = DeviceRootDatabaseSecretProvider(root, envelope, legacyProvider, legacy, "session_database")

        val migrated = provider.getSecret() as ClientSecret.RawKey

        assertThat(migrated.bytes).isEqualTo(legacyBytes)
        assertThat(envelope.isFile).isTrue()
        assertThat(legacy.exists()).isFalse()
    }

    private fun provider(
        root: FakeDeviceRootKeyService,
        envelope: java.io.File,
        legacy: java.io.File,
    ) = DeviceRootDatabaseSecretProvider(
        deviceRootKeyService = root,
        envelopeFile = envelope,
        legacyProvider = mockk(relaxed = true),
        legacySecretFile = legacy,
        databaseName = "session_database",
    )
}

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
    if (candidate.isEmpty() || candidate.size > size) return false
    return (0..size - candidate.size).any { offset ->
        candidate.indices.all { index -> this[offset + index] == candidate[index] }
    }
}
