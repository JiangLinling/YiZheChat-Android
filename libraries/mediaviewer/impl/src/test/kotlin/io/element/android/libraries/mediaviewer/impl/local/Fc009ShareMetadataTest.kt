/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Intent transport only; OEM display and real cross-UID grants require device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class Fc009ShareMetadataTest {
    private val uri = Uri.parse("content://test.securemedia/v1/0123456789abcdef0123456789abcdef0123456789abcdef")
    private val filename = "QA_TC065_20261007.txt"
    private val media = aLocalMedia(uri).let { it.copy(info = it.info.copy(filename = filename, mimeType = "text/plain")) }

    @Test
    fun `chooser and recipient receive same opaque uri with named clip and read only grant`() = runTest {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val sut = actions(activity)
            moleculeFlow(RecompositionMode.Immediate) {
                CompositionLocalProvider(
                    LocalContext provides activity,
                    LocalActivityResultRegistryOwner provides NoOpActivityResultRegistryOwner(),
                ) { sut.Configure() }
            }.test {
                awaitItem()
                assertThat(sut.share(media).isSuccess).isTrue()
                val chooser = shadowOf(activity).nextStartedActivity
                assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
                @Suppress("DEPRECATION")
                val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
                assertThat(send.type).isEqualTo("text/plain")
                @Suppress("DEPRECATION")
                val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                assertThat(stream).isEqualTo(uri)
                for (intent in listOf(send, chooser)) {
                    val clip = requireNotNull(intent.clipData)
                    assertThat(intent.flags).isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    assertThat(clip.itemCount).isEqualTo(1)
                    assertThat(clip.getItemAt(0).uri).isEqualTo(uri)
                    assertThat(clip.description.label.toString()).isEqualTo(filename)
                    assertThat(clip.description.getMimeType(0)).isEqualTo("text/plain")
                    assertThat(intent.data).isNull()
                }
                assertThat(uri.toString()).doesNotContain(filename)
            }
        }
    }

    @Test
    fun `revoked source cannot be shared`() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.use {
            val result = actions(it.get(), readAuthorized = false).share(media)
            assertThat(result.exceptionOrNull()).isInstanceOf(ProtectedMediaReadUnavailableException::class.java)
            assertThat(shadowOf(it.get()).nextStartedActivity).isNull()
        }
    }

    @Test
    fun `share policy revoked at handoff prevents chooser launch`() = runTest {
        val allowed = FakeDataProtectionPolicyProvider(allowExternalShare = true)
        val denied = FakeDataProtectionPolicyProvider(allowExternalShare = false)
        var reads = 0
        val policy = object : DataProtectionPolicyProvider {
            override fun current() = if (reads++ == 0) allowed.current() else denied.current()
        }
        Robolectric.buildActivity(Activity::class.java).setup().use {
            val result = actions(it.get(), policy = policy).share(media)
            assertThat(result.exceptionOrNull()).isInstanceOf(DataProtectionActionDeniedException.ExternalShare::class.java)
            assertThat(shadowOf(it.get()).nextStartedActivity).isNull()
        }
    }

    private fun TestScope.actions(
        activity: Activity,
        readAuthorized: Boolean = true,
        policy: DataProtectionPolicyProvider = FakeDataProtectionPolicyProvider(allowExternalShare = true),
    ) = AndroidLocalMediaActions(
        activity,
        testCoroutineDispatchers(),
        aBuildMeta(),
        policy,
        object : SecureMediaLeaseManager {
            override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease = error("Not used")
            override fun isReadAuthorized(uri: Uri): Boolean = readAuthorized
        },
    )
}
