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
