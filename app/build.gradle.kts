/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.android.build.gradle.tasks.GenerateBuildConfig
import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
import config.BuildTimeConfig
import extension.AssetCopyTask
import extension.GitBranchNameValueSource
import extension.GitRevisionValueSource
import extension.allEnterpriseImpl
import extension.allFeaturesImpl
import extension.allLibrariesImpl
import extension.allServicesImpl
import extension.buildConfigFieldStr
import extension.locales
import extension.setupDependencyInjection
import extension.testCommonDependencies
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.net.URI
import java.util.Base64
import java.util.HexFormat
import java.util.Locale
import java.util.Properties

plugins {
    id("io.element.android-compose-application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.huawei.agconnect)
    // When using precompiled plugins, we need to apply the firebase plugin like this
    id(libs.plugins.firebaseAppDistribution.get().pluginId)
    id("kotlin-parcelize")
    alias(libs.plugins.licensee)
    alias(libs.plugins.kotlin.serialization)
    // To be able to update the firebase.xml files, uncomment and build the project
    // alias(libs.plugins.gms.google.services)
}

// Release trust anchor currently published by https://security.yizhesy.cn/v1/public/signing-key.
// A server key rotation must deliberately update this value; a self-consistent stale local Gradle
// key must never be allowed to produce another installable YiZhe release.
val yizheAuthoritySigningKeyId = "5e347cd737ddf238"
val yizheNextAndroidSigningCertificateSha256 = "9da04c7a7493a51552ef6030e40c8962cdd2139bee8d3fc223e3a34dc64a36d3"

// Next is deliberately opt-in. Its public configuration is kept outside the checkout so a
// production properties file cannot accidentally make a Next artifact connect to Stable.
val isNextBuildRequested = gradle.startParameter.taskNames.any { it.contains("next", ignoreCase = true) }
val unifiedSourceManifest = rootProject.file("unified-source-manifest.json")
val unifiedSourceManifestSha256 = if (isNextBuildRequested) {
    check(unifiedSourceManifest.isFile) { "Next packaging requires the reviewed cumulative source manifest." }
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(unifiedSourceManifest.readBytes()))
} else ""
val nextBuildProperties = Properties()
fun nextBuildProperty(name: String): String = nextBuildProperties.getProperty(name)?.trim().orEmpty().also {
    check(it.isNotBlank() && !it.contains("replace", ignoreCase = true) && !it.contains("placeholder", ignoreCase = true)) {
        "Missing or placeholder Next configuration: $name"
    }
}

if (isNextBuildRequested) {
    check(providers.gradleProperty("yizhe.next.enabled").orNull == "true") {
        "Next builds require -Pyizhe.next.enabled=true."
    }
    val configFile = providers.gradleProperty("yizhe.next.configFile").orNull?.let(::file)
    check(configFile?.isFile == true) {
        "Next builds require -Pyizhe.next.configFile=<protected external Next properties file>."
    }
    val canonicalConfig = requireNotNull(configFile).canonicalFile
    val forbiddenConfigFiles = listOf(
        rootProject.file("security-production.properties"),
        rootProject.file("security-production.properties.example"),
        rootProject.file("gradle.properties"),
    ).map { it.canonicalFile }
    check(canonicalConfig !in forbiddenConfigFiles && !canonicalConfig.name.contains("production", ignoreCase = true)) {
        "Next configuration must not reuse a Stable production properties file."
    }
    canonicalConfig.inputStream().use(nextBuildProperties::load)
}

val nextManifestValues = if (isNextBuildRequested) {
    fun requireNextHttpsUrl(name: String): String {
        val value = nextBuildProperty(name)
        val uri = runCatching { URI(value) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.US).orEmpty()
        val stableHosts = setOf("security.yizhesy.cn", "www.yizhesy.cn", "matrix.yizhesy.cn")
        val numericHost = host.matches(Regex("[0-9.]+")) || host.contains(':')
        check(
            uri?.scheme == "https" && host.isNotBlank() && host !in stableHosts && !numericHost && host != "localhost" &&
                uri.userInfo == null && uri.query == null && uri.fragment == null
        ) {
            "$name must be a non-Stable HTTPS hostname, never a numeric address."
        }
        return value.trimEnd('/')
    }

    fun requireNextPin(name: String): String {
        val value = nextBuildProperty(name)
        val decoded = runCatching { Base64.getDecoder().decode(value.removePrefix("sha256/")) }.getOrNull()
        check(value.startsWith("sha256/") && decoded?.size == 32) { "$name must be a SHA-256 SPKI pin." }
        return value
    }

    fun requireNextUpdateBaseUrl(name: String): String {
        val value = requireNextHttpsUrl(name)
        val uri = URI(value)
        check((uri.port in 1024..65535) && (uri.path.isEmpty() || uri.path == "/") && uri.query == null && uri.fragment == null) {
            "$name must use a dedicated HTTPS high port with no path, query, or fragment."
        }
        return value
    }

    val controlSigningKey = nextBuildProperty("next.control.signingKeyDerB64")
    val kmsSigningKey = nextBuildProperty("next.kms.signingKeyDerB64")
    val signingKeyBytes = runCatching { Base64.getDecoder().decode(controlSigningKey) }.getOrNull()
    val signingKey = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(requireNotNull(signingKeyBytes)))
    }.getOrNull() as? ECPublicKey
    check(signingKey?.params?.curve?.field?.fieldSize == 256 && controlSigningKey == kmsSigningKey) {
        "Next Control and KMS must use the same valid P-256 public signing key."
    }
    val derivedKeyId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(requireNotNull(signingKeyBytes))).take(16)
    check(nextBuildProperty("next.control.signingKeyId") == derivedKeyId && derivedKeyId != yizheAuthoritySigningKeyId) {
        "Next Control signing key id must match its non-Stable public key."
    }
    val controlPin = requireNextPin("next.control.certificatePin")
    val kmsPins = nextBuildProperty("next.kms.certificatePins").split(',').map(String::trim).map {
        val decoded = runCatching { Base64.getDecoder().decode(it.removePrefix("sha256/")) }.getOrNull()
        check(it.startsWith("sha256/") && decoded?.size == 32) { "next.kms.certificatePins must contain SHA-256 SPKI pins." }
        it
    }
    check(kmsPins.size >= 2 && kmsPins.toSet().size == kmsPins.size && controlPin in kmsPins) {
        "Next KMS pins must contain distinct current and backup pins including the Control pin."
    }
    val updateChannel = nextBuildProperty("next.update.channel")
    check(updateChannel.matches(Regex("next-[a-z0-9][a-z0-9-]{1,62}"))) {
        "next.update.channel must be a dedicated next-* channel."
    }
    check(nextBuildProperty("next.kms.maximumOfflineLeaseMillis").toLongOrNull() in 1L..86_400_000L) {
        "next.kms.maximumOfflineLeaseMillis must be between 1 ms and 24 hours."
    }
    check(nextBuildProperty("next.kms.maximumOfflineLicenseUses").toIntOrNull() in 1..16) {
        "next.kms.maximumOfflineLicenseUses must be between 1 and 16."
    }
    check(nextBuildProperty("next.kms.clockRollbackToleranceMillis").toLongOrNull() in 0L..120_000L) {
        "next.kms.clockRollbackToleranceMillis must be between 0 and 2 minutes."
    }
    mapOf(
        "yizheControlBaseUrl" to requireNextHttpsUrl("next.control.baseUrl"),
        "yizheControlSigningKeyDerB64" to controlSigningKey,
        "yizheControlSigningKeyId" to derivedKeyId,
        "yizheControlCertificatePin" to controlPin,
        "yizheKmsBaseUrl" to requireNextHttpsUrl("next.kms.baseUrl"),
        "yizheKmsSigningKeyDerB64" to kmsSigningKey,
        "yizheKmsCertificatePins" to kmsPins.joinToString(),
        "yizheKmsMaximumOfflineLeaseMillis" to nextBuildProperty("next.kms.maximumOfflineLeaseMillis"),
        "yizheKmsMaximumOfflineLicenseUses" to nextBuildProperty("next.kms.maximumOfflineLicenseUses"),
        "yizheKmsClockRollbackToleranceMillis" to nextBuildProperty("next.kms.clockRollbackToleranceMillis"),
        "yizheOfficePreviewBaseUrl" to requireNextHttpsUrl("next.officePreview.baseUrl"),
        "yizheOfficePreviewCertificatePin" to requireNextPin("next.officePreview.certificatePin"),
        "yizheUpdateChannel" to updateChannel,
        "yizheNextUpdateCdnBaseUrl" to requireNextUpdateBaseUrl("next.update.cdnBaseUrl"),
        "yizheNextUpdateFallbackBaseUrl" to requireNextUpdateBaseUrl("next.update.fallbackBaseUrl"),
        "yizheNextMatrixHomeserverUrl" to requireNextHttpsUrl("next.matrix.homeserverUrl"),
        "yizheNextRtcBaseUrl" to requireNextHttpsUrl("next.rtc.elementCallBaseUrl"),
    )
} else {
    emptyMap()
}

android {
    namespace = "io.element.android.x"

    defaultConfig {
        applicationId = BuildTimeConfig.APPLICATION_ID
        targetSdk = Versions.TARGET_SDK
        versionCode = Versions.VERSION_CODE
        versionName = Versions.VERSION_NAME
        manifestPlaceholders["yizheUnifiedSourceManifestSha256"] = ""
        manifestPlaceholders["yizheControlBaseUrl"] = providers.gradleProperty("yizhe.control.baseUrl").orElse("").get()
        manifestPlaceholders["yizheControlSigningKeyDerB64"] = providers.gradleProperty("yizhe.control.signingKeyDerB64").orElse("").get()
        manifestPlaceholders["yizheControlSigningKeyId"] = providers.gradleProperty("yizhe.control.signingKeyId").orElse("").get()
        manifestPlaceholders["yizheControlCertificatePin"] = providers.gradleProperty("yizhe.control.certificatePin").orElse("").get()
        manifestPlaceholders["yizheKmsBaseUrl"] = providers.gradleProperty("yizhe.kms.baseUrl").orElse("").get()
        manifestPlaceholders["yizheKmsSigningKeyDerB64"] = providers.gradleProperty("yizhe.kms.signingKeyDerB64").orElse("").get()
        manifestPlaceholders["yizheKmsCertificatePins"] = providers.gradleProperty("yizhe.kms.certificatePins").orElse("").get()
        manifestPlaceholders["yizheKmsMaximumOfflineLeaseMillis"] = providers.gradleProperty("yizhe.kms.maximumOfflineLeaseMillis").orElse("0").get()
        manifestPlaceholders["yizheKmsMaximumOfflineLicenseUses"] = providers.gradleProperty("yizhe.kms.maximumOfflineLicenseUses").orElse("0").get()
        manifestPlaceholders["yizheKmsClockRollbackToleranceMillis"] = providers.gradleProperty("yizhe.kms.clockRollbackToleranceMillis").orElse("0").get()
        manifestPlaceholders["yizheOfficePreviewBaseUrl"] = providers.gradleProperty("yizhe.officePreview.baseUrl").orElse("").get()
        manifestPlaceholders["yizheOfficePreviewCertificatePin"] = providers.gradleProperty("yizhe.officePreview.certificatePin").orElse("").get()
        manifestPlaceholders["yizheUpdateChannel"] = ""
        manifestPlaceholders["yizheNextUpdateCdnBaseUrl"] = ""
        manifestPlaceholders["yizheNextUpdateFallbackBaseUrl"] = ""
        manifestPlaceholders["yizheNextMatrixHomeserverUrl"] = ""
        manifestPlaceholders["yizheNextRtcBaseUrl"] = ""
        val supportedAbis = listOf("armeabi-v7a", "x86", "arm64-v8a", "x86_64")
        val requestedBuildAbi = providers.gradleProperty("yizhe.buildAbi").orNull
        check(requestedBuildAbi == null || requestedBuildAbi in supportedAbis) {
            "Unsupported yizhe.buildAbi: $requestedBuildAbi"
        }

        // Keep abiFilter for the universalApk, or constrain native compilation for a requested single-ABI artifact.
        ndk {
            abiFilters += requestedBuildAbi?.let(::listOf) ?: supportedAbis
        }

        // Ref: https://developer.android.com/studio/build/configure-apk-splits.html#configure-abi-split
        splits {
            // Configures multiple APKs based on ABI.
            abi {
                val buildingAppBundle = gradle.startParameter.taskNames.any { it.contains("bundle") }

                // A requested single ABI is compiled as one native-only APK; normal APK builds keep the existing split matrix.
                isEnable = !buildingAppBundle && requestedBuildAbi == null

                // By default all ABIs are included, so use reset() and include to specify that we only
                // want APKs for armeabi-v7a, x86, arm64-v8a and x86_64.
                // Resets the list of ABIs that Gradle should create APKs for to none.
                reset()

                if (!buildingAppBundle && requestedBuildAbi == null) {
                    // Specifies a list of ABIs that Gradle should create APKs for.
                    include(*supportedAbis.toTypedArray())
                    // Generate a universal APK that includes all ABIs, so user who installs from CI tool can use this one by default.
                    isUniversalApk = true
                }
            }
        }

        androidResources {
            localeFilters += locales
        }
    }

    val releaseSigningValues = mapOf(
        "storeFile" to providers.environmentVariable("YIZHE_RELEASE_STORE_FILE").orNull,
        "storePassword" to providers.environmentVariable("YIZHE_RELEASE_STORE_PASSWORD").orNull,
        "keyAlias" to providers.environmentVariable("YIZHE_RELEASE_KEY_ALIAS").orNull,
        "keyPassword" to providers.environmentVariable("YIZHE_RELEASE_KEY_PASSWORD").orNull,
    )
    if (releaseSigningValues.values.any { !it.isNullOrBlank() }) {
        check(!gradle.startParameter.isConfigurationCacheRequested) {
            "Production signing secrets must not be used with Gradle configuration cache; add --no-configuration-cache."
        }
    }
    val releaseSigningConfig = if (releaseSigningValues.values.all { !it.isNullOrBlank() }) {
        signingConfigs.create("yizheRelease") {
            storeFile = file(requireNotNull(releaseSigningValues["storeFile"]))
            storePassword = releaseSigningValues["storePassword"]
            keyAlias = releaseSigningValues["keyAlias"]
            keyPassword = releaseSigningValues["keyPassword"]
        }
    } else {
        null
    }
    val internalSigningValues = mapOf(
        "storeFile" to providers.environmentVariable("YIZHE_INTERNAL_STORE_FILE").orNull,
        "storePassword" to providers.environmentVariable("YIZHE_INTERNAL_STORE_PASSWORD").orNull,
        "keyAlias" to providers.environmentVariable("YIZHE_INTERNAL_KEY_ALIAS").orNull,
        "keyPassword" to providers.environmentVariable("YIZHE_INTERNAL_KEY_PASSWORD").orNull,
    )
    if (internalSigningValues.values.any { !it.isNullOrBlank() }) {
        check(internalSigningValues.values.all { !it.isNullOrBlank() }) {
            "Internal Release signing variables must be supplied as a complete set."
        }
        check(!gradle.startParameter.isConfigurationCacheRequested) {
            "Internal Release signing secrets must not be used with Gradle configuration cache."
        }
    }
    val internalSigningConfig = if (internalSigningValues.values.all { !it.isNullOrBlank() }) {
        signingConfigs.create("yizheInternalRelease") {
            storeFile = file(requireNotNull(internalSigningValues["storeFile"]))
            storePassword = internalSigningValues["storePassword"]
            keyAlias = internalSigningValues["keyAlias"]
            keyPassword = internalSigningValues["keyPassword"]
        }
    } else {
        null
    }

    signingConfigs {
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeFile = file("./signature/debug.keystore")
            storePassword = "android"
        }
        register("nightly") {
            keyAlias = System.getenv("ELEMENT_ANDROID_NIGHTLY_KEYID")
                ?: project.property("signing.element.nightly.keyId") as? String?
            keyPassword = System.getenv("ELEMENT_ANDROID_NIGHTLY_KEYPASSWORD")
                ?: project.property("signing.element.nightly.keyPassword") as? String?
            storeFile = file("./signature/nightly.keystore")
            storePassword = System.getenv("ELEMENT_ANDROID_NIGHTLY_STOREPASSWORD")
                ?: project.property("signing.element.nightly.storePassword") as? String?
        }
    }

    val nextDebugSigningConfig = if (isNextBuildRequested) {
        val nextSigningValues = mapOf(
            "storeFile" to providers.environmentVariable("YIZHE_NEXT_DEBUG_STORE_FILE").orNull,
            "storePassword" to providers.environmentVariable("YIZHE_NEXT_DEBUG_STORE_PASSWORD").orNull,
            "keyAlias" to providers.environmentVariable("YIZHE_NEXT_DEBUG_KEY_ALIAS").orNull,
            "keyPassword" to providers.environmentVariable("YIZHE_NEXT_DEBUG_KEY_PASSWORD").orNull,
        )
        check(nextSigningValues.values.all { !it.isNullOrBlank() }) {
            "Next Debug requires its complete YIZHE_NEXT_DEBUG_* signing identity."
        }
        val nextStoreFile = file(requireNotNull(nextSigningValues["storeFile"])).canonicalFile
        check(nextStoreFile.isFile) {
            "Next Debug keystore does not exist."
        }
        check(!requireNotNull(nextSigningValues["keyAlias"]).equals("androiddebugkey", ignoreCase = true)) {
            "Next Debug cannot use the Stable Android debug alias."
        }
        val keyStoreType = when (nextStoreFile.extension.lowercase()) {
            "p12", "pfx" -> "PKCS12"
            "jks", "keystore" -> "JKS"
            else -> KeyStore.getDefaultType()
        }
        val nextKeyStore = KeyStore.getInstance(keyStoreType)
        nextStoreFile.inputStream().use { nextKeyStore.load(it, requireNotNull(nextSigningValues["storePassword"]).toCharArray()) }
        val nextCertificate = nextKeyStore.getCertificate(requireNotNull(nextSigningValues["keyAlias"])) as? X509Certificate
        check(nextCertificate != null && !nextCertificate.subjectX500Principal.name.contains("Android Debug", ignoreCase = true)) {
            "Next Debug must use a non-debug signing certificate."
        }
        val nextCertificateSha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(requireNotNull(nextCertificate).encoded)
        )
        check(nextCertificateSha256 == yizheNextAndroidSigningCertificateSha256) {
            "Next Debug signing certificate does not match the approved Next test signer."
        }
        signingConfigs.create("nextDebug") {
            storeFile = nextStoreFile
            storePassword = nextSigningValues["storePassword"]
            keyAlias = nextSigningValues["keyAlias"]
            keyPassword = nextSigningValues["keyPassword"]
        }
    } else {
        null
    }

    val buildType = if (isEnterpriseBuild) "Enterprise" else "FOSS"
    logger.warnInBox("Building ${defaultConfig.applicationId} (${BuildTimeConfig.APPLICATION_NAME}) [$buildType]")

    buildTypes {
        val oAuthRedirectSchemeBase = BuildTimeConfig.METADATA_HOST_REVERSED ?: "io.element.android"
        getByName("debug") {
            resValue(
                "string",
                "login_redirect_scheme",
                "$oAuthRedirectSchemeBase.debug",
            )
            applicationIdSuffix = ".debug"
            signingConfig = nextDebugSigningConfig ?: signingConfigs.getByName("debug")
        }

        getByName("release") {
            resValue(
                "string",
                "login_redirect_scheme",
                oAuthRedirectSchemeBase,
            )
            // Never produce an internally distributed release with the public debug key.
            signingConfig = releaseSigningConfig

            optimization {
                enable = true
                keepRules {
                    files.add(File(projectDir, "common-proguard-rules.pro"))
                    files.add(getDefaultProguardFile("proguard-android-optimize.txt"))

                    // Depending on whether the app flavor is enterprise or not we want to use different proguard rules.
                    val flavorProguardFile = if (isEnterpriseBuild) {
                        // Custom rules for enterprise builds
                        File(projectDir, "enterprise-proguard-rules.pro")
                    } else {
                        // These default rules prevent the OSS app from being obfuscated
                        File(projectDir, "default-proguard-rules.pro")
                    }

                    if (flavorProguardFile.exists()) {
                        files.add(flavorProguardFile)
                    } else {
                        logger.warn("Proguard file ${flavorProguardFile.absolutePath} does not exist")
                    }
                }
            }
        }

        register("internalRelease") {
            applicationIdSuffix = ".internal"
            versionNameSuffix = "-internal"
            isDebuggable = false
            signingConfig = internalSigningConfig
            matchingFallbacks += listOf("release")
            // The public OSS release deliberately keeps readable class names via
            // default-proguard-rules.pro. Internal Release must be a genuinely
            // obfuscated R8 build, while retaining the same functional/security
            // keep rules and Android optimizer defaults as Release.
            optimization {
                enable = true
                keepRules {
                    files.add(File(projectDir, "common-proguard-rules.pro"))
                    files.add(getDefaultProguardFile("proguard-android-optimize.txt"))
                }
            }
            resValue(
                "string",
                "login_redirect_scheme",
                "$oAuthRedirectSchemeBase.internal",
            )
        }

        // Next Debug uses the proven startup optimization by default; explicit false remains available for debugging.
        // Debug identity, signing and BuildConfig.DEBUG stay unchanged.
        if (providers.gradleProperty("yizhe.startup.optimizeDebug").map(String::toBoolean).getOrElse(isNextBuildRequested)) {
            getByName("debug") {
                optimization {
                    enable = true
                    keepRules {
                        files.add(File(projectDir, "common-proguard-rules.pro"))
                        files.add(getDefaultProguardFile("proguard-android-optimize.txt"))
                        files.add(File(projectDir, "default-proguard-rules.pro"))
                    }
                }
            }
        }

        // Non-debuggable, debug-signed variant used for physical-device startup acceptance.
        // Keeping the debug application id/signature preserves the test account while allowing
        // AGP to package app/src/main/baseline-prof.txt into assets/dexopt.
        register("startupProfile") {
            initWith(getByName("debug"))
            isDebuggable = false
            matchingFallbacks += listOf("debug")
            versionNameSuffix = "-startup-profile"
        }

        register("nightly") {
            val release = getByName("release")
            initWith(release)
            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-nightly"
            resValue(
                "string",
                "login_redirect_scheme",
                "$oAuthRedirectSchemeBase.nightly",
            )
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("nightly")

            firebaseAppDistribution {
                artifactType = "APK"
                // We upload the universal APK to fix this error:
                // "App Distribution found more than 1 output file for this variant.
                // Please contact firebase-support@google.com for help using APK splits with App Distribution."
                artifactPath = "$rootDir/app/build/outputs/apk/gplay/nightly/app-gplay-universal-nightly.apk"
                // artifactType = "AAB"
                // artifactPath = "$rootDir/app/build/outputs/bundle/nightly/app-nightly.aab"
                releaseNotesFile = "tools/release/ReleaseNotesNightly.md"
                groups = if (isEnterpriseBuild) {
                    "enterprise-testers"
                } else {
                    "external-testers"
                }
                // This should not be required, but if I do not add the appId, I get this error:
                // "App Distribution halted because it had a problem uploading the APK: [404] Requested entity was not found."
                appId = if (isEnterpriseBuild) {
                    "1:912726360885:android:3f7e1fe644d99d5a00427c"
                } else {
                    "1:912726360885:android:e17435e0beb0303000427c"
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }
    flavorDimensions += "store"
    productFlavors {
        create("gplay") {
            dimension = "store"
            isDefault = true
            buildConfigFieldStr("SHORT_FLAVOR_DESCRIPTION", "G")
            buildConfigFieldStr("FLAVOR_DESCRIPTION", "GooglePlay")
        }
        create("fdroid") {
            dimension = "store"
            buildConfigFieldStr("SHORT_FLAVOR_DESCRIPTION", "F")
            buildConfigFieldStr("FLAVOR_DESCRIPTION", "FDroid")
        }
        if (isNextBuildRequested) {
            create("next") {
                dimension = "store"
                applicationId = "com.yizhe.chat.next"
                // Keep cumulative QA upgrades local to Next without invalidating every shared build plugin.
                versionCode = maxOf(Versions.VERSION_CODE, 20260687)
                versionName = if (Versions.VERSION_CODE < 20260687) "26.06.87" else Versions.VERSION_NAME
                signingConfig = nextDebugSigningConfig
                manifestPlaceholders.putAll(nextManifestValues)
                manifestPlaceholders["yizheUnifiedSourceManifestSha256"] = unifiedSourceManifestSha256
                buildConfigFieldStr("SHORT_FLAVOR_DESCRIPTION", "N")
                buildConfigFieldStr("FLAVOR_DESCRIPTION", "YiZheChat Next")
            }
        }
    }

    packaging {
        resources.pickFirsts += setOf(
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )

        jniLibs {
            useLegacyPackaging = project.findProperty("useLegacyPackaging")?.toString()?.toBoolean()
        }
    }
}

val verifyYiZheSecurityReleaseConfig = tasks.register("verifyYiZheSecurityReleaseConfig") {
    group = "verification"
    description = "Fails a production artifact build when security endpoints, pins, keys, or release signing are absent."
    notCompatibleWithConfigurationCache("Validates release-only Gradle properties, environment variables, and the signing file at execution time.")
    doLast {
        val requiredProperties = listOf(
            "yizhe.control.baseUrl",
            "yizhe.control.signingKeyDerB64",
            "yizhe.control.signingKeyId",
            "yizhe.control.certificatePin",
            "yizhe.kms.baseUrl",
            "yizhe.kms.signingKeyDerB64",
            "yizhe.kms.certificatePins",
            "yizhe.kms.maximumOfflineLeaseMillis",
            "yizhe.kms.maximumOfflineLicenseUses",
            "yizhe.kms.clockRollbackToleranceMillis",
            "yizhe.officePreview.baseUrl",
            "yizhe.officePreview.certificatePin",
        )
        val missingProperties = requiredProperties.filter { providers.gradleProperty(it).orNull.isNullOrBlank() }
        check(missingProperties.isEmpty()) { "Missing YiZheChat production security properties: ${missingProperties.joinToString()}" }
        listOf("yizhe.control.baseUrl", "yizhe.kms.baseUrl").forEach { name ->
            check(providers.gradleProperty(name).get() == "https://security.yizhesy.cn/") {
                "$name must use the isolated production security origin."
            }
        }
        check(providers.gradleProperty("yizhe.officePreview.baseUrl").get() == "https://www.yizhesy.cn/api/mobile/office-previews") {
            "yizhe.officePreview.baseUrl must use the approved self-hosted production endpoint."
        }

        fun validatePin(pin: String, name: String): String {
            val normalized = pin.trim()
            check(normalized.startsWith("sha256/")) { "$name must contain only SHA-256 SPKI pins." }
            val digest = runCatching { Base64.getDecoder().decode(normalized.removePrefix("sha256/")) }.getOrNull()
            check(digest?.size == 32) { "$name contains a malformed SHA-256 SPKI pin." }
            return normalized
        }
        val controlPin = validatePin(providers.gradleProperty("yizhe.control.certificatePin").get(), "yizhe.control.certificatePin")
        validatePin(providers.gradleProperty("yizhe.officePreview.certificatePin").get(), "yizhe.officePreview.certificatePin")
        val kmsPins = providers.gradleProperty("yizhe.kms.certificatePins").get().split(',').map {
            validatePin(it, "yizhe.kms.certificatePins")
        }
        check(kmsPins.size >= 2 && kmsPins.toSet().size == kmsPins.size) {
            "yizhe.kms.certificatePins must contain distinct current and backup pins."
        }
        check(controlPin in kmsPins) { "The control-plane pin must be present in the KMS pin set." }

        val controlSigningKey = providers.gradleProperty("yizhe.control.signingKeyDerB64").get()
        val kmsSigningKey = providers.gradleProperty("yizhe.kms.signingKeyDerB64").get()
        check(controlSigningKey == kmsSigningKey && !controlSigningKey.contains("replace", ignoreCase = true)) {
            "Control and KMS must pin the same non-placeholder signing public key."
        }
        val signingKeyBytes = runCatching { Base64.getDecoder().decode(controlSigningKey) }.getOrNull()
        val signingKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(requireNotNull(signingKeyBytes)))
        }.getOrNull() as? ECPublicKey
        check(signingKey?.params?.curve?.field?.fieldSize == 256) {
            "The control-plane signing key must be a valid P-256 X.509 public key."
        }
        val signingKeyId = providers.gradleProperty("yizhe.control.signingKeyId").get()
        check(signingKeyId.matches(Regex("[A-Za-z0-9._:-]{8,128}")) && !signingKeyId.contains("replace", ignoreCase = true)) {
            "yizhe.control.signingKeyId is malformed or still a placeholder."
        }
        val derivedSigningKeyId = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(requireNotNull(signingKeyBytes)),
        ).take(16)
        check(signingKeyId == derivedSigningKeyId) {
            "yizhe.control.signingKeyId must equal the SHA-256-derived control-plane public key id."
        }
        check(signingKeyId == yizheAuthoritySigningKeyId) {
            "Release signing key id does not match the repository-pinned current server authority. " +
                "Verify /v1/public/signing-key and update the trust anchor only as an explicit key rotation."
        }

        val maximumOfflineLeaseMillis = providers.gradleProperty("yizhe.kms.maximumOfflineLeaseMillis").get().toLongOrNull()
        val maximumOfflineUses = providers.gradleProperty("yizhe.kms.maximumOfflineLicenseUses").get().toIntOrNull()
        val clockRollbackToleranceMillis = providers.gradleProperty("yizhe.kms.clockRollbackToleranceMillis").get().toLongOrNull()
        check(maximumOfflineLeaseMillis != null && maximumOfflineLeaseMillis in 1..86_400_000) {
            "The production offline lease must be between 1 ms and 24 hours."
        }
        check(maximumOfflineUses != null && maximumOfflineUses in 1..16) {
            "The production offline license must allow between 1 and 16 uses."
        }
        check(clockRollbackToleranceMillis != null && clockRollbackToleranceMillis in 0..120_000) {
            "The production clock rollback tolerance must be between 0 and 2 minutes."
        }

        val releaseSigningValues = mapOf(
            "storeFile" to providers.environmentVariable("YIZHE_RELEASE_STORE_FILE").orNull,
            "storePassword" to providers.environmentVariable("YIZHE_RELEASE_STORE_PASSWORD").orNull,
            "keyAlias" to providers.environmentVariable("YIZHE_RELEASE_KEY_ALIAS").orNull,
            "keyPassword" to providers.environmentVariable("YIZHE_RELEASE_KEY_PASSWORD").orNull,
        )
        val missingSigning = releaseSigningValues.filterValues { it.isNullOrBlank() }.keys
        check(missingSigning.isEmpty()) { "Release signing is incomplete: ${missingSigning.joinToString()}" }
        val signingFile = file(requireNotNull(releaseSigningValues["storeFile"]))
        check(signingFile.isFile) { "YIZHE_RELEASE_STORE_FILE does not exist." }
        check(signingFile.name != "debug.keystore") { "The Android debug keystore cannot sign a production release." }
        val keyAlias = requireNotNull(releaseSigningValues["keyAlias"])
        check(!keyAlias.equals("androiddebugkey", ignoreCase = true)) { "The Android debug alias cannot sign a production release." }
        val keyStoreType = when (signingFile.extension.lowercase()) {
            "p12", "pfx" -> "PKCS12"
            "jks", "keystore" -> "JKS"
            else -> KeyStore.getDefaultType()
        }
        val keyStore = KeyStore.getInstance(keyStoreType)
        signingFile.inputStream().use {
            keyStore.load(it, requireNotNull(releaseSigningValues["storePassword"]).toCharArray())
        }
        check(keyStore.isKeyEntry(keyAlias)) { "The configured release alias is not a private-key entry." }
        val certificate = keyStore.getCertificate(keyAlias) as? X509Certificate
        check(certificate != null && !certificate.subjectX500Principal.name.contains("Android Debug", ignoreCase = true)) {
            "An Android debug certificate cannot sign a production release."
        }
        certificate.checkValidity()
        check(certificate.notAfter.time - System.currentTimeMillis() >= 365L * 24 * 60 * 60 * 1000) {
            "The production Android signing certificate must remain valid for at least one year."
        }
        check(keyStore.getKey(keyAlias, requireNotNull(releaseSigningValues["keyPassword"]).toCharArray()) != null) {
            "The production release key cannot be opened with the configured key password."
        }
    }

}

val verifyYiZheInternalReleaseConfig = tasks.register("verifyYiZheInternalReleaseConfig") {
    group = "verification"
    description = "Fails an Internal Release build when its public security configuration or independent signing identity is absent."
    notCompatibleWithConfigurationCache("Validates Internal Release Gradle properties, environment variables, and signing material.")
    doLast {
        val requiredProperties = listOf(
            "yizhe.control.baseUrl",
            "yizhe.control.signingKeyDerB64",
            "yizhe.control.signingKeyId",
            "yizhe.control.certificatePin",
            "yizhe.kms.baseUrl",
            "yizhe.kms.signingKeyDerB64",
            "yizhe.kms.certificatePins",
            "yizhe.kms.maximumOfflineLeaseMillis",
            "yizhe.kms.maximumOfflineLicenseUses",
            "yizhe.kms.clockRollbackToleranceMillis",
            "yizhe.officePreview.baseUrl",
            "yizhe.officePreview.certificatePin",
        )
        val missingProperties = requiredProperties.filter { providers.gradleProperty(it).orNull.isNullOrBlank() }
        check(missingProperties.isEmpty()) {
            "Missing YiZheChat Internal Release security properties: ${missingProperties.joinToString()}"
        }
        listOf("yizhe.control.baseUrl", "yizhe.kms.baseUrl").forEach { name ->
            check(providers.gradleProperty(name).get().startsWith("https://")) {
                "$name must use HTTPS for Internal Release."
            }
        }
        check(providers.gradleProperty("yizhe.officePreview.baseUrl").get().startsWith("https://")) {
            "yizhe.officePreview.baseUrl must use HTTPS for Internal Release."
        }
        val controlSigningKey = providers.gradleProperty("yizhe.control.signingKeyDerB64").get()
        val kmsSigningKey = providers.gradleProperty("yizhe.kms.signingKeyDerB64").get()
        check(controlSigningKey == kmsSigningKey) {
            "Internal Release control and KMS must pin the same signing public key."
        }
        val signingKeyBytes = runCatching { Base64.getDecoder().decode(controlSigningKey) }.getOrNull()
        val signingKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(requireNotNull(signingKeyBytes)))
        }.getOrNull() as? ECPublicKey
        check(signingKey?.params?.curve?.field?.fieldSize == 256) {
            "Internal Release must pin a valid P-256 control-plane signing key."
        }
        val derivedSigningKeyId = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(requireNotNull(signingKeyBytes)),
        ).take(16)
        check(providers.gradleProperty("yizhe.control.signingKeyId").get() == derivedSigningKeyId) {
            "Internal Release signing key id does not match the pinned public key."
        }
        check(derivedSigningKeyId == yizheAuthoritySigningKeyId) {
            "Internal Release signing key id does not match the repository-pinned current server authority. " +
                "Verify /v1/public/signing-key and update the trust anchor only as an explicit key rotation."
        }
        val pins = buildList {
            add(providers.gradleProperty("yizhe.control.certificatePin").get())
            addAll(providers.gradleProperty("yizhe.kms.certificatePins").get().split(','))
            add(providers.gradleProperty("yizhe.officePreview.certificatePin").get())
        }.map(String::trim).filter(String::isNotEmpty)
        check(pins.isNotEmpty() && pins.all { pin ->
            pin.startsWith("sha256/") && runCatching {
                Base64.getDecoder().decode(pin.removePrefix("sha256/")).size == 32
            }.getOrDefault(false)
        }) {
            "Internal Release contains a malformed SHA-256 SPKI pin."
        }
        val maximumOfflineLeaseMillis = providers.gradleProperty("yizhe.kms.maximumOfflineLeaseMillis").get().toLongOrNull()
        val maximumOfflineUses = providers.gradleProperty("yizhe.kms.maximumOfflineLicenseUses").get().toIntOrNull()
        val clockRollbackToleranceMillis = providers.gradleProperty("yizhe.kms.clockRollbackToleranceMillis").get().toLongOrNull()
        check(maximumOfflineLeaseMillis != null && maximumOfflineLeaseMillis in 1..86_400_000)
        check(maximumOfflineUses != null && maximumOfflineUses in 1..16)
        check(clockRollbackToleranceMillis != null && clockRollbackToleranceMillis in 0..120_000)

        val internalSigningValues = mapOf(
            "storeFile" to providers.environmentVariable("YIZHE_INTERNAL_STORE_FILE").orNull,
            "storePassword" to providers.environmentVariable("YIZHE_INTERNAL_STORE_PASSWORD").orNull,
            "keyAlias" to providers.environmentVariable("YIZHE_INTERNAL_KEY_ALIAS").orNull,
            "keyPassword" to providers.environmentVariable("YIZHE_INTERNAL_KEY_PASSWORD").orNull,
        )
        val missingSigning = internalSigningValues.filterValues { it.isNullOrBlank() }.keys
        check(missingSigning.isEmpty()) { "Internal Release signing is incomplete: ${missingSigning.joinToString()}" }
        val signingFile = file(requireNotNull(internalSigningValues["storeFile"]))
        check(signingFile.isFile && signingFile.name != "debug.keystore") {
            "Internal Release must use its own non-debug signing file."
        }
        providers.environmentVariable("YIZHE_RELEASE_STORE_FILE").orNull?.takeIf(String::isNotBlank)?.let { productionPath ->
            check(file(productionPath).canonicalFile != signingFile.canonicalFile) {
                "Internal Release cannot reuse the production signing file."
            }
        }
        val keyAlias = requireNotNull(internalSigningValues["keyAlias"])
        check(!keyAlias.equals("androiddebugkey", ignoreCase = true)) {
            "Internal Release cannot use the Android debug alias."
        }
        val keyStore = KeyStore.getInstance("PKCS12")
        signingFile.inputStream().use {
            keyStore.load(it, requireNotNull(internalSigningValues["storePassword"]).toCharArray())
        }
        check(keyStore.isKeyEntry(keyAlias)) { "Internal Release alias is not a private-key entry." }
        val certificate = keyStore.getCertificate(keyAlias) as? X509Certificate
        check(certificate != null && certificate.subjectX500Principal.name.contains("YiZheChat Internal Release")) {
            "Internal Release certificate identity is missing or incorrect."
        }
        check(!certificate.subjectX500Principal.name.contains("Android Debug", ignoreCase = true))
        certificate.checkValidity()
        check(keyStore.getKey(keyAlias, requireNotNull(internalSigningValues["keyPassword"]).toCharArray()) != null) {
            "Internal Release private key cannot be opened."
        }
    }
}

tasks.configureEach {
    if (name.startsWith("assemble") || name.startsWith("bundle")) {
        when {
            name.endsWith("InternalRelease") -> dependsOn(verifyYiZheInternalReleaseConfig)
            name.endsWith("Release") -> dependsOn(verifyYiZheSecurityReleaseConfig)
        }
    }
}

androidComponents {
    val requestedBuildAbi = providers.gradleProperty("yizhe.buildAbi").orNull

    // map for the version codes last digit
    // x86 must have greater values than arm
    // 64 bits have greater value than 32 bits
    val abiVersionCodes = mapOf(
        "armeabi-v7a" to 1,
        "arm64-v8a" to 2,
        "x86" to 3,
        "x86_64" to 4,
    )

    onVariants { variant ->
        // Assigns a different version code for each output APK
        // other than the universal APK.
        variant.outputs.forEach { output ->
            // A single-ABI build disables APK splitting, so the output itself has no ABI filter.
            // Keep the ABI suffix in the version code so it can replace the equivalent split APK.
            val name = output.filters.find { it.filterType == ABI }?.identifier ?: requestedBuildAbi

            // Stores the value of abiCodes that is associated with the ABI for this variant.
            val abiCode = abiVersionCodes[name] ?: 0
            // Assigns the new version code to output.versionCode, which changes the version code
            // for only the output APK, not for the variant itself.
            output.versionCode.set((output.versionCode.orNull ?: 0) * 10 + abiCode)
        }
    }

    val reportingExtension: ReportingExtension = project.extensions.getByType(ReportingExtension::class.java)
    configureLicensesTasks(reportingExtension)
}

setupDependencyInjection()

// These combined configurations are declared explicitly so the Huawei provider is limited to
// the signed Google Play release variants that have matching AG Connect applications.
val gplayReleaseImplementation by configurations.creating
val gplayInternalReleaseImplementation by configurations.creating

dependencies {
    allLibrariesImpl()
    allServicesImpl()
    if (isEnterpriseBuild) {
        allEnterpriseImpl(project)
        implementation(projects.appicon.enterprise)
    } else {
        implementation(projects.features.enterprise.implFoss)
        implementation(projects.appicon.yizhe)
    }
    allFeaturesImpl(project)
    implementation(projects.features.migration.api)
    implementation(projects.appnav)
    implementation(projects.appconfig)
    implementation(projects.libraries.uiStrings)
    implementation(projects.security.contentProtection.impl)
    implementation(projects.security.dataProtection.impl)
    implementation(projects.security.secureStorage.impl)
    implementation(projects.services.analytics.compose)

    if (ModulesConfig.pushProvidersConfig.includeFirebase) {
        "gplayImplementation"(projects.libraries.pushproviders.firebase)
    }
    if (ModulesConfig.pushProvidersConfig.includeUnifiedPush) {
    implementation(projects.libraries.pushproviders.unifiedpush)
    implementation(projects.libraries.pushproviders.api)
    }
    implementation(libs.appyx.core)
    implementation(libs.androidx.splash)
    implementation(libs.androidx.core)
    implementation(libs.androidx.corektx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.preference)
    implementation(libs.coil)

    implementation(platform(libs.network.okhttp.bom))
    implementation(libs.network.okhttp.logging)
    implementation(libs.serialization.json)

    implementation(libs.matrix.emojibase.bindings)

    testCommonDependencies(libs)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.services.toolbox.test)

    gplayReleaseImplementation(projects.libraries.pushproviders.huawei)
    gplayInternalReleaseImplementation(projects.libraries.pushproviders.huawei)
}

tasks.register("verifyHuaweiVariantIsolation") {
    group = "verification"
    description = "Resolves release variant graphs and fails if Huawei AGC/HMS escapes configured release variants."
    doLast {
        val expectedAgcTasks = setOf(
            "processGplayReleaseAGCPlugin",
            "processGplayInternalReleaseAGCPlugin",
        )
        val actualAgcTasks = tasks.names.filterTo(mutableSetOf()) { it.endsWith("AGCPlugin") }
        check(actualAgcTasks == expectedAgcTasks) {
            "Unexpected AG Connect task isolation: expected=$expectedAgcTasks actual=$actualAgcTasks"
        }

        val rootConfig = file("agconnect-services.json")
        check(!rootConfig.exists()) { "AG Connect configuration must not be placed in the app root source set." }

        val configuredVariants = linkedMapOf(
            "gplayRelease" to "com.yizhe.chat",
            "gplayInternalRelease" to "com.yizhe.chat.internal",
        )
        configuredVariants.forEach { (variant, expectedPackage) ->
            val variantConfig = file("src/$variant/agconnect-services.json")
            check(variantConfig.isFile) { "$variant AG Connect configuration is missing." }
            val variantConfigText = variantConfig.readText()
            val configuredPackages = Regex("\\\"package_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .findAll(variantConfigText)
                .map { it.groupValues[1] }
                .toSet()
            check(configuredPackages == setOf(expectedPackage)) {
                "$variant AG Connect package mismatch: expected=$expectedPackage actual=$configuredPackages"
            }
            check(!Regex("\\\"(client_secret|api_key)\\\"\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(variantConfigText)) {
                "$variant AG Connect configuration contains a key that must remain server-side."
            }
        }

        val expectations = linkedMapOf(
            "gplayRelease" to true,
            "gplayInternalRelease" to true,
            "fdroidRelease" to false,
        )
        expectations.forEach { (variant, expectsHuawei) ->
            val componentIds = configurations.getByName("${variant}RuntimeClasspath")
                .incoming.resolutionResult.allComponents.map { it.id }
            val hasHuaweiProvider = componentIds.any {
                it is ProjectComponentIdentifier && it.projectPath == ":libraries:pushproviders:huawei"
            }
            val hasHmsPush = componentIds.any {
                it is ModuleComponentIdentifier && it.group == "com.huawei.hms" && it.module == "push"
            }
            check(hasHuaweiProvider == expectsHuawei) {
                "$variant Huawei provider isolation mismatch: expected=$expectsHuawei actual=$hasHuaweiProvider"
            }
            check(hasHmsPush == expectsHuawei) {
                "$variant HMS Push isolation mismatch: expected=$expectsHuawei actual=$hasHmsPush"
            }
            println(
                "HUAWEI_VARIANT_ISOLATION variant=$variant " +
                    "agc=${variant in configuredVariants} json=${variant in configuredVariants} " +
                    "provider=$hasHuaweiProvider hmsPush=$hasHmsPush",
            )
        }
        println("HUAWEI_AGC_TASKS ${actualAgcTasks.sorted().joinToString()}")
    }
}

tasks.withType<GenerateBuildConfig>().configureEach {
    outputs.upToDateWhen { false }
    val gitRevision = providers.of(GitRevisionValueSource::class.java) {}.get()
    val gitBranchName = providers.of(GitBranchNameValueSource::class.java) {}.get()
    android.defaultConfig.buildConfigFieldStr("GIT_REVISION", gitRevision)
    android.defaultConfig.buildConfigFieldStr("GIT_BRANCH_NAME", gitBranchName)
}

licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    allow("EPL-1.0")
    allowUrl("https://opensource.org/license/bsd-3-clause")
    allowUrl("https://opensource.org/licenses/MIT")
    allowUrl("https://developer.android.com/studio/terms.html")
    allowUrl("https://www.zetetic.net/sqlcipher/license/")
    allowUrl("https://jsoup.org/license")
    allowUrl("https://asm.ow2.io/license.html")
    allowUrl("https://www.gnu.org/licenses/agpl-3.0.txt")
    allowUrl("https://github.com/mhssn95/compose-color-picker/blob/main/LICENSE")
    ignoreDependencies("com.github.matrix-org", "matrix-analytics-events")
    // Huawei HMS artifacts do not publish license metadata in their Maven POMs.
    // Keep these explicit vendor-group exceptions auditable instead of disabling Licensee validation.
    ignoreDependencies(groupId = "com.huawei.agconnect")
    ignoreDependencies(groupId = "com.huawei.android.hms")
    ignoreDependencies(groupId = "com.huawei.hmf")
    ignoreDependencies(groupId = "com.huawei.hms")
    // Ignore dependency that are not third-party licenses to us.
    ignoreDependencies(groupId = "io.element.android")
}

fun Project.configureLicensesTasks(reportingExtension: ReportingExtension) {
    androidComponents {
        onVariants { variant ->
            val capitalizedVariantName = variant.name.replaceFirstChar {
                if (it.isLowerCase()) {
                    it.titlecase(Locale.getDefault())
                } else {
                    it.toString()
                }
            }
            val artifactsFile = reportingExtension.baseDirectory.file("licensee/android$capitalizedVariantName/artifacts.json")

            val copyArtifactsTask =
                project.tasks.register<AssetCopyTask>("copy${capitalizedVariantName}LicenseeReportToAssets") {
                    inputFile.set(artifactsFile)
                    targetFileName.set("licensee-artifacts.json")
                }
            variant.sources.assets?.addGeneratedSourceDirectory(
                copyArtifactsTask,
                AssetCopyTask::outputDirectory,
            )
            copyArtifactsTask.dependsOn("licenseeAndroid$capitalizedVariantName")
        }
    }
}

configurations.all {
    resolutionStrategy {
        dependencySubstitution {
            val tink = libs.google.tink.get()
            substitute(module("com.google.crypto.tink:tink")).using(module("${tink.group}:${tink.name}:${tink.version}"))
        }
    }
}
