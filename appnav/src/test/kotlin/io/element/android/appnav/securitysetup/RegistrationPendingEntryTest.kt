/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.appnav.securitysetup

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.appnav.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "zh-rCN-h1024dp")
class RegistrationPendingEntryTest {
    @Test
    fun `ordinary login shows the original registration exit before recovery or generic security retry`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = mutableListOf<SecuritySetupEvent>()
        val resources = activity!!.resources
        setContent {
            ElementPreview {
                SecuritySetupView(SecuritySetupState(
                    phase = SecuritySetupPhase.ACTION_REQUIRED,
                    errorCode = "REGISTRATION_PENDING", identityConfirmationRequired = true, eventSink = events::add,
                ))
            }
        }
        onNodeWithText(resources.getString(R.string.screen_registration_pending_title)).assertExists()
        onNodeWithText(resources.getString(R.string.screen_security_setup_retry)).assertDoesNotExist()
        onNodeWithText(resources.getString(R.string.screen_security_identity_submit_recovery_key)).assertDoesNotExist()
        onNodeWithText(resources.getString(R.string.screen_registration_pending_continue)).performClick()
        assertThat(events).containsExactly(SecuritySetupEvent.ContinueRegistration)
    }
}
