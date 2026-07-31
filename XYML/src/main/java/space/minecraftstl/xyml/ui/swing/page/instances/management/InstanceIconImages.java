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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.image.InstanceIconData;
import space.minecraftstl.xyml.setting.InstanceIconType;

import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Objects;

/// Converts safely decoded instance-icon pixels into exact-size Swing images.
///
/// Bundled images are decoded by the shared bounded loader on a background executor and retained as immutable
/// pixels. After that preload, the icon chooser can create its small Swing wrappers on the EDT without classpath,
/// filesystem, or ImageIO work. Custom images always pass through the same bounded loader and are not cached.
@NotNullByDefault
final class InstanceIconImages {
    /// Lock protecting successful bundled-pixel preload and lookup.
    private static final Object BUILT_IN_PIXELS_LOCK = new Object();

    /// Immutable decoded pixels retained for every successfully preloaded bundled type.
    private static final EnumMap<InstanceIconType, InstanceIconData> BUILT_IN_PIXELS =
            new EnumMap<>(InstanceIconType.class);

    /// Prevents utility-class construction.
    private InstanceIconImages() {
    }

    /// Preloads every bundled instance icon outside the Swing event-dispatch thread.
    ///
    /// Repeated calls reuse both this adapter's immutable pixels and the underlying bounded loader cache.
    ///
    /// @throws IllegalStateException when called on the EDT or a mandatory bundled resource is unavailable
    static void preloadBuiltIns() {
        requireBackgroundThread();
        for (InstanceIconType iconType : InstanceIconType.values()) {
            synchronized (BUILT_IN_PIXELS_LOCK) {
                if (BUILT_IN_PIXELS.containsKey(iconType)) {
                    continue;
                }
            }
            InstanceIconData loaded = space.minecraftstl.xyml.image.InstanceIconLoader.loadBuiltIn(iconType);
            synchronized (BUILT_IN_PIXELS_LOCK) {
                BUILT_IN_PIXELS.putIfAbsent(iconType, loaded);
            }
        }
    }

    /// Loads the current custom or bundled state through the shared bounded decoder.
    ///
    /// This method performs filesystem work for custom files and therefore must run outside the EDT.
    ///
    /// @param state current persisted icon state
    /// @param size required square output size in pixels
    /// @return exact-size Swing icon suitable for the overview preview
    static ImageIcon load(InstanceIconStore.Snapshot state, int size) {
        requireBackgroundThread();
        InstanceIconStore.Snapshot validatedState = Objects.requireNonNull(state, "state");
        InstanceIconData pixels = space.minecraftstl.xyml.image.InstanceIconLoader.load(
                validatedState.builtInType(),
                validatedState.customImage());
        return toImageIcon(pixels, size);
    }

    /// Returns one preloaded bundled icon without performing I/O or image decoding on the EDT.
    ///
    /// A background caller may fill a missing cache entry defensively. An EDT caller receives a failure instead
    /// of silently blocking, which makes the required overview preload observable during tests.
    ///
    /// @param iconType bundled icon type, including `DEFAULT`
    /// @param size required square output size in pixels
    /// @return exact-size in-memory Swing icon
    static ImageIcon loadBuiltIn(InstanceIconType iconType, int size) {
        InstanceIconType validatedType = Objects.requireNonNull(iconType, "iconType");
        @Nullable InstanceIconData pixels;
        synchronized (BUILT_IN_PIXELS_LOCK) {
            pixels = BUILT_IN_PIXELS.get(validatedType);
        }
        if (pixels == null) {
            requireBackgroundThread();
            InstanceIconData loaded = space.minecraftstl.xyml.image.InstanceIconLoader.loadBuiltIn(validatedType);
            synchronized (BUILT_IN_PIXELS_LOCK) {
                BUILT_IN_PIXELS.putIfAbsent(validatedType, loaded);
                pixels = BUILT_IN_PIXELS.get(validatedType);
            }
        }
        return toImageIcon(Objects.requireNonNull(pixels, "preloaded pixels"), size);
    }

    /// Converts fixed 40-pixel source data into one centered exact-size ARGB image.
    ///
    /// @param pixels immutable normalized source pixels
    /// @param size required positive square size
    /// @return exact-size in-memory Swing icon
    private static ImageIcon toImageIcon(InstanceIconData pixels, int size) {
        InstanceIconData validatedPixels = Objects.requireNonNull(pixels, "pixels");
        int validatedSize = requirePositive(size, "size");
        BufferedImage source = new BufferedImage(
                InstanceIconData.WIDTH,
                InstanceIconData.HEIGHT,
                BufferedImage.TYPE_INT_ARGB);
        source.setRGB(
                0,
                0,
                InstanceIconData.WIDTH,
                InstanceIconData.HEIGHT,
                validatedPixels.copyArgbPixels(),
                0,
                InstanceIconData.WIDTH);
        if (validatedSize == InstanceIconData.WIDTH) {
            return new ImageIcon(source);
        }

        BufferedImage target = new BufferedImage(
                validatedSize,
                validatedSize,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, validatedSize, validatedSize, null);
        } finally {
            graphics.dispose();
            source.flush();
        }
        return new ImageIcon(target);
    }

    /// Validates one required positive dimension.
    ///
    /// @param value candidate dimension
    /// @param name parameter name
    /// @return validated dimension
    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /// Rejects icon resource and filesystem access on the Swing event-dispatch thread.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Instance icon loading must run outside the event-dispatch thread");
        }
    }
}
