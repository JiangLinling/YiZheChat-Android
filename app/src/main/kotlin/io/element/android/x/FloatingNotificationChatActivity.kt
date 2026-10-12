/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.x

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Window presentation only. MainActivity retains admission, lock, navigation, drafts and sending. */
class FloatingNotificationChatActivity : MainActivity() {
    override val useAppStartupSplash = false
    private var requestedScale = .8f
    private var contentScale by mutableFloatStateOf(.8f)
    private var centerX = .5f
    private var centerY = .5f
    private var imeBottom = 0
    private var resizePreviewActive = false
    private var resizePreviewEnding = false
    private var previewBaseScale = .8f
    private var previewScale = .8f
    private var sizeFramePending = false
    private var previewLayoutWidth = -1
    private var previewLayoutHeight = -1
    private val sizeFrame = Runnable {
        sizeFramePending = false
        if (resizePreviewActive) {
            previewScale += (requestedScale - previewScale) * .55f
            if (abs(requestedScale - previewScale) < .0005f) previewScale = requestedScale
            updateSize(previewScale)
            if (previewScale != requestedScale) scheduleSize()
            else if (resizePreviewEnding) finishResizePreview()
        } else updateSize()
    }
    private var chromeMounted = false
    private var surfaceColor = Color.TRANSPARENT
    private var fittedScale = .8f
    private var appliedSurfaceColor: Int? = null
    private var appliedCornerRadius = -1f
    private val keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        updateImeHeight()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedScale = savedInstanceState?.getFloat("floating_scale", .8f) ?: .8f
        centerX = savedInstanceState?.getFloat("floating_center_x", .5f) ?: .5f
        centerY = savedInstanceState?.getFloat("floating_center_y", .5f) ?: .5f
        super.onCreate(savedInstanceState)
        // This window owns proportional keyboard fitting; platform pan/resize must not compete.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.decorView.setPadding(0, 0, 0, 0)
        // A cold process gets a neutral, bounded surface, never the main application's logo.
        val root = findViewById<FrameLayout>(android.R.id.content)
        if (root.childCount == 0) {
            root.addView(ProgressBar(this), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            updateImeHeight()
            insets
        }
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
        updateSize()
    }

    @Composable
    override fun MainContentPresentation(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        // Reuse the real chat tree. dp, sp, hit testing and IME coordinates share one density.
        // No screenshot texture or second message/composer implementation is introduced.
        CompositionLocalProvider(LocalDensity provides Density(density.density * contentScale, density.fontScale)) {
            // Native window bounds already exclude the keyboard; do not subtract it a second time.
            Box(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.ime)) { content() }
        }
    }

    override fun onMainContentShown() {
        val root = findViewById<FrameLayout>(android.R.id.content)
        root.getChildAt(0)?.layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(0, dp(24), 0, dp(20))
        }
        chromeMounted = false // setContent replaces the previous native children during recovery.
        mountChrome(root)
        updateSize()
    }

    override fun onMainSurfaceColor(color: Int) {
        surfaceColor = color
        updateSurface()
    }

    private fun updateSurface() {
        val radius = if (fittedScale >= .99f) 0f else dp(22).toFloat()
        val root = findViewById<FrameLayout>(android.R.id.content)
        if (appliedSurfaceColor == surfaceColor && appliedCornerRadius == radius && root.background != null) return
        appliedSurfaceColor = surfaceColor
        appliedCornerRadius = radius
        root.apply {
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = radius
            }
            clipToOutline = true
            elevation = dp(12).toFloat()
        }
    }

    private fun mountChrome(root: FrameLayout) {
        if (chromeMounted) return
        chromeMounted = true
        for (left in listOf(true, false)) {
            val handle = CornerHandle(left).apply {
                contentDescription = getString(if (left) R.string.a11y_notification_window_left else R.string.a11y_notification_window_right)
            }
            bindResize(handle, left)
            root.addView(handle, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or if (left) Gravity.LEFT else Gravity.RIGHT))
        }
        val grip = TextView(this).apply {
            text = "•••"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.GRAY)
            contentDescription = getString(R.string.a11y_notification_window_move)
        }
        var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f
        grip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = centerX; startY = centerY
                }
                MotionEvent.ACTION_MOVE -> {
                    val safe = safeBounds()
                    centerX = startX + (event.rawX - downX) / safe.width()
                    centerY = startY + (event.rawY - downY) / safe.height()
                    updateSize()
                }
                MotionEvent.ACTION_UP -> grip.performClick()
            }
            true
        }
        root.addView(grip, FrameLayout.LayoutParams(dp(64), dp(24), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(TextView(this).apply {
            text = "×"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.GRAY)
            contentDescription = getString(R.string.a11y_notification_window_close)
            setOnClickListener { finishAndRemoveTask() }
        }, FrameLayout.LayoutParams(dp(40), dp(28), Gravity.TOP or Gravity.RIGHT))
    }

    private fun bindResize(handle: CornerHandle, left: Boolean) {
        var downX = 0f; var downY = 0f; var initialScale = 1f
        var baseWidth = 1; var baseHeight = 1; var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (resizePreviewActive) finishResizePreview()
                    // Let touches on the composer behind the empty part of this view pass through.
                    if (!handle.hitsCurve(event.x, event.y)) return@setOnTouchListener false
                    downX = event.rawX; downY = event.rawY; dragging = false
                    val safe = safeBounds()
                    baseWidth = safe.width(); baseHeight = safe.height()
                    val position = geometry(safe)
                    initialScale = position.scale
                    // Start from the actual fitted centre, including movement and keyboard limits.
                    centerX = (position.left + position.width / 2f) / baseWidth
                    centerY = (position.top + position.height / 2f) / baseHeight
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        beginResizePreview(initialScale)
                    }
                    if (dragging) {
                        requestedScale = floatingChatDragScale(initialScale, baseWidth, baseHeight, left, dx, dy)
                        scheduleSize()
                    }
                }
                // A tap deliberately has no resize/minimize action. CANCEL retains the last size.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) { resizePreviewEnding = true; scheduleSize() }
                    else if (event.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                }
            }
            true
        }
    }

    private fun scheduleSize() {
        if (!sizeFramePending) {
            sizeFramePending = true
            window.decorView.postOnAnimation(sizeFrame)
        }
    }

    private fun beginResizePreview(scale: Float) {
        val root = findViewById<FrameLayout>(android.R.id.content)
        previewLayoutWidth = root.layoutParams.width; previewLayoutHeight = root.layoutParams.height
        previewBaseScale = scale; previewScale = scale
        resizePreviewActive = true; resizePreviewEnding = false
        root.layoutParams = root.layoutParams.apply { width = root.width; height = root.height }
        root.pivotX = 0f; root.pivotY = 0f
        // The real view tree stays mounted. GPU transforms move text, media, composer and chrome
        // together without rebuilding all message rows for each finger sample.
        root.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    private fun finishResizePreview() {
        if (!resizePreviewActive) return
        window.decorView.removeCallbacks(sizeFrame); sizeFramePending = false
        resizePreviewActive = false; resizePreviewEnding = false
        val root = findViewById<FrameLayout>(android.R.id.content)
        root.scaleX = 1f; root.scaleY = 1f
        root.setLayerType(View.LAYER_TYPE_NONE, null)
        root.layoutParams = root.layoutParams.apply { width = previewLayoutWidth; height = previewLayoutHeight }
        updateSize()
    }

    private fun safeBounds(): Rect {
        val metrics = windowManager.maximumWindowMetrics
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
        )
        return Rect(bars.left, bars.top, metrics.bounds.width() - bars.right, metrics.bounds.height() - bars.bottom)
    }

    private fun geometry(safe: Rect, scale: Float = requestedScale): FloatingChatWindowBounds {
        val displayBottom = windowManager.maximumWindowMetrics.bounds.bottom
        return floatingChatWindowBounds(safe.width(), safe.height(), scale, centerX, centerY,
            floatingChatImeOcclusion(displayBottom, safe.bottom, imeBottom))
    }

    private fun updateSize(scale: Float = requestedScale) {
        val safe = safeBounds()
        val position = geometry(safe, scale)
        fittedScale = position.scale
        if (resizePreviewActive) {
            val root = findViewById<FrameLayout>(android.R.id.content)
            val ratio = position.scale / previewBaseScale
            root.scaleX = ratio; root.scaleY = ratio
        } else contentScale = (position.width.toFloat() / safe.width().coerceAtLeast(1)).coerceIn(.25f, 1f)
        val params = window.attributes
        val x = safe.left + position.left
        val y = safe.top + position.top
        // Avoid repeatedly relaying out the focused IME window for an unchanged geometry.
        if (params.width != position.width || params.height != position.height || params.x != x || params.y != y) {
            params.width = position.width; params.height = position.height
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.x = x; params.y = y
            window.attributes = params
        }
        if (!resizePreviewActive) updateSurface()
    }

    private fun updateImeHeight() {
        // A floating view's IME inset describes its current intersection with the keyboard.
        // Feeding it back into window sizing oscillates. Maximum metrics calculate server insets
        // against the full display, so the keyboard boundary does not change with our window.
        val metrics = windowManager.maximumWindowMetrics
        val height = metrics.windowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
        if (imeBottom != height) { imeBottom = height; updateSize() }
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(sizeFrame)
        window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(keyboardLayoutListener)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSize()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat("floating_scale", requestedScale)
        outState.putFloat("floating_center_x", centerX)
        outState.putFloat("floating_center_y", centerY)
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private inner class CornerHandle(private val left: Boolean) : View(this@FloatingNotificationChatActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND
        }
        fun hitsCurve(x: Float, y: Float): Boolean = floatingChatHandleHit(
            if (left) x / resources.displayMetrics.density else (width - x) / resources.displayMetrics.density,
            y / resources.displayMetrics.density,
        )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val path = Path().apply {
                if (left) {
                    moveTo(dp(8).toFloat(), dp(12).toFloat())
                    quadTo(dp(8).toFloat(), dp(40).toFloat(), dp(36).toFloat(), dp(40).toFloat())
                } else {
                    moveTo(dp(40).toFloat(), dp(12).toFloat())
                    quadTo(dp(40).toFloat(), dp(40).toFloat(), dp(12).toFloat(), dp(40).toFloat())
                }
            }
            canvas.drawPath(path, paint)
        }
    }
}

internal data class FloatingChatWindowBounds(val left: Int, val top: Int, val width: Int, val height: Int, val scale: Float)

internal fun floatingChatWindowBounds(width: Int, height: Int, scale: Float, centerX: Float, centerY: Float, imeBottom: Int): FloatingChatWindowBounds {
    val available = (height - imeBottom).coerceAtLeast(1)
    val fitted = min(scale.coerceIn(.4f, 1f), available.toFloat() / height.coerceAtLeast(1))
    val w = (width * fitted).roundToInt().coerceIn(1, width.coerceAtLeast(1))
    val h = (height * fitted).roundToInt().coerceIn(1, available)
    return FloatingChatWindowBounds(
        (width * centerX - w / 2f).roundToInt().coerceIn(0, width - w),
        (height * centerY - h / 2f).roundToInt().coerceIn(0, available - h),
        w, h, fitted,
    )
}

internal fun floatingChatDragScale(initial: Float, width: Int, height: Int, left: Boolean, dx: Float, dy: Float): Float {
    val signedX = if (left) -dx else dx
    return (initial + 2f * (signedX * width + dy * height) / (width.toFloat() * width + height.toFloat() * height)).coerceIn(.4f, 1f)
}

/** 7dp tolerance around the painted curve; transparent interior must not cover composer actions. */
internal fun floatingChatHandleHit(x: Float, y: Float): Boolean = (0..40).any { index ->
    val t = index / 40f
    val dx = x - (8f + 28f * t * t)
    val dy = y - (12f + 56f * t - 28f * t * t)
    dx * dx + dy * dy <= 49f
}

/** Full-display keyboard occlusion, excluding the navigation bar already removed from safe bounds. */
internal fun floatingChatImeOcclusion(displayBottom: Int, safeBottom: Int, globalImeBottom: Int): Int =
    (globalImeBottom - (displayBottom - safeBottom).coerceAtLeast(0)).coerceAtLeast(0)
