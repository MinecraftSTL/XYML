// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable visible and accessible text for the installed resource-pack catalog.
///
/// @param pageTitle page heading
/// @param refreshAction normal refresh command label
/// @param refreshingAction in-progress refresh command label
/// @param refreshTooltip refresh command tooltip
/// @param retryAction failed scan retry command label
/// @param retryTooltip failed scan retry command tooltip
/// @param idleText text before lazy loading starts
/// @param loadingText local index loading text
/// @param emptyText empty resource-pack directory text
/// @param failureTitle local scan failure heading
/// @param unsupportedText unsupported Minecraft-version text
/// @param detailsTitle details-region heading
/// @param noSelectionText details text with no loaded selection
/// @param fileNameLabel exact file-name field label
/// @param pathLabel normalized path field label
/// @param descriptionLabel complete description field label
/// @param compatibilityLabel compatibility field label
/// @param enabledLabel enabled-state field label
/// @param enabledText enabled-state affirmative text
/// @param disabledText enabled-state negative text
/// @param compatibleText compatible pack text
/// @param tooNewText newer-format pack text
/// @param tooOldText older-format pack text
/// @param invalidText invalid-format pack text
/// @param missingPackMetadataText missing pack metadata text
/// @param missingGameMetadataText missing managed-game metadata text
@NotNullByDefault
public record ResourcePackCatalogStrings(
        String pageTitle,
        String refreshAction,
        String refreshingAction,
        String refreshTooltip,
        String retryAction,
        String retryTooltip,
        String idleText,
        String loadingText,
        String emptyText,
        String failureTitle,
        String unsupportedText,
        String detailsTitle,
        String noSelectionText,
        String fileNameLabel,
        String pathLabel,
        String descriptionLabel,
        String compatibilityLabel,
        String enabledLabel,
        String enabledText,
        String disabledText,
        String compatibleText,
        String tooNewText,
        String tooOldText,
        String invalidText,
        String missingPackMetadataText,
        String missingGameMetadataText) {
    /// Validates all localized resource-pack catalog text.
    public ResourcePackCatalogStrings {
        Objects.requireNonNull(pageTitle, "pageTitle");
        Objects.requireNonNull(refreshAction, "refreshAction");
        Objects.requireNonNull(refreshingAction, "refreshingAction");
        Objects.requireNonNull(refreshTooltip, "refreshTooltip");
        Objects.requireNonNull(retryAction, "retryAction");
        Objects.requireNonNull(retryTooltip, "retryTooltip");
        Objects.requireNonNull(idleText, "idleText");
        Objects.requireNonNull(loadingText, "loadingText");
        Objects.requireNonNull(emptyText, "emptyText");
        Objects.requireNonNull(failureTitle, "failureTitle");
        Objects.requireNonNull(unsupportedText, "unsupportedText");
        Objects.requireNonNull(detailsTitle, "detailsTitle");
        Objects.requireNonNull(noSelectionText, "noSelectionText");
        Objects.requireNonNull(fileNameLabel, "fileNameLabel");
        Objects.requireNonNull(pathLabel, "pathLabel");
        Objects.requireNonNull(descriptionLabel, "descriptionLabel");
        Objects.requireNonNull(compatibilityLabel, "compatibilityLabel");
        Objects.requireNonNull(enabledLabel, "enabledLabel");
        Objects.requireNonNull(enabledText, "enabledText");
        Objects.requireNonNull(disabledText, "disabledText");
        Objects.requireNonNull(compatibleText, "compatibleText");
        Objects.requireNonNull(tooNewText, "tooNewText");
        Objects.requireNonNull(tooOldText, "tooOldText");
        Objects.requireNonNull(invalidText, "invalidText");
        Objects.requireNonNull(missingPackMetadataText, "missingPackMetadataText");
        Objects.requireNonNull(missingGameMetadataText, "missingGameMetadataText");
    }

    /// Returns the localized text for one toolkit-neutral compatibility state.
    ///
    /// @param compatibility compatibility state to present
    /// @return localized compatibility text
    public String compatibilityText(ResourcePackCompatibility compatibility) {
        return switch (Objects.requireNonNull(compatibility, "compatibility")) {
            case COMPATIBLE -> compatibleText;
            case TOO_NEW -> tooNewText;
            case TOO_OLD -> tooOldText;
            case INVALID -> invalidText;
            case MISSING_PACK_META -> missingPackMetadataText;
            case MISSING_GAME_META -> missingGameMetadataText;
        };
    }

    /// Returns the localized text for one options-enabled state.
    ///
    /// @param enabled whether the resource pack is enabled
    /// @return localized enabled-state text
    public String enabledText(boolean enabled) {
        return enabled ? enabledText : disabledText;
    }
}
