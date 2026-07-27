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
package space.minecraftstl.xyml.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Immutable toolkit-neutral pixels for one normalized instance icon.
///
/// Pixels use the Java ARGB integer layout and are always stored in row-major order on a fixed 40-by-40 canvas.
/// Construction and copy access both isolate mutable arrays, so UI adapters can safely cache and share this value.
@NotNullByDefault
public final class InstanceIconData {
    /// Fixed normalized icon width in pixels.
    public static final int WIDTH = 40;

    /// Fixed normalized icon height in pixels.
    public static final int HEIGHT = 40;

    /// Exact number of pixels in every normalized icon.
    public static final int PIXEL_COUNT = WIDTH * HEIGHT;

    /// Private row-major ARGB pixels, never exposed directly or modified after construction.
    private final int @Unmodifiable [] argbPixels;

    /// Creates one fixed-size icon from a defensive copy of row-major ARGB pixels.
    ///
    /// @param argbPixels exactly 1,600 caller-owned ARGB pixels
    public InstanceIconData(int[] argbPixels) {
        Objects.requireNonNull(argbPixels, "argbPixels");
        if (argbPixels.length != PIXEL_COUNT) {
            throw new IllegalArgumentException(
                    "Instance icon requires exactly " + PIXEL_COUNT + " pixels");
        }
        this.argbPixels = argbPixels.clone();
    }

    /// Returns the fixed normalized width.
    ///
    /// @return 40 pixels
    public int width() {
        return WIDTH;
    }

    /// Returns the fixed normalized height.
    ///
    /// @return 40 pixels
    public int height() {
        return HEIGHT;
    }

    /// Returns one ARGB pixel from the fixed canvas.
    ///
    /// @param x zero-based horizontal coordinate
    /// @param y zero-based vertical coordinate
    /// @return packed ARGB pixel
    public int argbAt(int x, int y) {
        if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) {
            throw new IndexOutOfBoundsException("Instance icon coordinate outside 40-by-40 canvas");
        }
        return argbPixels[y * WIDTH + x];
    }

    /// Returns a caller-owned mutable copy of every row-major ARGB pixel.
    ///
    /// Mutating the returned array cannot affect this value or another copy.
    ///
    /// @return independent ARGB pixel copy
    public int[] copyArgbPixels() {
        return argbPixels.clone();
    }

    /// Compares normalized pixel content.
    ///
    /// @param other candidate value
    /// @return whether both values contain identical 40-by-40 ARGB pixels
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof InstanceIconData iconData
                && Arrays.equals(argbPixels, iconData.argbPixels);
    }

    /// Returns a content-derived hash code.
    ///
    /// @return ARGB pixel hash
    @Override
    public int hashCode() {
        return Arrays.hashCode(argbPixels);
    }

    /// Returns a compact diagnostic description without expanding pixel content.
    ///
    /// @return fixed-dimension icon description
    @Override
    public String toString() {
        return "InstanceIconData[width=" + WIDTH + ", height=" + HEIGHT + "]";
    }
}
