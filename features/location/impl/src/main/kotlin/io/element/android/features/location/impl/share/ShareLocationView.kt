/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH")

package io.element.android.features.location.impl.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.location.api.Location
import io.element.android.features.location.api.internal.centerBottomEdge
import io.element.android.features.location.impl.R
import io.element.android.features.location.impl.common.MapDefaults
import io.element.android.features.location.impl.common.UserLocationState
import io.element.android.features.location.impl.common.rememberUserLocationState
import io.element.android.features.location.impl.common.ui.LocationConstraintsDialog
import io.element.android.features.location.impl.common.ui.LocationFloatingActionButton
import io.element.android.features.location.impl.common.ui.MapBottomSheetScaffold
import io.element.android.features.location.impl.common.ui.UserLocationPuck
import io.element.android.features.location.impl.share.ShareLocationEvent.StartLiveLocationShare
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.designsystem.components.LocationPin
import io.element.android.libraries.designsystem.components.PinVariant
import io.element.android.libraries.designsystem.components.async.AsyncIndicator
import io.element.android.libraries.designsystem.components.async.AsyncIndicatorHost
import io.element.android.libraries.designsystem.components.async.rememberAsyncIndicatorState
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.dialogs.ListDialog
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.components.list.RadioButtonListItem
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.matrix.ui.model.getAvatarData
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.collections.immutable.ImmutableList
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.rememberCameraState
import kotlin.time.Duration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyShareLocationView(
    state: ShareLocationState,
    navigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ShareLocationDialogs(state)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val canChooseLive = state.canShareLiveLocation && state.pausedLiveLocationShare == null
    val isLiveMode = selectedTab == 1 && canChooseLive

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Expanded)
    )
    val cameraState = rememberCameraState(firstPosition = MapDefaults.defaultCameraPosition)
    val userLocationState = rememberUserLocationState(state.hasLocationPermission)
    val userLocation = userLocationState.location

    fun shareSelectedLocation() {
        val selectedLocation = if (state.trackUserLocation) {
            userLocation?.position?.value?.let { Location(lat = it.latitude, lon = it.longitude) }
        } else {
            cameraState.position.target.let { Location(lat = it.latitude, lon = it.longitude) }
        } ?: return
        state.eventSink(
            ShareLocationEvent.ShareStaticLocation(
                location = selectedLocation,
                isPinned = !state.trackUserLocation,
            )
        )
    }

    LaunchedEffect(cameraState.isCameraMoving) {
        if (cameraState.moveReason == CameraMoveReason.GESTURE) {
            state.eventSink(ShareLocationEvent.StopTrackingUserLocation)
        }
    }

    MapBottomSheetScaffold(
        customMapStyleUrl = state.customMapStyleUrl,
        cameraState = cameraState,
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetDragHandle = null,
        sheetSwipeEnabled = false,
        topBar = {
            androidx.compose.material3.CenterAlignedTopAppBar(
                title = {
                    androidx.compose.material3.TabRow(
                        selectedTabIndex = if (isLiveMode) 1 else 0,
                        modifier = Modifier.widthIn(max = 196.dp).fillMaxWidth(),
                        containerColor = ElementTheme.colors.bgCanvasDefault,
                        contentColor = ElementTheme.colors.textActionPrimary,
                        divider = {},
                    ) {
                        listOf(R.string.screen_share_location_tab_location, R.string.screen_share_location_tab_live).forEachIndexed { index, label ->
                            val selected = index == if (isLiveMode) 1 else 0
                            androidx.compose.material3.Tab(
                                selected = selected,
                                enabled = index == 0 || canChooseLive,
                                onClick = {
                                    selectedTab = index
                                    if (index == 1 && state.hasLocationPermission) {
                                        state.eventSink(ShareLocationEvent.StartTrackingUserLocation)
                                    }
                                },
                                text = {
                                    Text(
                                        text = stringResource(label),
                                        style = ElementTheme.typography.fontBodyMdMedium,
                                        color = if (selected) ElementTheme.colors.textActionPrimary else ElementTheme.colors.textSecondary,
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                },
                navigationIcon = {
                    TextButton(
                        text = stringResource(CommonStrings.action_cancel),
                        onClick = navigateUp,
                    )
                },
                actions = {
                    Button(
                        text = stringResource(CommonStrings.action_send),
                        size = ButtonSize.MediumLowPadding,
                        enabled = if (isLiveMode) {
                            state.startLiveLocationAction !is AsyncAction.Loading
                        } else {
                            state.shareStaticLocationAction !is AsyncAction.Loading && (!state.trackUserLocation || userLocation != null)
                        },
                        onClick = {
                            if (isLiveMode) {
                                state.eventSink(
                                    ShareLocationEvent.InitiateLiveLocationShare
                                )
                            } else {
                                shareSelectedLocation()
                            }
                        },
                    )
                },
            )
        },
        sheetContent = {
            BottomSheetContent(
                state = state,
                userLocationState = userLocationState,
                isLiveMode = isLiveMode,
            )
        },
        mapContent = {
            UserLocationPuck(
                cameraState = cameraState,
                locationState = userLocationState,
                trackUserLocation = state.trackUserLocation
            )
        },
        overlayContent = { sheetPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sheetPadding)
            ) {
                val variant = if (state.trackUserLocation) {
                    PinVariant.UserLocation(isLive = false, avatarData = state.currentUser.getAvatarData(AvatarSize.LocationPin))
                } else {
                    PinVariant.PinnedLocation
                }
                if (!isLiveMode) {
                    LocationPin(
                        variant = variant,
                        modifier = Modifier.centerBottomEdge(this),
                    )
                }
            }
            LocationFloatingActionButton(
                isMapCenteredOnUser = state.trackUserLocation,
                onClick = { state.eventSink(ShareLocationEvent.StartTrackingUserLocation) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(sheetPadding)
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 56.dp),
            )
            LocationActionView(
                action = state.shareStaticLocationAction,
                loadingText = stringResource(CommonStrings.common_waiting),
                onActionSuccess = navigateUp,
            )
            StartLiveLocationActionView(state.startLiveLocationAction, navigateUp)
        }
    )
}

@Composable
private fun StartLiveLocationActionView(
    action: AsyncAction<Unit>,
    onActionSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) = LocationActionView(
    action = action,
    loadingText = stringResource(CommonStrings.common_waiting_live_location),
    onActionSuccess = onActionSuccess,
    modifier = modifier,
)

@Composable
private fun LocationActionView(
    action: AsyncAction<Unit>,
    loadingText: String,
    onActionSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val updatedOnActionSuccess by rememberUpdatedState(onActionSuccess)
    Box(modifier = modifier) {
        val asyncIndicatorState = rememberAsyncIndicatorState()
        AsyncIndicatorHost(state = asyncIndicatorState)

        when (action) {
            is AsyncAction.Loading -> {
                LaunchedEffect(action) {
                    asyncIndicatorState.enqueue {
                        AsyncIndicator.Loading(text = loadingText)
                    }
                }
            }
            is AsyncAction.Failure -> {
                LaunchedEffect(action) {
                    asyncIndicatorState.enqueue(AsyncIndicator.DURATION_SHORT) {
                        AsyncIndicator.Failure(
                            text = stringResource(CommonStrings.common_something_went_wrong),
                        )
                    }
                }
            }
            is AsyncAction.Success -> {
                LaunchedEffect(action) { updatedOnActionSuccess() }
            }
            else -> Unit
        }
    }
}

@Composable
private fun BottomSheetContent(
    state: ShareLocationState,
    userLocationState: UserLocationState,
    isLiveMode: Boolean,
) {
    Spacer(Modifier.height(20.dp))
    if (isLiveMode) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.screen_share_location_tab_live)) },
            supportingContent = { Text(stringResource(R.string.screen_share_location_live_mode_hint)) },
            leadingContent = ListItemContent.Icon(
                iconSource = IconSource.Vector(CompoundIcons.LocationPinSolid()),
                tintColor = ElementTheme.colors.iconAccentPrimary,
            ),
        )
    } else {
        SelectedLocationItem(isCurrentLocation = state.trackUserLocation && userLocationState.location != null)
    }
}

@Composable
private fun SelectedLocationItem(isCurrentLocation: Boolean) {
    ListItem(
        headlineContent = {
            Text(
                stringResource(
                    if (isCurrentLocation) {
                        R.string.screen_share_location_selected_current
                    } else {
                        R.string.screen_share_location_selected_pin
                    }
                )
            )
        },
        supportingContent = {
            Text(stringResource(R.string.screen_share_location_drag_hint))
        },
        leadingContent = ListItemContent.Icon(
            iconSource = IconSource.Vector(
                if (isCurrentLocation) {
                    CompoundIcons.LocationNavigatorCentred()
                } else {
                    CompoundIcons.LocationNavigator()
                }
            )
        )
    )
}

@Composable
private fun LiveLocationDurationDialog(
    durations: ImmutableList<LiveLocationDuration>,
    onSelectDuration: (Duration) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    ListDialog(
        title = stringResource(R.string.screen_share_location_live_location_duration_picker_title),
        submitText = stringResource(CommonStrings.action_continue),
        onSubmit = { onSelectDuration(durations[selectedIndex].duration) },
        onDismissRequest = onDismiss,
        applyPaddingToContents = false,
        verticalArrangement = Arrangement.Top
    ) {
        itemsIndexed(durations) { index, duration ->
            RadioButtonListItem(
                headline = duration.formatted,
                selected = index == selectedIndex,
                onSelect = { selectedIndex = index },
                compactLayout = true,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun ShareLocationViewPreview(
    @PreviewParameter(ShareLocationStateProvider::class) state: ShareLocationState
) = ElementPreview {
    ShareLocationView(
        state = state,
        navigateUp = {},
    )
}

@Composable
fun ShareLocationView(state: ShareLocationState, navigateUp: () -> Unit, modifier: Modifier = Modifier) {
    LegacyShareLocationView(state, navigateUp, modifier)
}

@Composable
internal fun ShareLocationDialogs(state: ShareLocationState) {
    when (val dialogState = state.dialogState) {
        ShareLocationState.Dialog.None -> Unit
        is ShareLocationState.Dialog.Constraints -> LocationConstraintsDialog(
            state = dialogState.state,
            appName = state.appName,
            onRequestPermissions = { state.eventSink(ShareLocationEvent.RequestPermissions) },
            onOpenAppSettings = { state.eventSink(ShareLocationEvent.OpenAppSettings) },
            onOpenLocationSettings = { state.eventSink(ShareLocationEvent.OpenLocationSettings) },
            onDismiss = { state.eventSink(ShareLocationEvent.DismissDialog) },
        )
        ShareLocationState.Dialog.LiveLocationDisclaimer -> ConfirmationDialog(
            content = stringResource(R.string.screen_share_location_live_location_disclaimer_title),
            submitText = stringResource(CommonStrings.action_accept),
            cancelText = stringResource(CommonStrings.action_decline),
            onSubmitClick = { state.eventSink(ShareLocationEvent.AcceptLiveLocationDisclaimer) },
            onDismiss = { state.eventSink(ShareLocationEvent.DismissDialog) },
        )
        is ShareLocationState.Dialog.LiveLocationDurations -> LiveLocationDurationDialog(
            durations = dialogState.durations,
            onSelectDuration = { duration ->
                state.eventSink(StartLiveLocationShare(duration))
            },
            onDismiss = { state.eventSink(ShareLocationEvent.DismissDialog) },
        )
    }
}
