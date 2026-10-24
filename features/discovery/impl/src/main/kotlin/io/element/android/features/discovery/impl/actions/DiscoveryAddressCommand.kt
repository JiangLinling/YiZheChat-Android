/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import java.util.UUID
import kotlinx.serialization.json.*

internal data class DiscoveryAddressCommand(override val operationId: String, val addressId: Long?, val address: JsonObject) : DiscoveryBusinessCommand {
    override val path get() = "/v1/discovery/business/member-addresses"
    init {
        require(UUID.fromString(operationId).toString() == operationId)
        require(addressId == null || addressId in 1..Int.MAX_VALUE.toLong())
        validateAddressFields(address)
    }
    override fun toString() = "DiscoveryAddressCommand(operationId=$operationId, addressId=$addressId, address=<redacted>)"
    override fun bytes() = buildJsonObject {
        put("operation_id", operationId); put("address_id", addressId?.let(::JsonPrimitive) ?: JsonNull); put("address", address)
    }.toString().encodeToByteArray()
    companion object {
        fun parse(value: JsonObject): DiscoveryAddressCommand {
            require(value.keys == setOf("operation_id", "address_id", "address"))
            val id = value.getValue("address_id").takeUnless { it == JsonNull }?.jsonPrimitive?.also { require(!it.isString) }?.long
            return DiscoveryAddressCommand(value.getValue("operation_id").jsonPrimitive.also { require(it.isString) }.content, id,
                value.getValue("address").jsonObject)
        }
    }
}

internal fun validateAddressFields(address: JsonObject) {
        require(address.keys == setOf("receiver_name", "receiver_phone", "province", "city", "district", "detail", "is_default"))
        listOf("receiver_name", "province", "city", "district", "detail", "receiver_phone").forEach { key ->
            val text = address.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val limit = when (key) { "detail" -> 300; "receiver_phone" -> 11; else -> 80 }
            require(text.isNotBlank() && text == text.trim() && text.length <= limit && text.none { it.isISOControl() })
        }
        require(address.getValue("receiver_phone").jsonPrimitive.content.matches(Regex("1[0-9]{10}")))
        address.getValue("is_default").jsonPrimitive.also { require(!it.isString && it.booleanOrNull != null) }
}
