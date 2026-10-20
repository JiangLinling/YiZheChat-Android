/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** One current base APK, not downloaded bytes, release labels, or signing-history certificates. */
internal data class NextInstalledPackageSnapshot(
    val packageName: String,
    val versionCode: Long,
    val canonicalApkPath: String,
    val splitApkPaths: List<String>,
    val currentSignerSha256: List<String>,
    val lastUpdateTime: Long,
    val byteLength: Long,
    val lastModifiedTime: Long,
)

internal interface NextInstalledPackageSource {
    fun snapshot(): NextInstalledPackageSnapshot
    fun openBaseApk(snapshot: NextInstalledPackageSnapshot): InputStream
}

/** Always reads this process's own application package. No caller-supplied package/path is accepted. */
internal class AndroidNextInstalledPackageSource(context: Context) : NextInstalledPackageSource {
    private val application = context.applicationContext

    override fun snapshot(): NextInstalledPackageSnapshot {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { "Current signer inspection is unavailable." }
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.packageManager.getPackageInfo(application.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            application.packageManager.getPackageInfo(application.packageName, flags)
        }
        val app = checkNotNull(info.applicationInfo)
        check(info.packageName == application.packageName && app.uid == application.applicationInfo.uid)
        val file = File(checkNotNull(app.sourceDir)).canonicalFile
        check(file == File(application.applicationInfo.sourceDir).canonicalFile) { "Running package source changed." }
        check(file.isFile && file.canRead()) { "Installed base APK cannot be read." }
        return NextInstalledPackageSnapshot(
            packageName = info.packageName,
            versionCode = info.longVersionCode,
            canonicalApkPath = file.path,
            splitApkPaths = app.splitSourceDirs.orEmpty().toList(),
            currentSignerSha256 = info.signingInfo?.apkContentsSigners.orEmpty().map { installedDigest(it.toByteArray()) },
            lastUpdateTime = info.lastUpdateTime,
            byteLength = file.length(),
            lastModifiedTime = file.lastModified(),
        )
    }

    override fun openBaseApk(snapshot: NextInstalledPackageSnapshot): InputStream {
        check(snapshot == this.snapshot()) { "Installed package changed before reading." }
        return File(snapshot.canonicalApkPath).inputStream()
    }
}

/** Constructible only after a signed current-version manifest matches the observed installed bytes. */
internal class VerifiedNextInstalledArtifact private constructor(
    private val packageName: String,
    private val versionCode: Long,
    private val apkSha256: String,
    private val signerSha256: String,
    private val buildId: String,
) {
    fun claim(operationId: String, installationId: String, claimNonce: String): NextInstalledArtifactClaim =
        NextInstalledArtifactClaim(operationId, installationId, packageName, versionCode, apkSha256, signerSha256, buildId, claimNonce)
            .also { it.payload() }

    companion object {
        fun inspect(
            source: NextInstalledPackageSource,
            signedPublishedRelease: JsonObject,
            trustedNowMs: Long,
            verifier: ManagementSignatureVerifier,
        ): VerifiedNextInstalledArtifact {
            check(trustedNowMs > 0)
            check(signedPublishedRelease.keys == setOf("release", "key_id", "signature_b64"))
            val payload = signedPublishedRelease["release"] as? JsonObject ?: error("Release payload is unavailable.")
            check(payload.keys == RELEASE_FIELDS)
            val envelope = SignedJsonEnvelopeWire(
                release = payload,
                keyId = signedPublishedRelease.requiredInstalledString("key_id"),
                signatureBase64 = signedPublishedRelease.requiredInstalledString("signature_b64"),
            )
            check(verifier.verify(envelope)) { "Published release signature is invalid." }
            // Reject numeric strings even when a serializer could coerce them.
            listOf("schema_version", "version_code", "issued_at_epoch_millis", "expires_at_epoch_millis").forEach { key ->
                val number = payload[key] as? JsonPrimitive ?: error("Invalid release number.")
                check(!number.isString && number.content.matches(Regex("0|[1-9][0-9]*")))
            }
            val release = Json.decodeFromJsonElement(AppReleaseWire.serializer(), payload)
            check(release.schemaVersion == 2 && release.versionCode > 0)
            check(release.apkSha256.matches(INSTALLED_SHA256) && release.signingCertificateSha256.matches(INSTALLED_SHA256))
            check(release.buildId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")))
            check(release.issuedAtEpochMillis in 1..trustedNowMs && release.expiresAtEpochMillis > trustedNowMs)
            val before = source.snapshot().ownedSnapshot()
            check(before.splitApkPaths.isEmpty()) { "Split APK identity requires a separately proven contract." }
            check(before.currentSignerSha256.size == 1) { "Exactly one current APK signer is required." }
            check(before.packageName == release.packageName && before.versionCode == release.versionCode)
            check(before.currentSignerSha256.single() == release.signingCertificateSha256)
            check(before.canonicalApkPath.isNotBlank() && before.byteLength in 1..MAX_BASE_APK_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            var consumed = 0L
            source.openBaseApk(before).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(count > 0) { "Installed APK read made no progress." }
                    consumed += count
                    check(consumed <= before.byteLength) { "Installed APK grew during inspection." }
                    digest.update(buffer, 0, count)
                }
            }
            check(consumed == before.byteLength) { "Installed APK read was incomplete." }
            check(source.snapshot().ownedSnapshot() == before) { "Installed package changed during inspection." }
            val observedDigest = digest.digest().installedHex()
            check(observedDigest == release.apkSha256) { "Installed bytes differ from the signed published artifact." }
            return VerifiedNextInstalledArtifact(
                before.packageName,
                before.versionCode,
                observedDigest,
                before.currentSignerSha256.single(),
                release.buildId
            )
        }
    }
}

private fun NextInstalledPackageSnapshot.ownedSnapshot() = copy(splitApkPaths = splitApkPaths.toList(), currentSignerSha256 = currentSignerSha256.toList())
private fun JsonObject.requiredInstalledString(key: String): String {
    val value = this[key] as? JsonPrimitive ?: error("Invalid release signature field.")
    check(value.isString && value.content.isNotBlank())
    return value.content
}
private fun installedDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).installedHex()
private fun ByteArray.installedHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
private val INSTALLED_SHA256 = Regex("[0-9a-f]{64}")
private const val MAX_BASE_APK_BYTES = 2L * 1024 * 1024 * 1024
private val RELEASE_FIELDS = setOf(
    "schema_version",
    "version_code",
    "version_name",
    "package_name",
    "apk_url",
    "apk_fallback_url",
    "apk_sha256",
    "build_id",
    "signing_certificate_sha256",
    "release_notes",
    "mandatory",
    "issued_at_epoch_millis",
    "expires_at_epoch_millis"
)
