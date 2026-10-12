/* SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.messages.impl.timeline

import android.graphics.Rect
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.libraries.matrix.api.timeline.ReceiptType
import timber.log.Timber

/** Loading a composition behind a hidden startup surface must not consume unread messages. */
@Composable
internal fun rememberTimelineUserVisible(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var shown by remember(view) { mutableStateOf(false) }
    val focused = LocalWindowInfo.current.isWindowFocused
    DisposableEffect(lifecycle, view) {
        val visibleRect = Rect()
        fun refresh() {
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            shown = view.isShown && view.getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ -> refresh() }
        val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { refresh() }
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { refresh() }
        var tree = view.viewTreeObserver
        fun bindTree() {
            tree = view.viewTreeObserver
            tree.addOnGlobalLayoutListener(layoutListener)
            tree.addOnWindowFocusChangeListener(focusListener)
        }
        fun unbindTree() {
            if (tree.isAlive) {
                tree.removeOnGlobalLayoutListener(layoutListener)
                tree.removeOnWindowFocusChangeListener(focusListener)
            }
        }
        val attachmentListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { unbindTree(); bindTree(); refresh() }
            override fun onViewDetachedFromWindow(v: View) { shown = false; unbindTree() }
        }
        lifecycle.addObserver(lifecycleObserver)
        view.addOnAttachStateChangeListener(attachmentListener)
        bindTree()
        refresh()
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            view.removeOnAttachStateChangeListener(attachmentListener)
            unbindTree()
        }
    }
    LaunchedEffect(resumed, focused, shown) {
        // Boolean-only diagnostic: no room, user, message, or event identity.
        Timber.tag("YiZheReadVisibility").d("resumed=%s focused=%s shown=%s", resumed, focused, shown)
    }
    return resumed && focused && shown
}

/** Group public receipt policy controls disclosure, not the user's private unread bookkeeping. */
internal fun roomReadReceiptType(isDm: Boolean, groupPublicAllowed: Boolean, sendPublic: Boolean): ReceiptType =
    if (sendPublic && (isDm || groupPublicAllowed)) ReceiptType.READ else ReceiptType.READ_PRIVATE
