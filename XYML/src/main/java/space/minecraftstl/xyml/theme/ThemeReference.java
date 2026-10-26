// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import com.google.gson.annotations.SerializedName;
import space.minecraftstl.xyml.util.gson.JsonSerializable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Identifies one theme declared by an installed theme pack.
///
/// @param packId the theme-pack identifier
/// @param themeId the selected theme identifier inside the pack, or `null` for an unnamed single-theme pack
@NotNullByDefault
@JsonSerializable
public record ThemeReference(
        @SerializedName("packId") String packId,
        @SerializedName("themeId") @Nullable String themeId) {

    /// Creates a theme reference.
    ///
    /// @param packId the theme-pack identifier
    /// @param themeId the selected theme identifier inside the pack, or `null` for an unnamed single-theme pack
    public ThemeReference {
        packId = requireIdentifier(packId, "packId");
        themeId = themeId == null ? null : requireIdentifier(themeId, "themeId");
    }

    /// Validates one persisted theme identifier without loading the retired theme-pack renderer.
    ///
    /// @param value identifier value
    /// @param name component name
    /// @return validated identifier
    private static String requireIdentifier(String value, String name) {
        if (value.isBlank() || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(name + " is not a valid theme identifier: " + value);
        }
        return value;
    }
}
