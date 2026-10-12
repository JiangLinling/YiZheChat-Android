/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Inject
@SingleIn(AppScope::class)
class ManagementSignatureVerifier(
    private val configuration: ManagementControlConfiguration,
) {
    private val publicKey: ECPublicKey? by lazy {
        if (!configuration.isConfigured) return@lazy null
        runCatchingExceptions {
            KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(configuration.signingPublicKeyDerBase64))
            ) as ECPublicKey
        }.getOrNull()
    }

    internal fun verify(envelope: SignedJsonEnvelopeWire): Boolean {
        return verifyDetached(envelope.payload(), envelope.keyId, envelope.signatureBase64)
    }

    internal fun verifyDetached(payload: JsonElement, signingKeyId: String, signatureBase64: String): Boolean {
        return verifyBytes(canonicalJson(payload), signingKeyId, signatureBase64)
    }

    internal fun verifyBytes(payload: ByteArray, signingKeyId: String, signatureBase64: String): Boolean {
        val key = publicKey ?: return false
        if (signingKeyId != configuration.signingKeyId || keyId(key.encoded) != signingKeyId) return false
        val signature = runCatchingExceptions { Base64.getDecoder().decode(signatureBase64) }.getOrNull() ?: return false
        return runCatchingExceptions {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(payload)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    internal fun canonicalJson(element: JsonElement): ByteArray {
        return Json.encodeToString(JsonElement.serializer(), canonicalElement(element)).encodeToByteArray()
    }

    private fun canonicalElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy(Map.Entry<String, JsonElement>::key).associate { (key, value) ->
            key to canonicalElement(value)
        })
        is JsonArray -> JsonArray(element.map(::canonicalElement))
        else -> element
    }

    private fun keyId(encoded: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(encoded)
        .take(KEY_ID_DIGEST_BYTES)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val KEY_ID_DIGEST_BYTES = 8
    }
}
