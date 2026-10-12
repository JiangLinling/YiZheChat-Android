/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package extension

import dev.zacsweers.metro.gradle.MetroPluginExtension
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.the
import org.gradle.plugin.use.PluginDependency
import java.io.File

/**
 * Setup the Metro plugin with the shared configuration.
 * @param generateNodeFactories Whether to set up the KSP plugin and dependencies to generate Appyx Node factories.
 */
fun Project.setupDependencyInjection(
    generateNodeFactories: Boolean = shouldApplyAppyxCodegen(),
) {
    if (project.path.endsWith(":api")) {
        error("api module should not use setupDependencyInjection(). Move the implementation to `:impl` module")
    }

    val libs = the<LibrariesForLibs>()

    // Apply Metro plugin and configure it
    applyPluginIfNeeded(libs.plugins.metro)

    if (generateNodeFactories) {
        applyPluginIfNeeded(libs.plugins.ksp)

        // Annotations to generate DI code for Appyx nodes
        dependencies.implementation(project.project(":annotations"))
        // Code generator for the annotations above
        dependencies.add("ksp", project.project(":codegen"))

        configureReleaseNodeFactoryVerification()
        if (pluginManager.hasPlugin("io.element.android-compose-library")) {
            configureDebugNodeFactoryVerification()
        }
    }
}

/** Debug builds must carry the same generated navigation contributions as Release. */
private fun Project.configureDebugNodeFactoryVerification() {
    val verifyTask = tasks.register("verifyDebugNodeFactoryContributions") {
        dependsOn("compileDebugKotlin")
        val generatedSources = layout.buildDirectory.dir("generated/ksp/debug/kotlin")
        val debugClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debug")

        doLast {
            val generatedDirectory = generatedSources.get().asFile
            if (!generatedDirectory.isDirectory) return@doLast
            val classesDirectory = debugClasses.get().asFile
            val missingClasses = generatedDirectory.walkTopDown()
                .filter { it.isFile && it.name.endsWith("_Module.kt") }
                .map { it.relativeTo(generatedDirectory).path.removeSuffix(".kt") + ".class" }
                .filterNot { File(classesDirectory, it).isFile }
                .toList()
            check(missingClasses.isEmpty()) {
                "Debug node DI generation is incomplete in $path; refusing to package a crash-prone APK. " +
                    "Missing: ${missingClasses.joinToString()}"
            }
        }
    }
    tasks.matching {
        it.name == "bundleLibCompileToJarDebug" ||
            it.name == "bundleLibRuntimeToJarDebug" ||
            it.name == "createFullJarDebug"
    }.configureEach { dependsOn(verifyTask) }
}

/**
 * KSP node contributions are part of the runtime navigation graph. A partially restored
 * Release cache can contain the node and its assisted factory while omitting the generated
 * `*_Module.class`. Such an APK compiles but crashes as soon as that node is requested.
 *
 * Keep the Release KSP/Kotlin tasks out of the build cache and fail packaging whenever a
 * generated node module source has no matching compiled class. The check is shared by every
 * Compose library using node codegen so startup and later navigation nodes are covered alike.
 */
private fun Project.configureReleaseNodeFactoryVerification() {
    tasks.matching { it.name == "kspReleaseKotlin" || it.name == "compileReleaseKotlin" }.configureEach {
        outputs.cacheIf("Release node factories must be generated from the current sources") { false }
    }

    val verifyTask = tasks.register("verifyReleaseNodeFactoryContributions") {
        dependsOn("compileReleaseKotlin")
        val generatedSources = layout.buildDirectory.dir("generated/ksp/release/kotlin")
        val releaseClasses = layout.buildDirectory.dir("tmp/kotlin-classes/release")

        doLast {
            val generatedDirectory = generatedSources.get().asFile
            if (!generatedDirectory.isDirectory) return@doLast

            val classesDirectory = releaseClasses.get().asFile
            val missingClasses = generatedDirectory
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith("_Module.kt") }
                .map { source ->
                    source.relativeTo(generatedDirectory).path.removeSuffix(".kt") + ".class"
                }
                .filterNot { relativeClass -> File(classesDirectory, relativeClass).isFile }
                .toList()

            check(missingClasses.isEmpty()) {
                "Release node DI generation is incomplete in $path; refusing to package a crash-prone APK. " +
                    "Missing: ${missingClasses.joinToString()}"
            }
        }
    }

    tasks.matching {
        it.name == "bundleLibCompileToJarRelease" ||
            it.name == "bundleLibRuntimeToJarRelease" ||
            it.name == "createFullJarRelease"
    }.configureEach {
        dependsOn(verifyTask)
    }
}

// These dependencies should only be needed for compose library or application modules
private fun Project.shouldApplyAppyxCodegen(): Boolean {
    return project.pluginManager.hasPlugin("io.element.android-compose-library")
        || project.pluginManager.hasPlugin("io.element.android-compose-application")
}

private fun Project.applyPluginIfNeeded(plugin: Provider<PluginDependency>) {
    val pluginId = plugin.get().pluginId
    if (!pluginManager.hasPlugin(pluginId)) {
        pluginManager.apply(pluginId)
    }
}
