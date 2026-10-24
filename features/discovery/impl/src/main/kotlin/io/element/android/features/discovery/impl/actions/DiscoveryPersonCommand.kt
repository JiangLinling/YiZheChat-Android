/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import io.element.android.features.discovery.impl.screens.member.PersonEditKind
import io.element.android.features.discovery.impl.screens.member.PersonEditTarget
import io.element.android.features.discovery.impl.screens.member.parsePersonForm
import io.element.android.features.discovery.impl.screens.member.personId
import java.util.UUID
import kotlinx.serialization.json.*

internal data class DiscoveryPersonCommand(override val operationId: String, val child: Boolean,
    val childId: Long?, val profile: JsonObject) : DiscoveryBusinessCommand {
    override val path get() = if (child) "/v1/discovery/business/member-children" else "/v1/discovery/business/member-profile"
    init {
        require(UUID.fromString(operationId).toString() == operationId)
        require(childId == null || child && childId in 1..Int.MAX_VALUE.toLong())
        parsePersonForm(profile, child)
    }
    override fun toString() = "DiscoveryPersonCommand(<redacted>)"
    override fun bytes(): ByteArray = buildJsonObject {
        put("operation_id", operationId)
        if (child) put("child_user_id", childId?.let(::JsonPrimitive) ?: JsonNull)
        put("profile", profile)
    }.toString().encodeToByteArray()
    fun matches(target: PersonEditTarget) = child == (target.kind != PersonEditKind.SELF) &&
        (!child || childId == target.userId)
    companion object {
        fun parse(value: JsonObject): DiscoveryPersonCommand {
            val child = "child_user_id" in value
            require(value.keys == setOf("operation_id", "profile") + (if (child) setOf("child_user_id") else emptySet()))
            val operation = value.getValue("operation_id").jsonPrimitive.also { require(it.isString) }.content
            val id = if (child && value.getValue("child_user_id") != JsonNull) value.personId("child_user_id") else null
            return DiscoveryPersonCommand(operation, child, id, value.getValue("profile").jsonObject)
        }
    }
}

/** Minimal confirmed save location in the existing encrypted journal, never an identity grant. */
internal data class DiscoveryPersonResultLocator(val operationId: String, val target: PersonEditTarget, val userId: Long, val relationId: Long?) {
    init {
        require(UUID.fromString(operationId).toString() == operationId && userId in 1..Int.MAX_VALUE.toLong())
        require(target.userId == null || target.userId == userId)
        require(if (target.kind == PersonEditKind.SELF) relationId == null else relationId != null && relationId in 1..Int.MAX_VALUE.toLong())
    }
    override fun toString() = "DiscoveryPersonResultLocator(<redacted>)"
    fun json() = buildJsonObject {
        put("operation_id", operationId); put("kind", target.kind.name)
        put("target_id", target.userId?.let(::JsonPrimitive) ?: JsonNull)
        put("user_id", userId); put("relation_id", relationId?.let(::JsonPrimitive) ?: JsonNull)
    }
    companion object {
        fun parse(value: JsonObject): DiscoveryPersonResultLocator {
            require(value.keys == setOf("operation_id", "kind", "target_id", "user_id", "relation_id"))
            fun optional(key: String) = value.getValue(key).takeUnless { it == JsonNull }?.let { value.personId(key) }
            fun text(key: String) = value.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            return DiscoveryPersonResultLocator(text("operation_id"), PersonEditTarget(PersonEditKind.valueOf(text("kind")), optional("target_id")),
                value.personId("user_id"), optional("relation_id"))
        }
    }
}
