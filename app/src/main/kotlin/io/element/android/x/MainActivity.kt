/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumble.appyx.core.integrationpoint.NodeActivity
import com.bumble.appyx.core.plugin.NodeReadyObserver
import io.element.android.appnav.LocalCompactStartup
import io.element.android.appnav.LocalStartupSplashController
import io.element.android.appnav.StartupSplashController
import io.element.android.appnav.revealStartupSplashWhenLaidOut
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.lockscreen.api.LockScreenEntryPoint
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.features.lockscreen.api.handleSecureFlag
import io.element.android.features.lockscreen.api.applyScreenCaptureProtection
import io.element.android.features.lockscreen.api.shouldHideProtectedContent
import io.element.android.libraries.androidutils.clipboard.ClipboardIngress
import io.element.android.libraries.androidutils.clipboard.InAppClipboard
import io.element.android.libraries.androidutils.startup.StartupTrace
import io.element.android.libraries.architecture.appyx.DebugNavStateNodeHost
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.designsystem.theme.ElementThemeApp
import io.element.android.libraries.designsystem.utils.snackbar.LocalSnackbarDispatcher
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.services.analytics.compose.LocalAnalyticsService
import io.element.android.services.appnavstate.api.ROOM_OPENED_FROM_NOTIFICATION
import io.element.android.x.di.AppBindings
import io.element.android.x.intent.SafeUriHandler
import io.element.android.x.update.AppUpdateGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

private val loggerTag = LoggerTag("MainActivity")
private const val MAX_SYSTEM_SPLASH_VISIBLE_MILLIS = 30_000L

@Suppress("DEPRECATION")
private class InAppComposeClipboardManager(
    private val context: Context,
) : ClipboardManager {
    override fun setText(annotatedString: AnnotatedString) {
        InAppClipboard.copyPlainText(annotatedString.text)
    }

    override fun getText(): AnnotatedString? = ClipboardIngress.getPlainText(context)?.let(::AnnotatedString)

    override fun hasText(): Boolean = ClipboardIngress.hasPlainText(context)
}

private class InAppComposeClipboard(
    private val context: Context,
    override val nativeClipboard: android.content.ClipboardManager,
) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? {
        val text = ClipboardIngress.getPlainText(context) ?: return null
        return ClipEntry(ClipData.newPlainText("", text))
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.clipData
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
        if (text == null) {
            InAppClipboard.clear()
        } else {
            InAppClipboard.copyPlainText(text)
        }
    }
}

open class MainActivity : NodeActivity() {
    protected open val useAppStartupSplash: Boolean = true
    private lateinit var mainNode: MainNode
    private lateinit var appBindings: AppBindings
    @Volatile private var keepSystemSplashVisible = true
    private var startupDestinationPrepared = false
    private var startupLockState: LockScreenLockState = LockScreenLockState.Locked
    private val startupSplashController = StartupSplashController {
        startupDestinationPrepared = true
        releaseSystemSplashIfPossible()
    }
    private var mainContentShown = false
    private var startupFailureShown = false
    private var configuredPin: Boolean? = null
    private var protectedActivityResumed = false
    private val foregroundContentHidden = MutableStateFlow(true)
    private var foregroundCurtain: View? = null
    private var screenCaptureCallback: Activity.ScreenCaptureCallback? = null
    private var screenCaptureCallbackRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTrace.mark("MainActivity.onCreate")
        Timber.tag(loggerTag.value).d("onCreate, with savedInstanceState: ${savedInstanceState != null}")
        if (useAppStartupSplash) {
            installSplashScreen().apply {
                setKeepOnScreenCondition { keepSystemSplashVisible }
                // The destination must still pass every startup gate and be laid out.
                // Remove the system overlay atomically instead of fading through its background.
                setOnExitAnimationListener { provider ->
                    provider.remove()
                    StartupTrace.mark("MainActivity.SystemSplash.removed")
                }
            }
        }
        super.onCreate(savedInstanceState)
        StartupTrace.mark("MainActivity.super.onCreate")
        appBindings = runCatching { bindings<AppBindings>() }.getOrElse { failure ->
            Timber.tag(loggerTag.value).e(failure, "Application bindings are unavailable")
            showNativeStartupFailure()
            return
        }
        StartupTrace.mark("MainActivity.bindings")
        recordRestoredConfigurationChange(savedInstanceState)
        enableEdgeToEdge()
        window.decorView.doOnPreDraw {
            StartupTrace.mark("MainActivity.firstFrame")
        }
        lifecycleScope.launch {
            (application as ElementXApplication).startupState.collect { state ->
                when (state) {
                    ElementXApplication.StartupState.Initializing -> Unit
                    ElementXApplication.StartupState.Ready -> showMainContent()
                    is ElementXApplication.StartupState.Failed -> {
                        showNativeStartupFailure()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                delay(MAX_SYSTEM_SPLASH_VISIBLE_MILLIS)
                if (keepSystemSplashVisible && !mainContentShown) {
                    // A slow initializer is still running. READY can restore this surface later.
                    showNativeStartupFailure(timedOut = true)
                } else if (mainContentShown && startupLockState == LockScreenLockState.Unlocked) {
                    // Compose owns recoverable preparation; never replace it with a native error.
                    keepSystemSplashVisible = false
                }
            }
        }
    }

    private fun showNativeStartupFailure(timedOut: Boolean = false, retryable: Boolean = false) {
        if (startupFailureShown) return
        startupFailureShown = true
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val startupBackground = getColor(cn.yizhesy.yizhechat.appicon.R.color.yizhe_launcher_background)
        val startupTextPrimary = getColor(cn.yizhesy.yizhechat.appicon.R.color.yizhe_startup_text_primary)
        val startupTextSecondary = getColor(cn.yizhesy.yizhechat.appicon.R.color.yizhe_startup_text_secondary)
        val failureContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(
                TextView(this@MainActivity).apply {
                text = getString(
                    if (timedOut) {
                        R.string.screen_application_startup_timeout_title
                    } else {
                        R.string.screen_application_startup_failed_title
                    }
                )
                setTextColor(startupTextPrimary)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 20f
                gravity = Gravity.CENTER
            },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(32)
                    marginEnd = dp(32)
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = getString(
                        if (timedOut) {
                            R.string.screen_application_startup_timeout_description
                        } else {
                            R.string.screen_application_startup_failed_description
                        }
                    )
                    setTextColor(startupTextSecondary)
                    textSize = 14f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                },
            )
            addView(
                Button(this@MainActivity).apply {
                    text = getString(
                        if (retryable) {
                            R.string.screen_application_startup_retry
                        } else {
                            R.string.screen_application_startup_close
                        }
                    )
                    setOnClickListener {
                        if (retryable) recreate() else finishAndRemoveTask()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(24)
                },
            )
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(startupBackground)
                addView(
                    failureContent,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    ),
                )
            }
        )
        window.decorView.post {
            keepSystemSplashVisible = false
            StartupTrace.mark("MainActivity.StartupFailure.revealed")
        }
    }

    private fun showMainContent() {
        if (mainContentShown) return
        startupFailureShown = false
        mainContentShown = true
        val lockScreenService = appBindings.lockScreenService()
        startupLockState = lockScreenService.lockState.value
        setupLockManagement(lockScreenService, appBindings.lockScreenEntryPoint())
        StartupTrace.mark("MainActivity.setContent.begin")
        setContent {
            MainContentPresentation { MainContent(appBindings) }
        }
        onMainContentShown()
        StartupTrace.mark("MainActivity.setContent.end")
    }

    protected open fun onMainContentShown() = Unit
    protected open fun onMainSurfaceColor(color: Int) = Unit

    @Composable
    protected open fun MainContentPresentation(content: @Composable () -> Unit) { content() }

    @Suppress("DEPRECATION")
    @Composable
    private fun MainContent(appBindings: AppBindings) {
        LaunchedEffect(Unit) { StartupTrace.mark("MainActivity.MainContent") }
        val colors by remember {
            appBindings.enterpriseService().semanticColorsFlow(sessionId = null)
        }.collectAsState(SemanticColorsLightDark.default)
        val inAppClipboard = remember {
            InAppComposeClipboard(
                context = this,
                nativeClipboard = requireNotNull(getSystemService(android.content.ClipboardManager::class.java)),
            )
        }
        val inAppClipboardManager = remember { InAppComposeClipboardManager(this) }
        CompositionLocalProvider(
            LocalStartupSplashController provides startupSplashController,
            LocalCompactStartup provides !useAppStartupSplash,
        ) {
            ElementThemeApp(
                appPreferencesStore = appBindings.preferencesStore(),
                featureFlagService = appBindings.featureFlagService(),
                compoundLight = colors.light,
                compoundDark = colors.dark,
                buildMeta = appBindings.buildMeta()
            ) {
                val surfaceColor = ElementTheme.colors.bgCanvasDefault.toArgb()
                SideEffect { onMainSurfaceColor(surfaceColor) }
                CompositionLocalProvider(
                LocalSnackbarDispatcher provides appBindings.snackbarDispatcher(),
                LocalClipboard provides inAppClipboard,
                LocalClipboardManager provides inAppClipboardManager,
                LocalUriHandler provides SafeUriHandler(this),
                LocalAnalyticsService provides appBindings.analyticsService(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ElementTheme.colors.bgCanvasDefault),
                    ) {
                        AppUpdateGate(
                            managementControlService = appBindings.managementControlService(),
                            networkMonitor = appBindings.networkMonitor(),
                            securityRefreshService = appBindings.managementControlCoordinator(),
                            appOperationalStateCoordinator = appBindings.appOperationalStateCoordinator(),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                ExistingAppContent(appBindings)
                                val lockState by appBindings.lockScreenService().lockState.collectAsState()
                                if (lockState == LockScreenLockState.Unlocked) {
                                    appBindings.foregroundNotificationHost().Render()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ExistingAppContent(appBindings: AppBindings) {
        LaunchedEffect(Unit) { StartupTrace.mark("MainActivity.migration.begin") }
        val migrationState = appBindings.migrationEntryPoint().present()
        LaunchedEffect(migrationState.migrationAction.isSuccess()) {
            if (migrationState.migrationAction.isSuccess()) StartupTrace.mark("MainActivity.migration.ready")
        }
        if (migrationState.migrationAction.isSuccess()) {
            MainNodeHost()
        } else {
            appBindings.migrationEntryPoint().Render(
                state = migrationState,
                modifier = Modifier.revealStartupSplashWhenLaidOut(
                    enabled = !migrationState.migrationAction.isUninitialized(),
                ),
            )
        }
    }

    @Composable
    private fun MainNodeHost() {
        StartupTrace.mark("MainActivity.MainNodeHost")
        // TODO this is a temporary helper to capture the nav state in a more readable format for crash reports
        // Revert to `NodeHost` once this is fixed
        DebugNavStateNodeHost(integrationPoint = appyxV1IntegrationPoint) {
            MainNode(
                it,
                plugins = listOf(
                    object : NodeReadyObserver<MainNode> {
                        override fun init(node: MainNode) {
                            Timber.tag(loggerTag.value).d("onMainNodeInit")
                            mainNode = node
                            handleIntentWithLifecycle(intent)
                        }
                    },
                ),
                context = applicationContext
            )
        }
    }

    private fun setupLockManagement(
        lockScreenService: LockScreenService,
        lockScreenEntryPoint: LockScreenEntryPoint
    ) {
        lockScreenService.handleSecureFlag(
            activity = this,
            policyProvider = appBindings.dataProtectionPolicyProvider(),
            policyRevision = appBindings.managementControlCoordinator().policyRevision,
            localContentHidden = foregroundContentHidden,
        )
        lifecycleScope.launch {
            lockScreenService.isPinSetup().collect { configured ->
                configuredPin = configured
                updateForegroundProtection()
            }
        }
        lifecycleScope.launch {
            // Keep the native window protected while STOPPED, before task snapshots and resume drawing.
            lockScreenService.lockState.collect { updateForegroundProtection() }
        }
        updateForegroundProtection()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                lockScreenService.lockState.collect { state ->
                    startupLockState = state
                    if (state == LockScreenLockState.Locked) {
                        InAppClipboard.clear()
                        startActivity(lockScreenEntryPoint.pinUnlockIntent(this@MainActivity))
                    } else {
                        releaseSystemSplashIfPossible()
                    }
                }
            }
        }
    }

    private fun updateForegroundProtection() {
        if (!::appBindings.isInitialized || !mainContentShown) return
        val hidden = shouldHideProtectedContent(
            configuredPin,
            protectedActivityResumed,
            appBindings.lockScreenService().lockState.value,
        )
        foregroundContentHidden.value = hidden
        applyScreenCaptureProtection(this, appBindings.dataProtectionPolicyProvider(), hidden)
        if (hidden && foregroundCurtain == null) {
            foregroundCurtain = View(this).apply {
                setBackgroundColor(getColor(cn.yizhesy.yizhechat.appicon.R.color.yizhe_launcher_background))
                isClickable = true
                isFocusable = true
                elevation = 1_000f
            }.also { curtain ->
                (window.decorView as ViewGroup).addView(
                    curtain,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            }
        }
        foregroundCurtain?.visibility = if (hidden) View.VISIBLE else View.GONE
    }

    private fun releaseSystemSplashIfPossible() {
        if (!keepSystemSplashVisible || !startupDestinationPrepared || startupLockState != LockScreenLockState.Unlocked) return
        keepSystemSplashVisible = false
        StartupTrace.mark("MainActivity.SystemSplash.released")
    }

    /**
     * Called when:
     * - the launcher icon is clicked (if the app is already running);
     * - a notification is clicked.
     * - a deep link have been clicked
     * - the app is going to background (<- this is strange)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Timber.tag(loggerTag.value).d("onNewIntent")
        // If the mainNode is not init yet, keep the intent for later.
        // It can happen when the activity is killed by the system. The methods are called in this order :
        // onCreate(savedInstanceState=true) -> onNewIntent -> onResume -> onMainNodeInit
        if (::mainNode.isInitialized) {
            handleIntentWithLifecycle(intent)
        } else {
            setIntent(intent)
        }
    }

    private fun handleIntentWithLifecycle(intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> "share_intent"
            Intent.ACTION_VIEW -> if (intent.getBooleanExtra(ROOM_OPENED_FROM_NOTIFICATION, false)) "notification_tap" else "deep_link"
            else -> null
        }
        if (trigger == null) {
            lifecycleScope.launch {
                runCatching { mainNode.handleIntent(intent) }
                    .onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        Timber.tag(loggerTag.value).w(failure, "Navigation entry failed")
                    }
            }
            return
        }
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            var dispatched = false
            runCatching {
                appBindings.appOperationalStateCoordinator().perform(
                    lifecycleOperationRequest(FullAppLifecycleId.LC_04, trigger)
                ) {
                    mainNode.handleIntent(intent)
                    dispatched = true
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                Timber.tag(loggerTag.value).w(failure, "Lifecycle entry failed for external intent")
                if (!dispatched) {
                    runCatching { mainNode.handleIntent(intent) }.onFailure { navigationFailure ->
                        if (navigationFailure is CancellationException) throw navigationFailure
                        Timber.tag(loggerTag.value).w(navigationFailure, "External target could not be opened")
                    }
                }
            }
        }
    }

    private fun recordRestoredConfigurationChange(savedInstanceState: Bundle?) {
        val previousFontScale = savedInstanceState?.getFloat(SAVED_FONT_SCALE, Float.NaN) ?: Float.NaN
        val previousLocales = savedInstanceState?.getString(SAVED_LOCALES)
        val trigger = when {
            !previousFontScale.isNaN() && previousFontScale != resources.configuration.fontScale -> "font_scale_change"
            previousLocales != null && previousLocales != resources.configuration.locales.toLanguageTags() -> "locale_change"
            else -> null
        } ?: return
        appBindings.appCoroutineScope().launch(Dispatchers.Main.immediate) {
            runCatching {
                appBindings.appOperationalStateCoordinator().perform(
                    lifecycleOperationRequest(FullAppLifecycleId.LC_24, trigger)
                ) { Unit }
            }.onFailure { failure ->
                Timber.tag(loggerTag.value).w(failure, "Configuration lifecycle entry failed")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat(SAVED_FONT_SCALE, resources.configuration.fontScale)
        outState.putString(SAVED_LOCALES, resources.configuration.locales.toLanguageTags())
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        updateForegroundProtection()
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = screenCaptureCallback ?: Activity.ScreenCaptureCallback {
                appBindings.appCoroutineScope().launch(Dispatchers.Main.immediate) {
                    runCatching {
                        appBindings.appOperationalStateCoordinator().perform(
                            lifecycleOperationRequest(FullAppLifecycleId.LC_09, "capture_attempt")
                        ) { Unit }
                    }.onFailure { failure ->
                        Timber.tag(loggerTag.value).w(failure, "Screen capture lifecycle entry failed")
                    }
                }
            }.also { screenCaptureCallback = it }
            runCatching {
                registerScreenCaptureCallback(mainExecutor, callback)
                screenCaptureCallbackRegistered = true
            }.onFailure { failure ->
                screenCaptureCallbackRegistered = false
                Timber.tag(loggerTag.value).w(failure, "Screen capture observer is unavailable on this Android build")
            }
        }
    }

    override fun onPause() {
        protectedActivityResumed = false
        updateForegroundProtection()
        if (::appBindings.isInitialized) appBindings.foregroundNotificationHost().setResumed(false)
        super.onPause()
        Timber.tag(loggerTag.value).d("onPause")
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (screenCaptureCallbackRegistered) {
                screenCaptureCallback?.let { callback ->
                    runCatching { unregisterScreenCaptureCallback(callback) }
                        .onFailure { failure ->
                            Timber.tag(loggerTag.value).w(failure, "Screen capture observer unregister failed")
                        }
                }
                screenCaptureCallbackRegistered = false
            }
        }
        InAppClipboard.clear()
        super.onStop()
        Timber.tag(loggerTag.value).d("onStop")
    }

    override fun onResume() {
        protectedActivityResumed = true
        updateForegroundProtection()
        super.onResume()
        Timber.tag(loggerTag.value).d("onResume")
        if (::appBindings.isInitialized) {
            appBindings.foregroundNotificationHost().setResumed(true)
            lifecycleScope.launch {
                runCatching {
                    appBindings.appOperationalStateCoordinator().perform(
                        lifecycleOperationRequest(FullAppLifecycleId.LC_09, "foreground")
                    ) { Unit }
                }.onFailure { failure ->
                    Timber.tag(loggerTag.value).w(failure, "Foreground security lifecycle entry failed")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(loggerTag.value).d("onDestroy")
    }

    private companion object {
        const val SAVED_FONT_SCALE = "lifecycle.saved_font_scale"
        const val SAVED_LOCALES = "lifecycle.saved_locales"
    }
}
