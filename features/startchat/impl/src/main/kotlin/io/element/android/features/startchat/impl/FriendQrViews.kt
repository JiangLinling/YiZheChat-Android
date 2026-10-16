/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.startchat.impl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.androidutils.system.copyToSystemClipboard
import io.element.android.libraries.androidutils.system.toast
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.permissions.api.PermissionsEvent
import io.element.android.libraries.permissions.api.PermissionsState
import io.element.android.libraries.permissions.api.PermissionsView
import io.element.android.libraries.qrcode.QrCodeCameraView
import io.element.android.libraries.qrcode.QrCodeImage
import io.element.android.libraries.qrcode.createQrCodeBitmap
import io.element.android.libraries.qrcode.decodeQrCodeBitmap
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

private val ScannerGreen = Color(0xFF35D573)
private val ScannerBackground = Color(0xFF101412)

@Composable
internal fun FriendScannerView(
    permissionState: PermissionsState,
    onBack: () -> Unit,
    onMyQrCode: () -> Unit,
    onScanned: (ByteArray) -> FriendQrScanResult,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val currentOnScanned by rememberUpdatedState(onScanned)
    val scanner = remember { FriendScannerState { currentOnScanned(it) } }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scanner.pause()
            coroutineScope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    decodeQrCodeFromUri(context.contentResolver, uri)
                }
                scanner.scanned(bytes)
            }
        }
    }

    LaunchedEffect(permissionState.permissionGranted) {
        if (!permissionState.permissionGranted && !permissionState.permissionAlreadyDenied) {
            permissionState.eventSink(PermissionsEvent.RequestPermissions)
        }
    }

    FriendScannerContent(
        permissionGranted = permissionState.permissionGranted,
        scanning = scanner.scanning,
        result = scanner.result,
        onBack = onBack,
        onMyQrCode = onMyQrCode,
        onPickImage = {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onScanAgain = scanner::scanAgain,
        cameraContent = {
            QrCodeCameraView(
                modifier = Modifier.fillMaxSize(),
                isScanning = scanner.scanning,
                onScanQrCode = { bytes ->
                    if (scanner.scanning) scanner.scanned(bytes)
                },
            )
        },
        modifier = modifier,
    )
    PermissionsView(state = permissionState)
}

@Composable
internal fun FriendScannerContent(
    permissionGranted: Boolean,
    scanning: Boolean,
    result: FriendQrScanResult?,
    onBack: () -> Unit,
    onMyQrCode: () -> Unit,
    onPickImage: () -> Unit,
    onScanAgain: () -> Unit,
    cameraContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ScannerBackground),
    ) {
        if (permissionGranted) {
            cameraContent()
        }

        ScannerMask(isScanning = permissionGranted && scanning)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.42f), CircleShape),
            ) {
                Icon(
                    imageVector = CompoundIcons.Close(),
                    contentDescription = stringResource(CommonStrings.action_close),
                    tint = Color.White,
                )
            }

            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 13.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (permissionGranted && scanning) ScannerGreen else Color.White.copy(alpha = 0.5f), CircleShape),
                )
                Text(
                    text = stringResource(
                        if (permissionGranted) R.string.screen_friend_scan_active
                        else R.string.screen_friend_scan_waiting,
                    ),
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = Color.White,
                )
            }
        }

        Text(
            text = stringResource(
                when {
                    result == FriendQrScanResult.Self -> R.string.screen_friend_scan_self
                    result == FriendQrScanResult.Invalid -> R.string.screen_friend_scan_invalid
                    permissionGranted -> R.string.screen_friend_scan_description
                    else -> R.string.screen_friend_scan_camera_permission
                },
            ),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 190.dp)
                .padding(horizontal = 32.dp),
            style = ElementTheme.typography.fontBodyLgMedium,
            color = if (result == FriendQrScanResult.Invalid) ElementTheme.colors.textCriticalPrimary else Color.White,
            textAlign = TextAlign.Center,
        )

        ScannerBottomPanel(
            canScanAgain = result == FriendQrScanResult.Self || result == FriendQrScanResult.Invalid,
            onMyQrCode = onMyQrCode,
            onPickImage = onPickImage,
            onScanAgain = onScanAgain,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ScannerMask(
    isScanning: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "friend-qr-scanner")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "green-scan-line",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val frameSize = min(size.width - 48.dp.toPx(), 304.dp.toPx())
        val frameLeft = (size.width - frameSize) / 2f
        val preferredCenterY = size.height * 0.40f
        val frameTop = (preferredCenterY - frameSize / 2f).coerceAtLeast(104.dp.toPx())
        val frameRight = frameLeft + frameSize
        val frameBottom = frameTop + frameSize
        val maskColor = Color.Black.copy(alpha = 0.46f)

        drawRect(maskColor, topLeft = Offset.Zero, size = Size(size.width, frameTop))
        drawRect(maskColor, topLeft = Offset(0f, frameBottom), size = Size(size.width, size.height - frameBottom))
        drawRect(maskColor, topLeft = Offset(0f, frameTop), size = Size(frameLeft, frameSize))
        drawRect(maskColor, topLeft = Offset(frameRight, frameTop), size = Size(size.width - frameRight, frameSize))

        val cornerLength = 30.dp.toPx()
        val cornerWidth = 4.dp.toPx()
        val inset = 1.dp.toPx()
        val cornerColor = Color.White.copy(alpha = 0.96f)

        fun corner(start: Offset, horizontalEnd: Offset, verticalEnd: Offset) {
            drawLine(cornerColor, start, horizontalEnd, cornerWidth, StrokeCap.Round)
            drawLine(cornerColor, start, verticalEnd, cornerWidth, StrokeCap.Round)
        }
        corner(
            Offset(frameLeft + inset, frameTop + inset),
            Offset(frameLeft + cornerLength, frameTop + inset),
            Offset(frameLeft + inset, frameTop + cornerLength),
        )
        corner(
            Offset(frameRight - inset, frameTop + inset),
            Offset(frameRight - cornerLength, frameTop + inset),
            Offset(frameRight - inset, frameTop + cornerLength),
        )
        corner(
            Offset(frameLeft + inset, frameBottom - inset),
            Offset(frameLeft + cornerLength, frameBottom - inset),
            Offset(frameLeft + inset, frameBottom - cornerLength),
        )
        corner(
            Offset(frameRight - inset, frameBottom - inset),
            Offset(frameRight - cornerLength, frameBottom - inset),
            Offset(frameRight - inset, frameBottom - cornerLength),
        )

        if (isScanning) {
            val lineInset = 14.dp.toPx()
            val scanY = frameTop + lineInset + (frameSize - lineInset * 2f) * progress
            val lineLeft = frameLeft + lineInset
            val lineWidth = frameSize - lineInset * 2f
            val scanBrush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, ScannerGreen, ScannerGreen, Color.Transparent),
                startX = lineLeft,
                endX = lineLeft + lineWidth,
            )
            drawRect(
                brush = scanBrush,
                topLeft = Offset(lineLeft, scanY - 10.dp.toPx()),
                size = Size(lineWidth, 20.dp.toPx()),
                alpha = 0.22f,
            )
            drawRect(
                brush = scanBrush,
                topLeft = Offset(lineLeft, scanY - 1.dp.toPx()),
                size = Size(lineWidth, 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun ScannerBottomPanel(
    canScanAgain: Boolean,
    onMyQrCode: () -> Unit,
    onPickImage: () -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.76f))
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (canScanAgain) {
            Button(
                text = stringResource(R.string.screen_friend_scan_again),
                onClick = onScanAgain,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScannerAction(
                text = stringResource(R.string.screen_friend_scan_from_image),
                icon = CompoundIcons.Image(),
                onClick = onPickImage,
                modifier = Modifier.weight(1f),
            )
            ScannerAction(
                text = stringResource(R.string.screen_add_friend_my_qr_code),
                icon = CompoundIcons.QrCode(),
                onClick = onMyQrCode,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.screen_add_friend_scan),
            style = ElementTheme.typography.fontHeadingSmMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .size(width = 30.dp, height = 3.dp)
                .background(ScannerGreen, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun ScannerAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = Color.White,
        )
        Spacer(Modifier.size(9.dp))
        Text(
            text = text,
            style = ElementTheme.typography.fontBodyMdMedium,
            color = Color.White,
            maxLines = 1,
        )
    }
}

private fun decodeQrCodeFromUri(
    contentResolver: android.content.ContentResolver,
    uri: Uri,
): ByteArray? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > MAX_QR_IMAGE_SIDE || bounds.outHeight / sampleSize > MAX_QR_IMAGE_SIDE) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)?.let(::decodeQrCodeBitmap)
    }
}.getOrNull()

private const val MAX_QR_IMAGE_SIDE = 2_048

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MyFriendQrCodeView(
    user: MatrixUser,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val qrData = remember(user.userId) { FriendQrCode.encode(user.userId) }
    val copiedMessage = stringResource(R.string.screen_friend_account_copied)
    val saveSuccessMessage = stringResource(R.string.screen_friend_qr_saved)
    val saveFailureMessage = stringResource(R.string.screen_friend_qr_save_failed)
    val saveQrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            check(createQrCodeBitmap(qrData).compress(Bitmap.CompressFormat.PNG, 100, stream))
                        } ?: error("Unable to open selected QR destination")
                    }.isSuccess
                }
                context.toast(if (saved) saveSuccessMessage else saveFailureMessage)
            }
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(titleStr = stringResource(R.string.screen_friend_my_qr_title), navigationIcon = { BackButton(onClick = onBack) }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(user.displayName ?: user.userId.value, style = ElementTheme.typography.fontHeadingMdBold)
            Box(Modifier.background(Color.White, RoundedCornerShape(24.dp)).padding(20.dp)) {
                QrCodeImage(data = qrData, modifier = Modifier.size(260.dp))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(user.userId.value, color = ElementTheme.colors.textSecondary)
                IconButton(
                    onClick = { context.copyToSystemClipboard(user.userId.value, copiedMessage) },
                ) {
                    Icon(
                        imageVector = CompoundIcons.Copy(),
                        contentDescription = stringResource(R.string.screen_friend_copy_account),
                    )
                }
            }
            OutlinedButton(
                text = stringResource(R.string.screen_friend_save_qr),
                leadingIcon = IconSource.Vector(CompoundIcons.Download()),
                onClick = {
                    val accountName = user.userId.value.substringAfter('@').substringBefore(':').ifBlank { "account" }
                    saveQrLauncher.launch("yizhe-qr-$accountName.png")
                },
            )
            Text(stringResource(R.string.screen_friend_my_qr_description), textAlign = TextAlign.Center, color = ElementTheme.colors.textSecondary)
        }
    }
}

@PreviewsDayNight
@Composable
private fun FriendScannerContentPreview(result: FriendQrScanResult? = null) = ElementPreview {
    FriendScannerContent(
        permissionGranted = true,
        scanning = result == null,
        result = result,
        onBack = {},
        onMyQrCode = {},
        onPickImage = {},
        onScanAgain = {},
        cameraContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF52685B), Color(0xFF17201B)),
                        ),
                    ),
            )
        },
    )
}

@PreviewsDayNight
@Composable
private fun MyFriendQrCodeViewPreview() = ElementPreview {
    MyFriendQrCodeView(user = MatrixUser(io.element.android.libraries.matrix.api.core.UserId("@yizhe:example.org")), onBack = {})
}

@PreviewsDayNight
@Composable
private fun FriendScannerSelfPreview() = FriendScannerContentPreview(FriendQrScanResult.Self)

@PreviewsDayNight
@Composable
private fun FriendScannerInvalidPreview() = FriendScannerContentPreview(FriendQrScanResult.Invalid)
