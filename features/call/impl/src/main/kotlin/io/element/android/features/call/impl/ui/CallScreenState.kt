/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.ui

import io.element.android.libraries.architecture.AsyncData

data class CallScreenState(
    val urlState: AsyncData<String>,
    val webViewError: String?,
    val userAgent: String,
    val isCallActive: Boolean,
    val eventSink: (CallScreenEvent) -> Unit,
    val peerName: String? = null,
    val peerAvatarUrl: String? = null,
    val isAudioCall: Boolean = true,
    val isIncoming: Boolean = false,
    val isGroupCall: Boolean = false,
    val isNetworkDisconnected: Boolean = false,
    val isTerminating: Boolean = false,
    val isMediaConnected: Boolean = false,
    val connectedSeconds: Long = 0,
    // Protected local HTML and passive protocol negotiation may overlap native admission.
    // Identity, room writes, keys and media permissions still wait for Success.
    val preparationUrl: String? = null,
)
