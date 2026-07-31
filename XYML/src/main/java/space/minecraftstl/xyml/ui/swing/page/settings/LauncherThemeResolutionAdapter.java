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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.ThemeColorType;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.theme.ThemeColor;
import space.minecraftstl.xyml.theme.ThemeColorSource;
import space.minecraftstl.xyml.theme.ThemeColorStyle;
import space.minecraftstl.xyml.theme.ThemeResolutionRequest;
import space.minecraftstl.xyml.theme.ThemeResolveContext;
import space.minecraftstl.xyml.theme.ThemeUserAppearanceOverrides;

import java.util.Objects;

import static space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher.requireEventThread;

/// Maps launcher launcher properties and override membership into one immutable theme-resolution request.
@NotNullByDefault
public final class LauncherThemeResolutionAdapter {
    /// Prevents utility instantiation.
    private LauncherThemeResolutionAdapter() {
    }

    /// Captures selected-theme and supported user-override values on the Swing EDT.
    ///
    /// @param settings thread-confined loaded launcher settings
    /// @param context current theme resolution context
    /// @return immutable request
    public static ThemeResolutionRequest snapshot(
            LauncherSettings settings,
            ThemeResolveContext context) {
        requireEventThread();
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(context, "context");

        boolean brightnessOverridden = settings.getThemeAppearanceOverrides().contains(
                LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE);
        ThemeBrightnessPreference brightness = ThemeBrightnessPreference.fromSetting(
                brightnessOverridden,
                settings.themeBrightnessModeProperty().get());
        @Nullable ThemeColorSource color = colorOverride(settings);
        @Nullable ThemeColorStyle colorStyle = colorStyleOverride(settings);
        return new ThemeResolutionRequest(
                settings.getSelectedThemeOrDefault(),
                context,
                new ThemeUserAppearanceOverrides(brightness, color, colorStyle, null));
    }

    /// Resolves a custom color only when the corresponding appearance key is overridden.
    ///
    /// Historical default, system, and wallpaper overrides are ignored because the Swing runtime does not provide
    /// faithful implementations for those sources. They therefore inherit the selected theme instead of rendering
    /// a misleading fallback color.
    ///
    /// @param settings loaded launcher settings
    /// @return explicit color source, or `null` to inherit the selected theme
    private static @Nullable ThemeColorSource colorOverride(LauncherSettings settings) {
        if (!settings.getThemeAppearanceOverrides().contains(LauncherSettings.THEME_APPEARANCE_COLOR)) {
            return null;
        }
        ThemeColorType colorType = Objects.requireNonNullElse(
                settings.themeColorTypeProperty().get(),
                ThemeColorType.DEFAULT);
        if (colorType != ThemeColorType.CUSTOM) {
            return null;
        }
        return ThemeColorSource.custom(Objects.requireNonNullElse(
                settings.customThemeColorProperty().get(),
                ThemeColor.DEFAULT));
    }

    /// Parses the persisted style only when the corresponding appearance key is overridden.
    ///
    /// Unknown historical values fall back to the stable launcher style instead of disabling the selected theme.
    ///
    /// @param settings loaded launcher settings
    /// @return explicit style, or `null` to inherit the selected theme
    private static @Nullable ThemeColorStyle colorStyleOverride(LauncherSettings settings) {
        if (!settings.getThemeAppearanceOverrides().contains(LauncherSettings.THEME_APPEARANCE_COLOR_STYLE)) {
            return null;
        }
        @Nullable String rawStyle = settings.themeColorStyleProperty().get();
        if (rawStyle == null) {
            return ThemeColorStyle.FIDELITY;
        }
        try {
            return ThemeColorStyle.parse(rawStyle);
        } catch (IllegalArgumentException ignored) {
            return ThemeColorStyle.FIDELITY;
        }
    }
}
