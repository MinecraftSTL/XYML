// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Common resource boundary for bundled and safely installed theme packs.
@NotNullByDefault
public sealed interface ThemePackPackage permits BuiltinThemePack, InstalledThemePack {
    /// Returns the validated manifest.
    ///
    /// @return package manifest
    ThemePackManifest manifest();

    /// Resolves one normalized theme-pack asset without opening it on the calling thread.
    ///
    /// @param entryName normalized referenced asset
    /// @return reopenable resource
    ThemePackResource asset(String entryName);

    /// Returns a persisted reference for one declared theme.
    ///
    /// @param theme selected theme
    /// @return stable selection reference
    /// @throws IllegalArgumentException when the theme is not declared by this package
    default ThemeReference referenceFor(Theme theme) {
        Objects.requireNonNull(theme, "theme");
        if (!manifest().themes().contains(theme)) {
            throw new IllegalArgumentException("Theme does not belong to pack " + manifest().id());
        }
        return new ThemeReference(manifest().id(), theme.id());
    }
}
