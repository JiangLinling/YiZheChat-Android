package io.element.android.features.roomdetails.impl

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.qrcode.QrCodeImage
import io.element.android.libraries.qrcode.createQrCodeBitmap
import io.element.android.security.dataprotection.api.GroupShareCapability
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Composable
internal fun GroupShareQrDialog(capability: GroupShareCapability, name: String, onDismiss: () -> Unit, onSendCard: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch {
            val success = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    check(createQrCodeBitmap(capability.link()).compress(Bitmap.CompressFormat.PNG, 100, stream))
                } ?: error("QR destination unavailable")
            }.isSuccess }
            android.widget.Toast.makeText(context, if (success) R.string.screen_group_share_saved else R.string.screen_group_share_save_error,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, maxLines = 2)
                QrCodeImage(capability.link(), Modifier.size(240.dp))
                Text(stringResource(R.string.screen_group_share_valid_until,
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(capability.expiresAtMillis))))
                Button(text = stringResource(R.string.screen_group_share_card), onClick = onSendCard)
                TextButton(text = stringResource(R.string.screen_group_share_save), onClick = { save.launch("yizhe-group-qr.png") })
                TextButton(text = stringResource(io.element.android.libraries.ui.strings.CommonStrings.action_close), onClick = onDismiss)
            }
        }
    }
}

@PreviewsDayNight
@Composable
private fun GroupQrPreview() = ElementPreview {
    GroupShareQrDialog(GroupShareCapability("!group:example.org", "example-token", 1791990000000), "Group", {}, {})
}
