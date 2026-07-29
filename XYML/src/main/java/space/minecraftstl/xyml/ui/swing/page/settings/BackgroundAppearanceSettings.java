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
import space.minecraftstl.xyml.setting.BackgroundType;
import space.minecraftstl.xyml.theme.BackgroundLoadPolicy;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;

import java.util.Objects;

/// Immutable launcher-background settings captured and persisted as one consistent unit.
///
/// @param type configured primary background source
/// @param builtinBackgroundId selected bundled wallpaper identifier
/// @param customImagePath local image file or directory path, or an empty string
/// @param networkImageUrl remote image URL, or an empty string
/// @param customPaint custom solid-color or JavaFX-compatible gradient expression, or `null`
/// @param opacity background opacity in the inclusive range zero to one
/// @param networkCachePolicy whether a network image may be cached locally
/// @param fallbackType non-network fallback source
/// @param fallbackPaint fallback solid-color or JavaFX-compatible gradient expression
/// @param loadPolicy behavior while an image background is loading
/// @param windowTransparent whether transparent background pixels reveal the desktop
/// @param sourceOverridden whether the primary source overrides the selected theme
/// @param opacityOverridden whether opacity overrides the selected theme
/// @param windowTransparencyOverridden whether window transparency overrides the selected theme
@NotNullByDefault
public record BackgroundAppearanceSettings(
        BackgroundType type,
        String builtinBackgroundId,
        String customImagePath,
        String networkImageUrl,
        @Nullable String customPaint,
        double opacity,
        NetworkBackgroundImageCachePolicy networkCachePolicy,
        BackgroundType fallbackType,
        String fallbackPaint,
        BackgroundLoadPolicy loadPolicy,
        boolean windowTransparent,
        boolean sourceOverridden,
        boolean opacityOverridden,
        boolean windowTransparencyOverridden) {
    /// Validates and normalizes one complete background setting.
    public BackgroundAppearanceSettings {
        Objects.requireNonNull(type, "type");
        builtinBackgroundId = Objects.requireNonNull(builtinBackgroundId, "builtinBackgroundId").trim();
        customImagePath = Objects.requireNonNull(customImagePath, "customImagePath").trim();
        networkImageUrl = Objects.requireNonNull(networkImageUrl, "networkImageUrl").trim();
        if (customPaint != null) {
            customPaint = customPaint.trim();
            if (customPaint.isEmpty()) {
                customPaint = null;
            }
        }
        if (!Double.isFinite(opacity) || opacity < 0.0 || opacity > 1.0) {
            throw new IllegalArgumentException("Background opacity must be between zero and one");
        }
        Objects.requireNonNull(networkCachePolicy, "networkCachePolicy");
        Objects.requireNonNull(fallbackType, "fallbackType");
        if (fallbackType != BackgroundType.BUILTIN
                && fallbackType != BackgroundType.PAINT
                && fallbackType != BackgroundType.THEME_COLOR) {
            throw new IllegalArgumentException("Unsupported background fallback type: " + fallbackType);
        }
        fallbackPaint = Objects.requireNonNull(fallbackPaint, "fallbackPaint").trim();
        Objects.requireNonNull(loadPolicy, "loadPolicy");
    }
}
