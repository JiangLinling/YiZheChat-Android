/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.security.dataprotection.api.DataProtectionActionDeniedException
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class AndroidLocalMediaActionsTest {
    @Test
    @Config(sdk = [35])
    fun `unavailable protected read reports actionable failure before creating export`() = runTest {
        val provider = RecordingMediaStoreProvider()
        registerMediaStoreProvider(provider)
        val sut = createAndroidLocalMediaActions(secureReadAuthorized = false)

        val result = sut.saveOnDisk(aLocalMedia(Uri.parse("content://test.securemedia/v1/expired-qa-read")))

        assertThat(result.exceptionOrNull()).isInstanceOf(ProtectedMediaReadUnavailableException::class.java)
        assertThat(provider.insertCount).isEqualTo(0)
        assertThat(provider.publishCount).isEqualTo(0)
    }

    @Test
    fun `unavailable protected read rejects external open before handoff`() = runTest {
        val sut = createAndroidLocalMediaActions(secureReadAuthorized = false)

        val result = sut.open(aLocalMedia(Uri.parse("content://test.securemedia/v1/expired-qa-read")))

        assertThat(result.exceptionOrNull()).isInstanceOf(ProtectedMediaReadUnavailableException::class.java)
    }

    @Test
    fun `present - AndroidLocalMediaAction configure`() = runTest {
        val sut = createAndroidLocalMediaActions()
        moleculeFlow(RecompositionMode.Immediate) {
            CompositionLocalProvider(
                LocalContext provides RuntimeEnvironment.getApplication(),
                LocalActivityResultRegistryOwner provides NoOpActivityResultRegistryOwner()
            ) {
                sut.Configure()
            }
        }.test {
            awaitItem()
        }
    }

    @Test
    fun `test AndroidLocalMediaAction share`() = runTest {
        val sut = createAndroidLocalMediaActions()
        val result = sut.share(aLocalMedia(Uri.parse("file://afile")))
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `test AndroidLocalMediaAction open`() = runTest {
        val sut = createAndroidLocalMediaActions()
        val result = sut.open(aLocalMedia(Uri.parse("file://afile")))
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `test AndroidLocalMediaAction save on disk`() = runTest {
        val sut = createAndroidLocalMediaActions()
        val result = sut.saveOnDisk(aLocalMedia(Uri.parse("file://afile")))
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `managed policy blocks media export before accessing the source`() = runTest {
        val sut = createAndroidLocalMediaActions(allowContentExport = false)

        val result = sut.saveOnDisk(aLocalMedia(Uri.parse("file://afile")))

        assertThat(result.exceptionOrNull()).isInstanceOf(DataProtectionActionDeniedException.ContentExport::class.java)
    }

    @Test
    fun `managed policy blocks external sharing before creating a share grant`() = runTest {
        val sut = createAndroidLocalMediaActions(allowExternalShare = false)

        val result = sut.share(aLocalMedia(Uri.parse("file://afile")))

        assertThat(result.exceptionOrNull()).isInstanceOf(DataProtectionActionDeniedException.ExternalShare::class.java)
    }

    @Test
    fun `media actions re-read a hot-refreshed signed policy for every user action`() = runTest {
        val policyProvider = FakeDataProtectionPolicyProvider(
            allowContentExport = false,
            allowExternalShare = false,
        )
        val sut = createAndroidLocalMediaActions(policyProvider = policyProvider)

        assertThat(sut.saveOnDisk(aLocalMedia(Uri.parse("file://afile"))).exceptionOrNull())
            .isInstanceOf(DataProtectionActionDeniedException.ContentExport::class.java)
        assertThat(sut.share(aLocalMedia(Uri.parse("file://afile"))).exceptionOrNull())
            .isInstanceOf(DataProtectionActionDeniedException.ExternalShare::class.java)

        policyProvider.allowContentExport = true
        policyProvider.allowExternalShare = true

        assertThat(sut.saveOnDisk(aLocalMedia(Uri.parse("file://afile"))).exceptionOrNull())
            .isNotInstanceOf(DataProtectionActionDeniedException.ContentExport::class.java)
        assertThat(sut.share(aLocalMedia(Uri.parse("file://afile"))).exceptionOrNull())
            .isNotInstanceOf(DataProtectionActionDeniedException.ExternalShare::class.java)
    }

    @Test
    @Config(sdk = [28])
    fun `export revoked after copy does not publish destination or retain staging`() = runTest {
        val source = File.createTempFile("export-source", ".txt").apply { writeText("protected") }
        val filename = "revoked-${System.nanoTime()}.txt"
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val destination = File(downloads, filename)
        val policyProvider = allowThenDenyPolicyProvider()
        val sut = createAndroidLocalMediaActions(policyProvider = policyProvider)
        val media = aLocalMedia(Uri.fromFile(source)).let { it.copy(info = it.info.copy(filename = filename)) }

        val result = sut.saveOnDisk(media)

        assertThat(result.exceptionOrNull()).isInstanceOf(DataProtectionActionDeniedException.ContentExport::class.java)
        assertThat(destination.exists()).isFalse()
        assertThat(downloads.listFiles().orEmpty().any { it.name.startsWith(".$filename.") && it.name.endsWith(".pending") }).isFalse()
        source.delete()
    }

    @Test
    fun `external open rechecks policy immediately before activity handoff`() = runTest {
        val sut = createAndroidLocalMediaActions(policyProvider = allowThenDenyPolicyProvider())

        val result = sut.open(aLocalMedia(Uri.parse("content://test/protected")))

        assertThat(result.exceptionOrNull()).isInstanceOf(DataProtectionActionDeniedException.ContentExport::class.java)
    }

    @Test
    fun `protected media without a live lease handle fails closed`() = runTest {
        val sut = createAndroidLocalMediaActions()

        val result = sut.saveOnDisk(aLocalMedia(Uri.parse("content://com.example.securemedia/v1/${"a".repeat(48)}")))

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `cancellation observed during a source read writes no further plaintext`() = runTest {
        val output = ByteArrayOutputStream()
        var published = false
        val copyJob = launch {
            val owner = coroutineContext.job
            val input = object : InputStream() {
                override fun read(): Int = error("Bulk read expected")

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    owner.cancel()
                    buffer[offset] = 1
                    return 1
                }
            }
            copyCancellable(input, output)
            published = true
        }

        copyJob.join()

        assertThat(copyJob.isCancelled).isTrue()
        assertThat(output.size()).isEqualTo(0)
        assertThat(published).isFalse()
    }

    @Test
    @Config(sdk = [28])
    fun `pre Android 10 protected export fails closed before public staging`() = runTest {
        val filename = "protected-${System.nanoTime()}.bin"
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val sut = createAndroidLocalMediaActions(secureReadAuthorized = true)
        val media = aLocalMedia(Uri.parse("content://com.example.securemedia/v1/${"a".repeat(48)}")).let {
            it.copy(info = it.info.copy(filename = filename))
        }

        val result = sut.saveOnDisk(media)

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(File(downloads, filename).exists()).isFalse()
        assertThat(downloads.listFiles().orEmpty().any { it.name.startsWith(".$filename.") }).isFalse()
    }

    @Test
    @Config(sdk = [35])
    fun `pending media row is deleted when policy is revoked before publish`() = runTest {
        val provider = RecordingMediaStoreProvider()
        registerMediaStoreProvider(provider)
        val source = File.createTempFile("export-source", ".txt").apply { writeText("protected") }
        val sut = createAndroidLocalMediaActions(policyProvider = allowThenDenyPolicyProvider())

        val result = sut.saveOnDisk(aLocalMedia(Uri.fromFile(source)))

        assertThat(result.exceptionOrNull()).isInstanceOf(DataProtectionActionDeniedException.ContentExport::class.java)
        assertThat(provider.insertCount).isEqualTo(1)
        assertThat(provider.publishCount).isEqualTo(0)
        assertThat(provider.deleteCount).isEqualTo(1)
        source.delete()
    }

    @Test
    @Config(sdk = [35])
    fun `cancellation after pending row creation deletes it without publishing`() = runTest {
        lateinit var exportJob: kotlinx.coroutines.Job
        val provider = RecordingMediaStoreProvider(onOpen = { exportJob.cancel() })
        registerMediaStoreProvider(provider)
        val source = File.createTempFile("export-source", ".txt").apply { writeText("protected") }
        val sut = createAndroidLocalMediaActions()

        exportJob = launch { sut.saveOnDisk(aLocalMedia(Uri.fromFile(source))) }
        exportJob.join()

        assertThat(exportJob.isCancelled).isTrue()
        assertThat(provider.insertCount).isEqualTo(1)
        assertThat(provider.publishCount).isEqualTo(0)
        assertThat(provider.deleteCount).isEqualTo(1)
        source.delete()
    }

    @Test
    @Config(sdk = [35])
    fun `successful pending media export publishes exactly once`() = runTest {
        val provider = RecordingMediaStoreProvider()
        registerMediaStoreProvider(provider)
        val source = File.createTempFile("export-source", ".txt").apply { writeText("complete") }
        val sut = createAndroidLocalMediaActions()

        val result = sut.saveOnDisk(aLocalMedia(Uri.fromFile(source)))

        assertThat(result.isSuccess).isTrue()
        assertThat(provider.insertCount).isEqualTo(1)
        assertThat(provider.publishCount).isEqualTo(1)
        assertThat(provider.deleteCount).isEqualTo(0)
        assertThat(provider.output.readText()).isEqualTo("complete")
        source.delete()
    }

    @Test
    @Config(sdk = [35])
    fun `cleanup failure is suppressed under original policy revocation`() = runTest {
        val provider = RecordingMediaStoreProvider(deleteResult = 0)
        registerMediaStoreProvider(provider)
        val source = File.createTempFile("export-source", ".txt").apply { writeText("protected") }
        val sut = createAndroidLocalMediaActions(policyProvider = allowThenDenyPolicyProvider())

        val result = sut.saveOnDisk(aLocalMedia(Uri.fromFile(source)))

        val failure = result.exceptionOrNull()
        assertThat(failure).isInstanceOf(DataProtectionActionDeniedException.ContentExport::class.java)
        assertThat(failure?.suppressed?.single()).isInstanceOf(IllegalStateException::class.java)
        assertThat(provider.publishCount).isEqualTo(0)
        source.delete()
    }

    private fun allowThenDenyPolicyProvider(): DataProtectionPolicyProvider {
        val allowed = FakeDataProtectionPolicyProvider(allowContentExport = true)
        val denied = FakeDataProtectionPolicyProvider(allowContentExport = false)
        var reads = 0
        return object : DataProtectionPolicyProvider {
            override fun current() = if (reads++ == 0) allowed.current() else denied.current()
        }
    }

    private fun registerMediaStoreProvider(provider: ContentProvider) {
        provider.attachInfo(
            RuntimeEnvironment.getApplication(),
            ProviderInfo().apply { authority = MediaStore.AUTHORITY },
        )
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
    }

    private fun TestScope.createAndroidLocalMediaActions(
        allowContentExport: Boolean = true,
        allowExternalShare: Boolean = true,
        policyProvider: DataProtectionPolicyProvider = FakeDataProtectionPolicyProvider(
            allowContentExport = allowContentExport,
            allowExternalShare = allowExternalShare,
        ),
        secureReadAuthorized: Boolean = false,
        secureMediaLeaseManager: SecureMediaLeaseManager = object : SecureMediaLeaseManager {
            override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease = error("Not used")
            override fun isReadAuthorized(uri: Uri): Boolean = secureReadAuthorized
        },
    ) = AndroidLocalMediaActions(
        RuntimeEnvironment.getApplication(),
        testCoroutineDispatchers(),
        aBuildMeta(),
        policyProvider,
        secureMediaLeaseManager,
    )
}

private class RecordingMediaStoreProvider(
    private val deleteResult: Int = 1,
    private val onOpen: () -> Unit = {},
) : ContentProvider() {
    val output: File = File.createTempFile("pending-export", ".bin")
    var insertCount = 0
    var publishCount = 0
    var deleteCount = 0

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        insertCount += 1
        check(values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1)
        return Uri.parse("content://${MediaStore.AUTHORITY}/downloads/1")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        onOpen()
        return ParcelFileDescriptor.open(
            output,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE,
        )
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        check(values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0)
        publishCount += 1
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deleteCount += 1
        if (deleteResult == 1) output.delete()
        return deleteResult
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null
}
