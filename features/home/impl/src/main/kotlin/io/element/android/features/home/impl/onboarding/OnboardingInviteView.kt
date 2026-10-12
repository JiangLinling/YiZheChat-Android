/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.home.impl.R
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.ProgressDialog
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.qrcode.QrCodeImage
import io.element.android.security.dataprotection.api.OnboardingInvitation
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingInviteView(
    state: OnboardingInviteState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = stringResource(R.string.screen_onboarding_invite_title),
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { padding ->
        when (val invitation = state.invitation) {
            AsyncData.Uninitialized, is AsyncData.Loading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    ProgressDialog(text = stringResource(R.string.screen_onboarding_invite_creating))
                }
            }
            is AsyncData.Failure -> {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.screen_onboarding_invite_error),
                        textAlign = TextAlign.Center,
                        color = ElementTheme.colors.textSecondary,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        text = stringResource(R.string.screen_onboarding_invite_retry),
                        onClick = { state.eventSink(OnboardingInviteEvent.Generate) },
                    )
                }
            }
            is AsyncData.Success -> InvitationContent(
                invitation = invitation.data,
                onCopy = { value -> context.copyPublicInvitation(value) },
                onGenerateAnother = { state.eventSink(OnboardingInviteEvent.Generate) },
                onRevoke = { state.eventSink(OnboardingInviteEvent.Revoke) },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun InvitationContent(
    invitation: OnboardingInvitation,
    onCopy: (String) -> Unit,
    onGenerateAnother: () -> Unit,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val active = invitation.status == "ACTIVE"
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (active) {
            Box(Modifier.background(Color.White, RoundedCornerShape(24.dp)).padding(18.dp)) {
                QrCodeImage(data = invitation.invitationUrl, modifier = Modifier.size(244.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.screen_onboarding_invite_code),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
            )
            Text(
                text = invitation.inviteCode.chunked(2).joinToString(" "),
                style = ElementTheme.typography.fontHeadingXlBold.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(6.dp))
            val expiry = android.text.format.DateFormat.getDateFormat(context).format(Date(invitation.expiresAtEpochMillis)) + " " +
                android.text.format.DateFormat.getTimeFormat(context).format(Date(invitation.expiresAtEpochMillis))
            Text(
                text = stringResource(R.string.screen_onboarding_invite_expires, expiry),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.screen_onboarding_invite_hint),
                textAlign = TextAlign.Center,
                color = ElementTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                text = stringResource(R.string.screen_onboarding_invite_copy_link),
                onClick = { onCopy(invitation.invitationUrl) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                text = stringResource(R.string.screen_onboarding_invite_copy_code),
                onClick = { onCopy(invitation.inviteCode) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                text = stringResource(R.string.screen_onboarding_invite_generate_another),
                onClick = onGenerateAnother,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.screen_onboarding_invite_generate_another_hint),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                text = stringResource(R.string.screen_onboarding_invite_revoke),
                onClick = onRevoke,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Spacer(Modifier.height(80.dp))
            Text(
                text = stringResource(R.string.screen_onboarding_invite_revoked),
                style = ElementTheme.typography.fontHeadingMdBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun Context.copyPublicInvitation(value: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("YiZhe invitation", value))
    Toast.makeText(this, getString(R.string.screen_onboarding_invite_copied), Toast.LENGTH_SHORT).show()
}

@Preview
@Composable
private fun OnboardingInviteViewPreview() = ElementPreview {
    OnboardingInviteView(
        state = OnboardingInviteState(
            invitation = AsyncData.Success(
                OnboardingInvitation(
                    publicId = "abcdefghijklmnopqrstuvwxyz012345",
                    inviteCode = "381204",
                    inviterUserId = "@yizheinviter:yizhesy.cn",
                    inviterDisplayName = "推荐人",
                    status = "ACTIVE",
                    expiresAtEpochMillis = System.currentTimeMillis() + 3_600_000,
                    invitationUrl = "https://www.yizhesy.cn/i/abcdefghijklmnopqrstuvwxyz012345",
                )
            ),
            eventSink = {},
        ),
        onBack = {},
    )
}
