/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.securestorage.api.DeviceRiskSignal
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

data class DeviceAttestationKeyDescriptor(
    val publicKeySha256: ByteArray,
    val certificateChainDer: List<ByteArray>,
    val hardwareSecurityLevel: HardwareSecurityLevel,
    val strongBoxRequested: Boolean,
) {
    val hasHardwareAttestation: Boolean = certificateChainDer.size > 1 &&
        (hardwareSecurityLevel == HardwareSecurityLevel.STRONGBOX ||
            hardwareSecurityLevel == HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT)
}

interface DeviceAttestationBackend {
    fun load(alias: String): DeviceAttestationKeyDescriptor?

    fun create(alias: String, attestationChallenge: ByteArray): DeviceAttestationKeyDescriptor

    fun sign(alias: String, statement: ByteArray): ByteArray

    fun destroy(alias: String): Boolean
}

data class DevicePlatformAssessment(
    val packageName: String,
    val appVersionCode: Long,
    val firstInstallTimeEpochMillis: Long,
    val signingCertificateSha256: List<ByteArray>,
    val riskSignals: Set<DeviceRiskSignal>,
)

interface DevicePlatformSignalSource {
    fun assess(): DevicePlatformAssessment
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidDeviceAttestationBackend(
    @ApplicationContext private val context: Context,
) : DeviceAttestationBackend {
    private val keyStores = ReloadingAndroidKeyStore()

    override fun load(alias: String): DeviceAttestationKeyDescriptor? {
        val loaded = keyStores.read { keyStore ->
            val privateKey = keyStore.getKey(alias, null) ?: return@read null
            val chain = keyStore.getCertificateChain(alias)?.toList().orEmpty()
            LoadedAttestationKey(privateKey, chain).takeIf { chain.isNotEmpty() }
        } ?: return null
        val privateKey = loaded.privateKey
        val chain = loaded.certificateChain
        val leafPublicKey = chain.firstOrNull()?.publicKey ?: return null
        val descriptor = DeviceAttestationKeyDescriptor(
            publicKeySha256 = sha256(leafPublicKey.encoded),
            certificateChainDer = chain.map { it.encoded },
            hardwareSecurityLevel = hardwareSecurityLevel(privateKey),
            strongBoxRequested = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                hardwareSecurityLevel(privateKey) == HardwareSecurityLevel.STRONGBOX,
        )
        return descriptor
    }

    override fun create(alias: String, attestationChallenge: ByteArray): DeviceAttestationKeyDescriptor {
        load(alias)?.let { return it }
        val requestStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (requestStrongBox) {
            try {
                generate(alias, attestationChallenge, strongBox = true, includeAttestation = true)
                return requireNotNull(load(alias)).copy(strongBoxRequested = true)
            } catch (_: Exception) {
                // OEMs can surface StrongBox exhaustion/provider defects as ProviderException or
                // KeyStoreException wrappers instead of StrongBoxUnavailableException.
                destroy(alias)
            }
        }
        try {
            generate(alias, attestationChallenge, strongBox = false, includeAttestation = true)
        } catch (_: Exception) {
            // Some OEM Android Keystore providers do not implement key attestation correctly. Generate a stable
            // signing key, but expose the missing attestation chain as a risk signal instead of claiming trust.
            destroy(alias)
            generate(alias, attestationChallenge, strongBox = false, includeAttestation = false)
        }
        return requireNotNull(load(alias))
    }

    override fun sign(alias: String, statement: ByteArray): ByteArray {
        val privateKey = keyStores.read { it.getKey(alias, null) as? java.security.PrivateKey }
            ?: throw SecureStorageKeyUnavailableException()
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(statement)
            sign()
        }
    }

    override fun destroy(alias: String): Boolean = try {
        keyStores.read { keyStore ->
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            (!keyStore.containsAlias(alias)).takeIf { it }
        } == true
    } catch (_: Exception) {
        false
    }

    private fun generate(alias: String, challenge: ByteArray, strongBox: Boolean, includeAttestation: Boolean) {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
        if (includeAttestation) builder.setAttestationChallenge(challenge)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(strongBox)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(builder.build())
            generateKeyPair()
        }
    }

    @Suppress("DEPRECATION")
    private fun hardwareSecurityLevel(key: java.security.Key): HardwareSecurityLevel = try {
        val keyInfo = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> HardwareSecurityLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT
                // Android defines UNKNOWN_SECURE as being at least equivalent to a TEE.
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> HardwareSecurityLevel.SOFTWARE
                else -> HardwareSecurityLevel.UNKNOWN
            }
        } else if (keyInfo.isInsideSecureHardware) {
            HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT
        } else {
            HardwareSecurityLevel.SOFTWARE
        }
    } catch (_: Exception) {
        HardwareSecurityLevel.UNKNOWN
    }

    private data class LoadedAttestationKey(
        val privateKey: java.security.Key,
        val certificateChain: List<java.security.cert.Certificate>,
    )

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val EC_CURVE = "secp256r1"
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidDevicePlatformSignalSource(
    @ApplicationContext private val context: Context,
) : DevicePlatformSignalSource {
    @Suppress("DEPRECATION")
    override fun assess(): DevicePlatformAssessment {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signingCertificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageInfo.signatures.orEmpty()
        }
        val appDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val risks = buildSet {
            if (appDebuggable) add(DeviceRiskSignal.DEBUGGABLE_APP)
            if (Build.TAGS?.contains("test-keys") == true) add(DeviceRiskSignal.TEST_KEYS_BUILD)
            if (!appDebuggable && (Debug.isDebuggerConnected() || Debug.waitingForDebugger())) {
                add(DeviceRiskSignal.DEBUGGER_ATTACHED)
            }
            if (ROOT_ARTIFACT_PATHS.any { File(it).exists() }) add(DeviceRiskSignal.ROOT_ARTIFACT_DETECTED)
            if (signingCertificates.isEmpty()) add(DeviceRiskSignal.APP_SIGNING_IDENTITY_UNAVAILABLE)
        }
        return DevicePlatformAssessment(
            packageName = context.packageName,
            appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong(),
            firstInstallTimeEpochMillis = packageInfo.firstInstallTime,
            signingCertificateSha256 = signingCertificates.map { sha256(it.toByteArray()) }.sortedBy(ByteArray::toHex),
            riskSignals = risks,
        )
    }

    private companion object {
        val ROOT_ARTIFACT_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/bin/su",
            "/data/local/xbin/su",
            "/system/app/Superuser.apk",
        )
    }
}
