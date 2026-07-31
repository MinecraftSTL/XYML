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
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.util.gson.JsonUtils;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests toolkit-neutral theme manifest parsing and ordered conditional resolution.
@NotNullByDefault
public final class ThemePackManifestDomainTest {
    /// The bundled default manifest resolves its light and dark assets without renderer-specific types.
    @Test
    public void parsesBundledDefaultThemeAndBrightnessOverride() throws Exception {
        ThemePackManifest manifest;
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(
                "/assets/themes/xyml.default/manifest.json"))) {
            manifest = JsonUtils.fromNonNullJsonFully(input, ThemePackManifest.class);
        }

        Theme theme = manifest.themes().get(0);
        ThemeAppearance light = theme.resolve(new ThemeResolveContext(ThemeBrightness.LIGHT, "windows", "zh"));
        ThemeAppearance dark = theme.resolve(new ThemeResolveContext(ThemeBrightness.DARK, "windows", "zh"));

        assertEquals("#6B69D6", Objects.requireNonNull(light.color()).resolveFallback().color());
        assertEquals(
                "assets/background-light.png",
                ((ThemeBackground.Image) Objects.requireNonNull(light.background()).source()).path());
        assertEquals(
                "assets/background-dark.png",
                ((ThemeBackground.Image) Objects.requireNonNull(dark.background()).source()).path());
        assertEquals(
                java.util.Set.of("assets/background-light.png", "assets/background-dark.png"),
                manifest.referencedAssets());
    }

    /// The bundled multi-theme classic manifest retains all stable theme identities and built-in wallpapers.
    @Test
    public void parsesBundledClassicThemePack() throws Exception {
        ThemePackManifest manifest;
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(
                "/assets/themes/xyml.classic/manifest.json"))) {
            manifest = JsonUtils.fromNonNullJsonFully(input, ThemePackManifest.class);
        }

        assertEquals(3, manifest.themes().size());
        Theme classic315 = Objects.requireNonNull(manifest.findTheme("2021-08-26"));
        ThemeBackgroundSettings background = Objects.requireNonNull(classic315.appearance().background());
        assertEquals(new ThemeBackground.Builtin("2021-08-26"), background.source());
        assertEquals(ThemeBrightness.LIGHT, classic315.appearance().brightness());
        assertEquals(ThemeColorStyle.FIDELITY, classic315.appearance().colorStyle());
        assertTrue(manifest.referencedAssets().isEmpty());
    }

    /// The production built-in catalog validates both packaged packs and resolves resources without a download.
    @Test
    public void loadsBundledCatalogOnCallerWorker() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            @Unmodifiable List<BuiltinThemePack> packs = new BuiltinThemePackCatalog()
                    .loadAll(executor)
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);

            assertEquals(List.of("xyml.default", "xyml.classic"),
                    packs.stream().map(pack -> pack.manifest().id()).toList());
            BuiltinThemePack defaultPack = packs.get(0);
            try (InputStream image = defaultPack.asset("assets/background-light.png").openStream()) {
                assertTrue(image.read() >= 0);
            }
            assertEquals(
                    LauncherSettings.DEFAULT_THEME_REFERENCE,
                    defaultPack.referenceFor(defaultPack.manifest().themes().get(0)));
            assertThrows(UnsupportedOperationException.class, () -> packs.add(defaultPack));
        } finally {
            executor.shutdownNow();
        }
    }

    /// Matching overrides merge nested background fields in declaration order while unknown conditions do not match.
    @Test
    public void appliesMatchingOverridesInDeclarationOrder() {
        ThemePackManifest manifest = parse("""
                {
                  "$schema": "https://raw.githubusercontent.com/MinecraftSTL/XYML/main/docs/schemas/theme-pack/1.0.0.json",
                  "id": "example.overrides",
                  "version": "1.0",
                  "name": "Overrides",
                  "theme": {
                    "color": "red",
                    "background": { "type": "builtin", "id": "2021-08-26", "opacity": 1.0 },
                    "overrides": [
                      {
                        "condition": { "brightness": "dark" },
                        "background": { "opacity": 0.75 }
                      },
                      {
                        "condition": { "os": ["windows", "linux"] },
                        "background": { "opacity": 0.5 }
                      },
                      {
                        "condition": { "future-key": "future-value" },
                        "brightness": "light"
                      }
                    ]
                  }
                }
                """);

        ThemeAppearance resolved = manifest.themes().get(0).resolve(
                new ThemeResolveContext(ThemeBrightness.DARK, "windows", "en"));
        ThemeBackgroundSettings background = Objects.requireNonNull(resolved.background());

        assertEquals(new ThemeBackground.Builtin("2021-08-26"), background.source());
        assertEquals(0.5, background.opacity());
        assertNull(resolved.brightness());
        assertEquals(ThemeBrightness.DARK, resolved.toResolvedTheme(
                new ThemeResolveContext(ThemeBrightness.DARK, "windows", "en")).brightness());
    }

    /// Optional malformed metadata is ignored, while required multi-theme identities and duplicate IDs are rejected.
    @Test
    public void preservesHistoricalOptionalRecoveryAndRejectsAmbiguity() {
        ThemePackManifest optionalRecovery = parse("""
                {
                  "$schema": "https://raw.githubusercontent.com/MinecraftSTL/XYML/main/docs/schemas/theme-pack/1.0.0.json",
                  "id": "example.optional",
                  "version": "1.0",
                  "name": "Optional",
                  "authors": "invalid",
                  "description": "",
                  "icon": "../escape.png",
                  "theme": { "id": "../bad", "name": "", "icon": "bad.png" }
                }
                """);

        assertTrue(optionalRecovery.authors().isEmpty());
        assertNull(optionalRecovery.description());
        assertNull(optionalRecovery.icon());
        assertNull(optionalRecovery.themes().get(0).id());
        assertNull(optionalRecovery.themes().get(0).name());

        assertThrows(RuntimeException.class, () -> parse("""
                {
                  "$schema": "https://raw.githubusercontent.com/MinecraftSTL/XYML/main/docs/schemas/theme-pack/1.0.0.json",
                  "id": "example.duplicate",
                  "version": "1.0",
                  "name": "Duplicate",
                  "themes": [
                    { "id": "same", "name": "First" },
                    { "id": "same", "name": "Second" }
                  ]
                }
                """));
    }

    /// Serialization round-trips the neutral styles, numeric contrast, conditions, and resource references.
    @Test
    public void roundTripsToolkitNeutralAppearance() {
        ThemePackManifest source = parse("""
                {
                  "$schema": "https://raw.githubusercontent.com/MinecraftSTL/XYML/main/docs/schemas/theme-pack/1.0.0.json",
                  "id": "example.roundtrip",
                  "version": "2.0",
                  "name": "Round Trip",
                  "theme": {
                    "color": { "source": "wallpaper" },
                    "brightness": "dark",
                    "colorStyle": "fruit-salad",
                    "contrast": 0.25,
                    "background": { "type": "paint", "paint": "#123456", "opacity": 0.8 },
                    "titleBar": { "transparent": true }
                  }
                }
                """);

        ThemePackManifest restored = parse(JsonUtils.GSON.toJson(source));
        ThemeAppearance appearance = restored.themes().get(0).appearance();

        assertTrue(appearance.color() instanceof ThemeColorSource.Wallpaper);
        assertEquals(ThemeBrightness.DARK, appearance.brightness());
        assertEquals(ThemeColorStyle.FRUIT_SALAD, appearance.colorStyle());
        assertEquals(new ThemeContrast(0.25), appearance.contrast());
        assertTrue(Objects.requireNonNull(appearance.titleBar()).transparent());
        assertFalse(appearance.isEmpty());
    }

    /// Parses one manifest string through the repository's production Gson adapter.
    private static ThemePackManifest parse(String json) {
        return Objects.requireNonNull(JsonUtils.fromJson(json, ThemePackManifest.class));
    }
}
