// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Validated theme pack stored entirely in launcher classpath resources.
///
/// @param resourceRoot absolute classpath directory without a trailing slash
/// @param manifest parsed bundled manifest
@NotNullByDefault
public record BuiltinThemePack(String resourceRoot, ThemePackManifest manifest) implements ThemePackPackage {
    /// Validates the fixed bundled-resource namespace.
    public BuiltinThemePack {
        resourceRoot = Objects.requireNonNull(resourceRoot, "resourceRoot");
        Objects.requireNonNull(manifest, "manifest");
        if (!resourceRoot.startsWith("/assets/themes/")
                || resourceRoot.endsWith("/")
                || resourceRoot.contains("..")
                || resourceRoot.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Unsafe built-in theme-pack resource root: " + resourceRoot);
        }
    }

    /// Resolves one asset within this bundled pack.
    @Override
    public ThemePackResource asset(String entryName) {
        String normalized = ThemePackAsset.normalizeEntryName(entryName);
        return new ThemePackResource.Builtin(resourceRoot + "/" + normalized, normalized);
    }
}
