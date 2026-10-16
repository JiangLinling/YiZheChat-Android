/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.MutableState
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseState
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.mediaviewer.impl.local.office.FilePreviewLeaseRegistry
import io.element.android.libraries.mediaviewer.impl.local.office.filePreviewLeaseBinding
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider
import io.element.android.security.securestorage.api.SecureMediaAuthenticatedReadRejection
import io.element.android.security.securestorage.api.SecureMediaExternalRecipient
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalOpenChooserAuthorizationTest {
    @Test
    fun `expiry while choosing rejects before any external operation or launch`() = checkSelection(
        rejection = SecureMediaAuthenticatedReadRejection.EXPIRED,
    )

    @Test
    fun `revocation while choosing rejects before any external operation or launch`() = checkSelection(
        rejection = SecureMediaAuthenticatedReadRejection.REVOKED,
    )

    @Test
    fun `closed source while choosing rejects before any external operation or launch`() = checkSelection(
        rejection = SecureMediaAuthenticatedReadRejection.CLOSED,
    )

    @Test
    fun `valid selection hands off the original source and recipient`() = checkSelection()

    @Test
    fun `unrelated preparation failure retains its original error`() = checkSelection(
        preparationFailure = IllegalStateException("Selected application is no longer available."),
    )

    @Test
    fun `cancel selection does not prepare or launch`() = checkSelection(cancel = true)

    private fun checkSelection(
        rejection: SecureMediaAuthenticatedReadRejection? = null,
        preparationFailure: Exception? = null,
        cancel: Boolean = false,
    ) = runTest {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://${context.packageName}.securemedia/v1/${"a".repeat(48)}")
        val file = mockk<MediaFile>(relaxed = true) {
            every { uri() } returns uri
            every { uriString() } returns uri.toString()
        }
        val source = aLocalMedia(uri)
        val token = FilePreviewLeaseRegistry.register(source.filePreviewLeaseBinding(), file)
        val lease = checkNotNull(FilePreviewLeaseRegistry.acquire(token, source.filePreviewLeaseBinding(), uri.authority!!))
        val local = source.copy(filePreviewLeaseToken = token)
        val recipient = SecureMediaExternalRecipient(12345, "example.reader", "example.reader.Viewer")
        var currentRejection: SecureMediaAuthenticatedReadRejection? = null
        val manager = mockk<SecureMediaLeaseManager> {
            every { readAuthorizationRejection(uri) } answers { currentRejection }
        }
        val launch = ExternalReadLaunch("test-external-operation", Intent(Intent.ACTION_VIEW))
        val handoff = mockk<ExternalReadHandoff>(relaxed = true) {
            every { recipients(local) } returns listOf(ExternalReadRecipientOption(recipient, "Reader"))
            coEvery { prepare(local, recipient) } answers { preparationFailure?.let { throw it }; launch }
        }
        val launcher = mockk<ManagedActivityResultLauncher<Intent, ActivityResult>>(relaxed = true)
        val actions = AndroidLocalMediaActions(
            context, testCoroutineDispatchers(), aBuildMeta(),
            FakeDataProtectionPolicyProvider(allowContentExport = true), manager, handoff,
        )
        // Drive the same pending selection as Configure's callbacks without creating an external activity.
        actions.javaClass.getDeclaredField("externalLauncher").apply { isAccessible = true }.set(actions, launcher)
        try {
            val opening = async { actions.open(local) }
            runCurrent()
            assertThat(opening.isCompleted).isFalse()
            coVerify(exactly = 0) { handoff.prepare(any(), any()) }
            @Suppress("UNCHECKED_CAST")
            val selection = actions.javaClass.getDeclaredField("selection").apply { isAccessible = true }
                .get(actions) as MutableState<Any?>
            val request = checkNotNull(selection.value)
            @Suppress("UNCHECKED_CAST")
            val answer = request.javaClass.getDeclaredField("answer").apply { isAccessible = true }
                .get(request) as CompletableDeferred<SecureMediaExternalRecipient?>
            currentRejection = rejection
            answer.complete(if (cancel) null else recipient)
            val result = try { opening.await() } catch (cancelled: CancellationException) { Result.failure(cancelled) }
            val failure = result.exceptionOrNull()
            when {
                cancel -> assertThat(failure).isInstanceOf(CancellationException::class.java)
                rejection != null -> {
                    assertThat((failure as ProtectedMediaReadUnavailableException).reason).isEqualTo(rejection)
                    assertThat(lease.state.value).isInstanceOf(FilePreviewLeaseState.Released::class.java)
                    verify(exactly = 1) { file.close() }
                }
                preparationFailure != null -> assertThat(failure).isSameInstanceAs(preparationFailure)
                else -> assertThat(result.isSuccess).isTrue()
            }
            val prepared = !cancel && rejection == null
            val launched = prepared && preparationFailure == null
            coVerify(exactly = if (prepared) 1 else 0) { handoff.prepare(local, recipient) }
            verify(exactly = if (launched) 1 else 0) { launcher.launch(any()) }
            if (launched) verify(exactly = 1) { launcher.launch(launch.intent) }
            verify(exactly = 0) { launcher.launch(any(), any()) }
            verify(exactly = if (launched) 1 else 0) { handoff.dispatched(launch.operationId) }
            verify(exactly = 0) { manager.issue(any()) }
            assertThat(selection.value).isNull()
        } finally {
            lease.release(FilePreviewLeaseReleaseReason.USER_CLOSE)
        }
    }
}
