/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.LifecycleMediaOutcome
import io.element.android.security.dataprotection.api.LifecycleMediaReservation
import io.element.android.security.dataprotection.api.LifecycleMediaResource
import io.element.android.security.dataprotection.api.LifecycleMediaResourceType
import io.element.android.security.dataprotection.api.LifecycleMediaRuntimeCleanup
import io.element.android.security.dataprotection.api.LifecycleMediaSeal
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Stored inside the original Lifecycle owner record; no content, key or bearer capability. */
internal data class StoredMediaClosure(
    val reservation: LifecycleMediaReservation,
    val producerProcessSha256: String,
    val admitted: Set<LifecycleMediaResource> = emptySet(),
    val runtimeFacts: Map<String, LifecycleMediaRuntimeCleanup> = emptyMap(),
    val runtimeAcknowledgements: Map<String, String> = emptyMap(),
    val seal: LifecycleMediaSeal? = null,
    val sealAcknowledgement: String? = null,
) {
    init {
        require(producerProcessSha256.matches(Regex("[0-9a-f]{64}")))
        require(admitted.all { it in reservation.resources })
        runtimeFacts.forEach { (identity, fact) ->
            require(identity == fact.recordSha256 && fact.operationId == reservation.operationId && fact.subjects == reservation.subjects)
            require(LifecycleMediaResource(LifecycleMediaResourceType.ANDROID_MEDIA_RUNTIME, identity) in reservation.resources)
        }
        require(runtimeAcknowledgements.keys.all { it in runtimeFacts })
        require(runtimeAcknowledgements.values.all { it.matches(Regex("android-media-runtime-cleanup:v1:[0-9a-f]{64}")) })
        require(seal == null || seal.reservation == reservation && seal.ownerProcessSha256 == producerProcessSha256)
        require(sealAcknowledgement == null || seal != null && sealAcknowledgement.startsWith("android-media-manifest:v1:"))
        require(seal?.outcome != LifecycleMediaOutcome.SUCCEEDED || admitted.containsAll(reservation.resources))
    }

    fun encode(): JsonObject = buildJsonObject {
        put("schema", 1)
        put("reservation", mediaResourceRequest(reservation))
        put("producer_process", producerProcessSha256)
        put("admitted", buildJsonArray {
            reservation.resources.filter { it in admitted }.forEach { resource -> add(buildJsonObject {
                put("type", resource.type.name)
                put("identity", resource.recordSha256)
            }) }
        })
        put("runtime_facts", buildJsonObject { runtimeFacts.toSortedMap().forEach { (id, fact) -> put(id, mediaRuntimeRequest(fact)) } })
        put("runtime_acknowledgements", buildJsonObject { runtimeAcknowledgements.toSortedMap().forEach { (id, ref) -> put(id, ref) } })
        put("seal", seal?.let(::mediaSealRequest) ?: JsonNull)
        put("seal_acknowledgement", sealAcknowledgement?.let(::JsonPrimitive) ?: JsonNull)
    }

    companion object {
        fun decode(value: JsonObject): StoredMediaClosure {
            require(value.long("schema") == 1L)
            val request = value.getValue("reservation").jsonObject
            require(request.long("protocol_version") == 1L)
            val subjects = LifecycleSubjectHashes(installationId = request.string("installation_id"),
                accountIdHash = request.string("account_id_hash"), matrixDeviceIdHash = request.string("matrix_device_id_hash"),
                roomIdHash = request.optional("room_id_hash"), eventIdHash = request.optional("event_id_hash"))
            val reservation = LifecycleMediaReservation(request.string("operation_id"), subjects,
                request.getValue("resources").jsonArray.map { entry -> entry.jsonObject.let {
                    LifecycleMediaResource(LifecycleMediaResourceType.valueOf(it.string("resource_type")), it.string("resource_id_hash"))
                } })
            val facts = value.getValue("runtime_facts").jsonObject.mapValues { (_, entry) -> entry.jsonObject.let {
                require(it.long("protocol_version") == 1L)
                require(it.string("operation_id") == reservation.operationId)
                require(it.string("installation_id") == subjects.installationId && it.string("account_id_hash") == subjects.accountIdHash &&
                    it.string("matrix_device_id_hash") == subjects.matrixDeviceIdHash && it.optional("room_id_hash") == subjects.roomIdHash &&
                    it.optional("event_id_hash") == subjects.eventIdHash)
                LifecycleMediaRuntimeCleanup(reservation.operationId, subjects, it.string("record_sha256"), it.long("released_at_ms"),
                    it.getValue("runtime_released").jsonPrimitive.boolean, it.getValue("active_work_finished").jsonPrimitive.boolean,
                    it.getValue("owned_payload_cleared").jsonPrimitive.boolean)
            } }
            val seal = value["seal"]?.takeUnless { it is JsonNull }?.jsonObject?.let {
                val decoded = LifecycleMediaSeal(reservation, it.string("owner_process_sha256"), it.long("sealed_at_ms"),
                    LifecycleMediaOutcome.valueOf(it.string("outcome")))
                require(mediaSealRequest(decoded) == it)
                decoded
            }
            return StoredMediaClosure(reservation, value.string("producer_process"),
                value.getValue("admitted").jsonArray.map { it.jsonObject.let { resource ->
                    LifecycleMediaResource(LifecycleMediaResourceType.valueOf(resource.string("type")), resource.string("identity"))
                } }.toSet(), facts, value.getValue("runtime_acknowledgements").jsonObject.mapValues { it.value.jsonPrimitive.content },
                seal, value.optional("seal_acknowledgement"))
        }
    }
}

private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
private fun JsonObject.optional(key: String) = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
