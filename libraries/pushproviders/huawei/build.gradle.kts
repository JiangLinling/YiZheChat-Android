@file:Suppress("UnstableApiUsage")

import extension.setupDependencyInjection
import extension.testCommonDependencies

/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-library")
}

setupDependencyInjection()

android {
    namespace = "io.element.android.libraries.pushproviders.huawei"

    buildTypes {
        getByName("release") {
            consumerProguardFiles("consumer-proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(libs.androidx.corektx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.huawei.hms.push)
    implementation(libs.kotlinx.collections.immutable)
    implementation(projects.features.call.api)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.core)
    implementation(projects.libraries.di)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.push.api)
    implementation(projects.libraries.pushproviders.api)
    implementation(projects.libraries.pushproviders.unifiedpush)
    implementation(projects.libraries.pushstore.api)
    implementation(projects.libraries.push.impl)
    implementation(projects.libraries.sessionStorage.api)
    implementation(projects.libraries.troubleshoot.api)
    implementation(projects.services.toolbox.api)

    testCommonDependencies(libs)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.libraries.pushstore.test)
    testImplementation(projects.libraries.sessionStorage.test)
}
