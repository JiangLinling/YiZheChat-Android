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
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.element.android.security.contentprotection.impl"
}

setupDependencyInjection()

dependencies {
    api(projects.security.contentProtection.api)
    implementation(projects.security.dataProtection.api)
    implementation(projects.security.secureStorage.api)
    implementation(projects.libraries.sessionStorage.api)
    implementation(projects.libraries.di)
    implementation(projects.services.toolbox.api)
    implementation(platform(libs.network.okhttp.bom))
    implementation(libs.network.okhttp)
    implementation(platform(libs.network.retrofit.bom))
    implementation(libs.network.retrofit)
    implementation(libs.network.retrofit.converter.serialization)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(projects.security.secureStorage.test)
    testImplementation(projects.libraries.sessionStorage.test)
    testImplementation(projects.services.toolbox.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.network.mockwebserver)
    testCommonDependencies(libs)
}
