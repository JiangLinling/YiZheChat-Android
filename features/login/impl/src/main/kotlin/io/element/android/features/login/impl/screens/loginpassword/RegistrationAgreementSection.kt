/* Copyright (c) 2026 YiZheChat contributors. */
package io.element.android.features.login.impl.screens.loginpassword

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.login.impl.R
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Checkbox
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.security.dataprotection.api.RegistrationAgreement

@Composable
internal fun RegistrationAgreementSection(accepted: Boolean, enabled: Boolean, onAcceptedChange: (Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val agreementName = stringResource(R.string.screen_registration_agreement_title)
    val statement = stringResource(R.string.screen_registration_agreement_accept, agreementName)
    val statementWithLink = buildAnnotatedString {
        append(statement)
        val start = statement.indexOf(agreementName)
        if (start >= 0) {
            val end = start + agreementName.length
            addStyle(SpanStyle(color = ElementTheme.colors.textActionAccent, textDecoration = TextDecoration.Underline), start, end)
            addLink(LinkAnnotation.Clickable(tag = "registration-agreement", linkInteractionListener = { open = true }), start, end)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row {
            Checkbox(checked = accepted, enabled = enabled, onCheckedChange = onAcceptedChange)
            Text(
                text = statementWithLink,
                modifier = Modifier.padding(top = 10.dp),
                style = ElementTheme.typography.fontBodySmRegular,
            )
        }
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
                    Text(stringResource(R.string.screen_registration_agreement_title), style = ElementTheme.typography.fontHeadingMdBold)
                    Text(
                        text = RegistrationAgreement.text,
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                        style = ElementTheme.typography.fontBodyMdRegular,
                    )
                    Button(text = stringResource(R.string.screen_registration_agreement_return), onClick = { open = false },
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
