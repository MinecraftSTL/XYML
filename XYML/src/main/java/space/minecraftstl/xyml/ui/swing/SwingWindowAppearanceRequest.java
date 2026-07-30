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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.theme.BackgroundLoadPolicy;
import space.minecraftstl.xyml.theme.BuiltinBackground;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;
import space.minecraftstl.xyml.theme.ResolvedThemeSelection;
import space.minecraftstl.xyml.theme.ThemeBackground;
import space.minecraftstl.xyml.theme.ThemeBackgroundSettings;
import space.minecraftstl.xyml.theme.ThemeBrightness;
import space.minecraftstl.xyml.theme.ThemePackResource;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsSnapshot;
import space.minecraftstl.xyml.ui.swing.page.settings.BackgroundAppearanceSettings;

import java.util.Objects;

/// Immutable background and native-window request produced from one theme resolution and one settings snapshot.
///
/// @param source exact primary background source
/// @param fallback exact non-network fallback source
/// @param opacity primary and fallback opacity in the inclusive range zero to one
/// @param loadPolicy background replacement behavior while image I/O is active
/// @param networkCachePolicy whether network image bytes may be cached locally
/// @param windowTransparent whether unpainted window pixels reveal the desktop
@NotNullByDefault
public record SwingWindowAppearanceRequest(
        SwingBackgroundSource source,
        SwingBackgroundSource fallback,
        double opacity,
        BackgroundLoadPolicy loadPolicy,
        NetworkBackgroundImageCachePolicy networkCachePolicy,
        boolean windowTransparent) {
    /// Validates one complete request.
    public SwingWindowAppearanceRequest {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fallback, "fallback");
        if (!Double.isFinite(opacity) || opacity < 0.0 || opacity > 1.0) {
            throw new IllegalArgumentException("Window background opacity must be between zero and one");
        }
        Objects.requireNonNull(loadPolicy, "loadPolicy");
        Objects.requireNonNull(networkCachePolicy, "networkCachePolicy");
    }

    /// Creates the bundled XYML request used before the selected theme package finishes offline resolution.
    ///
    /// @param brightness concrete startup brightness
    /// @return complete renderer request backed only by the matching bundled XYML image
    public static SwingWindowAppearanceRequest initial(ThemeBrightness brightness) {
        ThemeBrightness concreteBrightness = Objects.requireNonNull(brightness, "brightness");
        String assetName = concreteBrightness == ThemeBrightness.DARK
                ? "assets/background-dark.png"
                : "assets/background-light.png";
        SwingBackgroundSource source = new SwingBackgroundSource.ThemePackImage(
                new ThemePackResource.Builtin(
                        "/assets/themes/xyml.default/" + assetName,
                        assetName));
        return new SwingWindowAppearanceRequest(
                source,
                source,
                1.0,
                BackgroundLoadPolicy.WAIT_FOR_BACKGROUND,
                NetworkBackgroundImageCachePolicy.DISABLED,
                false);
    }

    /// Resolves theme inheritance and launcher overrides into one renderer-ready request.
    ///
    /// @param selection exact selected theme, package, and resolved appearance
    /// @param snapshot same-generation persisted appearance controls
    /// @return complete renderer request
    public static SwingWindowAppearanceRequest resolve(
            ResolvedThemeSelection selection,
            AppearanceSettingsSnapshot snapshot) {
        ResolvedThemeSelection selected = Objects.requireNonNull(selection, "selection");
        BackgroundAppearanceSettings settings = Objects.requireNonNull(snapshot, "snapshot").background();
        @Nullable ThemeBackgroundSettings themeBackground = selected.appearance().background();
        SwingBackgroundSource primary = settings.sourceOverridden()
                ? settingsSource(settings)
                : themeSource(selected, themeBackground);
        double opacity = settings.opacityOverridden()
                ? settings.opacity()
                : themeBackground != null && themeBackground.opacity() != null
                        ? themeBackground.opacity()
                        : 1.0;
        boolean transparent = settings.windowTransparencyOverridden()
                ? settings.windowTransparent()
                : Boolean.TRUE.equals(selected.appearance().windowTransparent());
        return new SwingWindowAppearanceRequest(
                primary,
                fallbackSource(settings),
                opacity,
                settings.loadPolicy(),
                settings.networkCachePolicy(),
                transparent);
    }

    /// Converts the explicit launcher source setting.
    private static SwingBackgroundSource settingsSource(BackgroundAppearanceSettings settings) {
        return switch (settings.type()) {
            case DEFAULT -> new SwingBackgroundSource.DefaultLocal();
            case BUILTIN -> new SwingBackgroundSource.Builtin(
                    BuiltinBackground.fromIdOrFallback(settings.builtinBackgroundId()));
            case CUSTOM -> new SwingBackgroundSource.Local(settings.customImagePath());
            case NETWORK -> new SwingBackgroundSource.Network(settings.networkImageUrl());
            case PAINT -> new SwingBackgroundSource.Paint(
                    Objects.requireNonNullElse(settings.customPaint(), ""));
            case THEME_COLOR -> new SwingBackgroundSource.ThemeColorFill();
        };
    }

    /// Converts the effective theme source while retaining package ownership for image assets.
    private static SwingBackgroundSource themeSource(
            ResolvedThemeSelection selection,
            @Nullable ThemeBackgroundSettings background) {
        if (background == null || background.source() == null
                || background.source() instanceof ThemeBackground.Default) {
            return new SwingBackgroundSource.DefaultLocal();
        }
        ThemeBackground source = background.source();
        if (source instanceof ThemeBackground.Builtin builtin) {
            return new SwingBackgroundSource.Builtin(BuiltinBackground.fromIdOrFallback(builtin.id()));
        }
        if (source instanceof ThemeBackground.Image image) {
            return new SwingBackgroundSource.ThemePackImage(selection.themePackage().asset(image.path()));
        }
        if (source instanceof ThemeBackground.Paint paint) {
            return new SwingBackgroundSource.Paint(paint.paint());
        }
        if (source instanceof ThemeBackground.ThemeColorFill) {
            return new SwingBackgroundSource.ThemeColorFill();
        }
        throw new IllegalStateException("Unsupported resolved theme background: " + source);
    }

    /// Converts the configured always-local fallback source.
    private static SwingBackgroundSource fallbackSource(BackgroundAppearanceSettings settings) {
        return switch (settings.fallbackType()) {
            case BUILTIN -> new SwingBackgroundSource.Builtin(
                    BuiltinBackground.fromIdOrFallback(settings.builtinBackgroundId()));
            case PAINT -> new SwingBackgroundSource.Paint(settings.fallbackPaint());
            case THEME_COLOR -> new SwingBackgroundSource.ThemeColorFill();
            default -> throw new IllegalStateException(
                    "Unsupported normalized background fallback: " + settings.fallbackType());
        };
    }
}
