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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests selected-theme lookup, condition resolution, fallback, and user-override ordering.
@NotNullByDefault
public final class ThemeSelectionResolverTest {
    /// The default pack follows context brightness before explicit user overrides are applied.
    @Test
    public void resolvesConditionsThenUserOverrides() throws Exception {
        ThemeSelectionResolver resolver = resolver();
        ThemeResolveContext context = new ThemeResolveContext(ThemeBrightness.DARK, "windows", "zh");
        ThemeColor custom = new ThemeColor("custom", "#147D64");
        ThemeResolutionRequest request = new ThemeResolutionRequest(
                new ThemeReference("xyml.default", null),
                context,
                new ThemeUserAppearanceOverrides(
                        ThemeBrightnessPreference.LIGHT,
                        ThemeColorSource.custom(custom),
                        ThemeColorStyle.VIBRANT,
                        ThemeContrast.HIGH));

        ResolvedThemeSelection selected = resolver.resolve(request);

        assertAll(
                () -> assertFalse(selected.fallbackUsed()),
                () -> assertEquals(request.selectedTheme(), selected.effectiveReference()),
                () -> assertEquals(custom, selected.theme().primaryColorSeed()),
                () -> assertEquals(ThemeBrightness.LIGHT, selected.theme().brightness()),
                () -> assertEquals(ThemeColorStyle.VIBRANT, selected.theme().colorStyle()),
                () -> assertEquals(ThemeContrast.HIGH, selected.theme().contrast()));
    }

    /// A classic theme contributes its explicit light brightness and orange seed when the user follows the theme.
    @Test
    public void followsSelectedClassicTheme() throws Exception {
        ThemeSelectionResolver resolver = resolver();
        ThemeReference reference = new ThemeReference("xyml.classic", "2015-06-22");
        ThemeResolutionRequest request = new ThemeResolutionRequest(
                reference,
                new ThemeResolveContext(ThemeBrightness.DARK, "windows", "en"),
                ThemeUserAppearanceOverrides.INHERIT_THEME);

        ResolvedThemeSelection selected = resolver.resolve(request);

        assertAll(
                () -> assertFalse(selected.fallbackUsed()),
                () -> assertEquals(reference, selected.effectiveReference()),
                () -> assertEquals(ThemeBrightness.LIGHT, selected.theme().brightness()),
                () -> assertEquals(ThemeColor.of("#E67E22"), selected.theme().primaryColorSeed()));
    }

    /// A stale package or theme ID falls back to the bundled default without mutating the requested reference.
    @Test
    public void fallsBackFromMissingSelection() throws Exception {
        ThemeSelectionResolver resolver = resolver();
        ThemeReference missing = new ThemeReference("missing.pack", "missing-theme");
        ThemeResolutionRequest request = new ThemeResolutionRequest(
                missing,
                new ThemeResolveContext(ThemeBrightness.DARK, "linux", "en"),
                ThemeUserAppearanceOverrides.INHERIT_THEME);

        ResolvedThemeSelection selected = resolver.resolve(request);

        assertAll(
                () -> assertTrue(selected.fallbackUsed()),
                () -> assertEquals(missing, selected.requestedReference()),
                () -> assertEquals(ThemeSelectionResolver.DEFAULT_FALLBACK, selected.effectiveReference()),
                () -> assertEquals(ThemeBrightness.DARK, selected.theme().brightness()),
                () -> assertEquals(ThemeColor.of("#5555FF"), selected.theme().primaryColorSeed()));
    }

    /// Loads the packaged offline inventory and creates one resolver.
    ///
    /// @return resolver containing both built-in packs
    private static ThemeSelectionResolver resolver() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            @Unmodifiable List<BuiltinThemePack> packs = new BuiltinThemePackCatalog()
                    .loadAll(executor)
                    .toCompletableFuture()
                    .get(10L, TimeUnit.SECONDS);
            return new ThemeSelectionResolver(packs);
        } finally {
            executor.shutdownNow();
        }
    }
}
