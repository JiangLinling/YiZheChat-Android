import extension.testCommonDependencies

plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "io.element.android.libraries.localofficeviewer.impl"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(projects.libraries.localofficeviewer.api)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coroutines.core)
    implementation(libs.timber)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.uiStrings)

    testCommonDependencies(libs, true)
}
