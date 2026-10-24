plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.features.discovery.api"
}

dependencies {
    api(projects.libraries.architecture)
    implementation(libs.coroutines.core)
}
