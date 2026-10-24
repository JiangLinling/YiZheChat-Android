/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services.feedback

import io.element.android.features.discovery.impl.screens.services.*
import kotlinx.serialization.json.*

internal fun feedbackForm(draft: FeedbackDraft): JsonObject {
    require(draft.issues().isEmpty() && draft == draft.normalized())
    return buildJsonObject {
        put("content_score", draft.contentScore); put("teaching_score", draft.teachingScore); put("service_score", draft.serviceScore)
        put("outcome_score", draft.outcomeScore); put("recommend_score", draft.recommendScore); put("gain_text", draft.gain)
        put("improvement_text", draft.improvement); put("wants_contact", draft.wantsContact)
        put("issue_tags", buildJsonArray { FeedbackTag.entries.filter { it in draft.tags }.forEach { add(it.sourceLabel) } })
    }
}

internal fun parseFeedbackForm(value: JsonObject): FeedbackDraft {
    value.serviceExact("content_score", "teaching_score", "service_score", "outcome_score", "recommend_score", "gain_text", "improvement_text", "issue_tags", "wants_contact")
    fun score(key: String, range: IntRange) = value.serviceNumber(key).also { require(it in range.first.toLong()..range.last.toLong()) }.toInt()
    val tags = value.getValue("issue_tags").jsonArray.also { require(it.size <= 7) }.map { element ->
        val name = element.jsonPrimitive.also { require(it.isString) }.content
        FeedbackTag.entries.single { it.sourceLabel == name }
    }
    require(tags.distinct().size == tags.size && tags == FeedbackTag.entries.filter { it in tags })
    val draft = FeedbackDraft(score("content_score", 1..5), score("teaching_score", 1..5), score("service_score", 1..5),
        score("outcome_score", 1..5), score("recommend_score", 0..10), value.serviceText("gain_text", 300), value.serviceText("improvement_text", 300), tags.toSet(),
        value.getValue("wants_contact").jsonPrimitive.also { require(!it.isString) }.boolean)
    require(draft == draft.normalized() && draft.issues().isEmpty())
    return draft
}

internal fun parseFeedbackPage(value: JsonObject, target: FeedbackTarget): FeedbackPage {
    value.serviceExact("course_id", "session_id", "enrollment_id", "course_title", "person_name", "availability", "can_submit", "feedback_id", "revision", "feedback")
    require(value.serviceTarget() == target)
    val availability = when (value.getValue("availability")) {
        JsonPrimitive("open") -> FeedbackAvailability.OPEN
        JsonPrimitive("submitted") -> FeedbackAvailability.SUBMITTED
        else -> error("Unknown feedback availability")
    }
    val submitted = availability == FeedbackAvailability.SUBMITTED
    require(submitted == (value.getValue("feedback_id") != JsonNull) && submitted == (value.getValue("revision") != JsonNull) &&
        submitted == (value.getValue("feedback") != JsonNull))
    return FeedbackPage(target, value.serviceText("course_title", 255), value.serviceText("person_name", 120), availability,
        value.getValue("can_submit").jsonPrimitive.also { require(!it.isString) }.boolean,
        if (submitted) value.serviceId("feedback_id") else null, if (submitted) value.serviceText("revision", 64) else null,
        if (submitted) parseFeedbackForm(value.getValue("feedback").jsonObject) else FeedbackDraft())
}
