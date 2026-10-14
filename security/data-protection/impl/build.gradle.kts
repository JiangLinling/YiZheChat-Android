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
    namespace = "io.element.android.security.dataprotection.impl"
}

setupDependencyInjection()

dependencies {
    api(projects.security.dataProtection.api)
    implementation(projects.security.contentProtection.api)
    implementation(projects.security.secureStorage.api)
    api(projects.libraries.encryptedDb)
    implementation(projects.libraries.di)
    implementation(projects.libraries.sessionStorage.api)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.push.api)
    implementation(projects.features.messages.api)
    implementation(projects.libraries.core)
    implementation(platform(libs.network.okhttp.bom))
    implementation(libs.network.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.collections.immutable)

    testCommonDependencies(libs)
    testImplementation(projects.security.secureStorage.test)
    testImplementation(projects.security.contentProtection.test)
    testImplementation(projects.libraries.sessionStorage.test)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.libraries.push.test)
    testImplementation(libs.network.mockwebserver)
    testImplementation(libs.coroutines.test)
}
