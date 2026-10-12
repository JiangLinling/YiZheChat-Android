import config.BuildTimeConfig
import extension.buildConfigFieldStr
import extension.testCommonDependencies
import java.net.URI
import java.util.Properties

/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
plugins {
    id("io.element.android-library")
}

val isNextBuildRequested = gradle.startParameter.taskNames.any { it.contains("next", ignoreCase = true) }
val nextProperties = Properties()
if (isNextBuildRequested) {
    check(providers.gradleProperty("yizhe.next.enabled").orNull == "true") {
        "Next builds require -Pyizhe.next.enabled=true."
    }
    val configFile = providers.gradleProperty("yizhe.next.configFile").orNull?.let(::file)
    check(configFile?.isFile == true) {
        "Next builds require -Pyizhe.next.configFile=<protected external Next properties file>."
    }
    requireNotNull(configFile).inputStream().use(nextProperties::load)
}

fun nextProperty(name: String): String = nextProperties.getProperty(name)?.trim().orEmpty().also {
    check(it.isNotBlank() && !it.contains("placeholder", ignoreCase = true) && !it.contains("replace", ignoreCase = true)) {
        "Missing or placeholder Next configuration: $name"
    }
}

fun nextHttpsUrl(name: String): String {
    val value = nextProperty(name)
    val uri = runCatching { URI(value) }.getOrNull()
    val host = uri?.host?.lowercase().orEmpty()
    check(uri?.scheme == "https" && host.isNotBlank() && host !in setOf("matrix.yizhesy.cn", "security.yizhesy.cn", "www.yizhesy.cn") &&
        !host.matches(Regex("[0-9.]+")) && !host.contains(':') && host != "localhost" &&
        uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "$name must be a non-Stable HTTPS hostname, never a numeric address."
    }
    return value.trimEnd('/')
}

android {
    namespace = "io.element.android.appconfig"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        val nextHomeserverUrl = if (isNextBuildRequested) nextHttpsUrl("next.matrix.homeserverUrl") else "https://matrix.yizhesy.cn"
        val nextUserPrefix = if (isNextBuildRequested) nextProperty("next.matrix.userPrefix") else "yizhe"
        check(!isNextBuildRequested || nextUserPrefix.matches(Regex("[a-z][a-z0-9_]{1,31}"))) {
            "next.matrix.userPrefix must be a Matrix localpart prefix."
        }
        check(!isNextBuildRequested || nextUserPrefix == "yizhe") {
            "next.matrix.userPrefix must match the control-plane onboarding localpart prefix."
        }
        val nextRtcBaseUrl = if (isNextBuildRequested) nextHttpsUrl("next.rtc.elementCallBaseUrl") else ""
        val nextPushRegistrarBaseUrl = if (isNextBuildRequested) nextHttpsUrl("next.push.registrarBaseUrl") else ""
        buildConfigField("boolean", "IS_NEXT_DEPLOYMENT", isNextBuildRequested.toString())
        buildConfigFieldStr("YIZHECHAT_HOMESERVER_URL", nextHomeserverUrl)
        buildConfigFieldStr("YIZHECHAT_USER_PREFIX", nextUserPrefix)
        buildConfigFieldStr("NEXT_RTC_BASE_URL", nextRtcBaseUrl)
        buildConfigFieldStr("NEXT_PUSH_REGISTRAR_BASE_URL", nextPushRegistrarBaseUrl)
        buildConfigFieldStr(
            name = "URL_POLICY",
            value = if (isEnterpriseBuild) {
                BuildTimeConfig.URL_POLICY ?: ""
            } else {
                "https://element.io/cookie-policy"
            },
        )
        buildConfigFieldStr(
            name = "BUG_REPORT_URL",
            value = BuildTimeConfig.BUG_REPORT_URL ?: "",
        )
        buildConfigFieldStr(
            name = "BUG_REPORT_APP_NAME",
            value = BuildTimeConfig.BUG_REPORT_APP_NAME ?: "",
        )
    }
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.androidx.annotationjvm)
    implementation(libs.androidx.corektx)
    implementation(projects.libraries.matrix.api)
    testCommonDependencies(libs)
}
