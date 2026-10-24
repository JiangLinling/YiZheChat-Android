/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.api

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Local resource scheduling only; never grants access or changes lifecycle/proof identity. */
enum class KmsTransportPriority {
    CURRENT_MESSAGE,
    USER_MEDIA,
    FOREGROUND_BOOTSTRAP,
    BACKGROUND,
}

class KmsTransportSchedulingContext(val priority: KmsTransportPriority) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<KmsTransportSchedulingContext>
}
