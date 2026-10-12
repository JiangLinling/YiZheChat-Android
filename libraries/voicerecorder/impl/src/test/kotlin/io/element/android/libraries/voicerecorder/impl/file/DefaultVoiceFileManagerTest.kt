/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.voicerecorder.impl.file

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.hash.md5
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class DefaultVoiceFileManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `plaintext cache wipe cannot erase intent or bypass a still-live capture owner`() {
        val cache = temporaryFolder.newFolder("cache")
        val sessions = File(temporaryFolder.newFolder("files"), "sessions")
        val config = VoiceFileConfig("voice", "ogg", "audio/ogg")
        val room = FakeBaseRoom(roomId = RoomId("!cache-wipe:example.org"))
        val manager = DefaultVoiceFileManager(cache, config, room, sessions)
        val id = "lc17-capture-aaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-44444444-4444-4444-a444-444444444444"
        val scratch = manager.createFile(id)
        check(cache.deleteRecursively())
        val whileOwned = DefaultVoiceFileManager(cache, config, room, sessions)
        assertThat(whileOwned.pendingCaptureCleanups()).isEmpty()
        // The native capture owner stops even when another layer has already erased its scratch.
        manager.deleteFile(scratch)
        val recovered = DefaultVoiceFileManager(cache, config, room, sessions)
        assertThat(recovered.pendingCaptureCleanups()).containsExactly(id)
        assertThat(recovered.captureCleanupResult(id)).isEqualTo("VOICE_CAPTURE_CANCELLED_AND_CLEANED")
        recovered.acknowledgeCaptureCleanup(id)
    }

    @Test
    fun `physically completed handoff or failure keeps its outcome across process recovery`() {
        val config = VoiceFileConfig("voice", "ogg", "audio/ogg")
        val room = FakeBaseRoom(roomId = RoomId("!capture:example.org"))
        for (outcome in listOf("VOICE_CAPTURE_RELEASED_AND_CLEANED", "VOICE_CAPTURE_FAILED_AND_CLEANED")) {
            val manager = DefaultVoiceFileManager(temporaryFolder.root, config, room, File(temporaryFolder.root, "sessions"))
            val id = "lc17-capture-aaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-${java.util.UUID.randomUUID()}"
            val scratch = manager.createFile(id)
            manager.markCaptureCleanup(scratch, outcome)
            manager.deleteFile(scratch)
            val recovered = DefaultVoiceFileManager(temporaryFolder.root, config, room, File(temporaryFolder.root, "sessions"))
            assertThat(recovered.captureCleanupResult(id)).isEqualTo(outcome)
            recovered.acknowledgeCaptureCleanup(id)
        }
    }

    @Test
    fun `capture identity survives cleanup until durable receipt acknowledgement`() {
        val config = VoiceFileConfig("voice", "ogg", "audio/ogg")
        val room = FakeBaseRoom(roomId = RoomId("!capture:example.org"))
        val manager = DefaultVoiceFileManager(temporaryFolder.root, config, room, File(temporaryFolder.root, "sessions"))
        val operationId = "lc17-capture-aaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-11111111-1111-4111-a111-111111111111"
        val scratch = manager.createFile(operationId)
        assertThat(manager.createFile(operationId)).isEqualTo(scratch)
        assertThat(manager.pendingCaptureCleanups()).isEmpty()
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { manager.acknowledgeCaptureCleanup(operationId) }
        DefaultVoiceFileManager(temporaryFolder.root, config, room, File(temporaryFolder.root, "sessions"))
        assertThat(scratch.exists()).isTrue()
        manager.deleteFile(scratch)
        assertThat(manager.pendingCaptureCleanups()).containsExactly(operationId)
        val recovered = DefaultVoiceFileManager(temporaryFolder.root, config, room, File(temporaryFolder.root, "sessions"))
        assertThat(recovered.pendingCaptureCleanups()).containsExactly(operationId)
        assertThat(recovered.captureCleanupResult(operationId)).isEqualTo("VOICE_CAPTURE_CANCELLED_AND_CLEANED")
        recovered.acknowledgeCaptureCleanup(operationId)
        assertThat(recovered.pendingCaptureCleanups()).isEmpty()
    }

    @Test
    fun `process-reclaimed mapped scratch yields its original cleanup identity`() {
        val config = VoiceFileConfig("voice", "ogg", "audio/ogg")
        val room = FakeBaseRoom(roomId = RoomId("!capture:example.org"))
        val manager = DefaultVoiceFileManager(temporaryFolder.root, config, room, File(temporaryFolder.root, "sessions"))
        val seed = manager.createFile()
        val directory = seed.parentFile
        manager.deleteFile(seed)
        val operationId = "lc17-capture-aaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-22222222-2222-4222-a222-222222222222"
        val intents = File(temporaryFolder.root, "voice_capture_intents/${room.roomId.value.md5()}").apply { mkdirs() }
        File(intents, operationId + ".capture-intent").writeText("VOICE_CAPTURE_CANCELLED_AND_CLEANED")
        val scratch = File(directory, "$operationId.ogg").apply { writeText("owned scratch fixture") }
        val owner = File(intents, "$operationId.ogg.lock").apply { createNewFile() }
        val recovered = DefaultVoiceFileManager(temporaryFolder.root, config, room, File(temporaryFolder.root, "sessions"))
        assertThat(scratch.exists()).isFalse()
        assertThat(owner.exists()).isFalse()
        assertThat(recovered.pendingCaptureCleanups()).containsExactly(operationId)
    }

    @Test
    fun `a second room instance does not recover an active recording`() {
        val room = FakeBaseRoom(roomId = RoomId("!voice:example.org"))
        val config = VoiceFileConfig(cacheSubdir = "voice", fileExt = "ogg", mimeType = "audio/ogg")
        val first = DefaultVoiceFileManager(temporaryFolder.root, config, room, File(temporaryFolder.root, "sessions"))
        val activeScratch = first.createFile().apply { writeBytes("active recording".encodeToByteArray()) }

        DefaultVoiceFileManager(temporaryFolder.root, config, room, File(temporaryFolder.root, "sessions"))

        assertThat(activeScratch.exists()).isTrue()
        assertThat(activeScratch.readText()).isEqualTo("active recording")
        assertThat(File(activeScratch.parentFile, activeScratch.name + ".lock").exists()).isTrue()
        first.deleteFile(activeScratch)
        assertThat(activeScratch.exists()).isFalse()
        assertThat(File(activeScratch.parentFile, activeScratch.name + ".lock").exists()).isFalse()
    }

    @Test
    fun `recovery respects another process owner lock and reclaims after process death`() {
        val room = FakeBaseRoom(roomId = RoomId("!voice:example.org"))
        val config = VoiceFileConfig(cacheSubdir = "voice", fileExt = "ogg", mimeType = "audio/ogg")
        val seedManager = DefaultVoiceFileManager(temporaryFolder.root, config, room, File(temporaryFolder.root, "sessions"))
        val seedFile = seedManager.createFile()
        val roomDirectory = seedFile.parentFile
        seedManager.deleteFile(seedFile)
        val externallyOwnedScratch = File(roomDirectory, "lc17-capture-aaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-33333333-3333-4333-a333-333333333333.ogg").apply {
            writeBytes("still recording".encodeToByteArray())
        }
        val mappedId = externallyOwnedScratch.name.removeSuffix(".ogg")
        val intents = File(temporaryFolder.root, "voice_capture_intents/${room.roomId.value.md5()}").apply { mkdirs() }
        File(intents, mappedId + ".capture-intent").writeText("VOICE_CAPTURE_CANCELLED_AND_CLEANED")
        val ownerFile = File(intents, externallyOwnedScratch.name + ".lock")
        val readyFile = File(temporaryFolder.root, "owner-ready")
        val helperSource = File(temporaryFolder.root, "CrossProcessLockOwner.java").apply {
            writeText(
                """
                import java.io.RandomAccessFile;
                import java.nio.channels.FileLock;
                import java.nio.file.Files;
                import java.nio.file.Path;

                public final class CrossProcessLockOwner {
                    public static void main(String[] args) throws Exception {
                        try (RandomAccessFile handle = new RandomAccessFile(args[0], "rw");
                             FileLock ignored = handle.getChannel().lock()) {
                            Files.createFile(Path.of(args[1]));
                            while (true) {
                                Thread.sleep(1_000L);
                            }
                        }
                    }
                }
                """.trimIndent()
            )
        }
        val javaExecutable = File(
            System.getProperty("java.home"),
            "bin/${if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) "java.exe" else "java"}",
        )
        val ownerProcess = ProcessBuilder(javaExecutable.path, helperSource.path, ownerFile.path, readyFile.path)
            .redirectErrorStream(true)
            .start()

        try {
            var attempts = 0
            while (!readyFile.exists() && attempts < 500) {
                if (!ownerProcess.isAlive) {
                    error("Lock-owner process exited early: ${ownerProcess.inputStream.bufferedReader().readText()}")
                }
                Thread.sleep(10L)
                attempts++
            }
            assertThat(readyFile.exists()).isTrue()
            val whileOwned = DefaultVoiceFileManager(temporaryFolder.root, config, room, File(temporaryFolder.root, "sessions"))
            assertThat(whileOwned.pendingCaptureCleanups()).isEmpty()
            assertThat(externallyOwnedScratch.exists()).isTrue()
            assertThat(externallyOwnedScratch.readText()).isEqualTo("still recording")
        } finally {
            ownerProcess.destroyForcibly()
            assertThat(ownerProcess.waitFor(10, TimeUnit.SECONDS)).isTrue()
        }

        val afterDeath = DefaultVoiceFileManager(temporaryFolder.root, config, room, File(temporaryFolder.root, "sessions"))
        assertThat(afterDeath.pendingCaptureCleanups()).containsExactly(mappedId)
        assertThat(externallyOwnedScratch.exists()).isFalse()
        assertThat(ownerFile.exists()).isFalse()
    }
}
