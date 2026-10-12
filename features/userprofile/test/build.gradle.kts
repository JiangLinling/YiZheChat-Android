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
    namespace = "io.element.android.features.userprofile.test"
}

dependencies {
    api(projects.features.userprofile.api)
    implementation(projects.libraries.matrix.api)
    implementation(libs.coroutines.core)
}
