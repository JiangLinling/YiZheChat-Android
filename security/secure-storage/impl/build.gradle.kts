import extension.setupDependencyInjection
import extension.testCommonDependencies

/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.security.securestorage.impl"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }
}

setupDependencyInjection()

dependencies {
    api(projects.security.secureStorage.api)
    implementation(projects.libraries.di)
    implementation(libs.androidx.annotationjvm)
    implementation(libs.coroutines.core)

    testImplementation(projects.security.secureStorage.test)
    testCommonDependencies(libs)

    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.test.truth)
    androidTestImplementation(libs.test.runner)
}
