/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.*

/** Validates both new requests and decrypted journal replay against Guest enrollment_models.Selection. */
internal fun validateEnrollmentSelection(value: JsonObject) {
    require(value.keys == DiscoveryCourseQuoteCommand.selectionKeys)
    val people = value.getValue("student_user_ids").jsonArray.map { element ->
        element.jsonPrimitive.also { require(!it.isString) }.long.also { require(it in 1..Int.MAX_VALUE.toLong()) }
    }
    require(people.size in 1..10 && people.distinct().size == people.size)
    val companionMode = value.choice("companion_mode", setOf("none", "guardian"))
    val companions = value.getValue("companions").jsonArray
    require(companions.size == if (companionMode == "guardian") 1 else 0)
    companions.forEach { element ->
        val companion = element.jsonObject
        require(companion.keys == setOf("type", "relation", "user_id"))
        companion.choice("type", setOf("adult")); companion.choice("relation", setOf("guardian"))
        val id = companion.getValue("user_id").jsonPrimitive.also { require(!it.isString) }.long
        require(id in 1..Int.MAX_VALUE.toLong() && id !in people)
    }
    val start = value.dateTime("service_start_date")
    val end = value.dateTime("service_end_date")
    require(end > start && java.time.Duration.between(start, end) <= java.time.Duration.ofDays(60))
    require(people.size + companions.size <= 10)

    val room = value.getValue("accommodation").jsonObject
    require(room.keys.all { it in setOf("mode", "share_mode", "preference", "check_in_date", "check_out_date", "rooming_group") })
    val mode = room.choice("mode", setOf("none", "single", "share"))
    room.choice("share_mode", setOf("none", "auto", "join", "public", "private"))
    room.choice("preference", setOf("same_gender", "none"))
    if (mode != "none") {
        require(room.date("check_in_date") == start.toLocalDate() && room.date("check_out_date") == end.toLocalDate())
    } else {
        require(room["check_in_date"] == null || room["check_in_date"] == JsonNull)
        require(room["check_out_date"] == null || room["check_out_date"] == JsonNull)
    }
    val group = room.getValue("rooming_group").jsonObject
    require(group.keys.all { it == "group_id" })
    group["group_id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.let {
        require(!it.isString && it.long in 1..Int.MAX_VALUE.toLong())
    }

    val meals = value.getValue("meals").jsonArray
    require(meals.size <= 30)
    val mealKeys = meals.map { element ->
        val meal = element.jsonObject
        require(meal.keys == setOf("service_date", "meal_type"))
        val date = meal.date("service_date")
        require(date >= start.toLocalDate() && date <= end.toLocalDate())
        date to meal.choice("meal_type", setOf("breakfast", "lunch", "dinner"))
    }
    require(mealKeys.distinct().size == mealKeys.size)
    val transport = value.getValue("transport").jsonObject
    require(transport.keys == setOf("mode", "items"))
    transport.choice("mode", setOf("none"))
    require(transport.getValue("items").jsonArray.isEmpty())

    val pickup = value.getValue("pickup_request").jsonObject
    require(pickup.keys.all { it in setOf("enabled", "direction", "transport_type", "transport_no", "planned_at", "origin_text",
        "destination_text", "companion_count", "luggage_text", "special_needs", "note", "origin_latitude", "origin_longitude") })
    val enabled = pickup.getValue("enabled").jsonPrimitive.also { require(!it.isString) }.boolean
    if (!enabled) require(pickup.keys == setOf("enabled")) // No stale private fields in disabled services.
    else {
        pickup.choice("direction", setOf("pickup", "dropoff"))
        pickup.boundedText("transport_type", 32, required = true)
        pickup.dateTime("planned_at") // The current server pickup window is independent of accommodation dates.
        pickup.boundedText("origin_text", 200, required = true)
        pickup.boundedText("destination_text", 200, required = true)
        pickup.boundedText("transport_no", 64)
        pickup.boundedText("luggage_text", 500)
        pickup.boundedText("special_needs", 500)
        pickup.boundedText("note", 500)
        val count = pickup.getValue("companion_count").jsonPrimitive.also { require(!it.isString) }.int
        require(count in 0..20)
        fun coordinate(key: String, limit: Double): Double? = pickup[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.let {
            require(!it.isString)
            it.double.also { number -> require(number.isFinite() && number in -limit..limit) }
        }
        val latitude = coordinate("origin_latitude", 90.0)
        val longitude = coordinate("origin_longitude", 180.0)
        require((latitude == null) == (longitude == null))
    }
    value.boundedText("diet_note", 1000)
    value.boundedText("special_note", 1000)
}

private fun JsonObject.choice(key: String, choices: Set<String>): String = getValue(key).jsonPrimitive.also {
    require(it.isString && it.content in choices)
}.content
private fun JsonObject.boundedText(key: String, limit: Int, required: Boolean = false): String? {
    val element = get(key)
    if (element == null || element == JsonNull) { require(!required); return null }
    return element.jsonPrimitive.also { require(it.isString) }.content.also {
        require(it.length <= limit && (!required || it.isNotBlank()))
    }
}
private fun JsonObject.date(key: String): LocalDate {
    val text = boundedText(key, 10, required = true)!!
    require(text.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    return LocalDate.parse(text)
}
private fun JsonObject.dateTime(key: String): LocalDateTime = LocalDateTime.parse(boundedText(key, 32, required = true)!!)
