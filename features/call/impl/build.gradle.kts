import extension.buildConfigFieldStr
import extension.readLocalProperty
import extension.setupDependencyInjection
import extension.testCommonDependencies
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.net.URI
import java.security.MessageDigest

/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-compose-library")
    id("kotlin-parcelize")
    alias(libs.plugins.kotlin.serialization)
}

val isNextRtcBuild = providers.gradleProperty("yizhe.next.enabled").orNull == "true"
val generatedNextRtcAssetsDir = layout.buildDirectory.dir("generated/nextRtcAssets")
val prepareNextRtcAssets = if (isNextRtcBuild) {
    check(providers.gradleProperty("yizhe.next.enabled").orNull == "true") {
        "Next RTC assets require -Pyizhe.next.enabled=true."
    }

    val rtcAssetsSource = providers.gradleProperty("yizhe.next.rtcAssetsDir").map(::file)
    val rtcAssetsManifest = providers.gradleProperty("yizhe.next.rtcAssetsManifest").map(::file)
    val expectedManifestSha256 = providers.gradleProperty("yizhe.next.rtcAssetsManifestSha256")
    val verifyNextRtcAssets = tasks.register("verifyNextRtcAssets") {
        // The manifest-backed verification below covers every asset. Declaring the
        // external source tree as a Gradle input would snapshot all files a second time.
        inputs.file(rtcAssetsManifest)
        inputs.property("expectedManifestSha256", expectedManifestSha256)
        doLast {
            val sourceRoot = rtcAssetsSource.get().toPath().toAbsolutePath().normalize()
            val manifestPath = rtcAssetsManifest.get().toPath().toAbsolutePath().normalize()
            check(Files.isDirectory(sourceRoot)) { "Next RTC assets directory does not exist: $sourceRoot" }
            check(Files.isRegularFile(manifestPath)) { "Next RTC assets manifest does not exist: $manifestPath" }
            check(!Files.isSymbolicLink(manifestPath)) { "Next RTC assets manifest may not be a symbolic link: $manifestPath" }

            fun sha256(path: java.nio.file.Path): String {
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(path).buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }

            val actualManifestSha256 = sha256(manifestPath)
            check(actualManifestSha256.equals(expectedManifestSha256.get(), ignoreCase = true)) {
                "Next RTC assets manifest SHA-256 mismatch: expected=${expectedManifestSha256.get()}, actual=$actualManifestSha256"
            }

            @Suppress("UNCHECKED_CAST")
            val manifest = JsonSlurper().parse(manifestPath.toFile()) as Map<String, Any?>
            check(manifest["protocol"] == "next.element-call-assets.v1") {
                "Unsupported Next RTC assets manifest protocol: ${manifest["protocol"]}"
            }
            check(manifest["nextOnly"] == true) { "Next RTC assets manifest must declare nextOnly=true." }
            check(manifest["upstream"] == "ee1e8c60e7417e6eb9d55536f862123685c37aa2") {
                "Next RTC assets manifest upstream commit is not approved: ${manifest["upstream"]}"
            }
            check(manifest["livekitTransportDistributionVerified"] == true) {
                "Next RTC assets must prove the patched LiveKit transport distribution was bundled."
            }
            check(manifest["e2eeWorkerDistributionVerified"] == true) {
                "Next RTC assets must preserve the reviewed LiveKit E2EE worker distribution."
            }
            @Suppress("UNCHECKED_CAST")
            val expectedFiles = manifest["files"] as? Map<String, String>
                ?: error("Next RTC assets manifest is missing the files inventory.")
            check("index.html" in expectedFiles) { "Next RTC assets manifest must include index.html." }

            val actualFiles = linkedMapOf<String, String>()
            Files.walk(sourceRoot).use { paths ->
                paths.forEach { path ->
                    check(!Files.isSymbolicLink(path)) { "Next RTC assets may not contain symbolic links: $path" }
                    if (Files.isRegularFile(path)) {
                        val relative = sourceRoot.relativize(path).toString().replace('\\', '/')
                        actualFiles[relative] = sha256(path)
                    }
                }
            }
            check(actualFiles.keys == expectedFiles.keys) {
                "Next RTC assets inventory mismatch: missing=${expectedFiles.keys - actualFiles.keys}, unexpected=${actualFiles.keys - expectedFiles.keys}"
            }
            expectedFiles.forEach { (relative, expectedSha256) ->
                check(actualFiles.getValue(relative).equals(expectedSha256, ignoreCase = true)) {
                    "Next RTC asset SHA-256 mismatch for $relative"
                }
            }

            // A valid inventory can still describe an unusable WebView bundle:
            // /assets/foo.js exists in a web build but Android serves it under
            // /element-call/assets/foo.js. Resolve the actual HTML requests here.
            val html = Files.readString(sourceRoot.resolve("index.html"))
            check(!Regex("<base\\b", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
                "Next RTC HTML must not override its embedded base URL."
            }
            val tags = Regex("<(script|link)\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).toList()
            check(tags.any { it.groupValues[1].equals("script", true) &&
                Regex("type\\s*=\\s*['\"]module['\"]", RegexOption.IGNORE_CASE).containsMatchIn(it.value) }) {
                "Next RTC HTML has no module entry point."
            }
            val base = URI("https://appassets.androidplatform.net/element-call/index.html")
            val referencePattern = Regex("(?:src|href)\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
            tags.forEach { tag ->
                referencePattern.findAll(tag.value).forEach { match ->
                    val resolved = base.resolve(match.groupValues[1]).normalize()
                    check(resolved.scheme == "https" && resolved.authority == base.authority &&
                        resolved.path.startsWith("/element-call/") && !resolved.path.contains('\\')) {
                        "Next RTC resource escapes its Android embedded origin: ${match.groupValues[1]}"
                    }
                    val asset = sourceRoot.resolve(resolved.path.removePrefix("/element-call/")).normalize()
                    check(asset.startsWith(sourceRoot) && Files.isRegularFile(asset)) {
                        "Next RTC resource is missing at its requested Android path: ${match.groupValues[1]}"
                    }
                }
            }
            check(Files.isRegularFile(sourceRoot.resolve("config.json"))) { "Next RTC embedded config.json is missing." }
        }
    }
    tasks.register<Sync>("prepareNextRtcAssets") {
        dependsOn(verifyNextRtcAssets)
        from(rtcAssetsSource) {
            into("element-call")
        }
        into(generatedNextRtcAssetsDir)
    }
} else {
    null
}

gradle.taskGraph.whenReady {
    if (isNextRtcBuild) {
        val forbiddenAppTasks = allTasks.filter { task ->
            task.project.path == ":app" && listOf("gplay", "fdroid", "stable").any { flavor ->
                task.name.contains(flavor, ignoreCase = true)
            }
        }
        check(forbiddenAppTasks.isEmpty()) {
            "Next RTC assets cannot be packaged by Stable app tasks: ${forbiddenAppTasks.joinToString { it.path }}"
        }
    }
}

android {
    namespace = "io.element.android.features.call.impl"

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    if (prepareNextRtcAssets != null) {
        sourceSets.getByName("main").assets.srcDir(generatedNextRtcAssetsDir)
    }

    defaultConfig {
        buildConfigFieldStr(
            name = "SENTRY_DSN",
            value = System.getenv("ELEMENT_CALL_SENTRY_DSN")
                ?: readLocalProperty("features.call.sentry.dsn")
                ?: ""
        )
        buildConfigFieldStr(
            name = "POSTHOG_USER_ID",
            value = System.getenv("ELEMENT_CALL_POSTHOG_USER_ID")
                ?: readLocalProperty("features.call.posthog.userid")
                ?: ""
        )
        buildConfigFieldStr(
            name = "POSTHOG_API_HOST",
            value = System.getenv("ELEMENT_CALL_POSTHOG_API_HOST")
                ?: readLocalProperty("features.call.posthog.api.host")
                ?: ""
        )
        buildConfigFieldStr(
            name = "POSTHOG_API_KEY",
            value = System.getenv("ELEMENT_CALL_POSTHOG_API_KEY")
                ?: readLocalProperty("features.call.posthog.api.key")
                ?: ""
        )
        buildConfigFieldStr(
            name = "RAGESHAKE_URL",
            value = System.getenv("ELEMENT_CALL_RAGESHAKE_URL")
                ?: readLocalProperty("features.call.regeshake.url")
                ?: ""
        )
    }
}

if (prepareNextRtcAssets != null) {
    tasks.named("preBuild").configure {
        dependsOn(prepareNextRtcAssets)
    }
}

setupDependencyInjection()

dependencies {
    implementation(projects.appconfig)
    implementation(projects.features.enterprise.api)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.androidutils)
    implementation(projects.libraries.audio.api)
    implementation(projects.libraries.core)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.featureflag.api)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.matrixmedia.api)
    implementation(projects.libraries.network)
    implementation(projects.features.networkmonitor.api)
    implementation(projects.libraries.preferences.api)
    implementation(projects.libraries.push.api)
    implementation(projects.libraries.uiStrings)
    implementation(projects.services.analytics.api)
    implementation(projects.services.appnavstate.api)
    implementation(projects.services.toolbox.api)
    implementation(projects.security.dataProtection.api)
    implementation(projects.security.secureStorage.api)
    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.network.retrofit)
    implementation(libs.serialization.json)
    if (!isNextRtcBuild) {
        implementation(libs.element.call.embedded)
    }
    api(projects.features.call.api)

    testCommonDependencies(libs, true)
    testImplementation(projects.features.call.test)
    testImplementation(projects.features.networkmonitor.test)
    testImplementation(projects.libraries.featureflag.test)
    testImplementation(projects.libraries.preferences.test)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.libraries.matrixmedia.test)
    testImplementation(projects.libraries.push.test)
    testImplementation(projects.services.analytics.test)
    testImplementation(projects.services.appnavstate.impl)
    testImplementation(projects.services.appnavstate.test)
    testImplementation(projects.services.toolbox.test)
    testImplementation(projects.security.dataProtection.test)
    testImplementation(projects.security.secureStorage.test)
}
