/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.featureflag.api

import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.core.meta.BuildType

/**
 * To enable or disable a FeatureFlags, change the `defaultValue` value.
 */
enum class FeatureFlags(
    override val key: String,
    override val title: String,
    override val description: String? = null,
    override val defaultValue: (BuildMeta) -> Boolean,
    override val isFinished: Boolean,
    override val isInLabs: Boolean = false,
) : Feature {
    ProgressiveProtectedVideoPlayback(
        key = "feature.progressive_protected_video_playback",
        title = "Progressive protected video playback",
        description = "Stream ordinary protected videos with one initial key release and local authenticated reads.",
        defaultValue = { true },
        isFinished = false,
    ),
    ContinuousProtectedVideoPlayback(
        key = "feature.continuous_protected_video_playback",
        title = "Continuous protected video playback",
        description = "Use bounded authenticated prefetch for segmented videos. Disabled pending device acceptance and owner rollout.",
        defaultValue = { false },
        isFinished = false,
    ),
    SegmentedProtectedVideoSending(
        key = "feature.segmented_protected_video_sending",
        title = "Segmented protected video sending",
        description = "Requires authenticated preparation, room capability and resource registration. Enable only after receiver acceptance.",
        defaultValue = { false },
        isFinished = false,
    ),
    ShowBlockedUsersDetails(
        key = "feature.showBlockedUsersDetails",
        title = "Show blocked users details",
        description = "Show the name and avatar of blocked users in the blocked users list",
        defaultValue = { false },
        isFinished = false,
    ),
    SyncOnPush(
        key = "feature.syncOnPush",
        title = "Sync on push",
        description = "Subscribe to room sync when a push is received",
        defaultValue = { true },
        isFinished = false,
    ),
    OnlySignedDeviceIsolationMode(
        key = "feature.onlySignedDeviceIsolationMode",
        title = "Exclude insecure devices when sending/receiving messages",
        description = "This setting controls how end-to-end encryption (E2E) keys are shared." +
            " Enabling it will prevent the inclusion of devices that have not been explicitly verified by their owners." +
            " You'll have to stop and re-open the app manually for that setting to take effect.",
        defaultValue = { false },
        isFinished = false,
    ),
    Knock(
        key = "feature.knock",
        title = "Ask to join",
        description = "Allow creating rooms which users can request access to.",
        defaultValue = { false },
        isFinished = false,
    ),
    PrintLogsToLogcat(
        key = "feature.print_logs_to_logcat",
        title = "Print logs to logcat",
        description = "Print logs to logcat in addition to log files. Requires an app restart to take effect." +
            "\n\nWARNING: this will make the logs visible in the device logs and may affect performance. " +
            "It's not intended for daily usage in release builds.",
        defaultValue = { buildMeta -> buildMeta.buildType != BuildType.RELEASE },
        // False so it's displayed in the developer options screen
        isFinished = false,
    ),
    SelectableMediaQuality(
        key = "feature.selectable_media_quality",
        title = "Select media quality per upload",
        description = "You can select the media quality for each attachment you upload.",
        defaultValue = { false },
        // False so it's displayed in the developer options screen
        isFinished = false,
    ),
    Threads(
        key = "feature.thread_timeline",
        title = "Threads",
        description = "Renders thread messages as a dedicated timeline. Restarting the app is required for this setting to fully take effect.",
        defaultValue = { false },
        isFinished = false,
        isInLabs = true,
    ),
    MultiAccount(
        key = "feature.multi_account",
        title = "Multi accounts",
        description = "Allow the application to connect to multiple accounts at the same time." +
            "\n\nWARNING: this feature is EXPERIMENTAL and UNSTABLE.",
        defaultValue = { false },
        isFinished = false,
    ),
    QrCodeLogin(
        key = "feature.qr_code_login",
        title = "QR Code Login",
        description = "Allow logging in on other devices using a QR code.",
        defaultValue = { false },
        isFinished = false,
    ),
    AllowBlackTheme(
        key = "feature.allow_black_theme",
        title = "Black theme",
        description = "Allow selecting the black appearance theme for battery saving on OLED.",
        defaultValue = { false },
        isFinished = false,
    ),
    ValidateNetworkWhenSchedulingNotificationFetching(
        key = "feature.validate_network_when_scheduling_notification_fetching",
        title = "Validate internet connectivity when scheduling notification fetching",
        description = "Only fetch events for push notifications when the device has internet connectivity. " +
            "Enabling this can be problematic in air-gapped environments.",
        defaultValue = { true },
        isFinished = false,
    ),
    SlashCommand(
        key = "feature.slash_command",
        title = "Parse slash commands in the message composer",
        description = "Allow parsing slash commands in the message composer and perform action.",
        defaultValue = { false },
        isFinished = false,
    ),
    RoomThreadList(
        key = "feature.room_thread_list",
        title = "Add a list of threads in a room",
        description = "Add a new screen with a list of threads in a room.",
        defaultValue = { false },
        isFinished = false,
    ),
    AutomaticBackPagination(
        key = "feature.automatic_back_pagination",
        title = "Automatic back pagination of rooms",
        description = "Allow the app to automatically back paginate in rooms to pre-fetch older messages in background." +
            "\nRequires an app restart to take effect.",
        defaultValue = { false },
        isFinished = false,
    ),
    SecureFileStore(
        key = "security.secure_file_store",
        title = "Encrypted sensitive file store",
        description = "Routes registered sensitive files through the versioned encrypted object store.",
        defaultValue = { true },
        isFinished = true,
    ),
    HardwareBackedVault(
        key = "security.hardware_backed_vault",
        title = "Require trusted Vault hardware",
        description = "Requires a TEE or StrongBox device root for account Vault keys; remote trust remains a separate KMS gate.",
        defaultValue = { true },
        isFinished = true,
    ),
    ContentProtection(
        key = "security.content_protection",
        title = "Inner content protection",
        description = "Enables versioned inner content envelopes and KMS-backed access control.",
        defaultValue = { true },
        isFinished = true,
    ),
    RemoteDeletion(
        key = "security.remote_deletion",
        title = "Remote deletion coordinator",
        description = "Enables signed remote deletion commands and non-content deletion acknowledgements.",
        defaultValue = { true },
        isFinished = true,
    ),
    UnreadIndicatorCount(
        key = "feature.unread_indicator_count",
        title = "Unread indicator count",
        description = "Show the number of unread messages on the unread indicator in the room list.",
        defaultValue = { true },
        isFinished = true,
    ),
}
