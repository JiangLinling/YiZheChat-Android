/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.security

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A local fence for one RTC admission attempt.
 *
 * This does not authorize a call. It prevents a late widget/admission result from attaching its
 * driver after the owning screen has already ended the attempt. The authoritative admission and
 * provider state continue to come from the existing protection, Matrix and RTC services.
 */
internal class CallJoinAttempt {
    private val active = AtomicBoolean(true)

    fun cancel(): Boolean = active.getAndSet(false)

    fun isActive(): Boolean = active.get()
}
