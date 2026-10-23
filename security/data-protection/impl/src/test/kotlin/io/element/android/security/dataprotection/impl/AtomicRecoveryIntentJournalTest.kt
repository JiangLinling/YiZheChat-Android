package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.RecoveryIntentRecord
import io.element.android.security.dataprotection.api.RecoveryIntentScope
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class AtomicRecoveryIntentJournalTest {
    @Test fun `confidential selection survives recreation and cannot be replaced inside same intent`() {
        val journal = AtomicRecoveryIntentJournal(RuntimeEnvironment.getApplication())
        journal.clearAccount(scope.accountIdHash)
        val confidential = record().copy(recoveryMode = io.element.android.security.dataprotection.api.RecoveryHistoryMode.CONFIDENTIAL)
        assertThat(journal.compareAndSet(scope, null, confidential)).isTrue()
        val restored = checkNotNull(AtomicRecoveryIntentJournal(RuntimeEnvironment.getApplication()).read(scope))
        assertThat(restored).isEqualTo(confidential)
        assertThat(restored.request().recoveryMode).isEqualTo(confidential.recoveryMode)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            journal.compareAndSet(scope, 1, restored.copy(revision = 2,
                recoveryMode = io.element.android.security.dataprotection.api.RecoveryHistoryMode.STANDARD))
        }
        assertThat(journal.read(scope)).isEqualTo(confidential)
        journal.clearAccount(scope.accountIdHash)
    }
    private val scope = RecoveryIntentScope("a".repeat(64), "atomic-installation-original", "b".repeat(64))
    private fun record(scope: RecoveryIntentScope = this.scope) = RecoveryIntentRecord(
        scope,
        "atomic-original-operation-001",
        "atomic-original-idempotency-001",
        100_000,
        1
    )
    @Test fun `recreation preserves exact owner and atomic CAS rejects stale writes`() {
        val context = RuntimeEnvironment.getApplication()
        val journal = AtomicRecoveryIntentJournal(context)
        journal.clearAccount(scope.accountIdHash)
        val original = record()
        assertThat(journal.compareAndSet(scope, null, original)).isTrue()
        val recreated = AtomicRecoveryIntentJournal(context)
        assertThat(recreated.read(scope)).isEqualTo(original)
        assertThat(recreated.compareAndSet(scope, null, original)).isFalse()
        val waiting = original.copy(revision = 2, recoveryId = "atomic-original-waiting", recoveryExpiresAtEpochMillis = 90_000)
        assertThat(recreated.compareAndSet(scope, 1, waiting)).isTrue()
        assertThat(journal.read(scope)).isEqualTo(waiting)
        assertThat(journal.compareAndSet(scope, 1, null)).isFalse()
        assertThat(journal.compareAndSet(scope, 2, null)).isTrue()
    }
    @Test fun `account cleanup preserves every unrelated scope`() {
        val journal = AtomicRecoveryIntentJournal(RuntimeEnvironment.getApplication())
        val other = scope.copy(accountIdHash = "c".repeat(64))
        journal.clearAccount(scope.accountIdHash)
        journal.clearAccount(other.accountIdHash)
        assertThat(journal.compareAndSet(scope, null, record())).isTrue()
        assertThat(journal.compareAndSet(other, null, record(other))).isTrue()
        assertThat(journal.clearAccount(scope.accountIdHash)).isTrue()
        assertThat(journal.read(scope)).isNull()
        assertThat(journal.read(other)).isEqualTo(record(other))
        journal.clearAccount(other.accountIdHash)
    }
    private fun base(scope: RecoveryIntentScope = this.scope): File {
        val id = MessageDigest.getInstance("SHA-256").digest("${scope.accountIdHash}\n${scope.installationId}\n${scope.matrixDeviceIdHash}".encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(RuntimeEnvironment.getApplication().noBackupFilesDir, "lc08_owned_intents_v1/$id.json")
    }
    @Test fun `committed backup restores after interrupted write without changing operation`() {
        val journal = AtomicRecoveryIntentJournal(RuntimeEnvironment.getApplication())
        journal.clearAccount(scope.accountIdHash)
        assertThat(journal.compareAndSet(scope, null, record())).isTrue()
        val base = base()
        val backup = File(base.path + ".bak")
        assertThat(base.renameTo(backup)).isTrue()
        assertThat(journal.read(scope)).isEqualTo(record())
        assertThat(base.exists()).isTrue()
        assertThat(backup.exists()).isFalse()
        journal.clearAccount(scope.accountIdHash)
    }
    @Test fun `complete isolated new file is uncommitted but its proven owner can be cleaned`() {
        val journal = AtomicRecoveryIntentJournal(RuntimeEnvironment.getApplication())
        journal.clearAccount(scope.accountIdHash)
        assertThat(journal.compareAndSet(scope, null, record())).isTrue()
        val base = base()
        val new = File(base.path + ".new")
        assertThat(base.renameTo(new)).isTrue()
        assertThat(journal.read(scope)).isNull()
        assertThat(journal.clearAccount(scope.accountIdHash)).isTrue()
        assertThat(new.exists()).isFalse()
    }
    @Test fun `truncated uncommitted owner prevents false cleanup completion`() {
        val journal = AtomicRecoveryIntentJournal(RuntimeEnvironment.getApplication())
        journal.clearAccount(scope.accountIdHash)
        val new = File(base().path + ".new").apply {
            parentFile?.mkdirs()
            writeText("{\"schema\":1,")
        }
        try {
            assertThat(journal.read(scope)).isNull()
            assertThat(journal.clearAccount(scope.accountIdHash)).isFalse()
            assertThat(new.exists()).isTrue()
        } finally {
            new.delete()
        }
    }
    @Test fun `separate journal instances admit only one CAS winner`() {
        val context = RuntimeEnvironment.getApplication()
        val journal = AtomicRecoveryIntentJournal(context)
        journal.clearAccount(scope.accountIdHash)
        assertThat(journal.compareAndSet(scope, null, record())).isTrue()
        val other = AtomicRecoveryIntentJournal(context)
        val next = record().copy(revision = 2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(listOf(
                Callable { journal.compareAndSet(scope, 1, next) },
                Callable { other.compareAndSet(scope, 1, next) }
            )).map { it.get() }
            assertThat(results.count { it }).isEqualTo(1)
            assertThat(journal.read(scope)).isEqualTo(next)
        } finally {
            executor.shutdownNow()
            journal.clearAccount(scope.accountIdHash)
        }
    }
}
