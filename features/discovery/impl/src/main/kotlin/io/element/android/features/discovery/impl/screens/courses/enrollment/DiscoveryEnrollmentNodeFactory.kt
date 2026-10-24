/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import com.bumble.appyx.core.modality.BuildContext
import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.session.AndroidDiscoveryIdentityGate
import io.element.android.features.discovery.impl.session.AndroidDiscoveryClock
import io.element.android.features.discovery.impl.screens.member.PersonEditTarget
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

@Inject
class DiscoveryEnrollmentNodeFactory internal constructor(
    private val port: DefaultDiscoveryEnrollmentPort,
    private val clock: AndroidDiscoveryClock,
    gate: AndroidDiscoveryIdentityGate,
    @SessionCoroutineScope scope: CoroutineScope,
) {
    private val accountRevision = gate.scope.runningFold(0L) { revision, _ -> revision + 1 }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    internal fun create(buildContext: BuildContext, courseId: Long, sessionId: Long, resumed: StateFlow<Boolean>,
        resumeRevision: StateFlow<Long>, onBack: () -> Unit, onCourses: () -> Unit,
        onAddChild: (() -> Unit)? = null, onEditTarget: ((PersonEditTarget) -> Unit)? = null, onOrder: ((String) -> Unit)? = null,
        returnedPerson: StateFlow<Long?>? = null, onPersonConsumed: () -> Unit = {}) =
        DiscoveryEnrollmentNode(buildContext, courseId, sessionId, port, accountRevision, resumed, resumeRevision, clock::wallMillis,
            onBack, onAddChild, onEditTarget, onOrder, onCourses, returnedPerson, onPersonConsumed)
}
