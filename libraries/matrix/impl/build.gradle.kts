import extension.setupDependencyInjection
import extension.testCommonDependencies

/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.element.android.libraries.matrix.impl"
}

setupDependencyInjection()

dependencies {
    releaseImplementation(libs.matrix.sdk)
    val isNextBuild = providers.gradleProperty("yizhe.next.enabled").orNull == "true"
    val localRustSdk = file("${rootDir.path}/libraries/rustsdk/matrix-rust-sdk-next.aar")
    if (isNextBuild) {
        check(localRustSdk.exists()) {
            "Next debug builds require the reviewed local Rust SDK AAR."
        }
        println("\nNote: Using Next-only local binary of the Rust SDK.\n")
        debugImplementation(projects.libraries.rustsdk)
    } else {
        debugImplementation(libs.matrix.sdk)
    }
    implementation(projects.libraries.rustlsTls)

    implementation(projects.appconfig)
    implementation(projects.features.enterprise.api)
    implementation(projects.libraries.androidutils)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.di)
    implementation(projects.libraries.featureflag.api)
    implementation(projects.libraries.network)
    implementation(projects.libraries.preferences.api)
    implementation(projects.libraries.sessionStorage.api)
    implementation(projects.libraries.workmanager.api)
    implementation(projects.services.analytics.api)
    implementation(projects.services.toolbox.api)
    implementation(projects.security.secureStorage.api)
    implementation(projects.security.dataProtection.api)
    implementation(projects.security.contentProtection.api)
    api(projects.libraries.matrix.api)
    implementation(projects.libraries.core)
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    implementation(platform(libs.network.okhttp.bom))
    implementation(libs.network.okhttp)

    testCommonDependencies(libs)
    testImplementation(projects.features.enterprise.test)
    testImplementation(projects.libraries.featureflag.test)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.libraries.preferences.test)
    testImplementation(projects.libraries.previewutils)
    testImplementation(projects.libraries.sessionStorage.test)
    testImplementation(projects.libraries.workmanager.test)
    testImplementation(projects.services.analytics.test)
    testImplementation(projects.services.toolbox.test)
    testImplementation(projects.security.secureStorage.test)
    testImplementation(projects.security.dataProtection.test)
    testImplementation(projects.security.contentProtection.test)
}
