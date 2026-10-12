/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.preferences.impl.root

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.preferences.impl.R
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.ui.components.aMatrixUser
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.security.dataprotection.api.ManagedAppRelease
import io.element.android.tests.testutils.EnsureNeverCalled
import io.element.android.tests.testutils.EnsureNeverCalledWithParam
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.ensureCalledOnce
import io.element.android.tests.testutils.ensureCalledOnceWithParam
import io.element.android.tests.testutils.pressBack
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferencesRootViewTest {
    @Test
    fun `clicking on back invokes back callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        ensureCalledOnce { callback ->
            setView(
                aPreferencesRootState(
                    eventSink = eventsRecorder
                ),
                onBackClick = callback,
            )
            pressBack()
        }
    }

    @Test
    fun `click on User profile invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        val user = aMatrixUser()
        ensureCalledOnceWithParam(user) { callback ->
            setView(
                aPreferencesRootState(
                    myUser = user,
                    eventSink = eventsRecorder,
                ),
                onOpenUserProfile = callback,
            )
            onNodeWithText("Alice").performClick()
        }
    }

    @Test
    fun `clicking on other session sends a SwitchToSession`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>()
        setView(
            aPreferencesRootState(
                isMultiAccountEnabled = true,
                otherSessions = listOf(
                    aMatrixUser(
                        id = A_USER_ID_2.value,
                        displayName = "Bob",
                    )
                ),
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText("Bob").performClick()
        eventsRecorder.assertSingle(PreferencesRootEvent.SwitchToSession(A_USER_ID_2))
    }

    @Test
    fun `click on Add account invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        ensureCalledOnce { callback ->
            setView(
                aPreferencesRootState(
                    isMultiAccountEnabled = true,
                    eventSink = eventsRecorder,
                ),
                onAddAccountClick = callback,
            )
            clickOn(CommonStrings.common_add_another_account)
        }
    }

    @Test
    fun `when multi account is not enabled, item is not shown`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                isMultiAccountEnabled = false,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.common_add_another_account)).assertDoesNotExist()
    }

    @Test
    fun `encryption is moved out of the root settings page`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                showSecureBackup = true,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.common_encryption)).assertDoesNotExist()
    }

    @Test
    fun `when showSecureBackup is false, item is not shown`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                showSecureBackup = false,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.common_encryption)).assertDoesNotExist()
    }

    @Test
    fun `click on Manage account invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        ensureCalledOnceWithParam("aUrl") { callback ->
            setView(
                aPreferencesRootState(
                    accountManagementUrl = "aUrl",
                    eventSink = eventsRecorder,
                ),
                onManageAccountClick = callback,
            )
            clickOn(CommonStrings.action_manage_account_and_devices)
        }
    }

    @Test
    fun `when accountManagementUrl is null, item is not shown`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                accountManagementUrl = null,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.action_manage_account_and_devices)).assertDoesNotExist()
    }

    @Test
    fun `click on Link new devices invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        ensureCalledOnce { callback ->
            setView(
                aPreferencesRootState(
                    showLinkNewDevice = true,
                    eventSink = eventsRecorder,
                ),
                onLinkNewDeviceClick = callback,
            )
            clickOn(CommonStrings.common_link_new_device)
        }
    }

    @Test
    fun `when showLinkNewDevice is false, item is not shown`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                showLinkNewDevice = false,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.common_link_new_device)).assertDoesNotExist()
    }

    @Test
    fun `analytics is moved out of the root settings page`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                showAnalyticsSettings = true,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.common_analytics)).assertDoesNotExist()
    }

    @Test
    fun `when showAnalyticsSettings is false, item is not shown`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                showAnalyticsSettings = false,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.common_analytics)).assertDoesNotExist()
    }

    @Test
    fun `report a problem is moved out of the root settings page`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                canReportBug = true,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.common_report_a_problem)).assertDoesNotExist()
    }

    @Test
    fun `when canReportBug is false, item is not shown`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                canReportBug = false,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.common_report_a_problem)).assertDoesNotExist()
    }

    @Test
    fun `screen lock is moved out of the root settings page`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(aPreferencesRootState(eventSink = eventsRecorder))
        onNodeWithText(activity!!.getString(CommonStrings.common_screen_lock)).assertDoesNotExist()
    }

    @Test
    fun `flat root omits redundant headings`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            state = aPreferencesRootState(eventSink = eventsRecorder),
            showTopBar = false,
        )

        onNodeWithText(activity!!.getString(CommonStrings.common_settings)).assertDoesNotExist()
        onNodeWithText("Account").assertDoesNotExist()
        onNodeWithText("Security").assertDoesNotExist()
        onNodeWithText("General").assertDoesNotExist()
        onNodeWithText("About").assertDoesNotExist()
    }

    @Test
    fun `notification settings remain visible for push diagnostics`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        ensureCalledOnce { callback ->
            setView(
                aPreferencesRootState(eventSink = eventsRecorder),
                onOpenNotificationSettings = callback,
            )
            clickOn(R.string.screen_notification_settings_title)
        }
    }

    @Test
    fun `developer settings stay hidden from the customer settings page`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                showDeveloperSettings = true,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.common_developer_options)).assertDoesNotExist()
    }

    @Test
    fun `when showDeveloperSettings is false, item is not shown`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                showDeveloperSettings = false,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.common_developer_options)).assertDoesNotExist()
    }

    @Test
    fun `click on Advanced settings invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        ensureCalledOnce { callback ->
            setView(
                aPreferencesRootState(
                    eventSink = eventsRecorder,
                ),
                onOpenAdvancedSettings = callback,
            )
            clickOn(CommonStrings.common_advanced_settings)
        }
    }

    @Test
    fun `labs stay hidden from the customer settings page`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                showLabsItem = true,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(R.string.screen_labs_title)).assertDoesNotExist()
    }

    @Test
    fun `when showLabsItem is false, item is not shown`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                showLabsItem = false,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(R.string.screen_labs_title)).assertDoesNotExist()
    }

    @Test
    fun `click on Blocked users invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        ensureCalledOnce { callback ->
            setView(
                aPreferencesRootState(
                    nbOfBlockedUsers = 1,
                    eventSink = eventsRecorder,
                ),
                onOpenBlockedUsers = callback,
            )
            clickOn(CommonStrings.common_blocked_users)
        }
    }

    @Test
    fun `when nbOfBlockedUsers is 0, item is not shown`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                nbOfBlockedUsers = 0,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.common_blocked_users)).assertDoesNotExist()
    }

    @Test
    fun `click on Remove this device invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        ensureCalledOnce { callback ->
            setView(
                aPreferencesRootState(
                    eventSink = eventsRecorder,
                ),
                onSignOutClick = callback,
            )
            onNodeWithText(activity!!.getString(R.string.screen_preferences_sign_out))
                .performScrollTo()
                .performClick()
        }
    }

    @Test
    fun `click on Deactivate invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        ensureCalledOnce { callback ->
            setView(
                aPreferencesRootState(
                    canDeactivateAccount = true,
                    eventSink = eventsRecorder,
                ),
                onDeactivateClick = callback,
            )
            onNodeWithText(activity!!.getString(CommonStrings.action_delete_account))
                .performScrollTo()
                .performClick()
        }
    }

    @Test
    fun `when canDeactivateAccount is false, item is not shown`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        setView(
            aPreferencesRootState(
                canDeactivateAccount = false,
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithText(activity!!.getString(CommonStrings.action_delete_account)).assertDoesNotExist()
    }

    @Test
    fun `version footer opens about without unlocking internal settings`() = runAndroidComposeUiTest {
        val version = "VERSION"
        val eventsRecorder = EventsRecorder<PreferencesRootEvent>(expectEvents = false)
        ensureCalledOnce { callback ->
            setView(
                aPreferencesRootState(
                    version = version,
                    eventSink = eventsRecorder,
                ),
                onOpenAbout = callback,
            )
            onNodeWithTag(PREFERENCES_UPDATE_FOOTER_TEST_TAG)
                .performScrollTo()
                .performClick()
        }
        eventsRecorder.assertEmpty()
    }

    @Test
    fun `version footer has no update dot and still opens about without a newer release`() = runAndroidComposeUiTest {
        val version = "VERSION"
        ensureCalledOnce { callback ->
            setView(aPreferencesRootState(version = version), onOpenAbout = callback)
            onNodeWithContentDescription(activity!!.getString(R.string.screen_about_update_available_badge)).assertDoesNotExist()
            onNodeWithTag(PREFERENCES_UPDATE_FOOTER_TEST_TAG)
                .performScrollTo()
                .performClick()
        }
    }

    @Test
    fun `newer optional release shows update dot and version footer opens about`() = runAndroidComposeUiTest {
        val version = "VERSION"
        ensureCalledOnce { callback ->
            setView(
                state = aPreferencesRootState(
                    version = version,
                    availableUpdate = ManagedAppRelease(
                        versionCode = 202606242,
                        versionName = "26.06.24-internal",
                        releaseNotes = "Test release",
                        mandatory = false,
                        apkUrl = "https://download.yizhesy.cn/v1/app/releases/files/test.apk",
                        apkFallbackUrl = "https://security.yizhesy.cn/v1/app/releases/files/test.apk",
                        apkSha256 = "a".repeat(64),
                        buildId = "test-build-202606242",
                        signingCertificateSha256 = "b".repeat(64),
                        packageName = "com.yizhe.chat.internal",
                    ),
                ),
                onOpenAbout = callback,
            )

            onNodeWithContentDescription(activity!!.getString(R.string.screen_about_update_available_badge)).assertExists()
            onNodeWithTag(PREFERENCES_UPDATE_FOOTER_TEST_TAG)
                .performScrollTo()
                .performClick()
        }
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.setView(
    state: PreferencesRootState,
    showTopBar: Boolean = true,
    onBackClick: () -> Unit = EnsureNeverCalled(),
    onAddAccountClick: () -> Unit = EnsureNeverCalled(),
    onSecureBackupClick: () -> Unit = EnsureNeverCalled(),
    onManageAccountClick: (url: String) -> Unit = EnsureNeverCalledWithParam(),
    onLinkNewDeviceClick: () -> Unit = EnsureNeverCalled(),
    onOpenAnalytics: () -> Unit = EnsureNeverCalled(),
    onOpenRageShake: () -> Unit = EnsureNeverCalled(),
    onOpenLockScreenSettings: () -> Unit = EnsureNeverCalled(),
    onOpenAbout: () -> Unit = EnsureNeverCalled(),
    onOpenDeveloperSettings: () -> Unit = EnsureNeverCalled(),
    onOpenAdvancedSettings: () -> Unit = EnsureNeverCalled(),
    onOpenLabs: () -> Unit = EnsureNeverCalled(),
    onOpenNotificationSettings: () -> Unit = EnsureNeverCalled(),
    onOpenChatSecurity: () -> Unit = EnsureNeverCalled(),
    onOpenMessageFavourites: () -> Unit = EnsureNeverCalled(),
    onOpenUserProfile: (MatrixUser) -> Unit = EnsureNeverCalledWithParam(),
    onOpenBlockedUsers: () -> Unit = EnsureNeverCalled(),
    onSignOutClick: () -> Unit = EnsureNeverCalled(),
    onDeactivateClick: () -> Unit = EnsureNeverCalled(),
) {
    setContent {
        PreferencesRootView(
            state = state,
            showTopBar = showTopBar,
            onBackClick = onBackClick,
            onAddAccountClick = onAddAccountClick,
            onSecureBackupClick = onSecureBackupClick,
            onManageAccountClick = onManageAccountClick,
            onLinkNewDeviceClick = onLinkNewDeviceClick,
            onOpenAnalytics = onOpenAnalytics,
            onOpenRageShake = onOpenRageShake,
            onOpenLockScreenSettings = onOpenLockScreenSettings,
            onOpenAbout = onOpenAbout,
            onOpenDeveloperSettings = onOpenDeveloperSettings,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            onOpenLabs = onOpenLabs,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onOpenChatSecurity = onOpenChatSecurity,
            onOpenMessageFavourites = onOpenMessageFavourites,
            onOpenUserProfile = onOpenUserProfile,
            onOpenBlockedUsers = onOpenBlockedUsers,
            onSignOutClick = onSignOutClick,
            onDeactivateClick = onDeactivateClick,
        )
    }
}
