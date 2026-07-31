/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
