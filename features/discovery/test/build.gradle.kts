plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.features.discovery.test"
}

dependencies {
    api(projects.features.discovery.api)
    implementation(libs.coroutines.core)
}
