import extension.setupDependencyInjection
import extension.testCommonDependencies

plugins {
    id("io.element.android-compose-library")
    id("kotlin-parcelize")
}

android {
    namespace = "io.element.android.features.discovery.impl"
    testOptions { unitTests { isIncludeAndroidResources = true } }
    defaultConfig {
        manifestPlaceholders["yizheDiscoveryEnabled"] = providers.gradleProperty("yizhe.discovery.enabled").orElse("false").get()
        manifestPlaceholders["yizheDiscoveryGatewayUrl"] = providers.gradleProperty("yizhe.discovery.gatewayUrl").orElse("").get()
        manifestPlaceholders["yizheDiscoveryGatewayPin"] = providers.gradleProperty("yizhe.discovery.gatewayPin").orElse("").get()
    }
}

setupDependencyInjection()

dependencies {
    api(projects.features.discovery.api)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.di)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.sessionStorage.api)
    implementation(projects.libraries.designsystem)
    implementation(projects.security.secureStorage.api)
    implementation(projects.security.dataProtection.api)
    implementation(platform(libs.network.okhttp.bom))
    implementation(libs.network.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    testCommonDependencies(libs, includeTestComposeView = true)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.network.mockwebserver)
    testImplementation(projects.features.discovery.test)
}
