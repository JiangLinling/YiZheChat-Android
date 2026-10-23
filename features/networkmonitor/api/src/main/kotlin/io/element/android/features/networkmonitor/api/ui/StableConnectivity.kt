package io.element.android.features.networkmonitor.api.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Duration.Companion.seconds

/** Presentation only: an unsuccessful reconnect must not repeatedly resize the whole screen. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Boolean>.stableIndicatorConnectivity(): Flow<Boolean> = distinctUntilChanged()
    .transformLatest { online ->
        // Show failure immediately; hide only after the connection stays recovered.
        // This never changes sync state, authorization or the sending gate.
        if (online) delay(2.seconds)
        emit(online)
    }
    .distinctUntilChanged()
