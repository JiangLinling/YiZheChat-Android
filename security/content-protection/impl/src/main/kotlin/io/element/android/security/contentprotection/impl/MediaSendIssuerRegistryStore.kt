/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.contentprotection.api.MediaSendAuthorityConfigurationProvider
import io.element.android.security.contentprotection.api.MediaSendIssuerKey
import io.element.android.security.contentprotection.api.MediaSendIssuerKeyStatus
import io.element.android.security.contentprotection.api.MediaSendIssuerRegistryRepository
import io.element.android.security.contentprotection.api.MediaSendTrustedTime
import io.element.android.security.contentprotection.api.VerifiedMediaSendIssuerRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MediaSendIssuerRegistryStore(
    private val configurationProvider: MediaSendAuthorityConfigurationProvider,
    private val durableStore: DurableMediaSendStateStore,
) : MediaSendIssuerRegistryRepository {
    @Volatile private var freshlyVerifiedEnvelopeSha256: String? = null

    override suspend fun installSignedRegistry(
        rawEnvelope: ByteArray,
        trustedTime: MediaSendTrustedTime,
    ): Result<VerifiedMediaSendIssuerRegistry> = runCatching {
        val registry = parseAndVerifySignedEnvelope(rawEnvelope)
        requireFreshAt(registry, trustedTime)
        val installed = durableStore.withLockedState(NAMESPACE, KEY) { currentState, commit ->
            val current = currentState?.let(::decodeAndVerifyStored)
            if (current != null) {
                require(registry.revision >= current.revision)
                if (registry.revision == current.revision) {
                    require(registry.signedEnvelopeSha256 == current.signedEnvelopeSha256)
                    return@withLockedState current
                }
                requireValidKeyTransitions(current, registry)
            }
            commit(DurableMediaSendStateStore.StoredState(registry.revision, registry.signedEnvelopeSha256, rawEnvelope.copyOf()))
            registry
        }
        freshlyVerifiedEnvelopeSha256 = installed.signedEnvelopeSha256
        installed
    }

    override suspend fun currentVerifiedRegistry(trustedTime: MediaSendTrustedTime): Result<VerifiedMediaSendIssuerRegistry> = runCatching {
        val state = requireNotNull(durableStore.read(NAMESPACE, KEY))
        val registry = decodeAndVerifyStored(state)
        require(registry.signedEnvelopeSha256 == freshlyVerifiedEnvelopeSha256)
        requireFreshAt(registry, trustedTime)
        registry
    }

    private fun decodeAndVerifyStored(state: DurableMediaSendStateStore.StoredState): VerifiedMediaSendIssuerRegistry {
        require(state.digestSha256 == sha256Hex(state.payload))
        val registry = parseAndVerifySignedEnvelope(state.payload)
        require(registry.revision == state.revision)
        require(registry.signedEnvelopeSha256 == state.digestSha256)
        return registry
    }

    private fun parseAndVerifySignedEnvelope(rawEnvelope: ByteArray): VerifiedMediaSendIssuerRegistry {
        require(rawEnvelope.size in 1..MAX_ENVELOPE_BYTES)
        val envelopeDigest = sha256Hex(rawEnvelope)
        val envelope = JSON.parseToJsonElement(rawEnvelope.decodeToString()).jsonObject
        envelope.requireKeys("contract", "payload", "signature")
        require(envelope.string("contract") == ENVELOPE_CONTRACT)
        val payload = envelope.getValue("payload").jsonObject
        payload.requireKeys("contract", "revision", "issuedAtMs", "expiresAtMs", "keys")
        require(payload.string("contract") == REGISTRY_CONTRACT)
        val revision = payload.positiveLong("revision")
        val issuedAt = payload.positiveLong("issuedAtMs")
        val expiresAt = payload.positiveLong("expiresAtMs").also { require(it > issuedAt) }
        val keys = payload.getValue("keys").jsonArray.map(::parseKey)
        require(keys.isNotEmpty() && keys.map { it.keyId }.toSet().size == keys.size)
        require(keys.count { it.status == MediaSendIssuerKeyStatus.ACTIVE } == 1)
        verifyRootSignature(payload, envelope.getValue("signature").jsonObject)
        return VerifiedMediaSendIssuerRegistry(revision, issuedAt, expiresAt, envelopeDigest, keys)
    }

    private fun requireFreshAt(registry: VerifiedMediaSendIssuerRegistry, trustedTime: MediaSendTrustedTime) {
        require(registry.issuedAtMillis <= trustedTime.lowerBoundMillis)
        require(trustedTime.upperBoundMillis < registry.expiresAtMillis)
    }

    private fun verifyRootSignature(payload: JsonObject, signatureObject: JsonObject) {
        signatureObject.requireKeys("keyId", "algorithm", "signatureBase64")
        require(signatureObject.string("algorithm") == ALGORITHM)
        val configuration = requireNotNull(configurationProvider.configuration())
        val root = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(configuration.releaseTrustRootPublicKeyDer))
        require(root is ECPublicKey && root.params.matchesP256())
        require(signatureObject.string("keyId") == sha256Hex(root.encoded).take(16))
        val signatureBytes = Base64.getDecoder().decode(signatureObject.string("signatureBase64"))
        require(Base64.getEncoder().encodeToString(signatureBytes) == signatureObject.string("signatureBase64"))
        val input = "$SIGNATURE_DOMAIN\n${payload.canonical()}".encodeToByteArray()
        require(Signature.getInstance(ALGORITHM).run { initVerify(root); update(input); verify(signatureBytes) })
    }

    private fun parseKey(element: JsonElement): MediaSendIssuerKey {
        val value = element.jsonObject
        value.requireKeys("keyId", "algorithm", "publicKeyPem", "notBeforeMs", "notAfterMs", "status")
        val keyId = value.string("keyId").also { require(it.matches(KEY_ID)) }
        require(value.string("algorithm") == ALGORITHM)
        val pem = value.string("publicKeyPem")
        val der = pem.trim().removePrefix(PUBLIC_BEGIN).removeSuffix(PUBLIC_END).filterNot(Char::isWhitespace)
        val decoded = Base64.getDecoder().decode(der)
        require(sha256Hex(decoded).take(16) == keyId)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decoded))
        require(publicKey is ECPublicKey && publicKey.params.matchesP256())
        val notBefore = value.positiveLong("notBeforeMs")
        val notAfter = value.positiveLong("notAfterMs").also { require(it > notBefore) }
        val status = MediaSendIssuerKeyStatus.valueOf(value.string("status"))
        return MediaSendIssuerKey(keyId, ALGORITHM, pem, notBefore, notAfter, status)
    }

    private fun requireValidKeyTransitions(old: VerifiedMediaSendIssuerRegistry, new: VerifiedMediaSendIssuerRegistry) {
        val nextById = new.keys.associateBy { it.keyId }
        old.keys.forEach { previous ->
            val next = nextById[previous.keyId] ?: error("issuer key removed")
            require(previous.algorithm == next.algorithm && previous.publicKeyPem == next.publicKeyPem)
            require(previous.notBeforeMillis == next.notBeforeMillis && previous.notAfterMillis == next.notAfterMillis)
            require(when (previous.status) {
                MediaSendIssuerKeyStatus.ACTIVE -> next.status != MediaSendIssuerKeyStatus.ACTIVE || next.keyId == new.keys.single { it.status == MediaSendIssuerKeyStatus.ACTIVE }.keyId
                MediaSendIssuerKeyStatus.RETAINED -> next.status != MediaSendIssuerKeyStatus.ACTIVE
                MediaSendIssuerKeyStatus.REVOKED -> next.status == MediaSendIssuerKeyStatus.REVOKED
            })
        }
    }

    private fun p256Parameters() = java.security.AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1")); getParameterSpec(java.security.spec.ECParameterSpec::class.java)
    }

    private fun java.security.spec.ECParameterSpec.matchesP256(): Boolean = p256Parameters().let { expected ->
        curve == expected.curve && generator == expected.generator && order == expected.order && cofactor == expected.cofactor
    }

    private companion object {
        val JSON = Json { isLenient = false; ignoreUnknownKeys = false; explicitNulls = false }
        const val NAMESPACE = "issuer-registry"
        const val KEY = "current"
        const val MAX_ENVELOPE_BYTES = 512 * 1024
        const val ENVELOPE_CONTRACT = "next.media-send-issuer-key-registry-envelope.v1"
        const val REGISTRY_CONTRACT = "next.media-send-issuer-key-registry.v1"
        const val SIGNATURE_DOMAIN = "YZC-NEXT-WP08-SEND-ISSUER-REGISTRY-v1"
        const val ALGORITHM = "SHA256withECDSA"
        const val PUBLIC_BEGIN = "-----BEGIN PUBLIC KEY-----"
        const val PUBLIC_END = "-----END PUBLIC KEY-----"
        val KEY_ID = Regex("^[0-9a-f]{16}$")
    }
}

private fun JsonObject.requireKeys(vararg expected: String) = require(keys == expected.toSet())
private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content.also {
    require(getValue(name).jsonPrimitive.isString)
}
private fun JsonObject.positiveLong(name: String): Long = requireNotNull(getValue(name).jsonPrimitive.longOrNull).also { require(it > 0) }

private fun JsonElement.canonical(): String = when (this) {
    JsonNull -> "null"
    is JsonPrimitive -> when {
        isString -> content.jsonQuoted()
        booleanOrNull != null -> content
        longOrNull != null -> longOrNull.toString()
        else -> error("non-integral JSON number")
    }
    is JsonArray -> joinToString(prefix = "[", postfix = "]", separator = ",") { it.canonical() }
    is JsonObject -> entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}", separator = ",") {
        "${it.key.jsonQuoted()}:${it.value.canonical()}"
    }
}

private fun String.jsonQuoted(): String = buildString(length + 2) {
    append('"')
    this@jsonQuoted.forEach { c -> when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b"); '\u000C' -> append("\\f")
        '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
    } }
    append('"')
}
