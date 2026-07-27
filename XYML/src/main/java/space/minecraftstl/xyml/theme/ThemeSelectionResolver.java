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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Resolves persisted theme references from an already validated offline package inventory.
///
/// Package IDs are indexed in encounter order. The first package with an ID wins so callers can place trusted
/// bundled packs before local installations and prevent a local duplicate from replacing a built-in fallback.
@NotNullByDefault
public final class ThemeSelectionResolver {
    /// Built-in default reference used by the convenience constructor.
    public static final ThemeReference DEFAULT_FALLBACK = new ThemeReference("hmcl.default", null);

    /// Validated packages retained in deterministic encounter order.
    private final @Unmodifiable List<ThemePackPackage> packages;

    /// First package by stable package ID.
    private final @Unmodifiable Map<String, ThemePackPackage> packagesById;

    /// Reference used when persisted selection is missing or invalid.
    private final ThemeReference fallbackReference;

    /// Creates a resolver using the built-in default reference as fallback.
    ///
    /// @param packages validated built-in packages followed by validated local packages
    public ThemeSelectionResolver(Collection<? extends ThemePackPackage> packages) {
        this(packages, DEFAULT_FALLBACK);
    }

    /// Creates a resolver with an explicit mandatory fallback reference.
    ///
    /// @param packages validated packages in trust and presentation order
    /// @param fallbackReference reference that must exist in the package inventory
    public ThemeSelectionResolver(
            Collection<? extends ThemePackPackage> packages,
            ThemeReference fallbackReference) {
        this.packages = List.copyOf(packages);
        this.fallbackReference = Objects.requireNonNull(fallbackReference, "fallbackReference");
        LinkedHashMap<String, ThemePackPackage> indexed = new LinkedHashMap<>();
        for (ThemePackPackage themePackage : this.packages) {
            ThemePackPackage checked = Objects.requireNonNull(themePackage, "package");
            indexed.putIfAbsent(checked.manifest().id(), checked);
        }
        packagesById = Map.copyOf(indexed);
        if (find(fallbackReference) == null) {
            throw new IllegalArgumentException("Theme fallback is unavailable: " + fallbackReference);
        }
    }

    /// Resolves theme conditions first and user overrides second.
    ///
    /// @param request complete resolution request
    /// @return effective reference and concrete renderer-independent values
    public ResolvedThemeSelection resolve(ThemeResolutionRequest request) {
        Objects.requireNonNull(request, "request");
        @Nullable SelectedTheme selected = find(request.selectedTheme());
        boolean fallbackUsed = selected == null;
        if (selected == null) {
            selected = Objects.requireNonNull(find(fallbackReference), "validated fallback");
        }

        ThemeAppearance appearance = selected.theme().resolve(request.context());
        ResolvedTheme themeValues = appearance.toResolvedTheme(request.context());
        ResolvedTheme effective = request.userOverrides().apply(themeValues, request.context().brightness());
        return new ResolvedThemeSelection(
                request.selectedTheme(),
                selected.reference(),
                effective,
                fallbackUsed);
    }

    /// Finds an exact package and theme reference from the indexed inventory.
    ///
    /// @param reference requested reference
    /// @return selected declaration, or `null` when either component is unavailable
    private @Nullable SelectedTheme find(ThemeReference reference) {
        @Nullable ThemePackPackage themePackage = packagesById.get(reference.packId());
        if (themePackage == null) {
            return null;
        }
        @Nullable Theme theme = themePackage.manifest().findTheme(reference.themeId());
        return theme != null ? new SelectedTheme(themePackage.referenceFor(theme), theme) : null;
    }

    /// One exact package-owned theme declaration.
    ///
    /// @param reference canonical reference
    /// @param theme parsed declaration
    @NotNullByDefault
    private record SelectedTheme(ThemeReference reference, Theme theme) {
        /// Validates both selected declaration values.
        private SelectedTheme {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(theme, "theme");
        }
    }
}
