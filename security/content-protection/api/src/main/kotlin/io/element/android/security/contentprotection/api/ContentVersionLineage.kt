/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.api

import io.element.android.security.securestorage.api.AccountVaultId

data class ContentVersionLineageScope(
    val vaultId: AccountVaultId,
    val roomId: String,
)

data class ContentVersionNode(
    val contentId: ContentId,
    val rootContentId: ContentId,
    val parentContentId: ContentId?,
    val operationId: String,
    val eventIdSha256: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

data class ContentVersionGraph(
    val rootContentId: ContentId,
    val generation: Long,
    val versions: List<ContentVersionNode>,
    /** Encrypted snapshots which remain journalled until their real object deletion succeeds. */
    val pendingRetirementCount: Int,
)

enum class ContentVersionRetirementState {
    CLEAN,
    PENDING,
}

data class ContentVersionAppendResult(
    /** The graph is durably committed whenever this result is returned. */
    val graph: ContentVersionGraph,
    /** PENDING means the append committed but at least one superseded encrypted snapshot still has cleanup debt. */
    val retirementState: ContentVersionRetirementState,
)

class ContentVersionLineageConflictException(message: String) : IllegalStateException(message)

class ContentVersionLineageCorruptException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

interface ContentVersionLineageStore {
    /** Atomically imports a complete authoritative graph; never publishes a partial prefix or overwrites a conflict. */
    suspend fun restoreAuthorityGraph(scope: ContentVersionLineageScope, nodes: List<ContentVersionNode>): ContentVersionGraph =
        throw ContentProtectionException.VersionLineageUnavailable()

    /**
     * Atomically appends one immutable version. Repeating the same operation and node is idempotent.
     * Reusing an operation for different data, adding a missing-parent branch, or extending a parent's expiry fails closed.
     */
    suspend fun append(scope: ContentVersionLineageScope, node: ContentVersionNode): ContentVersionAppendResult

    /** Enumerates the complete, validated descendant graph for [rootContentId]. */
    suspend fun graph(scope: ContentVersionLineageScope, rootContentId: ContentId): ContentVersionGraph?

    /** Exact scoped membership lookup; no event hash inference or historical reconstruction. */
    suspend fun rootForContent(scope: ContentVersionLineageScope, contentId: ContentId): ContentId? =
        graph(scope, contentId)?.rootContentId

    /** Retries encrypted-snapshot deletion debt without discarding an unresolved journal entry. */
    suspend fun retryPendingRetirements(scope: ContentVersionLineageScope): Int
}
