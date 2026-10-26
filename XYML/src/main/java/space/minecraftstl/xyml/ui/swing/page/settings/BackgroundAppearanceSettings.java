// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.setting.BackgroundType;
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
/// @param windowTransparent whether transparent background pixels reveal the desktop
/// @param sourceOverridden whether the primary source overrides the selected theme
/// @param opacityOverridden whether opacity overrides the selected theme
@NotNullByDefault
public record BackgroundAppearanceSettings(
        BackgroundType type,
        String builtinBackgroundId,
        String customImagePath,
        String networkImageUrl,
        @Nullable String customPaint,
        double opacity,
        NetworkBackgroundImageCachePolicy networkCachePolicy,
        boolean windowTransparent,
        boolean sourceOverridden,
        boolean opacityOverridden) {
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
    }
}
