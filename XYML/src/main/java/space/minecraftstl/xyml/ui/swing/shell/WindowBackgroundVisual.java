// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.Objects;

/// Decoded background layer ready for painting by the application shell.
///
/// @param image decoded image, or `null` for a paint-backed layer
/// @param fill stable paint used for paint backgrounds and image decode gaps
/// @param opacity layer opacity in the inclusive range zero to one
/// @param windowTransparent whether unpainted pixels reveal content behind the native window
@NotNullByDefault
record WindowBackgroundVisual(
        @Nullable BufferedImage image,
        WindowBackgroundPaint fill,
        double opacity,
        boolean windowTransparent) {
    /// Validates one decoded visual.
    WindowBackgroundVisual {
        Objects.requireNonNull(fill, "fill");
        if (!Double.isFinite(opacity) || opacity < 0.0 || opacity > 1.0) {
            throw new IllegalArgumentException("Background opacity must be between zero and one");
        }
    }

    /// Copies this visual with the native window's actually supported transparency state.
    ///
    /// @param transparent active native transparency state
    /// @return updated visual
    WindowBackgroundVisual withWindowTransparency(boolean transparent) {
        return new WindowBackgroundVisual(image, fill, opacity, transparent);
    }
}
