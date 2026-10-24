/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services.feedback

import io.element.android.features.discovery.impl.util.discoveryTextFits

internal data class FeedbackTarget(val courseId: Long, val sessionId: Long, val enrollmentId: Long) {
    init { require(listOf(courseId, sessionId, enrollmentId).all { it in 1..Int.MAX_VALUE.toLong() }) }
}
internal enum class FeedbackTag(val sourceLabel: String) {
    CONTENT("课程内容"), TEACHING("授课带班"), SERVICE("组织服务"), VENUE("场地环境"),
    MEALS_STAY("餐饮住宿"), PICKUP("接送安排"), OTHER("其他"),
}
internal enum class FeedbackIssue { SCORES, RECOMMENDATION, GAIN, IMPROVEMENT }
internal data class FeedbackDraft(
    val contentScore: Int = 0, val teachingScore: Int = 0, val serviceScore: Int = 0, val outcomeScore: Int = 0,
    val recommendScore: Int = -1, val gain: String = "", val improvement: String = "",
    val tags: Set<FeedbackTag> = emptySet(), val wantsContact: Boolean = false,
) {
    fun issues(): Set<FeedbackIssue> = buildSet {
        if (listOf(contentScore, teachingScore, serviceScore, outcomeScore).any { it !in 1..5 }) add(FeedbackIssue.SCORES)
        if (recommendScore !in 0..10) add(FeedbackIssue.RECOMMENDATION)
        if (!gain.trim().discoveryTextFits(300, 1)) add(FeedbackIssue.GAIN)
        if (!improvement.trim().discoveryTextFits(300)) add(FeedbackIssue.IMPROVEMENT)
    }
    fun normalized() = copy(gain = gain.trim(), improvement = improvement.trim(), tags = tags.toSet())
    override fun toString() = "FeedbackDraft(content=<redacted>)"
}
internal enum class FeedbackAvailability { OPEN, SUBMITTED, NOT_OPEN, CLOSED }
internal data class FeedbackPage(
    val target: FeedbackTarget, val courseTitle: String, val personName: String,
    val availability: FeedbackAvailability, val canSubmit: Boolean,
    val feedbackId: Long?, val revision: String?, val draft: FeedbackDraft,
) {
    init {
        require(feedbackId == null || feedbackId in 1..Int.MAX_VALUE.toLong())
        require((feedbackId == null) == (revision == null) && (revision == null || revision.matches(Regex("[a-f0-9]{64}"))))
        require(!canSubmit || availability in setOf(FeedbackAvailability.OPEN, FeedbackAvailability.SUBMITTED))
        require(availability != FeedbackAvailability.SUBMITTED || feedbackId != null)
    }
    override fun toString() = "FeedbackPage(target=$target, details=<redacted>)"
}
internal data class FeedbackSaved(val target: FeedbackTarget, val feedbackId: Long, val revision: String, val operationId: String? = null) {
    init { require(feedbackId in 1..Int.MAX_VALUE.toLong() && revision.matches(Regex("[a-f0-9]{64}"))) }
}
internal sealed interface FeedbackOutcome {
    data object Clear : FeedbackOutcome
    data object Pending : FeedbackOutcome
    data class Rejected(val reason: String) : FeedbackOutcome
    data class Saved(val receipt: FeedbackSaved) : FeedbackOutcome
}
/** Uses the accepted service-feedback contract and the original protected journal. */
internal interface DiscoveryFeedbackPort {
    suspend fun load(target: FeedbackTarget): FeedbackPage
    suspend fun submit(target: FeedbackTarget, feedbackId: Long?, revision: String?, draft: FeedbackDraft): FeedbackOutcome
    suspend fun recover(target: FeedbackTarget): FeedbackOutcome
    suspend fun acknowledge(receipt: FeedbackSaved)
}
internal enum class FeedbackPhase { LOADING, EDITING, READ_ONLY, SAVING, UNCERTAIN, SAVED, FAILED, CONFLICT, UNAVAILABLE }
internal data class FeedbackState(
    val phase: FeedbackPhase = FeedbackPhase.LOADING, val page: FeedbackPage? = null, val draft: FeedbackDraft? = null,
    val receipt: FeedbackSaved? = null, val showIssues: Boolean = false, val rejected: Boolean = false,
    val conflict: FeedbackPage? = null, val conflictReadFailed: Boolean = false,
)
