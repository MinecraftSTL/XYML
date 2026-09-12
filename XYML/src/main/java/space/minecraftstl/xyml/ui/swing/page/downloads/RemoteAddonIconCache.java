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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/// Lazily loads remote project icons without blocking Swing painting or changing row geometry.
///
/// Each URL owns one mutable fixed-size icon. The renderer can therefore cache the icon object
/// while a worker replaces its image and asks the owning list to repaint on the EDT.
@NotNullByDefault
final class RemoteAddonIconCache implements AutoCloseable {
    /// Fixed logical edge used by every catalog row icon.
    private static final int ICON_SIZE = 40;

    /// Maximum encoded response retained before image decoding.
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    /// Maximum decoded edge accepted before allocating the raster.
    private static final int MAX_IMAGE_EDGE = 2048;

    /// Maximum decoded pixels accepted before allocating the raster.
    private static final long MAX_IMAGE_PIXELS = 4_000_000L;

    /// Bundled icon shown before a remote image is available or after a failed request.
    private static final Icon PLACEHOLDER = new FlatSVGIcon(
            "assets/swing/icons/format-list-bulleted.svg",
            ICON_SIZE,
            ICON_SIZE);

    /// Caller-owned worker used for bounded network reads.
    private final Executor workerExecutor;

    /// URL-to-icon map shared by all rows in one panel.
    private final Map<String, AsyncIcon> icons = new ConcurrentHashMap<>();

    /// Prevents callbacks after the owning panel closes.
    private volatile boolean closed;

    /// Creates an icon cache using the panel's existing worker boundary.
    ///
    /// @param workerExecutor executor used for network image reads
    RemoteAddonIconCache(Executor workerExecutor) {
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
    }

    /// Returns a stable icon object and schedules its image request when needed.
    ///
    /// @param rawUrl provider icon URL, or blank when the provider has no icon
    /// @param repaint callback that repaints the owning list after a successful image load
    /// @return fixed-size icon suitable for a rich catalog row
    Icon icon(String rawUrl, Runnable repaint) {
        String url = Objects.requireNonNull(rawUrl, "rawUrl").trim();
        if (url.isEmpty() || !isHttpUrl(url) || closed) {
            return PLACEHOLDER;
        }
        AsyncIcon icon = icons.computeIfAbsent(url, ignored -> new AsyncIcon());
        icon.load(url, Objects.requireNonNull(repaint, "repaint"));
        return icon;
    }

    /// Releases the cache and suppresses already queued repaint callbacks.
    @Override
    public void close() {
        closed = true;
        icons.clear();
    }

    /// Accepts only HTTP(S) image URLs supplied by a remote repository.
    private static boolean isHttpUrl(String url) {
        try {
            String scheme = URI.create(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException malformedUrl) {
            return false;
        }
    }

    /// Fixed-size mutable icon whose delegate changes only after a worker completes.
    @NotNullByDefault
    private final class AsyncIcon implements Icon {
        /// Serializes one URL's in-flight request.
        private boolean loading;

        /// Scaled image, or null while the placeholder is active.
        private volatile @Nullable Image image;

        /// Starts one background read for this icon and list pair.
        private synchronized void load(String url, Runnable repaint) {
            if (loading || image != null || closed) {
                return;
            }
            loading = true;
            try {
                workerExecutor.execute(() -> loadImage(url, repaint));
            } catch (RuntimeException ignore) {
                loading = false;
            }
        }

        /// Reads, validates, and scales one image away from the EDT.
        private void loadImage(String url, Runnable repaint) {
            @Nullable Image loaded = null;
            try {
                URLConnection connection = URI.create(url).toURL().openConnection();
                connection.setConnectTimeout(5_000);
                connection.setReadTimeout(5_000);
                connection.setUseCaches(true);
                long contentLength = connection.getContentLengthLong();
                if (contentLength > MAX_IMAGE_BYTES) {
                    throw new IOException("Remote add-on icon exceeds the encoded size limit");
                }
                byte[] encoded;
                try (InputStream stream = connection.getInputStream()) {
                    encoded = stream.readNBytes(MAX_IMAGE_BYTES + 1);
                }
                if (encoded.length > MAX_IMAGE_BYTES) {
                    throw new IOException("Remote add-on icon exceeds the encoded size limit");
                }
                @Nullable BufferedImage source = decode(encoded);
                if (source != null && source.getWidth() > 0 && source.getHeight() > 0) {
                    loaded = scale(source);
                }
            } catch (IOException | RuntimeException ignored) {
                // A missing provider icon must never affect catalog search or installation.
            }
            @Nullable Image result = loaded;
            synchronized (this) {
                loading = false;
                if (result != null && !closed) {
                    image = result;
                }
            }
            if (result != null && !closed) {
                SwingUiDispatcher.INSTANCE.dispatchOrRun(repaint);
            }
        }

        /// Decodes one bounded image response after checking dimensions through the reader metadata.
        ///
        /// @param encoded bounded encoded response
        /// @return decoded image, or null when no supported reader accepts it
        private static @Nullable BufferedImage decode(byte[] encoded) throws IOException {
            try (@Nullable ImageInputStream imageInput = ImageIO.createImageInputStream(
                    new ByteArrayInputStream(encoded))) {
                if (imageInput == null) {
                    return null;
                }
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) {
                    return null;
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(imageInput, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (!validDimensions(width, height)) {
                        throw new IOException("Remote add-on icon dimensions exceed the limit");
                    }
                    return reader.read(0);
                } finally {
                    reader.dispose();
                }
            }
        }

        /// Checks dimensions before the image reader allocates a decoded raster.
        ///
        /// @param width encoded image width
        /// @param height encoded image height
        /// @return whether the dimensions fit the catalog icon budget
        private static boolean validDimensions(int width, int height) {
            return width > 0
                    && height > 0
                    && width <= MAX_IMAGE_EDGE
                    && height <= MAX_IMAGE_EDGE
                    && (long) width * height <= MAX_IMAGE_PIXELS;
        }

        /// Scales an arbitrary source image into the stable row slot while preserving aspect ratio.
        private static BufferedImage scale(BufferedImage source) {
            double ratio = Math.min(
                    (double) ICON_SIZE / source.getWidth(),
                    (double) ICON_SIZE / source.getHeight());
            int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
            int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(
                        RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            return scaled;
        }

        /// Paints the loaded image centered in the fixed icon slot.
        @Override
        public void paintIcon(@Nullable Component component, Graphics graphics, int x, int y) {
            @Nullable Image current = image;
            if (current == null) {
                PLACEHOLDER.paintIcon(component, graphics, x, y);
                return;
            }
            int width = current.getWidth(null);
            int height = current.getHeight(null);
            if (width <= 0 || height <= 0) {
                PLACEHOLDER.paintIcon(component, graphics, x, y);
                return;
            }
            int drawX = x + (ICON_SIZE - width) / 2;
            int drawY = y + (ICON_SIZE - height) / 2;
            graphics.drawImage(current, drawX, drawY, null);
        }

        /// Returns the stable row icon width.
        @Override
        public int getIconWidth() {
            return ICON_SIZE;
        }

        /// Returns the stable row icon height.
        @Override
        public int getIconHeight() {
            return ICON_SIZE;
        }
    }
}
