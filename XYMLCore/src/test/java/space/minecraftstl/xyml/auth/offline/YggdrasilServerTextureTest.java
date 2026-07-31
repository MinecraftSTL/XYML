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
package space.minecraftstl.xyml.auth.offline;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the embedded Yggdrasil service returns ImageIO-encoded PNG texture responses.
@NotNullByDefault
final class YggdrasilServerTextureTest {
    /// A cached buffered image survives a real loopback HTTP PNG round trip with cache headers.
    @Test
    void servesBufferedTextureAsPng() throws Exception {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0xFF123456);
        source.setRGB(1, 0, 0x80789ABC);
        Texture texture = Objects.requireNonNull(Texture.loadTexture(source));
        YggdrasilServer server = new YggdrasilServer(0);
        server.start();

        HttpURLConnection connection = (HttpURLConnection) URI.create(
                server.getRootUrl() + "/textures/" + texture.hash()).toURL().openConnection();
        try {
            assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());
            assertEquals("image/png", connection.getContentType());
            assertEquals('"' + texture.hash() + '"', connection.getHeaderField("Etag"));
            assertEquals("max-age=2592000, public", connection.getHeaderField("Cache-Control"));

            byte[] data = connection.getInputStream().readAllBytes();
            assertTrue(data.length > 8);
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(data));
            assertNotNull(decoded);
            assertEquals(2, decoded.getWidth());
            assertEquals(1, decoded.getHeight());
            assertEquals(0xFF123456, decoded.getRGB(0, 0));
            assertEquals(0x80789ABC, decoded.getRGB(1, 0));
        } finally {
            connection.disconnect();
            server.stop();
        }
    }
}
