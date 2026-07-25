/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.util.skin;

import org.jetbrains.annotations.NotNullByDefault;

import java.awt.image.BufferedImage;

/// Describes a Minecraft 1.8+ skin and normalizes the legacy 64x32 layout to 64x64.
///
/// Source images may use an integer scale factor. Pixel processing stays in the JDK image model so
/// account and authentication code does not require a graphical toolkit runtime.
///
/// @author yushijinhun
@NotNullByDefault
public final class NormalizedSkin {
    /// Copies one image region, optionally mirroring it horizontally.
    ///
    /// @param source source image
    /// @param destination destination image
    /// @param sourceX source x coordinate
    /// @param sourceY source y coordinate
    /// @param destinationX destination x coordinate
    /// @param destinationY destination y coordinate
    /// @param width region width
    /// @param height region height
    /// @param flipHorizontal whether to mirror source pixels horizontally
    private static void copyImage(
            BufferedImage source,
            BufferedImage destination,
            int sourceX,
            int sourceY,
            int destinationX,
            int destinationY,
            int width,
            int height,
            boolean flipHorizontal) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = source.getRGB(sourceX + x, sourceY + y);
                destination.setRGB(
                        destinationX + (flipHorizontal ? width - x - 1 : x),
                        destinationY + y,
                        pixel);
            }
        }
    }

    /// Original skin image supplied by the caller.
    private final BufferedImage texture;

    /// Normalized square skin image containing generated legacy limbs when required.
    private final BufferedImage normalizedTexture;

    /// Integer pixel scale relative to the canonical 64-pixel-wide format.
    private final int scale;

    /// Whether the original image used the legacy 2:1 skin layout.
    private final boolean oldFormat;

    /// Validates and normalizes a skin image.
    ///
    /// @param texture source skin image
    /// @throws InvalidSkinException if the dimensions are not a scaled 64x64 or 64x32 layout
    public NormalizedSkin(BufferedImage texture) throws InvalidSkinException {
        this.texture = texture;

        // check format
        int w = texture.getWidth();
        int h = texture.getHeight();
        if (w % 64 != 0) {
            throw new InvalidSkinException("Invalid size " + w + "x" + h);
        }
        if (w == h) {
            oldFormat = false;
        } else if (w == h * 2) {
            oldFormat = true;
        } else {
            throw new InvalidSkinException("Invalid size " + w + "x" + h);
        }

        // compute scale
        scale = w / 64;

        normalizedTexture = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
        copyImage(texture, normalizedTexture, 0, 0, 0, 0, w, h, false);
        if (oldFormat) {
            convertOldSkin();
        }
    }

    /// Generates the left-leg and left-arm regions omitted by the legacy skin layout.
    private void convertOldSkin() {
        copyImageRelative(4, 16, 20, 48, 4, 4, true); // Top Leg
        copyImageRelative(8, 16, 24, 48, 4, 4, true); // Bottom Leg
        copyImageRelative(0, 20, 24, 52, 4, 12, true); // Outer Leg
        copyImageRelative(4, 20, 20, 52, 4, 12, true); // Front Leg
        copyImageRelative(8, 20, 16, 52, 4, 12, true); // Inner Leg
        copyImageRelative(12, 20, 28, 52, 4, 12, true); // Back Leg
        copyImageRelative(44, 16, 36, 48, 4, 4, true); // Top Arm
        copyImageRelative(48, 16, 40, 48, 4, 4, true); // Bottom Arm
        copyImageRelative(40, 20, 40, 52, 4, 12, true); // Outer Arm
        copyImageRelative(44, 20, 36, 52, 4, 12, true); // Front Arm
        copyImageRelative(48, 20, 32, 52, 4, 12, true); // Inner Arm
        copyImageRelative(52, 20, 44, 52, 4, 12, true); // Back Arm
    }

    /// Copies a region whose coordinates are expressed in canonical 64-pixel skin units.
    ///
    /// @param sourceX source x coordinate
    /// @param sourceY source y coordinate
    /// @param destinationX destination x coordinate
    /// @param destinationY destination y coordinate
    /// @param width region width
    /// @param height region height
    /// @param flipHorizontal whether to mirror source pixels horizontally
    private void copyImageRelative(
            int sourceX,
            int sourceY,
            int destinationX,
            int destinationY,
            int width,
            int height,
            boolean flipHorizontal) {
        copyImage(
                normalizedTexture,
                normalizedTexture,
                sourceX * scale,
                sourceY * scale,
                destinationX * scale,
                destinationY * scale,
                width * scale,
                height * scale,
                flipHorizontal);
    }

    /// Returns the original caller-provided skin image.
    ///
    /// @return original skin image
    public BufferedImage getOriginalTexture() {
        return texture;
    }

    /// Returns the normalized square skin image.
    ///
    /// @return normalized skin image
    public BufferedImage getNormalizedTexture() {
        return normalizedTexture;
    }

    /// Returns the integer pixel scale relative to a 64-pixel-wide skin.
    ///
    /// @return image scale
    public int getScale() {
        return scale;
    }

    /// Reports whether the source used the legacy 2:1 layout.
    ///
    /// @return true for a legacy skin
    public boolean isOldFormat() {
        return oldFormat;
    }

    /// Detects whether the normalized skin uses the slim arm model.
    ///
    /// @return true for the slim model
    public boolean isSlim() {
        return (hasTransparencyRelative(50, 16, 2, 4) ||
                hasTransparencyRelative(54, 20, 2, 12) ||
                hasTransparencyRelative(42, 48, 2, 4) ||
                hasTransparencyRelative(46, 52, 2, 12)) ||
                (isAreaBlackRelative(50, 16, 2, 4) &&
                        isAreaBlackRelative(54, 20, 2, 12) &&
                        isAreaBlackRelative(42, 48, 2, 4) &&
                        isAreaBlackRelative(46, 52, 2, 12));
    }

    /// Checks one canonical skin region for any non-opaque pixel.
    ///
    /// @param x0 region x coordinate
    /// @param y0 region y coordinate
    /// @param width region width
    /// @param height region height
    /// @return true when at least one pixel is transparent
    private boolean hasTransparencyRelative(int x0, int y0, int width, int height) {
        x0 *= scale;
        y0 *= scale;
        width *= scale;
        height *= scale;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = normalizedTexture.getRGB(x0 + x, y0 + y);
                if (pixel >>> 24 != 0xff) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Checks whether every pixel in one canonical skin region is opaque black.
    ///
    /// @param x0 region x coordinate
    /// @param y0 region y coordinate
    /// @param width region width
    /// @param height region height
    /// @return true when every pixel is opaque black
    private boolean isAreaBlackRelative(int x0, int y0, int width, int height) {
        x0 *= scale;
        y0 *= scale;
        width *= scale;
        height *= scale;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = normalizedTexture.getRGB(x0 + x, y0 + y);
                if (pixel != 0xff000000) {
                    return false;
                }
            }
        }
        return true;
    }
}
