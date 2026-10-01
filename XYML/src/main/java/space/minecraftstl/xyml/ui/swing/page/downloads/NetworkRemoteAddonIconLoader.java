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

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.util.io.NetworkUtils;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Loads and decodes remote add-on icons on the I/O scheduler with bounded response and image sizes.
@NotNullByDefault
final class NetworkRemoteAddonIconLoader implements RemoteAddonIconLoader {
    /// Fixed result-row edge in pixels.
    private static final int ICON_SIZE = RemoteAddonIconCache.ICON_SIZE;

    /// Maximum encoded response accepted before image decoding.
    private static final int MAX_ENCODED_BYTES = 4 * 1024 * 1024;

    /// Maximum decoded source edge accepted before scaling.
    private static final int MAX_SOURCE_EDGE = 4096;

    /// Failure marker returned for invalid, unavailable, or undecodable icons.
    private static final Icon FAILURE = createPlaceholder(new Color(190, 90, 90, 180));

    /// Starts one bounded network and ImageIO operation away from the Swing EDT.
    @Override
    public CompletionStage<Icon> load(String rawUrl) {
        return CompletableFuture.supplyAsync(() -> loadSynchronously(rawUrl), Schedulers.io());
    }

    /// Downloads, validates, decodes, and scales one public icon response.
    private static Icon loadSynchronously(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            if (!NetworkUtils.isHttpUri(uri)) {
                return FAILURE;
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(rawUrl).openConnection();
            connection.setConnectTimeout(NetworkUtils.TIMEOUT_MILLIS);
            connection.setReadTimeout(NetworkUtils.TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", NetworkUtils.USER_AGENT);
            try {
                if (connection.getResponseCode() / 100 != 2) {
                    return FAILURE;
                }
                int contentLength = connection.getContentLength();
                if (contentLength > MAX_ENCODED_BYTES) {
                    return FAILURE;
                }
                byte[] bytes = readBounded(connection.getInputStream());
                BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
                if (source == null
                        || source.getWidth() > MAX_SOURCE_EDGE
                        || source.getHeight() > MAX_SOURCE_EDGE) {
                    return FAILURE;
                }
                return new ImageIcon(scale(source));
            } finally {
                connection.disconnect();
            }
        } catch (IOException | RuntimeException failure) {
            return FAILURE;
        }
    }

    /// Reads a response while enforcing the encoded-byte budget.
    private static byte[] readBounded(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > MAX_ENCODED_BYTES) {
                    throw new IOException("Remote icon exceeds encoded byte limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    /// Scales one decoded source into an exact square row icon.
    private static BufferedImage scale(BufferedImage source) {
        BufferedImage target = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, ICON_SIZE, ICON_SIZE, null);
        } finally {
            graphics.dispose();
            source.flush();
        }
        return target;
    }

    /// Creates a stable colored square fallback without loading a resource or touching the network.
    private static Icon createPlaceholder(Color color) {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, ICON_SIZE, ICON_SIZE);
        } finally {
            graphics.dispose();
        }
        return new ImageIcon(image);
    }
}
