/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

import extension.setupDependencyInjection
import extension.testCommonDependencies



plugins {
    id("io.element.android-compose-library")
}

setupDependencyInjection()

android {
    namespace = "io.element.android.libraries.mediapickers.impl"
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(projects.libraries.core)
    implementation(projects.libraries.di)
    api(projects.libraries.mediapickers.api)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.mediaviewer.api)
    implementation(projects.libraries.sessionStorage.api)
    implementation(projects.security.secureStorage.api)
    implementation(projects.security.dataProtection.api)
    testImplementation(projects.security.secureStorage.test)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.telephoto.zoomableimage)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.coil.compose)
    testCommonDependencies(libs, true)
}
