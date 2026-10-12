/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav.securitysetup

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import io.element.android.appnav.R
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.androidutils.background.AutostartPermissionState
import io.element.android.libraries.androidutils.background.xiaomiApplicationDetailsSettingsIntent
import io.element.android.libraries.androidutils.background.xiaomiAutostartPermissionState
import io.element.android.libraries.designsystem.atomic.molecules.ButtonColumnMolecule
import io.element.android.libraries.designsystem.atomic.molecules.IconTitleSubtitleMolecule
import io.element.android.libraries.designsystem.atomic.pages.HeaderFooterPage
import io.element.android.libraries.designsystem.background.OnboardingBackground
import io.element.android.libraries.designsystem.components.BigIcon
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.OnLifecycleEvent
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class DeviceCapability {
    MESSAGE_NOTIFICATIONS,
    MESSAGE_BANNERS,
    MICROPHONE_CAMERA,
    CALL_ALERTS,
    DO_NOT_DISTURB,
    BACKGROUND_RUNTIME,
    AUTOSTART,
    BACKGROUND_DATA,
}

internal data class DeviceCapabilityUiModel(
    val capability: DeviceCapability,
    val completed: Boolean,
    val awaitingManualConfirmation: Boolean,
    val requiredForSetup: Boolean,
)

@Composable
internal fun DeviceCapabilitySetupRoute(
    appOperationalStateCoordinator: AppOperationalStateCoordinator,
    lifecycleCoroutineScope: CoroutineScope,
    approvalPhase: SecuritySetupPhase,
    sessionVerificationStatus: SessionVerifiedStatus,
    lockScreenSetupRequired: Boolean,
    analyticsChoiceComplete: Boolean,
    activationFinalizing: Boolean,
    onApprovalEvent: (SecuritySetupEvent) -> Unit,
    onOpenSessionVerification: () -> Unit,
    onOpenLockScreenSetup: () -> Unit,
    onAnalyticsChoice: (Boolean) -> Unit,
    onRequirementsChanged: () -> Unit,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences(DEVICE_SETUP_PREFERENCES, Context.MODE_PRIVATE)
    }
    val previouslyCompleted = remember {
        preferences.getBoolean(DEVICE_SETUP_COMPLETE_V2, false) ||
            preferences.getBoolean(DEVICE_SETUP_COMPLETE_V3, false) ||
            preferences.getBoolean(DEVICE_SETUP_COMPLETE_V4, false)
    }
    var refresh by remember { mutableIntStateOf(0) }
    var attemptedCapabilityNames by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var manuallyConfirmedNames by rememberSaveable {
        val migratedManualConfirmations = if (previouslyCompleted) {
            DeviceCapability.entries.filter(DeviceCapability::isManuallyConfirmed).mapTo(mutableSetOf()) { it.name }
        } else {
            mutableSetOf()
        }
        mutableStateOf(
            preferences.getStringSet(DEVICE_SETUP_MANUAL_CONFIRMED, emptySet()).orEmpty().toSet() +
                migratedManualConfirmations
        )
    }
    var pendingRuntimeRequest by remember { mutableStateOf<DeviceCapability?>(null) }
    var pendingRuntimeOperationId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOperationId by rememberSaveable { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val operationId = pendingRuntimeOperationId
        pendingRuntimeOperationId = null
        pendingRuntimeRequest = null
        refresh++
        onRequirementsChanged()
        lifecycleCoroutineScope.launch {
            runCatching {
                appOperationalStateCoordinator.perform(
                    lifecycleOperationRequest(
                        lifecycleId = FullAppLifecycleId.LC_03,
                        trigger = if (operationId == null) "permission_result" else "feature_requires_permission",
                        operationId = operationId ?: java.util.UUID.randomUUID().toString(),
                    )
                ) { Unit }
            }
        }
    }

    fun markAttempted(capability: DeviceCapability) {
        attemptedCapabilityNames = attemptedCapabilityNames + capability.name
    }

    fun confirmManualCapability(capability: DeviceCapability) {
        manuallyConfirmedNames = manuallyConfirmedNames + capability.name
        preferences.edit().putStringSet(DEVICE_SETUP_MANUAL_CONFIRMED, manuallyConfirmedNames).apply()
        onRequirementsChanged()
    }

    fun requestRuntimePermissions(capability: DeviceCapability, permissions: Array<String>) {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_03, "feature_requires_permission")
        pendingRuntimeOperationId = request.operationId
        lifecycleCoroutineScope.launch { runCatching { appOperationalStateCoordinator.start(request) } }
        pendingRuntimeRequest = capability
        markAttempted(capability)
        permissionLauncher.launch(permissions)
    }

    fun recordSettingsLaunch() {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_03, "settings_return")
        settingsOperationId = request.operationId
        lifecycleCoroutineScope.launch { runCatching { appOperationalStateCoordinator.start(request) } }
    }

    fun configureCapability(capability: DeviceCapability) {
        val alreadyCompleted = context.isCapabilityGranted(capability) ||
            (capability.isManuallyConfirmed() && capability.name in manuallyConfirmedNames)
        if (alreadyCompleted) {
            return
        }
        if (capability.isManuallyConfirmed() && capability.name in attemptedCapabilityNames) {
            confirmManualCapability(capability)
            return
        }
        when (capability) {
            DeviceCapability.MESSAGE_NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && capability.name !in attemptedCapabilityNames) {
                    requestRuntimePermissions(capability, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    markAttempted(capability)
                    recordSettingsLaunch()
                    context.openNotificationSettings()
                }
            }
            DeviceCapability.MESSAGE_BANNERS -> {
                markAttempted(capability)
                recordSettingsLaunch()
                context.openNotificationSettings()
            }
            DeviceCapability.MICROPHONE_CAMERA -> {
                if (capability.name !in attemptedCapabilityNames) {
                    requestRuntimePermissions(capability, mediaRuntimePermissions())
                } else {
                    markAttempted(capability)
                    recordSettingsLaunch()
                    context.openApplicationDetails()
                }
            }
            DeviceCapability.CALL_ALERTS -> {
                markAttempted(capability)
                recordSettingsLaunch()
                context.openCallAlertSettings()
            }
            DeviceCapability.DO_NOT_DISTURB -> {
                markAttempted(capability)
                recordSettingsLaunch()
                context.openDoNotDisturbSettings()
            }
            DeviceCapability.BACKGROUND_RUNTIME -> {
                markAttempted(capability)
                recordSettingsLaunch()
                context.openBatteryOptimizationSettings()
            }
            DeviceCapability.AUTOSTART -> {
                markAttempted(capability)
                recordSettingsLaunch()
                context.openAutostartSettings()
            }
            DeviceCapability.BACKGROUND_DATA -> {
                markAttempted(capability)
                recordSettingsLaunch()
                context.openBackgroundDataSettings()
            }
        }
    }

    OnLifecycleEvent { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            refresh++
            val operationId = settingsOperationId
            if (operationId != null) {
                settingsOperationId = null
                lifecycleCoroutineScope.launch {
                    runCatching {
                        appOperationalStateCoordinator.perform(
                            lifecycleOperationRequest(
                                lifecycleId = FullAppLifecycleId.LC_03,
                                trigger = "settings_return",
                                operationId = operationId,
                            )
                        ) { Unit }
                    }
                }
            }
        }
    }

    val capabilities = remember(refresh, manuallyConfirmedNames, attemptedCapabilityNames) {
        DeviceCapability.entries.map { capability ->
            DeviceCapabilityUiModel(
                capability = capability,
                completed = context.isCapabilityGranted(capability) ||
                    (capability.isManuallyConfirmed() && capability.name in manuallyConfirmedNames),
                awaitingManualConfirmation = capability.isManuallyConfirmed() &&
                    capability.name in attemptedCapabilityNames &&
                    capability.name !in manuallyConfirmedNames,
                requiredForSetup = capability.isRequiredForSetup(),
            )
        }
    }
    val allCapabilitiesComplete = capabilities
        .filter(DeviceCapabilityUiModel::requiredForSetup)
        .all(DeviceCapabilityUiModel::completed)
    // LC-03 capabilities and analytics affect only their related features. The policy-required
    // local app lock remains a startup safeguard, but notification/media/background preferences
    // must not become a second LC-05/LC-06 readiness gate.
    val allPreApprovalRequirementsComplete = !lockScreenSetupRequired

    LaunchedEffect(
        previouslyCompleted,
        approvalPhase,
        sessionVerificationStatus,
        allPreApprovalRequirementsComplete,
    ) {
        if (previouslyCompleted &&
            approvalPhase == SecuritySetupPhase.READY &&
            sessionVerificationStatus is SessionVerifiedStatus.Verified &&
            allPreApprovalRequirementsComplete
        ) {
            onReady()
        }
    }

    BackHandler(enabled = true) {}
    DeviceCapabilitySetupWorkbenchView(
        capabilities = capabilities,
        approvalPhase = approvalPhase,
        sessionVerificationStatus = sessionVerificationStatus,
        activationFinalizing = activationFinalizing,
        lockScreenSetupRequired = lockScreenSetupRequired,
        analyticsChoiceComplete = analyticsChoiceComplete,
        onCapabilityAction = ::configureCapability,
        onOpenSessionVerification = onOpenSessionVerification,
        onOpenLockScreenSetup = onOpenLockScreenSetup,
        onAnalyticsChoice = onAnalyticsChoice,
        onApprovalAction = {
            if (approvalPhase == SecuritySetupPhase.READY) {
                when (sessionVerificationStatus) {
                    SessionVerifiedStatus.Verified -> {
                        context.markDeviceCapabilitySetupCompleted()
                        onReady()
                    }
                    SessionVerifiedStatus.NotVerified -> onOpenSessionVerification()
                    SessionVerifiedStatus.Unknown -> Unit
                }
            } else {
                onApprovalEvent(approvalPhase.toPrimaryEvent())
            }
        },
        allCapabilitiesComplete = allCapabilitiesComplete,
        allPreApprovalRequirementsComplete = allPreApprovalRequirementsComplete,
        modifier = modifier,
    )
}

@Composable
private fun DeviceCapabilitySetupWorkbenchView(
    capabilities: List<DeviceCapabilityUiModel>,
    approvalPhase: SecuritySetupPhase,
    sessionVerificationStatus: SessionVerifiedStatus,
    activationFinalizing: Boolean,
    lockScreenSetupRequired: Boolean,
    analyticsChoiceComplete: Boolean,
    onCapabilityAction: (DeviceCapability) -> Unit,
    onOpenSessionVerification: () -> Unit,
    onOpenLockScreenSetup: () -> Unit,
    onAnalyticsChoice: (Boolean) -> Unit,
    onApprovalAction: () -> Unit,
    allCapabilitiesComplete: Boolean,
    allPreApprovalRequirementsComplete: Boolean,
    modifier: Modifier = Modifier,
) {
    val completedCount = capabilities.count(DeviceCapabilityUiModel::completed)
    HeaderFooterPage(
        modifier = modifier.statusBarsPadding().fillMaxSize(),
        background = { OnboardingBackground() },
        header = {
            IconTitleSubtitleMolecule(
                modifier = Modifier.padding(top = 28.dp, bottom = 12.dp),
                iconStyle = BigIcon.Style.Default(CompoundIcons.Shield()),
                title = stringResource(R.string.screen_device_setup_workbench_title),
                subTitle = stringResource(
                    R.string.screen_device_setup_workbench_subtitle,
                    completedCount,
                    capabilities.size,
                ),
            )
        },
        footer = {
            ButtonColumnMolecule {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    text = approvalPhase.primaryActionLabel(
                        allPreApprovalRequirementsComplete,
                        sessionVerificationStatus,
                        activationFinalizing,
                    ),
                    enabled = allPreApprovalRequirementsComplete &&
                        !activationFinalizing &&
                        !(approvalPhase == SecuritySetupPhase.READY && sessionVerificationStatus is SessionVerifiedStatus.Unknown) &&
                        approvalPhase !in setOf(
                        SecuritySetupPhase.CHECKING,
                        SecuritySetupPhase.LOCATION_SUBMITTING,
                        SecuritySetupPhase.VALIDATION_PENDING,
                        SecuritySetupPhase.MANAGED_APPROVAL_PENDING,
                    ),
                    showProgress = activationFinalizing || approvalPhase in setOf(
                        SecuritySetupPhase.CHECKING,
                        SecuritySetupPhase.LOCATION_SUBMITTING,
                    ),
                    onClick = onApprovalAction,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_device_setup_section_notifications),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            capabilities.filter { it.capability in NOTIFICATION_CAPABILITIES }.forEach { item ->
                CapabilityCard(item = item, onAction = { onCapabilityAction(item.capability) })
            }

            Text(
                text = stringResource(R.string.screen_device_setup_section_calls_background),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            capabilities.filter { it.capability !in NOTIFICATION_CAPABILITIES }.forEach { item ->
                CapabilityCard(item = item, onAction = { onCapabilityAction(item.capability) })
            }

            SetupCardSurface {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.screen_device_setup_privacy_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = stringResource(R.string.screen_device_setup_privacy_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                text = stringResource(R.string.screen_device_setup_section_account_security),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            AccountSecurityCard(
                title = stringResource(R.string.screen_device_setup_app_lock_title),
                description = stringResource(R.string.screen_device_setup_app_lock_description),
                completed = !lockScreenSetupRequired,
                actionLabel = stringResource(R.string.screen_device_setup_app_lock_action),
                onAction = onOpenLockScreenSetup,
            )
            AnalyticsChoiceCard(
                completed = analyticsChoiceComplete,
                onChoice = onAnalyticsChoice,
            )
            SessionVerificationCard(
                approvalPhase = approvalPhase,
                status = sessionVerificationStatus,
                onAction = onOpenSessionVerification,
            )

            ApprovalCard(
                phase = approvalPhase,
                capabilitiesComplete = allPreApprovalRequirementsComplete,
            )
        }
    }
}

@Composable
private fun AccountSecurityCard(
    title: String,
    description: String,
    completed: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    SetupCardSurface {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SetupStatusHeader(title = title, completed = completed)
            if (!completed) {
                Text(text = description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(modifier = Modifier.fillMaxWidth(), text = actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
private fun AnalyticsChoiceCard(
    completed: Boolean,
    onChoice: (Boolean) -> Unit,
) {
    SetupCardSurface {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SetupStatusHeader(
                title = stringResource(R.string.screen_device_setup_analytics_title),
                completed = completed,
            )
            if (!completed) {
                Text(
                    text = stringResource(R.string.screen_device_setup_analytics_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.screen_device_setup_analytics_decline),
                        onClick = { onChoice(false) },
                    )
                    Button(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.screen_device_setup_analytics_allow),
                        onClick = { onChoice(true) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionVerificationCard(
    approvalPhase: SecuritySetupPhase,
    status: SessionVerifiedStatus,
    onAction: () -> Unit,
) {
    val approved = approvalPhase == SecuritySetupPhase.READY
    val completed = status is SessionVerifiedStatus.Verified
    SetupCardSurface {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SetupStatusHeader(
                title = stringResource(R.string.screen_device_setup_session_verification_title),
                completed = completed,
                pending = !approved || status is SessionVerifiedStatus.Unknown,
            )
            if (!completed) {
                Text(
                    text = stringResource(
                        if (approved) {
                            R.string.screen_device_setup_session_verification_description
                        } else {
                            R.string.screen_device_setup_session_verification_after_approval
                        }
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (approved && status is SessionVerifiedStatus.NotVerified) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.screen_device_setup_session_verification_action),
                        onClick = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStatusHeader(
    title: String,
    completed: Boolean,
    pending: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text = stringResource(
                when {
                    completed -> R.string.screen_device_setup_status_done
                    pending -> R.string.screen_device_setup_status_pending
                    else -> R.string.screen_device_setup_status_required
                }
            ),
            color = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun CapabilityCard(
    item: DeviceCapabilityUiModel,
    onAction: () -> Unit,
) {
    SetupCardSurface {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.capability.title(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = stringResource(
                        when {
                            item.completed -> R.string.screen_device_setup_status_done
                            item.requiredForSetup -> R.string.screen_device_setup_status_required
                            else -> R.string.screen_device_setup_status_optional
                        }
                    ),
                    color = when {
                        item.completed -> MaterialTheme.colorScheme.primary
                        item.requiredForSetup -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            if (!item.completed) {
                Text(
                    text = item.capability.description(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = item.capability.actionLabel(item.awaitingManualConfirmation),
                    onClick = onAction,
                )
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    phase: SecuritySetupPhase,
    capabilitiesComplete: Boolean,
) {
    SetupCardSurface {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_device_setup_approval_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = phase.approvalDescription(capabilitiesComplete),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (phase in setOf(SecuritySetupPhase.CHECKING, SecuritySetupPhase.LOCATION_SUBMITTING)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun SetupCardSurface(
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ElementTheme.colors.bgCanvasDefaultLevel1,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = 1.dp,
            color = ElementTheme.colors.borderSuccessSubtle.copy(alpha = 0.48f),
        ),
        shadowElevation = 1.dp,
    ) {
        content()
    }
}

@Composable
private fun DeviceCapability.title(): String = stringResource(
    when (this) {
        DeviceCapability.MESSAGE_NOTIFICATIONS -> R.string.screen_device_setup_notifications_title
        DeviceCapability.MESSAGE_BANNERS -> R.string.screen_device_setup_banners_title
        DeviceCapability.MICROPHONE_CAMERA -> R.string.screen_device_setup_media_title
        DeviceCapability.CALL_ALERTS -> R.string.screen_device_setup_calls_title
        DeviceCapability.DO_NOT_DISTURB -> R.string.screen_device_setup_dnd_title
        DeviceCapability.BACKGROUND_RUNTIME -> R.string.screen_device_setup_background_title
        DeviceCapability.AUTOSTART -> R.string.screen_device_setup_autostart_title
        DeviceCapability.BACKGROUND_DATA -> R.string.screen_device_setup_data_title
    }
)

@Composable
private fun DeviceCapability.description(): String = stringResource(
    when (this) {
        DeviceCapability.MESSAGE_NOTIFICATIONS -> R.string.screen_device_setup_notifications_description
        DeviceCapability.MESSAGE_BANNERS -> R.string.screen_device_setup_banners_description
        DeviceCapability.MICROPHONE_CAMERA -> R.string.screen_device_setup_media_description
        DeviceCapability.CALL_ALERTS -> R.string.screen_device_setup_calls_description
        DeviceCapability.DO_NOT_DISTURB -> R.string.screen_device_setup_dnd_description
        DeviceCapability.BACKGROUND_RUNTIME -> R.string.screen_device_setup_background_description
        DeviceCapability.AUTOSTART -> R.string.screen_device_setup_autostart_description
        DeviceCapability.BACKGROUND_DATA -> R.string.screen_device_setup_data_description
    }
)

@Composable
private fun DeviceCapability.actionLabel(awaitingManualConfirmation: Boolean): String {
    if (awaitingManualConfirmation) {
        return stringResource(
            if (this == DeviceCapability.MESSAGE_BANNERS) {
                R.string.screen_device_setup_banners_confirm
            } else {
                R.string.screen_device_setup_manual_confirm
            }
        )
    }
    return stringResource(
        when (this) {
            DeviceCapability.MESSAGE_NOTIFICATIONS -> R.string.screen_device_setup_allow_notifications
            DeviceCapability.MESSAGE_BANNERS -> R.string.screen_device_setup_open_banner_settings
            DeviceCapability.MICROPHONE_CAMERA -> R.string.screen_device_setup_allow_media
            DeviceCapability.CALL_ALERTS -> R.string.screen_device_setup_open_call_settings
            DeviceCapability.DO_NOT_DISTURB -> R.string.screen_device_setup_open_dnd_settings
            DeviceCapability.BACKGROUND_RUNTIME -> R.string.screen_device_setup_open_battery_settings
            DeviceCapability.AUTOSTART -> R.string.screen_device_setup_open_autostart_settings
            DeviceCapability.BACKGROUND_DATA -> R.string.screen_device_setup_open_data_settings
        }
    )
}

@Composable
private fun SecuritySetupPhase.primaryActionLabel(
    requirementsComplete: Boolean,
    sessionVerificationStatus: SessionVerifiedStatus,
    activationFinalizing: Boolean,
): String = stringResource(
    when {
        !requirementsComplete -> R.string.screen_device_setup_complete_required_first
        activationFinalizing -> R.string.screen_device_setup_waiting_session_state
        this == SecuritySetupPhase.NOT_SUBMITTED -> R.string.screen_device_setup_submit_approval
        this == SecuritySetupPhase.CHECKING -> R.string.screen_security_setup_checking
        this == SecuritySetupPhase.LOCATION_REQUIRED -> R.string.screen_security_setup_collect_location
        this == SecuritySetupPhase.LOCATION_SUBMITTING -> R.string.screen_device_setup_submitting_approval
        this == SecuritySetupPhase.LOCATION_PERMISSION_DENIED -> R.string.screen_security_setup_open_settings
        this == SecuritySetupPhase.LOCATION_UNAVAILABLE -> R.string.screen_security_setup_collect_location
        this == SecuritySetupPhase.VALIDATION_PENDING -> R.string.screen_device_setup_waiting_approval
        this == SecuritySetupPhase.MANAGED_APPROVAL_PENDING -> R.string.screen_entry_compatibility_preparing
        this == SecuritySetupPhase.READY && sessionVerificationStatus is SessionVerifiedStatus.Unknown ->
            R.string.screen_device_setup_waiting_session_state
        this == SecuritySetupPhase.READY && sessionVerificationStatus is SessionVerifiedStatus.NotVerified ->
            R.string.screen_device_setup_session_verification_action
        this == SecuritySetupPhase.READY -> R.string.screen_device_setup_start_using
        else -> R.string.screen_security_setup_retry
    }
)

@Composable
private fun SecuritySetupPhase.approvalDescription(capabilitiesComplete: Boolean): String = stringResource(
    when {
        !capabilitiesComplete -> R.string.screen_device_setup_approval_locked
        this == SecuritySetupPhase.NOT_SUBMITTED -> R.string.screen_device_setup_approval_ready
        this == SecuritySetupPhase.CHECKING -> R.string.screen_security_setup_checking
        this == SecuritySetupPhase.LOCATION_REQUIRED -> R.string.screen_security_setup_location_explanation
        this == SecuritySetupPhase.LOCATION_SUBMITTING -> R.string.screen_device_setup_submitting_approval
        this == SecuritySetupPhase.LOCATION_PERMISSION_DENIED -> R.string.screen_security_setup_location_permission_denied
        this == SecuritySetupPhase.LOCATION_UNAVAILABLE -> R.string.screen_security_setup_location_unavailable
        this == SecuritySetupPhase.VALIDATION_PENDING -> R.string.screen_security_setup_validation_pending
        this == SecuritySetupPhase.MANAGED_APPROVAL_PENDING -> R.string.screen_entry_compatibility_preparing
        this == SecuritySetupPhase.READY -> R.string.screen_security_setup_validation_complete
        this == SecuritySetupPhase.ACTION_REQUIRED -> R.string.screen_security_setup_action_required
        else -> R.string.screen_security_setup_service_unavailable
    }
)

private fun SecuritySetupPhase.toPrimaryEvent(): SecuritySetupEvent = when (this) {
    SecuritySetupPhase.NOT_SUBMITTED -> SecuritySetupEvent.Submit
    SecuritySetupPhase.LOCATION_REQUIRED,
    SecuritySetupPhase.LOCATION_PERMISSION_DENIED,
    SecuritySetupPhase.LOCATION_UNAVAILABLE -> SecuritySetupEvent.CollectLocation
    else -> SecuritySetupEvent.Retry
}

internal fun Context.hasCompletedDeviceCapabilitySetup(): Boolean {
    val preferences = getSharedPreferences(DEVICE_SETUP_PREFERENCES, Context.MODE_PRIVATE)
    return preferences.getBoolean(DEVICE_SETUP_COMPLETE_V2, false) ||
        preferences.getBoolean(DEVICE_SETUP_COMPLETE_V3, false) ||
        preferences.getBoolean(DEVICE_SETUP_COMPLETE_V4, false)
}

internal fun Context.markDeviceCapabilitySetupCompleted() {
    getSharedPreferences(DEVICE_SETUP_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(DEVICE_SETUP_COMPLETE_V4, true)
        .remove(DEVICE_SETUP_CURRENT_INDEX_V2)
        .apply()
}

internal fun DeviceCapability.isRequiredForSetup(): Boolean = false

private fun DeviceCapability.isManuallyConfirmed(): Boolean = this in setOf(
    DeviceCapability.MESSAGE_BANNERS,
    DeviceCapability.BACKGROUND_DATA,
)

private fun DeviceCapability.isAutomaticallyVerified(): Boolean = !isManuallyConfirmed()

private fun Context.isCapabilityGranted(capability: DeviceCapability): Boolean = when (capability) {
    DeviceCapability.MESSAGE_NOTIFICATIONS -> {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        runtimeGranted && NotificationManagerCompat.from(this).areNotificationsEnabled()
    }
    DeviceCapability.MESSAGE_BANNERS -> false
    DeviceCapability.MICROPHONE_CAMERA -> {
        val bluetoothGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        hasPermission(Manifest.permission.RECORD_AUDIO) &&
            hasPermission(Manifest.permission.CAMERA) &&
            bluetoothGranted
    }
    DeviceCapability.CALL_ALERTS -> {
        val fullScreenGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            NotificationManagerCompat.from(this).canUseFullScreenIntent()
        fullScreenGranted && isXiaomiBackgroundStartAllowed()
    }
    DeviceCapability.DO_NOT_DISTURB ->
        getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
    DeviceCapability.BACKGROUND_RUNTIME ->
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
    DeviceCapability.AUTOSTART -> xiaomiAutostartPermissionState() == AutostartPermissionState.ALLOWED
    DeviceCapability.BACKGROUND_DATA -> false
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun mediaRuntimePermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}.toTypedArray()

private fun Context.openApplicationDetails() {
    startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
}

private fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    if (!startSafely(intent)) {
        openApplicationDetails()
    }
}

private fun Context.openCallAlertSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        !NotificationManagerCompat.from(this).canUseFullScreenIntent() &&
        startSafely(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:$packageName".toUri()))
    ) {
        return
    }
    if (isXiaomiDevice()) {
        val intent = Intent(MIUI_PERMISSION_EDITOR_ACTION)
            .setClassName(MIUI_SECURITY_CENTER_PACKAGE, MIUI_PERMISSION_EDITOR_ACTIVITY)
            .putExtra(MIUI_EXTRA_PACKAGE_NAME, packageName)
        if (startSafely(intent)) {
            return
        }
    }
    openNotificationSettings()
}

private fun Context.openDoNotDisturbSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        startSafely(Intent("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS", "package:$packageName".toUri()))
    ) {
        return
    }
    if (!startSafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))) {
        openApplicationDetails()
    }
}

@SuppressLint("BatteryLife")
private fun Context.openBatteryOptimizationSettings() {
    xiaomiApplicationDetailsSettingsIntent()?.let { if (startSafely(it)) return }
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
    if (!startSafely(intent)) {
        startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun Context.openAutostartSettings() {
    xiaomiApplicationDetailsSettingsIntent()?.let { if (startSafely(it)) return }
    val intents = listOf(
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
    )
    if (intents.none(::startSafely)) {
        openApplicationDetails()
    }
}

private fun Context.openBackgroundDataSettings() {
    val intent = Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, "package:$packageName".toUri())
    if (!startSafely(intent)) {
        openApplicationDetails()
    }
}

private fun Context.startSafely(intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun Context.isXiaomiBackgroundStartAllowed(): Boolean {
    if (!isXiaomiDevice()) {
        return true
    }
    return runCatching {
        val appOpsManager = getSystemService(AppOpsManager::class.java)
        val checkOpNoThrow = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        checkOpNoThrow.invoke(appOpsManager, MIUI_OP_BACKGROUND_START_ACTIVITY, applicationInfo.uid, packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}

private fun isXiaomiDevice(): Boolean =
    Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Redmi", ignoreCase = true) ||
        Build.BRAND.equals("POCO", ignoreCase = true)

@PreviewsDayNight
@Composable
internal fun DeviceCapabilitySetupWorkbenchViewPreview() = ElementPreview {
    DeviceCapabilitySetupWorkbenchView(
        capabilities = DeviceCapability.entries.mapIndexed { index, capability ->
            DeviceCapabilityUiModel(
                capability = capability,
                completed = index < 2,
                awaitingManualConfirmation = false,
                requiredForSetup = capability.isRequiredForSetup(),
            )
        },
        approvalPhase = SecuritySetupPhase.NOT_SUBMITTED,
        sessionVerificationStatus = SessionVerifiedStatus.Unknown,
        activationFinalizing = false,
        lockScreenSetupRequired = true,
        analyticsChoiceComplete = false,
        onCapabilityAction = {},
        onOpenSessionVerification = {},
        onOpenLockScreenSetup = {},
        onAnalyticsChoice = {},
        onApprovalAction = {},
        allCapabilitiesComplete = false,
        allPreApprovalRequirementsComplete = false,
    )
}

private val NOTIFICATION_CAPABILITIES = setOf(
    DeviceCapability.MESSAGE_NOTIFICATIONS,
    DeviceCapability.MESSAGE_BANNERS,
)
private const val DEVICE_SETUP_PREFERENCES = "device_capability_setup"
private const val DEVICE_SETUP_COMPLETE_V2 = "complete_v2"
private const val DEVICE_SETUP_COMPLETE_V3 = "complete_v3"
private const val DEVICE_SETUP_COMPLETE_V4 = "complete_v4"
private const val DEVICE_SETUP_CURRENT_INDEX_V2 = "current_index_v2"
private const val DEVICE_SETUP_MANUAL_CONFIRMED = "manual_confirmed_v3"
private const val MIUI_OP_BACKGROUND_START_ACTIVITY = 10021
private const val MIUI_PERMISSION_EDITOR_ACTION = "miui.intent.action.APP_PERM_EDITOR"
private const val MIUI_SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
private const val MIUI_PERMISSION_EDITOR_ACTIVITY = "com.miui.permcenter.permissions.PermissionsEditorActivity"
private const val MIUI_EXTRA_PACKAGE_NAME = "extra_pkgname"
