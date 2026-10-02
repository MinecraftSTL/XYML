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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.image.EncodedImage;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bounded resource-pack icon decoding and asynchronous repaint publication.
@NotNullByDefault
final class ResourcePackIconCacheTest {
    /// Returns a stable item key without requiring a real resource-pack directory.
    private static ResourcePackCatalogItem item() {
        return new ResourcePackCatalogItem(
                Path.of("test-resource-pack"),
                "Test pack",
                "test-resource-pack",
                "",
                ResourcePackCompatibility.COMPATIBLE,
                true);
    }

    /// Rejects a missing pack.png with the existing placeholder icon.
    @Test
    void missingIconUsesPlaceholder() {
        ResourcePackIconCache cache = new ResourcePackIconCache();

        Icon icon = cache.iconFor(model(CompletableFuture.completedFuture(null)), item(), new JList<>());

        assertSame(ResourcePackIconCache.PLACEHOLDER, icon);
    }

    /// Rejects malformed encoded data with the existing failure icon.
    @Test
    void malformedIconUsesFailure() {
        ResourcePackIconCache cache = new ResourcePackIconCache();
        EncodedImage malformed = new EncodedImage(new byte[]{1, 2, 3, 4});

        Icon icon = cache.iconFor(
                model(CompletableFuture.completedFuture(malformed)),
                item(),
                new JList<>());

        assertSame(ResourcePackIconCache.FAILURE, icon);
    }

    /// Decodes a normal image and preserves the existing 32-by-32 row icon contract.
    @Test
    void normalIconDecodesToRowSize() throws IOException {
        ResourcePackIconCache cache = new ResourcePackIconCache();
        BufferedImage source = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, Color.RED.getRGB());

        Icon icon = cache.iconFor(
                model(CompletableFuture.completedFuture(new EncodedImage(encodePng(source)))),
                item(),
                new JList<>());

        assertTrue(icon instanceof ImageIcon);
        assertEquals(ResourcePackIconCache.ICON_SIZE, icon.getIconWidth());
        assertEquals(ResourcePackIconCache.ICON_SIZE, icon.getIconHeight());
    }

    /// Rejects an image whose declared edge exceeds the pre-decode limit.
    @Test
    void excessiveEdgeUsesFailureBeforePixelDecode() throws IOException {
        ResourcePackIconCache cache = new ResourcePackIconCache();

        Icon icon = cache.iconFor(
                model(CompletableFuture.completedFuture(
                        new EncodedImage(metadataPng(8_193, 1)))),
                item(),
                new JList<>());

        assertSame(ResourcePackIconCache.FAILURE, icon);
    }

    /// Rejects an image whose declared pixel count exceeds the pre-decode limit.
    @Test
    void excessivePixelCountUsesFailureBeforePixelDecode() throws IOException {
        ResourcePackIconCache cache = new ResourcePackIconCache();

        Icon icon = cache.iconFor(
                model(CompletableFuture.completedFuture(
                        new EncodedImage(metadataPng(4_096, 4_097)))),
                item(),
                new JList<>());

        assertSame(ResourcePackIconCache.FAILURE, icon);
    }

    /// Repaints the list after asynchronous icon data completes and exposes the decoded icon afterward.
    @Test
    void asynchronousCompletionRepaintsList() throws Exception {
        ResourcePackIconCache cache = new ResourcePackIconCache();
        CompletableFuture<EncodedImage> pending = new CompletableFuture<>();
        RecordingList list = new RecordingList();

        Icon loadingIcon = cache.iconFor(model(pending), item(), list);
        int repaintCountBeforeCompletion = list.repaintCount();

        pending.complete(new EncodedImage(encodePng(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB))));
        SwingUtilities.invokeAndWait(() -> { });
        Icon completedIcon = cache.iconFor(model(pending), item(), list);

        assertSame(ResourcePackIconCache.PLACEHOLDER, loadingIcon);
        assertNotSame(ResourcePackIconCache.PLACEHOLDER, completedIcon);
        assertTrue(list.repaintCount() > repaintCountBeforeCompletion);
    }

    /// Creates a model proxy exposing only the icon-loading boundary needed by this cache test.
    private static ResourcePackCatalogModel model(CompletionStage<EncodedImage> icon) {
        return (ResourcePackCatalogModel) Proxy.newProxyInstance(
                ResourcePackIconCacheTest.class.getClassLoader(),
                new Class<?>[]{ResourcePackCatalogModel.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("loadIcon")) {
                        return icon;
                    }
                    if (method.getReturnType() == OptionalInt.class) {
                        return OptionalInt.empty();
                    }
                    if (method.getReturnType() == OptionalLong.class) {
                        return OptionalLong.empty();
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }

    /// Encodes an in-memory source image using the normal PNG writer.
    private static byte[] encodePng(BufferedImage source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(source, "png", output);
        return output.toByteArray();
    }

    /// Writes a small PNG containing only valid dimension metadata for pre-decode tests.
    private static byte[] metadataPng(int width, int height) throws IOException {
        byte[] headerType = "IHDR".getBytes(StandardCharsets.US_ASCII);
        byte[] headerData = ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put((byte) 8)
                .put((byte) 6)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .array();
        CRC32 headerCrc = new CRC32();
        headerCrc.update(headerType);
        headerCrc.update(headerData);
        byte[] endType = "IEND".getBytes(StandardCharsets.US_ASCII);
        CRC32 endCrc = new CRC32();
        endCrc.update(endType);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            output.writeInt(headerData.length);
            output.write(headerType);
            output.write(headerData);
            output.writeInt((int) headerCrc.getValue());
            output.writeInt(0);
            output.write(endType);
            output.writeInt((int) endCrc.getValue());
        }
        return bytes.toByteArray();
    }

    /// Counts repaint requests while retaining normal JList behavior.
    private static final class RecordingList extends JList<Object> {
        private final AtomicInteger repaintCount = new AtomicInteger();

        /// Returns the number of repaint requests observed after construction.
        private int repaintCount() {
            return repaintCount.get();
        }

        /// Counts repaint requests from the asynchronous completion callback.
        @Override
        public void repaint() {
            if (repaintCount != null) {
                repaintCount.incrementAndGet();
            }
            super.repaint();
        }
    }
}
