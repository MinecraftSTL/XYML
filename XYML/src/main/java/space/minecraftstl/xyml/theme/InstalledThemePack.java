// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

/// Immutable descriptor for one validated theme pack installed as a local directory.
///
/// @param directory normalized installation directory
/// @param manifest parsed manifest
@NotNullByDefault
public record InstalledThemePack(Path directory, ThemePackManifest manifest) implements ThemePackPackage {
    /// Normalizes the directory and validates the manifest.
    public InstalledThemePack {
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Objects.requireNonNull(manifest, "manifest");
    }

    /// Creates a contained resource for one normalized referenced asset.
    ///
    /// Opening the returned resource repeats no-follow checks and must happen on background work.
    ///
    /// @param entryName asset entry name
    /// @return contained resource
    public ThemePackResource asset(String entryName) {
        return new ThemePackResource.ContainedFile(directory, entryName);
    }

}
