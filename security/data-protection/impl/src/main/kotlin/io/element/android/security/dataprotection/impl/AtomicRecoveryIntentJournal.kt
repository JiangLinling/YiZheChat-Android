package io.element.android.security.dataprotection.impl

import android.content.Context
import android.util.AtomicFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.dataprotection.api.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Non-secret scoped intent metadata, in noBackupFilesDir, using the existing owner-journal file pattern. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AtomicRecoveryIntentJournal(@ApplicationContext context: Context) : RecoveryIntentJournal {
    private val directory = File(context.noBackupFilesDir, "lc08_owned_intents_v1")
    private fun file(scope: RecoveryIntentScope): AtomicFile {
        val id = MessageDigest.getInstance("SHA-256").digest(
            "${scope.accountIdHash}\n${scope.installationId}\n${scope.matrixDeviceIdHash}".encodeToByteArray(),
        ).joinToString("") { "%02x".format(it.toInt() and 255) }
        return AtomicFile(File(directory, "$id.json"))
    }
    private fun AtomicFile.hasCommittedData() = baseFile.exists() || File(baseFile.path + ".bak").exists()
    private fun AtomicFile.hasAnyData() = hasCommittedData() || File(baseFile.path + ".new").exists()
    private fun AtomicFile.deleteKnownOwner(): Boolean {
        delete()
        val pending = File(baseFile.path + ".new")
        if (pending.exists() && !pending.delete()) return false
        return !hasAnyData()
    }
    override fun clearAccount(accountIdHash: String): Boolean = synchronized(mutationLock) {
        require(accountIdHash.matches(Regex("[a-f0-9]{64}")))
        // AtomicFile.openRead recovers an interrupted write before determining the owner.
        val bases = directory.listFiles()?.map { it.name.removeSuffix(".bak").removeSuffix(".new") }
            ?.filter { it.matches(Regex("[a-f0-9]{64}\\.json")) }?.toSet().orEmpty()
        for (name in bases) {
            val atomic = AtomicFile(File(directory, name))
            val data = runCatching {
                val bytes = if (atomic.hasCommittedData()) atomic.readFully() else File(atomic.baseFile.path + ".new").readBytes()
                JSONObject(bytes.decodeToString()).also { data ->
                    val owner = RecoveryIntentScope(data.getString("account"), data.getString("installation"), data.getString("device"))
                    check(data.getInt("schema") == 1 && file(owner).baseFile.name == name)
                }
            }.getOrElse { return@synchronized false }
            if (data.getString("account") == accountIdHash) {
                if (!atomic.deleteKnownOwner()) return@synchronized false
            }
        }
        true
    }

    override fun read(scope: RecoveryIntentScope): RecoveryIntentRecord? = synchronized(mutationLock) {
        val atomic = file(scope)
        if (!atomic.hasCommittedData()) return@synchronized null
        val data = JSONObject(atomic.readFully().decodeToString())
        check(data.getInt("schema") == 1)
        val storedScope = RecoveryIntentScope(data.getString("account"), data.getString("installation"), data.getString("device"))
        check(storedScope == scope) { "RECOVERY_JOURNAL_SCOPE_MISMATCH" }
        RecoveryIntentRecord(storedScope, data.getString("operation"), data.getString("idempotency"),
            data.getLong("deadline"), data.getLong("revision"),
            if (data.has("identityGeneration")) data.getString("identityGeneration") else null,
            if (data.has("mobileGeneration")) data.getLong("mobileGeneration") else null,
            if (data.has("recovery")) data.getString("recovery") else null,
            if (data.has("recoveryExpiry")) data.getLong("recoveryExpiry") else null)
    }
    override fun compareAndSet(scope: RecoveryIntentScope, expectedRevision: Long?, next: RecoveryIntentRecord?): Boolean = synchronized(mutationLock) {
        val current = read(scope)
        if (current?.revision != expectedRevision) return@synchronized false
        val atomic = file(scope)
        if (next == null) {
            // An unknown interrupted first write has no committed owner to authorize this CAS deletion.
            if (current == null && File(atomic.baseFile.path + ".new").exists()) return@synchronized false
            check(atomic.deleteKnownOwner())
            return@synchronized true
        }
        check(next.scope == scope && next.revision == (expectedRevision ?: 0L) + 1L)
        check(directory.exists() || directory.mkdirs())
        val data = JSONObject().put("schema", 1).put("account", scope.accountIdHash)
            .put("installation", scope.installationId).put("device", scope.matrixDeviceIdHash)
            .put("operation", next.operationId).put("idempotency", next.idempotencyKey)
            .put("deadline", next.deadlineEpochMillis).put("revision", next.revision)
        next.identityGeneration?.let { data.put("identityGeneration", it) }
        next.mobileGeneration?.let { data.put("mobileGeneration", it) }
        next.recoveryId?.let { data.put("recovery", it) }
        next.recoveryExpiresAtEpochMillis?.let { data.put("recoveryExpiry", it) }
        val stream = atomic.startWrite()
        try { stream.write(data.toString().encodeToByteArray()); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        true
    }
    private companion object { val mutationLock = Any() }
}
