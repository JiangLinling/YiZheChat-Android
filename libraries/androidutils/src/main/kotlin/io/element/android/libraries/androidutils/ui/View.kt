/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.ui

import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

fun View.hideKeyboard() {
    val imm = context?.getSystemService<InputMethodManager>()
    imm?.hideSoftInputFromWindow(windowToken, 0)
}

suspend fun View.hideKeyboardAndAwaitAnimation() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false) {
        // Keyboard is already hidden, no need to do anything
        return
    }

    val imm = context?.getSystemService<InputMethodManager>() ?: return
    val future = CompletableDeferred<Unit>()

    // LocalView is often AndroidComposeView. Replacing its insets listener breaks
    // Compose's WindowInsetsHolder and leaves the last IME height on later screens.
    // Observe layout without taking ownership of that listener (or animation callback).
    val observer = viewTreeObserver
    val layoutListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        ViewTreeObserver.OnGlobalLayoutListener {
            if (rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false) {
                future.complete(Unit)
            }
        }.also(observer::addOnGlobalLayoutListener)
    } else {
        null
    }
    try {
        val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            imm.hideSoftInputFromWindow(windowToken, 0).also { requestApplyInsets() }
        } else {
            @Suppress("DEPRECATION")
            imm.hideSoftInputFromWindow(windowToken, 0, object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode == InputMethodManager.RESULT_UNCHANGED_HIDDEN || resultCode == InputMethodManager.RESULT_HIDDEN) {
                        future.complete(Unit)
                    }
                }
            })
        }
        if (requested) {
            withTimeoutOrNull(1.seconds) { future.await() }
        }
    } finally {
        if (layoutListener != null) {
            if (observer.isAlive) {
                observer.removeOnGlobalLayoutListener(layoutListener)
            } else {
                viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            }
        }
    }
}

fun View.showKeyboard(andRequestFocus: Boolean = false) {
    if (andRequestFocus) {
        requestFocus()
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowInsetsController?.show(WindowInsets.Type.ime())
    } else {
        val imm = context?.getSystemService<InputMethodManager>()
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}

fun View.isKeyboardVisible(): Boolean {
    val imm = context?.getSystemService<InputMethodManager>()
    return imm?.isAcceptingText == true
}

suspend fun View.awaitWindowFocus() = suspendCancellableCoroutine { continuation ->
    if (hasWindowFocus()) {
        continuation.resume(Unit)
    } else {
        val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (hasFocus) {
                    viewTreeObserver.removeOnWindowFocusChangeListener(this)
                    continuation.resume(Unit)
                }
            }
        }

        viewTreeObserver.addOnWindowFocusChangeListener(listener)

        continuation.invokeOnCancellation {
            viewTreeObserver.removeOnWindowFocusChangeListener(listener)
        }
    }
}
