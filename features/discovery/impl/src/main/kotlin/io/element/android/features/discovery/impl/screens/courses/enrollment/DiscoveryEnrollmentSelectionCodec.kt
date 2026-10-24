/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import io.element.android.features.discovery.impl.actions.validateEnrollmentSelection
import kotlinx.serialization.json.*

/** Guest enrollment_models.Selection. Every identifier and service comes from the current authorised options. */
internal fun EnrollmentRequest.selection(options: EnrollmentOptions): JsonObject {
    require(courseId == options.courseId && sessionId == options.sessionId && optionsVersion == options.version)
    require(draft.issues(options).isEmpty())
    return buildJsonObject {
        put("student_user_ids", buildJsonArray { draft.personIds.sorted().forEach { add(it) } })
        put("companion_mode", if (draft.guardian) "guardian" else "none")
        put("companions", buildJsonArray {
            if (draft.guardian) add(buildJsonObject {
                put("type", "adult"); put("relation", "guardian"); put("user_id", options.self.id)
            })
        })
        put("service_start_date", options.startAt(draft.start, draft.room).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        put("service_end_date", options.endAt(draft.end, draft.room).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        put("accommodation", buildJsonObject {
            put("mode", when (draft.room) { EnrollmentRoom.NONE -> "none"; EnrollmentRoom.SINGLE -> "single"; EnrollmentRoom.SHARED -> "share" })
            put("share_mode", if (draft.room == EnrollmentRoom.SHARED) "auto" else "none")
            put("preference", if (draft.room == EnrollmentRoom.SHARED) "same_gender" else "none")
            put("check_in_date", if (draft.room == EnrollmentRoom.NONE) JsonNull else JsonPrimitive(draft.start.toString()))
            put("check_out_date", if (draft.room == EnrollmentRoom.NONE) JsonNull else JsonPrimitive(draft.end.toString()))
            put("rooming_group", buildJsonObject { put("group_id", JsonNull) })
        })
        put("meals", buildJsonArray {
            options.meals.filter { it.id in draft.mealIds }.sortedWith(compareBy({ it.date }, { it.kind.ordinal })).forEach { meal ->
                add(buildJsonObject { put("service_date", meal.date.toString()); put("meal_type", meal.kind.wireValue) })
            }
        })
        put("transport", buildJsonObject { put("mode", "none"); put("items", JsonArray(emptyList())) })
        put("pickup_request", buildJsonObject {
            val pickup = draft.pickup
            put("enabled", pickup.enabled)
            if (pickup.enabled) {
                put("direction", "pickup"); put("transport_type", pickup.mode.wireValue)
                put("transport_no", pickup.transportNumber.trim())
                put("planned_at", "${pickup.date}T${pickup.time}:00")
                put("origin_text", pickup.origin.trim()); put("destination_text", options.pickupDestination)
                // Original business field counts companions, while the page asks for total travellers.
                put("companion_count", pickup.peopleCount.toInt() - 1)
                put("luggage_text", pickup.luggage.trim()); put("special_needs", pickup.needs.trim()); put("note", JsonNull)
                put("origin_latitude", pickup.latitude?.let(::JsonPrimitive) ?: JsonNull)
                put("origin_longitude", pickup.longitude?.let(::JsonPrimitive) ?: JsonNull)
            }
        })
        put("diet_note", draft.dietNote.trim()); put("special_note", draft.specialNote.trim())
    }.also(::validateEnrollmentSelection)
}
