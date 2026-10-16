/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.theme

import androidx.compose.ui.graphics.Color
import io.element.android.compound.tokens.generated.SemanticColors

/**
 * YiZheChat's Chinese-inspired palette.
 *
 * Rice paper provides the neutral surfaces, bamboo green carries interactive states,
 * warm ink replaces cold greys, and cinnabar is reserved for critical feedback.
 */
internal object YiZhePalette {
    // Light: rice paper and ink
    val paper = Color(0xFFFCF7ED)
    val paperRaised = Color(0xFFFFFDF8)
    val paperSubtle = Color(0xFFF7F0E4)
    val paperPressed = Color(0xFFECE2D3)
    val paperDisabled = Color(0xFFE9E1D3)
    val ink = Color(0xFF242A25)
    val inkSecondary = Color(0xFF5D675F)
    val inkTertiary = Color(0xFF788178)
    val inkDisabled = Color(0xFF9B9B8E)

    // Bamboo green: the principal interactive colour
    val bamboo = Color(0xFF3F6B52)
    val bambooHovered = Color(0xFF355D46)
    val bambooPressed = Color(0xFF294A38)
    val bambooSoft = Color(0xFFE4EEE5)
    val bambooSoftHovered = Color(0xFFD8E6DA)
    val bambooDisabled = Color(0xFFB9C0B6)
    val bambooBorder = Color(0xFF6F8574)
    val sentBubble = Color(0xFFA6CBAE)

    // Supporting mineral colours
    val celadon = Color(0xFF2F6770)
    val celadonSoft = Color(0xFFE4EFF0)
    val cinnabar = Color(0xFFA84336)
    val cinnabarHovered = Color(0xFF91372D)
    val cinnabarSoft = Color(0xFFF7E4DE)
    val cinnabarSoftHovered = Color(0xFFF0D5CD)
    val gold = Color(0xFF9A6A2F)
    val goldSoft = Color(0xFFE9DAB8)

    // Dark: ink night and moonlit celadon
    val inkNight = Color(0xFF151A17)
    val inkNightRaised = Color(0xFF1B231D)
    val inkNightSubtle = Color(0xFF202A23)
    val inkNightPressed = Color(0xFF334038)
    val inkNightDisabled = Color(0xFF28312B)
    val moonPaper = Color(0xFFF2EDDF)
    val moonPaperSecondary = Color(0xFFB8BEAF)
    val moonPaperTertiary = Color(0xFF90998D)
    val moonPaperDisabled = Color(0xFF777D72)
    val celadonMoon = Color(0xFF91B49A)
    val celadonMoonHovered = Color(0xFFA4C6AB)
    val celadonMoonPressed = Color(0xFF7FA58A)
    val celadonNightSoft = Color(0xFF294335)
    val celadonNightSoftHovered = Color(0xFF31503E)
    val darkOnSolid = Color(0xFF17231A)
    val darkBorder = Color(0xFF819588)
    val darkBorderSubtle = Color(0xFF526056)
    val darkInfo = Color(0xFF8BC1C7)
    val darkInfoSoft = Color(0xFF20393D)
    val darkCinnabar = Color(0xFFE29183)
    val darkCinnabarHovered = Color(0xFFF0A194)
    val darkCinnabarSoft = Color(0xFF482723)
    val darkCinnabarSoftHovered = Color(0xFF59302A)
    val darkGold = Color(0xFFD6B474)
    val darkGoldSoft = Color(0xFF433A27)
}

internal fun SemanticColors.withYiZheLightColors(): SemanticColors = copy(
    bgAccentHovered = YiZhePalette.bambooHovered,
    bgAccentPressed = YiZhePalette.bambooPressed,
    bgAccentRest = YiZhePalette.bamboo,
    bgAccentSelected = YiZhePalette.bambooSoft,
    bgActionPrimaryDisabled = YiZhePalette.bambooDisabled,
    bgActionPrimaryHovered = YiZhePalette.bambooHovered,
    bgActionPrimaryPressed = YiZhePalette.bambooPressed,
    bgActionPrimaryRest = YiZhePalette.bamboo,
    bgActionSecondaryHovered = YiZhePalette.paperSubtle,
    bgActionSecondaryPressed = YiZhePalette.paperPressed,
    bgActionSecondaryRest = YiZhePalette.paperRaised,
    bgActionTertiaryHovered = YiZhePalette.paperSubtle,
    bgActionTertiaryRest = YiZhePalette.paperRaised,
    bgActionTertiarySelected = YiZhePalette.bambooSoft,
    bgBadgeAccent = YiZhePalette.goldSoft,
    bgBadgeCritical = YiZhePalette.cinnabarSoft,
    bgBadgeDefault = YiZhePalette.paperRaised,
    bgBadgeInfo = YiZhePalette.celadonSoft,
    bgBadgePrimary = YiZhePalette.ink,
    bgBadgeSecondary = YiZhePalette.paperPressed,
    bgCanvasDefault = YiZhePalette.paper,
    bgCanvasDefaultLevel1 = YiZhePalette.paperRaised,
    bgCanvasDisabled = YiZhePalette.paperDisabled,
    bgCriticalHovered = YiZhePalette.cinnabarHovered,
    bgCriticalPrimary = YiZhePalette.cinnabar,
    bgCriticalSubtle = YiZhePalette.cinnabarSoft,
    bgCriticalSubtleHovered = YiZhePalette.cinnabarSoftHovered,
    bgDecorative1 = Color(0xFFDEE8C9),
    bgDecorative2 = Color(0xFFD7E8E5),
    bgDecorative3 = Color(0xFFE6DCE5),
    bgDecorative4 = Color(0xFFDDDCE9),
    bgDecorative5 = Color(0xFFEDD8D8),
    bgDecorative6 = Color(0xFFF0DEC6),
    bgInfoSubtle = YiZhePalette.celadonSoft,
    bgSubtlePrimary = YiZhePalette.paperPressed,
    bgSubtleSecondary = YiZhePalette.paperSubtle,
    bgSubtleSecondaryLevel0 = Color(0xFFF3EBDE),
    bgSuccessSubtle = YiZhePalette.bambooSoft,
    borderAccentPrimary = YiZhePalette.bamboo,
    borderAccentSubtle = YiZhePalette.bambooBorder,
    borderCriticalHovered = YiZhePalette.cinnabarHovered,
    borderCriticalPrimary = YiZhePalette.cinnabar,
    borderCriticalSubtle = Color(0xFFD49A90),
    borderDisabled = Color(0xFFCFC7BA),
    borderFocused = YiZhePalette.bamboo,
    borderInfoSubtle = Color(0xFF8CAEB2),
    borderInteractiveHovered = YiZhePalette.bambooHovered,
    borderInteractivePrimary = YiZhePalette.bambooBorder,
    borderInteractiveSecondary = Color(0xFFB6B3A7),
    borderSuccessSubtle = Color(0xFF9AB5A0),
    gradientActionStop1 = Color(0xFF8EB39A),
    gradientActionStop2 = Color(0xFF71977D),
    gradientActionStop3 = YiZhePalette.bamboo,
    gradientActionStop4 = YiZhePalette.bambooPressed,
    gradientCriticalStop1 = YiZhePalette.cinnabarSoft,
    gradientCriticalStop2 = YiZhePalette.paper,
    gradientInfoStop1 = YiZhePalette.celadonSoft,
    gradientInfoStop2 = YiZhePalette.paper,
    gradientSubtleStop1 = Color(0xFFD4E4D7),
    gradientSubtleStop2 = Color(0xFFDFEADF),
    gradientSubtleStop3 = Color(0xFFE8F0E7),
    gradientSubtleStop4 = Color(0xFFF0F3EC),
    gradientSubtleStop5 = Color(0xFFF7F5ED),
    gradientSubtleStop6 = Color.Transparent,
    iconAccentPrimary = YiZhePalette.bamboo,
    iconAccentTertiary = Color(0xFF587B63),
    iconCriticalPrimary = YiZhePalette.cinnabar,
    iconDisabled = YiZhePalette.inkDisabled,
    iconInfoPrimary = YiZhePalette.celadon,
    iconOnSolidPrimary = YiZhePalette.paperRaised,
    iconPrimary = YiZhePalette.ink,
    iconPrimaryAlpha = YiZhePalette.ink.copy(alpha = 0.88f),
    iconQuaternary = YiZhePalette.inkDisabled,
    iconQuaternaryAlpha = YiZhePalette.ink.copy(alpha = 0.38f),
    iconSecondary = YiZhePalette.inkSecondary,
    iconSecondaryAlpha = YiZhePalette.ink.copy(alpha = 0.70f),
    iconSuccessPrimary = YiZhePalette.bamboo,
    iconTertiary = YiZhePalette.inkTertiary,
    iconTertiaryAlpha = YiZhePalette.ink.copy(alpha = 0.52f),
    separatorPrimary = Color(0xFFD8D0C3),
    separatorSecondary = Color(0xFFE7DFD2),
    textActionAccent = YiZhePalette.bamboo,
    textActionPrimary = YiZhePalette.ink,
    textBadgeAccent = Color(0xFF5B4A1E),
    textBadgeInfo = YiZhePalette.celadon,
    textCriticalPrimary = YiZhePalette.cinnabar,
    textDecorative1 = Color(0xFF4F622E),
    textDecorative2 = Color(0xFF376461),
    textDecorative3 = Color(0xFF694E66),
    textDecorative4 = Color(0xFF514E70),
    textDecorative5 = Color(0xFF744B4B),
    textDecorative6 = Color(0xFF744F2F),
    textDisabled = YiZhePalette.inkDisabled,
    textInfoPrimary = YiZhePalette.celadon,
    textLinkExternal = YiZhePalette.celadon,
    textOnSolidPrimary = YiZhePalette.paperRaised,
    textPrimary = YiZhePalette.ink,
    textSecondary = YiZhePalette.inkSecondary,
    textSuccessPrimary = YiZhePalette.bamboo,
)

internal fun SemanticColors.withYiZheDarkColors(): SemanticColors = copy(
    bgAccentHovered = YiZhePalette.celadonMoonHovered,
    bgAccentPressed = YiZhePalette.celadonMoonPressed,
    bgAccentRest = YiZhePalette.celadonMoon,
    bgAccentSelected = YiZhePalette.celadonNightSoft,
    bgActionPrimaryDisabled = Color(0xFF526157),
    bgActionPrimaryHovered = YiZhePalette.celadonMoonHovered,
    bgActionPrimaryPressed = YiZhePalette.celadonMoonPressed,
    bgActionPrimaryRest = YiZhePalette.celadonMoon,
    bgActionSecondaryHovered = Color(0xFF2A352D),
    bgActionSecondaryPressed = YiZhePalette.inkNightPressed,
    bgActionSecondaryRest = YiZhePalette.inkNightSubtle,
    bgActionTertiaryHovered = Color(0xFF2A352D),
    bgActionTertiaryRest = YiZhePalette.inkNight,
    bgActionTertiarySelected = YiZhePalette.celadonNightSoft,
    bgBadgeAccent = YiZhePalette.darkGoldSoft,
    bgBadgeCritical = YiZhePalette.darkCinnabarSoft,
    bgBadgeDefault = YiZhePalette.inkNightRaised,
    bgBadgeInfo = YiZhePalette.darkInfoSoft,
    bgBadgePrimary = YiZhePalette.moonPaper,
    bgBadgeSecondary = YiZhePalette.inkNightPressed,
    bgCanvasDefault = YiZhePalette.inkNight,
    bgCanvasDefaultLevel1 = YiZhePalette.inkNightRaised,
    bgCanvasDisabled = YiZhePalette.inkNightDisabled,
    bgCriticalHovered = YiZhePalette.darkCinnabarHovered,
    bgCriticalPrimary = YiZhePalette.darkCinnabar,
    bgCriticalSubtle = YiZhePalette.darkCinnabarSoft,
    bgCriticalSubtleHovered = YiZhePalette.darkCinnabarSoftHovered,
    bgDecorative1 = Color(0xFF34442A),
    bgDecorative2 = Color(0xFF284541),
    bgDecorative3 = Color(0xFF493747),
    bgDecorative4 = Color(0xFF39384A),
    bgDecorative5 = Color(0xFF4B3434),
    bgDecorative6 = Color(0xFF4B3B2A),
    bgInfoSubtle = YiZhePalette.darkInfoSoft,
    bgSubtlePrimary = Color(0xFF28352C),
    bgSubtleSecondary = YiZhePalette.inkNightSubtle,
    bgSubtleSecondaryLevel0 = YiZhePalette.inkNight,
    bgSuccessSubtle = YiZhePalette.celadonNightSoft,
    borderAccentPrimary = YiZhePalette.celadonMoon,
    borderAccentSubtle = YiZhePalette.darkBorder,
    borderCriticalHovered = YiZhePalette.darkCinnabarHovered,
    borderCriticalPrimary = YiZhePalette.darkCinnabar,
    borderCriticalSubtle = Color(0xFF8A5A52),
    borderDisabled = Color(0xFF3F4942),
    borderFocused = YiZhePalette.celadonMoon,
    borderInfoSubtle = Color(0xFF51767B),
    borderInteractiveHovered = YiZhePalette.celadonMoonHovered,
    borderInteractivePrimary = YiZhePalette.darkBorder,
    borderInteractiveSecondary = YiZhePalette.darkBorderSubtle,
    borderSuccessSubtle = Color(0xFF607D68),
    gradientActionStop1 = YiZhePalette.celadonMoonHovered,
    gradientActionStop2 = YiZhePalette.celadonMoon,
    gradientActionStop3 = YiZhePalette.celadonMoonPressed,
    gradientActionStop4 = Color(0xFF5F8069),
    gradientCriticalStop1 = YiZhePalette.darkCinnabarSoft,
    gradientCriticalStop2 = YiZhePalette.inkNight,
    gradientInfoStop1 = YiZhePalette.darkInfoSoft,
    gradientInfoStop2 = YiZhePalette.inkNight,
    gradientSubtleStop1 = Color(0xFF31493A),
    gradientSubtleStop2 = Color(0xFF2B4134),
    gradientSubtleStop3 = Color(0xFF26382E),
    gradientSubtleStop4 = Color(0xFF213027),
    gradientSubtleStop5 = Color(0xFF1B251F),
    gradientSubtleStop6 = Color.Transparent,
    iconAccentPrimary = YiZhePalette.celadonMoon,
    iconAccentTertiary = Color(0xFF7FA58A),
    iconCriticalPrimary = YiZhePalette.darkCinnabar,
    iconDisabled = YiZhePalette.moonPaperDisabled,
    iconInfoPrimary = YiZhePalette.darkInfo,
    iconOnSolidPrimary = YiZhePalette.darkOnSolid,
    iconPrimary = YiZhePalette.moonPaper,
    iconPrimaryAlpha = YiZhePalette.moonPaper.copy(alpha = 0.90f),
    iconQuaternary = YiZhePalette.moonPaperDisabled,
    iconQuaternaryAlpha = YiZhePalette.moonPaper.copy(alpha = 0.38f),
    iconSecondary = YiZhePalette.moonPaperSecondary,
    iconSecondaryAlpha = YiZhePalette.moonPaper.copy(alpha = 0.72f),
    iconSuccessPrimary = YiZhePalette.celadonMoon,
    iconTertiary = YiZhePalette.moonPaperTertiary,
    iconTertiaryAlpha = YiZhePalette.moonPaper.copy(alpha = 0.54f),
    separatorPrimary = Color(0xFF3B463E),
    separatorSecondary = Color(0xFF2B352E),
    textActionAccent = YiZhePalette.celadonMoon,
    textActionPrimary = YiZhePalette.moonPaper,
    textBadgeAccent = YiZhePalette.darkGold,
    textBadgeInfo = YiZhePalette.darkInfo,
    textCriticalPrimary = YiZhePalette.darkCinnabar,
    textDecorative1 = Color(0xFFB8CC8A),
    textDecorative2 = Color(0xFF8BC1BB),
    textDecorative3 = Color(0xFFC3A4BF),
    textDecorative4 = Color(0xFFAAA6CB),
    textDecorative5 = Color(0xFFC9A0A0),
    textDecorative6 = Color(0xFFD0AA82),
    textDisabled = YiZhePalette.moonPaperDisabled,
    textInfoPrimary = YiZhePalette.darkInfo,
    textLinkExternal = YiZhePalette.darkInfo,
    textOnSolidPrimary = YiZhePalette.darkOnSolid,
    textPrimary = YiZhePalette.moonPaper,
    textSecondary = YiZhePalette.moonPaperSecondary,
    textSuccessPrimary = YiZhePalette.celadonMoon,
)
