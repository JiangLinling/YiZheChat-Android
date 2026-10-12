/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

const val MEDIA_RESOURCE_CLOSURE_PROTOCOL = "media-resource-closure-v1"

enum class LifecycleMediaResourceType { LOCAL_MEDIA_OBJECT, ANDROID_MEDIA_RUNTIME }

data class LifecycleMediaResource(val type: LifecycleMediaResourceType, val recordSha256: String) {
    init { require(recordSha256.matches(Regex("[0-9a-f]{64}"))) }
}

data class LifecycleMediaReservation(
    val operationId: String,
    val subjects: LifecycleSubjectHashes,
    val resources: List<LifecycleMediaResource>,
) {
    init {
        requireMediaClosureScope(operationId, subjects)
        require(resources.size in 1..4096)
        require(resources == resources.distinct().sortedWith(compareBy({ it.type.name }, { it.recordSha256 })))
    }
}

enum class LifecycleMediaOutcome { SUCCEEDED, FAILED_FINAL, CANCELLED }

/** Producer closure is immutable; it does not assert that any consumer has released its resource. */
data class LifecycleMediaSeal(
    val reservation: LifecycleMediaReservation,
    val ownerProcessSha256: String,
    val sealedAtEpochMillis: Long,
    val outcome: LifecycleMediaOutcome,
) {
    init {
        require(ownerProcessSha256.matches(Regex("[0-9a-f]{64}")))
        require(sealedAtEpochMillis > 0)
        require(reservation.resources.any { it.type == LifecycleMediaResourceType.ANDROID_MEDIA_RUNTIME })
    }
}

/** Supplied only by the real runtime owner after all three observed conditions hold. */
data class LifecycleMediaRuntimeCleanup(
    val operationId: String,
    val subjects: LifecycleSubjectHashes,
    val recordSha256: String,
    val releasedAtEpochMillis: Long,
    val runtimeReleased: Boolean,
    val activeWorkFinished: Boolean,
    val ownedPayloadCleared: Boolean,
) {
    init {
        requireMediaClosureScope(operationId, subjects)
        require(recordSha256.matches(Regex("[0-9a-f]{64}")))
        require(releasedAtEpochMillis > 0)
        require(runtimeReleased && activeWorkFinished && ownedPayloadCleared)
    }
}

private fun requireMediaClosureScope(operationId: String, subjects: LifecycleSubjectHashes) {
    require(operationId.matches(Regex("[A-Za-z0-9_-]{16,256}")))
    require(subjects.installationId != null && subjects.installationId.length in 16..256)
    require(subjects.accountIdHash != null && subjects.matrixDeviceIdHash != null)
}
