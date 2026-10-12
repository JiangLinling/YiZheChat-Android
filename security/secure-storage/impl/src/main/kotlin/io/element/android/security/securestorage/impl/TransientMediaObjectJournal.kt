/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.impl

import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectIdentity
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SecureTransientMediaOwner
import io.element.android.security.securestorage.api.SecureTransientObjectDeletion
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Serialized by the owning SensitiveFileStore mutex. Intent and result are immutable publications. */
internal class TransientMediaObjectJournal(private val roots: SecureStorageRoots) {
    internal data class Entry(val identity: SecureObjectIdentity, val owner: SecureTransientMediaOwner, val process: String,
        val descriptorDigest: String, val digest: String)

    fun register(request: SecureObjectWriteRequest, process: String) {
        val identity = SecureObjectIdentity(request.objectId, request.vaultId, request.purpose)
        check(!acknowledgedDirectory(identity).exists()) { "An acknowledged transient media identity cannot be reused." }
        val owner = request.transientMediaOwner ?: run {
            check(entry(identity) == null) { "A transient media identity cannot become a persistent object." }
            return
        }
        val descriptorDigest = sha256(request.associatedData + ("\u0000" + request.expectedPlaintextLength).encodeToByteArray())
        val directory = directory(identity)
        val intent = File(directory, "intent")
        if (intent.exists()) {
            val existing = read(intent)
            check(existing.identity == identity && existing.owner == owner && existing.process == process && existing.descriptorDigest == descriptorDigest &&
                !File(directory, "released").exists() && !File(directory, "deleted").exists()) {
                "Transient media identity was reused after release or by another owner."
            }
            return
        }
        check(pendingDirectories().count { File(it, "intent").exists() } < 4096) { "Transient media cleanup backlog is full." }
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1)
                out.writeUTF(identity.objectId.value)
                out.writeUTF(identity.vaultId.value)
                out.writeUTF(identity.purpose.name)
                out.writeUTF(descriptorDigest)
                out.writeUTF(owner.operationId)
                out.writeUTF(owner.accountIdHash)
                out.writeUTF(owner.matrixDeviceIdHash)
                out.writeUTF(owner.installationId.orEmpty())
                out.writeUTF(owner.roomIdHash.orEmpty())
                out.writeUTF(owner.eventIdHash.orEmpty())
                out.writeUTF(process)
            }
        }.toByteArray()
        publish(intent, bytes)
    }

    fun entries(): List<Entry> {
        return pendingDirectories().mapNotNull { directory ->
            val intent = File(directory, "intent")
            if (!intent.exists()) return@mapNotNull null // An unpublished intent cannot precede object creation.
            try {
                read(intent)
            } catch (_: Exception) {
                // Retain unparseable original evidence and its capacity slot; do not block unrelated cleanup.
                timber.log.Timber.w("Transient media ownership record remains unreadable.")
                null
            }
        }
    }

    private fun pendingDirectories(): List<File> {
        val root = root()
        if (!root.exists()) return emptyList()
        return checkNotNull(root.listFiles()) { "Unable to enumerate transient media obligations." }
            .filter { it.isDirectory && it.name != "acknowledged" }
    }

    fun markReleased(identity: SecureObjectIdentity) {
        val intent = File(directory(identity), "intent")
        if (!intent.exists()) return
        val entry = read(intent)
        check(entry.identity == identity)
        publish(File(directory(identity), "released"), entry.digest.encodeToByteArray())
    }

    fun entry(identity: SecureObjectIdentity): Entry? = File(directory(identity), "intent").let {
        if (it.exists()) read(it) else null
    }

    fun eligible(entry: Entry, process: String): Boolean = entry.process != process || File(directory(entry.identity), "released").exists()

    fun deletion(entry: Entry, acknowledged: Boolean = false): SecureTransientObjectDeletion? {
        val time = confirmation(entry, "deleted", acknowledged) ?: return null
        return SecureTransientObjectDeletion(entry.owner, entry.identity, entry.digest, time, confirmation(entry, "lease-quiescent", acknowledged))
    }

    fun acknowledge(fact: SecureTransientObjectDeletion, receiptReference: String) {
        require(fact.leaseQuiescentAtEpochMillis != null)
        require(receiptReference.matches(Regex("android-media-object-cleanup:v1:[0-9a-f]{64}")))
        val retired = acknowledgedDirectory(fact.identity)
        val alreadyRetired = retired.exists()
        val source = if (alreadyRetired) retired else directory(fact.identity)
        val original = read(File(source, "intent"), alreadyRetired)
        check(deletion(original, alreadyRetired) == fact) { "Media acknowledgement does not match the retained original fact." }
        val confirmation = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use {
                it.writeUTF(fact.recordSha256)
                it.writeLong(fact.confirmedAbsentAtEpochMillis)
                it.writeLong(checkNotNull(fact.leaseQuiescentAtEpochMillis))
                it.writeUTF(receiptReference)
            }
        }.toByteArray()
        publish(File(source, "server-acknowledgement"), confirmation)
        if (alreadyRetired) return
        val parent = checkNotNull(retired.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Unable to retain media acknowledgement.")
        // Atomic directory rename keeps the original immutable evidence and the single-use identity.
        // A failed rename leaves the original pending journal available for an exact idempotent replay.
        if (!source.renameTo(retired)) throw IOException("Unable to retire acknowledged media obligation.")
    }

    fun markLeaseQuiescent(identity: SecureObjectIdentity, at: Long) {
        val entry = entry(identity) ?: return
        markConfirmation(entry, "lease-quiescent", at)
    }

    private fun confirmation(entry: Entry, name: String, acknowledged: Boolean = false): Long? {
        val result = File(if (acknowledged) acknowledgedDirectory(entry.identity) else directory(entry.identity), name)
        if (!result.exists()) return null
        DataInputStream(ByteArrayInputStream(readBounded(result))).use { input ->
            check(input.readUTF() == entry.digest) { "Transient deletion does not match the original object." }
            val time = input.readLong()
            check(time > 0 && input.read() == -1)
            return time
        }
    }

    fun markDeleted(entry: Entry, at: Long) {
        markConfirmation(entry, "deleted", at)
    }

    private fun markConfirmation(entry: Entry, name: String, at: Long) {
        if (confirmation(entry, name) != null) return
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { it.writeUTF(entry.digest); it.writeLong(at) }
        }.toByteArray()
        publish(File(directory(entry.identity), name), bytes)
    }

    private fun read(file: File, acknowledged: Boolean = false): Entry {
        val bytes = readBounded(file)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == 1)
            val identity = SecureObjectIdentity(SecureObjectId(input.readUTF()), AccountVaultId(input.readUTF()),
                SecureObjectPurpose.valueOf(input.readUTF()))
            val descriptorDigest = input.readUTF()
            require(descriptorDigest.matches(Regex("[0-9a-f]{64}")))
            val owner = SecureTransientMediaOwner(input.readUTF(), input.readUTF(), input.readUTF(),
                input.readUTF().ifEmpty { null }, input.readUTF().ifEmpty { null }, input.readUTF().ifEmpty { null })
            val process = input.readUTF()
            check(process.length in 1..128 && input.read() == -1)
            check(file.canonicalFile == File(if (acknowledged) acknowledgedDirectory(identity) else directory(identity), "intent").canonicalFile)
            Entry(identity, owner, process, descriptorDigest, sha256(bytes))
        }
    }

    private fun root() = File(roots.persistentRoot(), "transient-media-objects-v1").checked()
    private fun directory(identity: SecureObjectIdentity): File = File(root(), sha256(
        (identity.vaultId.value + "\u0000" + identity.purpose.name + "\u0000" + identity.objectId.value).encodeToByteArray()
    )).checked()
    private fun acknowledgedDirectory(identity: SecureObjectIdentity): File = File(File(root(), "acknowledged"), directory(identity).name).checked()
    private fun File.checked(): File = apply {
        require(canonicalFile.path.startsWith(roots.persistentRoot().canonicalFile.path + File.separator))
    }
    private fun readBounded(file: File): ByteArray {
        file.checked()
        require(file.length() in 1..65_536)
        return file.readBytes()
    }
    private fun publish(file: File, bytes: ByteArray) {
        file.checked()
        if (file.exists()) { check(readBounded(file).contentEquals(bytes)); return }
        val parent = file.parentFile!!
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Unable to create transient media journal.")
        val partial = File(parent, ".pending-${UUID.randomUUID()}")
        try {
            FileOutputStream(partial).use { it.write(bytes); it.flush(); it.fd.sync() }
            if (!partial.renameTo(file)) throw IOException("Unable to publish transient media obligation.")
        } finally { partial.delete() }
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** One identity per app process, shared by stores and all sessions. Never persisted as a secret capability. */
internal object SecureMediaProcessIdentity { val value: String = UUID.randomUUID().toString() }
