/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.share.api

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.JoinedRoom

/**
 * Result of handling an incoming share.
 *
 * [roomToHandoff] is only set for a single-room share when the share flow exclusively owns the
 * room instance that contains the local echo. Ownership transfers to navigation with this result;
 * this keeps the same timeline authoritative for upload progress, failure and remote echo.
 */
data class ShareResult(
    val roomIds: List<RoomId>,
    val roomToHandoff: JoinedRoom? = null,
)
