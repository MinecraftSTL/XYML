// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable labels and value formats for read-only Litematic metadata details.
///
/// @param pathLabel source path label
/// @param nameLabel schematic name label
/// @param authorLabel author label
/// @param descriptionLabel description label
/// @param createdLabel creation time label
/// @param modifiedLabel modification time label
/// @param regionCountLabel region count label
/// @param totalVolumeLabel total volume label
/// @param totalBlocksLabel total block count label
/// @param enclosingSizeLabel enclosing dimensions label
/// @param formatVersionLabel Litematic format version label
/// @param minecraftDataVersionLabel Minecraft data version label
/// @param previewLabel preview metadata label
/// @param unknownValue fallback for absent optional metadata
/// @param enclosingSizeFormat three-integer enclosing-size format
/// @param previewDimensionsFormat square preview dimensions format
/// @param previewPixelCountFormat non-square preview pixel-count format
/// @param previewUnavailableText text for absent preview data
@NotNullByDefault
public record SchematicMetadataStrings(
        String pathLabel,
        String nameLabel,
        String authorLabel,
        String descriptionLabel,
        String createdLabel,
        String modifiedLabel,
        String regionCountLabel,
        String totalVolumeLabel,
        String totalBlocksLabel,
        String enclosingSizeLabel,
        String formatVersionLabel,
        String minecraftDataVersionLabel,
        String previewLabel,
        String unknownValue,
        String enclosingSizeFormat,
        String previewDimensionsFormat,
        String previewPixelCountFormat,
        String previewUnavailableText) {
    /// Validates every localized label and format.
    public SchematicMetadataStrings {
        Objects.requireNonNull(pathLabel, "pathLabel");
        Objects.requireNonNull(nameLabel, "nameLabel");
        Objects.requireNonNull(authorLabel, "authorLabel");
        Objects.requireNonNull(descriptionLabel, "descriptionLabel");
        Objects.requireNonNull(createdLabel, "createdLabel");
        Objects.requireNonNull(modifiedLabel, "modifiedLabel");
        Objects.requireNonNull(regionCountLabel, "regionCountLabel");
        Objects.requireNonNull(totalVolumeLabel, "totalVolumeLabel");
        Objects.requireNonNull(totalBlocksLabel, "totalBlocksLabel");
        Objects.requireNonNull(enclosingSizeLabel, "enclosingSizeLabel");
        Objects.requireNonNull(formatVersionLabel, "formatVersionLabel");
        Objects.requireNonNull(minecraftDataVersionLabel, "minecraftDataVersionLabel");
        Objects.requireNonNull(previewLabel, "previewLabel");
        Objects.requireNonNull(unknownValue, "unknownValue");
        Objects.requireNonNull(enclosingSizeFormat, "enclosingSizeFormat");
        Objects.requireNonNull(previewDimensionsFormat, "previewDimensionsFormat");
        Objects.requireNonNull(previewPixelCountFormat, "previewPixelCountFormat");
        Objects.requireNonNull(previewUnavailableText, "previewUnavailableText");
    }
}
