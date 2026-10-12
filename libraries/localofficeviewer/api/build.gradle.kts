import extension.testCommonDependencies

plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.libraries.localofficeviewer.api"
}

dependencies {
    implementation(libs.coroutines.core)
    testCommonDependencies(libs)
}
