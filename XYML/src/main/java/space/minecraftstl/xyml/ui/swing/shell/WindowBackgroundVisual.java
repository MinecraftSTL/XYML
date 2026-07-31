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
