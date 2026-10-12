package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.matrix.api.permalink.SharedIdentityCard
import io.element.android.features.messages.impl.R

@Composable
internal fun IdentityCardView(card: SharedIdentityCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(min = 200.dp, max = 280.dp).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (card.isGroup) CompoundIcons.Space() else CompoundIcons.UserProfile(), contentDescription = null)
            Text(card.name, style = ElementTheme.typography.fontBodyLgMedium, maxLines = 2)
        }
        Text(stringResource(if (card.isGroup) R.string.screen_identity_group_card else R.string.screen_identity_contact_card),
            style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary)
    }
}

@PreviewsDayNight
@Composable
private fun IdentityCardPreview() = ElementPreview {
    IdentityCardView(SharedIdentityCard(false, "Alice", SharedIdentityCard.userLink("@alice:example.org")), {})
}
