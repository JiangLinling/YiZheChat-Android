/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services.feedback

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.actions.*
import io.element.android.features.discovery.impl.screens.services.*
import java.util.UUID
import kotlinx.serialization.json.*

@Inject
internal class DefaultDiscoveryFeedbackPort(private val reader: DiscoveryServicesReader, private val actions: DiscoveryBusinessActions) : DiscoveryFeedbackPort {
    override suspend fun load(target: FeedbackTarget) = reader.query(targetQuery("feedback", target)) { parseFeedbackPage(it, target) }

    suspend fun resumableTarget(): FeedbackTarget? = (actions.confirmedResult("feedback") as? DiscoveryFeedbackResult)?.target
        ?: (actions.pendingResult("feedback") as? DiscoveryFeedbackCommand)?.target

    override suspend fun submit(target: FeedbackTarget, feedbackId: Long?, revision: String?, draft: FeedbackDraft): FeedbackOutcome =
        execute(target, DiscoveryFeedbackCommand(UUID.randomUUID().toString(), target, feedbackId, revision, draft.normalized())) ?: FeedbackOutcome.Pending

    override suspend fun recover(target: FeedbackTarget): FeedbackOutcome {
        val saved = actions.confirmedResult("feedback") as? DiscoveryFeedbackResult
        val pending = actions.pendingResult("feedback") as? DiscoveryFeedbackCommand
        if (saved != null && pending?.operationId != saved.operationId) {
            require(saved.target == target)
            return FeedbackOutcome.Saved(saved.receipt())
        }
        return execute(target, null) ?: FeedbackOutcome.Clear
    }

    override suspend fun acknowledge(receipt: FeedbackSaved) {
        val saved = requireNotNull(actions.confirmedResult("feedback") as? DiscoveryFeedbackResult)
        require(saved.receipt() == receipt)
        actions.dismissResult(saved)
    }

    private suspend fun execute(target: FeedbackTarget, command: DiscoveryFeedbackCommand?): FeedbackOutcome? =
        actions.execute<FeedbackOutcome>(command, accepts = { it is DiscoveryFeedbackCommand && it.target == target },
            confirmedResult = { outcome -> (outcome as? FeedbackOutcome.Saved)?.receipt?.let {
                DiscoveryFeedbackResult(requireNotNull(it.operationId), it.target, it.feedbackId, it.revision)
            } }) { original, receipt ->
            require(original is DiscoveryFeedbackCommand && original.target == target)
            parseFeedbackReceipt(original, receipt)
        }
}

internal fun parseFeedbackReceipt(command: DiscoveryFeedbackCommand, receipt: JsonObject): FeedbackOutcome {
    require(receipt["operation_id"] == JsonPrimitive(command.operationId))
    return when (receipt["outcome"]) {
        JsonPrimitive("REJECTED") -> {
            receipt.serviceExact("operation_id", "outcome", "reason")
            FeedbackOutcome.Rejected(receipt.serviceText("reason", 64).also { require(it.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) })
        }
        JsonPrimitive("APPLIED") -> {
            receipt.serviceExact("operation_id", "outcome", "course_id", "session_id", "enrollment_id", "feedback_id", "revision")
            require(receipt.serviceTarget() == command.target)
            val id = receipt.serviceId("feedback_id")
            require(command.feedbackId == null || command.feedbackId == id)
            FeedbackOutcome.Saved(FeedbackSaved(command.target, id, receipt.serviceText("revision", 64), command.operationId))
        }
        else -> error("Invalid feedback receipt")
    }
}
