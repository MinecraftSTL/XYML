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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.BackgroundType;
import space.minecraftstl.xyml.theme.BackgroundLoadPolicy;
import space.minecraftstl.xyml.theme.BuiltinBackground;
import space.minecraftstl.xyml.theme.BuiltinThemePack;
import space.minecraftstl.xyml.theme.BuiltinThemePackCatalog;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;
import space.minecraftstl.xyml.theme.ResolvedThemeSelection;
import space.minecraftstl.xyml.theme.ThemeBrightness;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.theme.ThemePackResource;
import space.minecraftstl.xyml.theme.ThemeReference;
import space.minecraftstl.xyml.theme.ThemeResolutionRequest;
import space.minecraftstl.xyml.theme.ThemeResolveContext;
import space.minecraftstl.xyml.theme.ThemeSelectionResolver;
import space.minecraftstl.xyml.theme.ThemeUserAppearanceOverrides;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsSnapshot;
import space.minecraftstl.xyml.ui.swing.page.settings.BackgroundAppearanceSettings;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies theme-owned backgrounds and explicit launcher overrides produce complete renderer requests.
@NotNullByDefault
public final class SwingWindowAppearanceRequestTest {
    /// Startup requests use the matching bundled XYML image without a historical wallpaper fallback.
    @Test
    public void createsBrightnessSpecificBundledInitialRequests() {
        SwingWindowAppearanceRequest light = SwingWindowAppearanceRequest.initial(ThemeBrightness.LIGHT);
        SwingWindowAppearanceRequest dark = SwingWindowAppearanceRequest.initial(ThemeBrightness.DARK);

        assertAll(
                () -> assertInitialResource(light, "assets/background-light.png"),
                () -> assertInitialResource(dark, "assets/background-dark.png"));
    }

    /// Theme inheritance retains the validated package resource that owns the selected image.
    @Test
    public void resolvesThemeOwnedImageResource() {
        ResolvedThemeSelection selection = resolveDefaultTheme();

        SwingWindowAppearanceRequest request = SwingWindowAppearanceRequest.resolve(
                selection,
                snapshot(background(false, false, false)));

        SwingBackgroundSource.ThemePackImage image = assertInstanceOf(
                SwingBackgroundSource.ThemePackImage.class,
                request.source());
        SwingBackgroundSource.Builtin fallback = assertInstanceOf(
                SwingBackgroundSource.Builtin.class,
                request.fallback());
        assertAll(
                () -> assertEquals("assets/background-light.png", image.resource().name()),
                () -> assertEquals(BuiltinBackground.FALLBACK, fallback.background()),
                () -> assertEquals(1.0, request.opacity()),
                () -> assertEquals(BackgroundLoadPolicy.WAIT_FOR_BACKGROUND, request.loadPolicy()),
                () -> assertEquals(NetworkBackgroundImageCachePolicy.ENABLED, request.networkCachePolicy()),
                () -> assertFalse(request.windowTransparent()));
    }

    /// Explicit launcher source, opacity, transparency, cache, loading, and fallback values replace theme values.
    @Test
    public void appliesCompleteLauncherOverrides() {
        BackgroundAppearanceSettings background = new BackgroundAppearanceSettings(
                BackgroundType.NETWORK,
                BuiltinBackground.WALLPAPER_2016_02_25.id(),
                "unused.png",
                "https://textures.example.invalid/background.png",
                "#102030",
                0.35,
                NetworkBackgroundImageCachePolicy.DISABLED,
                BackgroundType.PAINT,
                "rgba(10, 20, 30, 0.5)",
                BackgroundLoadPolicy.SHOW_FALLBACK_WHILE_LOADING,
                true,
                true,
                true,
                true);

        SwingWindowAppearanceRequest request = SwingWindowAppearanceRequest.resolve(
                resolveDefaultTheme(),
                snapshot(background));

        SwingBackgroundSource.Network source = assertInstanceOf(
                SwingBackgroundSource.Network.class,
                request.source());
        SwingBackgroundSource.Paint fallback = assertInstanceOf(
                SwingBackgroundSource.Paint.class,
                request.fallback());
        assertAll(
                () -> assertEquals("https://textures.example.invalid/background.png", source.url()),
                () -> assertEquals("rgba(10, 20, 30, 0.5)", fallback.expression()),
                () -> assertEquals(0.35, request.opacity()),
                () -> assertEquals(BackgroundLoadPolicy.SHOW_FALLBACK_WHILE_LOADING, request.loadPolicy()),
                () -> assertEquals(NetworkBackgroundImageCachePolicy.DISABLED, request.networkCachePolicy()),
                () -> assertTrue(request.windowTransparent()));
    }

    /// Resolves the packaged light default theme without using network or external files.
    ///
    /// @return selected packaged default theme
    private static ResolvedThemeSelection resolveDefaultTheme() {
        @Unmodifiable List<BuiltinThemePack> packs = new BuiltinThemePackCatalog()
                .loadAll(Runnable::run)
                .toCompletableFuture()
                .join();
        return new ThemeSelectionResolver(packs).resolve(new ThemeResolutionRequest(
                new ThemeReference("xyml.default", null),
                new ThemeResolveContext(ThemeBrightness.LIGHT, "windows", "zh-Hans"),
                ThemeUserAppearanceOverrides.INHERIT_THEME));
    }

    /// Creates a complete appearance snapshot around one background configuration.
    ///
    /// @param background launcher-background controls
    /// @return complete appearance snapshot
    private static AppearanceSettingsSnapshot snapshot(BackgroundAppearanceSettings background) {
        return new AppearanceSettingsSnapshot(
                ThemeBrightnessPreference.THEME,
                8,
                0,
                24,
                1,
                true,
                background,
                true);
    }

    /// Creates the default persisted background while controlling only override membership.
    ///
    /// @param sourceOverridden whether the launcher source replaces the theme source
    /// @param opacityOverridden whether launcher opacity replaces theme opacity
    /// @param transparencyOverridden whether launcher transparency replaces theme transparency
    /// @return complete background controls
    private static BackgroundAppearanceSettings background(
            boolean sourceOverridden,
            boolean opacityOverridden,
            boolean transparencyOverridden) {
        return new BackgroundAppearanceSettings(
                BackgroundType.DEFAULT,
                BuiltinBackground.FALLBACK.id(),
                "",
                "",
                null,
                1.0,
                NetworkBackgroundImageCachePolicy.ENABLED,
                BackgroundType.BUILTIN,
                "#FFFFFF",
                BackgroundLoadPolicy.WAIT_FOR_BACKGROUND,
                false,
                sourceOverridden,
                opacityOverridden,
                transparencyOverridden);
    }

    /// Verifies one initial request remains entirely within the bundled XYML theme package.
    ///
    /// @param request initial request under test
    /// @param expectedAssetName expected package-relative asset name
    private static void assertInitialResource(
            SwingWindowAppearanceRequest request,
            String expectedAssetName) {
        SwingBackgroundSource.ThemePackImage source = assertInstanceOf(
                SwingBackgroundSource.ThemePackImage.class,
                request.source());
        SwingBackgroundSource.ThemePackImage fallback = assertInstanceOf(
                SwingBackgroundSource.ThemePackImage.class,
                request.fallback());
        ThemePackResource.Builtin resource = assertInstanceOf(
                ThemePackResource.Builtin.class,
                source.resource());
        assertAll(
                () -> assertEquals(expectedAssetName, resource.name()),
                () -> assertEquals(
                        "/assets/themes/xyml.default/" + expectedAssetName,
                        resource.resourcePath()),
                () -> assertEquals(source, fallback),
                () -> assertEquals(1.0, request.opacity()),
                () -> assertEquals(BackgroundLoadPolicy.WAIT_FOR_BACKGROUND, request.loadPolicy()),
                () -> assertEquals(NetworkBackgroundImageCachePolicy.DISABLED, request.networkCachePolicy()),
                () -> assertFalse(request.windowTransparent()));
    }
}
