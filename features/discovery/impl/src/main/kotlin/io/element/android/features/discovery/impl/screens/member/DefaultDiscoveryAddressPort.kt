/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.actions.DiscoveryAddressCommand
import io.element.android.features.discovery.impl.actions.DiscoveryBusinessActions
import io.element.android.features.discovery.impl.actions.validateAddressFields
import io.element.android.features.discovery.impl.network.DiscoveryTarget
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import java.util.UUID
import kotlinx.serialization.json.*

/** member-addresses-v1. No mini token, host user id, client owner id or arbitrary endpoint. */
@Inject
internal class DefaultDiscoveryAddressPort(
    private val access: DefaultDiscoveryAccess,
    private val transport: PinnedDiscoveryTransport,
    private val actions: DiscoveryBusinessActions,
) : DiscoveryAddressPort {
    override suspend fun load(): MemberAddressBook = access.withSession { session ->
        readCompleteAddressBook { page ->
            val request = buildJsonObject { put("kind", "addresses"); put("page", page); put("page_size", 20) }.toString().encodeToByteArray()
            try {
                val response = transport.post(DiscoveryTarget.GATEWAY, "/v1/discovery/business/member/query", request,
                    mapOf("Authorization" to "Bearer ${session.secret.value}"))
                try { parseAddressPage(Json.parseToJsonElement(response.decodeToString()).jsonObject, page, 20) } finally { response.fill(0) }
            } finally { request.fill(0) }
        }
    }

    override suspend fun save(addressId: Long?, draft: MemberAddressDraft): AddressOutcome {
        val normalized = draft.normalized()
        require(normalized.issues().isEmpty())
        val command = DiscoveryAddressCommand(UUID.randomUUID().toString(), addressId, buildJsonObject {
            put("receiver_name", normalized.name); put("receiver_phone", normalized.phone); put("province", normalized.province)
            put("city", normalized.city); put("district", normalized.district); put("detail", normalized.savedDetail)
            put("is_default", normalized.isDefault)
        })
        return execute(command) ?: AddressOutcome.Pending
    }
    override suspend fun recover(): AddressOutcome = execute(null) ?: AddressOutcome.Clear

    private suspend fun execute(command: DiscoveryAddressCommand?): AddressOutcome? = actions.execute(command,
        accepts = { it is DiscoveryAddressCommand }) { original, receipt ->
        require(original is DiscoveryAddressCommand)
        require(receipt.getValue("operation_id") == JsonPrimitive(original.operationId))
        when (receipt.getValue("outcome")) {
            JsonPrimitive("APPLIED") -> {
                require(receipt.keys == setOf("operation_id", "outcome", "address_id"))
                val id = receipt.id("address_id")
                require(original.addressId == null || original.addressId == id)
                // Retire the terminal intent before any optional refresh. Never retry a successful create because a read failed.
                AddressOutcome.Saved(book = null, addressId = id)
            }
            JsonPrimitive("REJECTED") -> {
                require(receipt.keys == setOf("operation_id", "outcome", "reason"))
                require(receipt.text("reason", 64).matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
                AddressOutcome.Rejected
            }
            else -> error("Invalid address outcome")
        }
    }
}

internal data class AddressPage(val ownerId: Long, val rows: List<MemberAddress>, val hasMore: Boolean, val defaultId: Long?, val total: Long)

internal suspend fun readCompleteAddressBook(readPage: suspend (Int) -> AddressPage): MemberAddressBook {
    val rows = linkedMapOf<Long, MemberAddress>()
    var first: AddressPage? = null
    for (page in 1..400) {
        val value = readPage(page)
        val previous = first
        if (previous == null) first = value else {
            // Offset pagination is not a snapshot. Known concurrent changes require a new read, never a partial "all addresses" list.
            require(previous.ownerId == value.ownerId && previous.defaultId == value.defaultId && previous.total == value.total)
        }
        value.rows.forEach { rows[it.id] = it }
        if (!value.hasMore) {
            require(rows.size.toLong() == value.total)
            require(rows.values.filter { it.draft.isDefault }.map { it.id } == listOfNotNull(value.defaultId))
            return MemberAddressBook(value.ownerId, rows.values.toList(), emptyList())
        }
    }
    error("Incomplete address list")
}
internal fun parseAddressPage(value: JsonObject, page: Int, size: Int): AddressPage {
    require(value.keys == setOf("owner_id", "items", "page", "page_size", "total", "has_more", "default_address_id"))
    require(value.number("page") == page.toLong() && value.number("page_size") == size.toLong())
    val total = value.number("total").also { require(it in 0..Int.MAX_VALUE.toLong()) }
    val more = value.getValue("has_more").jsonPrimitive.also { require(!it.isString) }.boolean
    require(more == (page.toLong() * size < total))
    val items = value.getValue("items").jsonArray
    require(items.size.toLong() == minOf(size.toLong(), (total - (page - 1L) * size).coerceAtLeast(0)))
    val rows = items.map { element ->
        val item = element.jsonObject
        require(item.keys == setOf("address_id", "receiver_name", "receiver_phone", "province", "city", "district", "detail", "is_default"))
        val id = item.id("address_id")
        val address = JsonObject(item - "address_id")
        // The same strict field validation is used for responses and decrypted intent replay.
        validateAddressFields(address)
        MemberAddress(id, MemberAddressDraft(item.text("receiver_name", 80), item.text("receiver_phone", 11), item.text("province", 80),
            item.text("city", 80), item.text("district", 80), item.text("detail", 300),
            isDefault = item.getValue("is_default").jsonPrimitive.boolean))
    }
    require(rows.map { it.id }.distinct().size == rows.size)
    val defaultId = value.getValue("default_address_id").takeUnless { it == JsonNull }?.jsonPrimitive?.let {
        require(!it.isString); it.long.also { id -> require(id in 1..Int.MAX_VALUE.toLong()) }
    }
    require(rows.filter { it.draft.isDefault }.all { it.id == defaultId })
    return AddressPage(value.id("owner_id"), rows, more, defaultId, total)
}
private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.long
private fun JsonObject.id(key: String) = number(key).also { require(it in 1..Int.MAX_VALUE.toLong()) }
private fun JsonObject.text(key: String, limit: Int) = getValue(key).jsonPrimitive.also { require(it.isString && it.content.length <= limit) }.content
