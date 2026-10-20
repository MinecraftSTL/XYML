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
package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.image.EncodedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Objects;

/// Decodes bounded in-memory catalog images into fixed-size Swing icons.
@NotNullByDefault
public final class CatalogIconSupport {
    /// Shared icon edge used by rich catalog rows and details headers.
    public static final int ICON_SIZE = 40;

    /// Maximum source image edge accepted before decoding pixels.
    private static final int MAX_SOURCE_EDGE = 8_192;

    /// Maximum source image pixel count accepted before decoding pixels.
    private static final long MAX_SOURCE_PIXELS = 16L * 1024L * 1024L;

    private CatalogIconSupport() {
    }

    /// Decodes one encoded image without touching the filesystem or network.
    ///
    /// @param encodedImage bounded encoded image, or null when metadata is absent
    /// @param fallback icon returned when data is absent, malformed, or unsafe
    /// @return fixed-size decoded icon or the supplied fallback
    public static Icon decode(@Nullable EncodedImage encodedImage, Icon fallback) {
        Icon checkedFallback = Objects.requireNonNull(fallback, "fallback");
        if (encodedImage == null) {
            return checkedFallback;
        }
        try (InputStream input = encodedImage.openStream();
                ImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                return checkedFallback;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                validateDimensions(reader.getWidth(0), reader.getHeight(0));
                BufferedImage source = reader.read(0);
                if (source == null) {
                    return checkedFallback;
                }
                validateDimensions(source.getWidth(), source.getHeight());
                BufferedImage target = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = target.createGraphics();
                try {
                    graphics.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(source, 0, 0, ICON_SIZE, ICON_SIZE, null);
                } finally {
                    graphics.dispose();
                }
                return new ImageIcon(target);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ignored) {
            return checkedFallback;
        }
    }

    /// Creates a bounded colored placeholder for missing catalog artwork.
    ///
    /// @param color placeholder color
    /// @return fixed-size placeholder icon
    public static Icon placeholder(Color color) {
        Color checkedColor = Objects.requireNonNull(color, "color");
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(checkedColor);
            graphics.fillRoundRect(0, 0, ICON_SIZE, ICON_SIZE, 8, 8);
        } finally {
            graphics.dispose();
        }
        return new ImageIcon(image);
    }

    /// Rejects dimensions that could cause excessive allocation.
    private static void validateDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE
                || (long) width * height > MAX_SOURCE_PIXELS) {
            throw new IOException("Catalog icon dimensions exceed safety limits");
        }
    }
}
