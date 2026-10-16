/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.startchat.impl

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "zh-rCN-h1024dp")
class FriendScannerContentTest {
    @Test
    fun `self shows accurate Chinese message and retry clears rejection`() = runAndroidComposeUiTest<ComponentActivity> {
        val scanner = FriendScannerState { FriendQrScanResult.Self }
        scanner.scanned("self".encodeToByteArray())
        setContent {
            ElementPreview {
                FriendScannerContent(
                    permissionGranted = true, scanning = scanner.scanning, result = scanner.result,
                    onBack = {}, onMyQrCode = {}, onPickImage = {}, onScanAgain = scanner::scanAgain,
                    cameraContent = {},
                )
            }
        }
        onNodeWithText("这是你自己的二维码，不能添加自己为好友").assertExists()
        onNodeWithText("这不是有效的乙者好友二维码").assertDoesNotExist()
        onNodeWithText("重新扫描").performClick()
        onNodeWithText("这是你自己的二维码，不能添加自己为好友").assertDoesNotExist()
        onNodeWithText("重新扫描").assertDoesNotExist()
        runOnIdle { assertThat(scanner.scanning).isTrue() }
    }

    @Test
    fun `invalid shows existing error and retry`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            ElementPreview {
                FriendScannerContent(
                    permissionGranted = false, scanning = false, result = FriendQrScanResult.Invalid,
                    onBack = {}, onMyQrCode = {}, onPickImage = {}, onScanAgain = {}, cameraContent = {},
                )
            }
        }
        onNodeWithText("这不是有效的乙者好友二维码").assertExists()
        onNodeWithText("重新扫描").assertExists()
        onNodeWithText("这是你自己的二维码，不能添加自己为好友").assertDoesNotExist()
    }

    @Test
    fun `handled has no rejection or retry`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            ElementPreview {
                FriendScannerContent(
                    permissionGranted = true, scanning = false, result = FriendQrScanResult.Handled,
                    onBack = {}, onMyQrCode = {}, onPickImage = {}, onScanAgain = {}, cameraContent = {},
                )
            }
        }
        onNodeWithText("重新扫描").assertDoesNotExist()
        onNodeWithText("这不是有效的乙者好友二维码").assertDoesNotExist()
        onNodeWithText("这是你自己的二维码，不能添加自己为好友").assertDoesNotExist()
    }
}
