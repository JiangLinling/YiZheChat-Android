/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.media

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleMediaObjectCleanup
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectIdentity
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureTransientMediaOwner
import io.element.android.security.securestorage.api.SecureTransientObjectDeletion
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class TransientMediaCleanupReporterTest {
    private val store = mockk<SensitiveFileStore>()
    private val coordinator = mockk<AppOperationalStateCoordinator>()
    private val pending = mutableListOf<SecureTransientObjectDeletion>()
    private val transmitted = mutableListOf<LifecycleMediaObjectCleanup>()
    private val receipt = "android-media-object-cleanup:v1:" + "e".repeat(64)

    private fun wire() {
        coEvery { store.recoverTransientMediaObjects() } answers { pending.toList() }
        coEvery { coordinator.recordMediaObjectCleanup(any()) } coAnswers {
            transmitted.add(firstArg())
            receipt
        }
        coEvery { store.acknowledgeTransientMediaDeletion(any(), receipt) } coAnswers {
            pending.remove(firstArg<SecureTransientObjectDeletion>())
            Unit
        }
    }

    private fun fact(index: Int = 1) = SecureTransientObjectDeletion(
        owner = SecureTransientMediaOwner("original-operation-$index", "a".repeat(64), "b".repeat(64),
            "original-installation", "c".repeat(64), "d".repeat(64)),
        identity = SecureObjectIdentity(SecureObjectId("transient-$index"), AccountVaultId("fixture-vault"), SecureObjectPurpose.ATTACHMENT),
        recordSha256 = index.toString().padStart(64, '0'), confirmedAbsentAtEpochMillis = 1234, leaseQuiescentAtEpochMillis = 1235,
    )

    @Test fun `restart resends exact retained object fact after lost server response`() = runTest {
        wire()
        val original = fact().also(pending::add)
        coEvery { coordinator.recordMediaObjectCleanup(any()) } throws IOException("response lost")
        TransientMediaCleanupReporter(store, coordinator).recoverAndReport()
        assertThat(pending).containsExactly(original)
        coVerify(exactly = 0) { store.acknowledgeTransientMediaDeletion(any(), any()) }
        coEvery { coordinator.recordMediaObjectCleanup(any()) } coAnswers { transmitted.add(firstArg()); receipt }
        TransientMediaCleanupReporter(store, coordinator).recoverAndReport()
        assertThat(pending).isEmpty()
        val reported = transmitted.single()
        assertThat(reported.operationId).isEqualTo(original.owner.operationId)
        assertThat(reported.subjects.installationId).isEqualTo(original.owner.installationId)
        assertThat(reported.recordSha256).isEqualTo(original.recordSha256)
        assertThat(reported.confirmedAbsentAtEpochMillis).isEqualTo(original.confirmedAbsentAtEpochMillis)
        assertThat(reported.leaseQuiescentAtEpochMillis).isEqualTo(original.leaseQuiescentAtEpochMillis)
        coVerify(exactly = 0) { coordinator.start(any()) }
        coVerify(exactly = 0) { coordinator.recordClientReceipt(any()) }
    }

    @Test fun `incomplete quiescence or installation is retained without reporting false completion`() = runTest {
        wire()
        pending.add(fact(1).copy(leaseQuiescentAtEpochMillis = null))
        pending.add(fact(2).let { it.copy(owner = it.owner.copy(installationId = null)) })
        TransientMediaCleanupReporter(store, coordinator).recoverAndReport()
        assertThat(pending).hasSize(2)
        coVerify(exactly = 0) { coordinator.recordMediaObjectCleanup(any()) }
        coVerify(exactly = 0) { store.acknowledgeTransientMediaDeletion(any(), any()) }
    }

    @Test fun `failed oldest batch cannot starve later recoverable records`() = runTest {
        wire()
        pending.addAll((1..34).map(::fact))
        coEvery { coordinator.recordMediaObjectCleanup(any()) } coAnswers {
            val value = firstArg<LifecycleMediaObjectCleanup>()
            if (value.recordSha256 < fact(33).recordSha256) throw IOException("original unavailable")
            transmitted.add(value)
            receipt
        }
        val reporter = TransientMediaCleanupReporter(store, coordinator)
        reporter.recoverAndReport()
        assertThat(pending).hasSize(34)
        reporter.recoverAndReport()
        assertThat(pending).hasSize(32)
        assertThat(transmitted.map { it.recordSha256 }).containsExactly(fact(33).recordSha256, fact(34).recordSha256)
    }

    @Test fun `local acknowledgement failure retains fact and later retry preserves all timestamps`() = runTest {
        wire()
        val original = fact().also(pending::add)
        coEvery { store.acknowledgeTransientMediaDeletion(any(), receipt) } throws IOException("journal full")
        TransientMediaCleanupReporter(store, coordinator).recoverAndReport()
        assertThat(pending).containsExactly(original)
        wire()
        TransientMediaCleanupReporter(store, coordinator).recoverAndReport()
        assertThat(pending).isEmpty()
        assertThat(transmitted).hasSize(2)
        assertThat(transmitted[0]).isEqualTo(transmitted[1])
    }

    @Test fun `cancelled transport keeps obligation and releases shared reporter ownership`() = runTest {
        wire()
        pending.add(fact())
        coEvery { coordinator.recordMediaObjectCleanup(any()) } throws CancellationException("cancelled")
        assertThat(runCatching { TransientMediaCleanupReporter(store, coordinator).recoverAndReport() }.exceptionOrNull())
            .isInstanceOf(CancellationException::class.java)
        coVerify(exactly = 0) { store.acknowledgeTransientMediaDeletion(any(), any()) }
        wire()
        TransientMediaCleanupReporter(store, coordinator).recoverAndReport()
        assertThat(pending).isEmpty()
    }
}
