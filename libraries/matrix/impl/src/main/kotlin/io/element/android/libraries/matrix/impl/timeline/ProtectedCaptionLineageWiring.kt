/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.timeline

import io.element.android.security.contentprotection.api.ContentId

/**
 * Durable two-phase boundary between a protected caption send and F37 version-lineage registration.
 *
 * [prepare] must resolve the target's current content version, persist the pending operation, and
 * return a child identity whose authoritative expiry does not exceed its parent. The pending fact
 * is retained after [matrixSendAccepted] until the Matrix event binding is observed and the exact
 * v4 `/v1/content-version-lineage/versions` registration and local lineage append both succeed.
 */
interface ProtectedCaptionLineageWiring {
    suspend fun prepare(targetEventId: String, operationId: String, targetContentId: ContentId, targetExpiresAt: Long): ProtectedCaptionEditPlan

    /** KMS protection may have partly succeeded before throwing; retain the child identity. */
    suspend fun protectionOutcomeUnknown(plan: ProtectedCaptionEditPlan, failure: Exception)

    suspend fun matrixSendAccepted(plan: ProtectedCaptionEditPlan)

    /** The SDK did not prove non-delivery. The durable pending operation must remain reconcilable. */
    suspend fun matrixSendOutcomeUnknown(plan: ProtectedCaptionEditPlan, failure: Exception)
}

data class ProtectedCaptionTarget(val contentId: ContentId, val expiresAtEpochMillis: Long)

data class ProtectedCaptionEditPlan(
    val targetEventId: String,
    val operationId: String,
    val childContentId: ContentId,
)

object UnavailableProtectedCaptionLineageWiring : ProtectedCaptionLineageWiring {
    override suspend fun prepare(targetEventId: String, operationId: String, targetContentId: ContentId, targetExpiresAt: Long): ProtectedCaptionEditPlan =
        throw SecurityException("Protected caption version lineage is unavailable.")

    override suspend fun protectionOutcomeUnknown(plan: ProtectedCaptionEditPlan, failure: Exception) = Unit

    override suspend fun matrixSendAccepted(plan: ProtectedCaptionEditPlan) = Unit

    override suspend fun matrixSendOutcomeUnknown(plan: ProtectedCaptionEditPlan, failure: Exception) = Unit
}
