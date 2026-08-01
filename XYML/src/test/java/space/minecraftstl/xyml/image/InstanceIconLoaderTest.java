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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.setting.GameInstanceIconType;

import javax.imageio.ImageIO;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable icon pixels, bundled resource coverage, safe custom decoding, and EDT rejection.
@NotNullByDefault
public final class InstanceIconLoaderTest {
    /// Opaque red ARGB pixel used by deterministic custom-image fixtures.
    private static final int OPAQUE_RED = 0xFFFF0000;

    /// Per-test directory for untrusted image fixtures.
    @TempDir
    private @Nullable Path temporaryDirectory;

    /// Defensive construction and copy access keep the fixed pixel value immutable.
    @Test
    public void instanceIconDataDefensivelyOwnsPixels() {
        int[] source = new int[InstanceIconData.PIXEL_COUNT];
        source[0] = OPAQUE_RED;
        InstanceIconData data = new InstanceIconData(source);
        source[0] = 0;
        int[] copy = data.copyArgbPixels();
        copy[0] = 0;

        assertEquals(InstanceIconData.WIDTH, data.width());
        assertEquals(InstanceIconData.HEIGHT, data.height());
        assertEquals(OPAQUE_RED, data.argbAt(0, 0));
        assertNotEquals(0, data.hashCode());
        assertEquals(data, new InstanceIconData(data.copyArgbPixels()));
        assertThrows(IllegalArgumentException.class, () -> new InstanceIconData(new int[1]));
        assertThrows(IndexOutOfBoundsException.class, () -> data.argbAt(-1, 0));
    }

    /// Every configured icon resolves to its packaged `@2x` raster and normalizes to visible 40-by-40 data.
    @Test
    public void coversEveryBundledHighResolutionResource() {
        for (GameInstanceIconType iconType : GameInstanceIconType.values()) {
            String resourcePath = InstanceIconLoader.bundledResourcePath(iconType);
            assertTrue(resourcePath.endsWith("@2x.png"), resourcePath);
            assertNotNull(InstanceIconLoader.class.getResource(resourcePath), resourcePath);

            InstanceIconData data = InstanceIconLoader.loadBuiltIn(iconType);
            assertEquals(InstanceIconData.WIDTH, data.width());
            assertEquals(InstanceIconData.HEIGHT, data.height());
            assertTrue(hasVisiblePixel(data), iconType.name());
        }
    }

    /// Concurrent bundled requests reuse the same safely published immutable cache value.
    @Test
    public void cachesBundledIconsAcrossConcurrentRequests() {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            @Unmodifiable List<CompletableFuture<InstanceIconData>> loads = IntStream.range(0, 32)
                    .mapToObj(ignored -> CompletableFuture.supplyAsync(
                            () -> InstanceIconLoader.loadBuiltIn(GameInstanceIconType.CHEST),
                            executor))
                    .toList();
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
            InstanceIconData first = loads.get(0).join();
            for (CompletableFuture<InstanceIconData> load : loads) {
                assertSame(first, load.join());
            }
            assertSame(first, InstanceIconLoader.loadBuiltIn(GameInstanceIconType.CHEST));
        } finally {
            executor.shutdownNow();
        }
    }

    /// A valid rectangular custom image takes precedence and is proportionally centered on transparency.
    @Test
    public void centersValidCustomImageOnStableCanvas() throws IOException {
        Path directory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
        Path customIcon = directory.resolve("custom.png");
        writeSolidPng(customIcon, 20, 10, OPAQUE_RED);

        InstanceIconData data = InstanceIconLoader.load(GameInstanceIconType.FORGE, customIcon);
        InstanceIconData reloaded = InstanceIconLoader.load(GameInstanceIconType.FORGE, customIcon);

        assertEquals(0, data.argbAt(20, 5));
        assertEquals(OPAQUE_RED, data.argbAt(20, 20));
        assertEquals(0, data.argbAt(20, 35));
        assertNotEquals(InstanceIconLoader.loadBuiltIn(GameInstanceIconType.DEFAULT), data);
        assertEquals(data, reloaded);
        assertNotSame(data, reloaded);
    }

    /// Corrupt, non-regular, and oversized encoded files all return the bundled default icon.
    @Test
    public void fallsBackForMalformedNonRegularAndOversizedFiles() throws IOException {
        Path directory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
        InstanceIconData expectedDefault = InstanceIconLoader.loadBuiltIn(GameInstanceIconType.DEFAULT);
        Path corrupt = directory.resolve("corrupt.png");
        Files.write(corrupt, new byte[]{1, 2, 3, 4});
        Path oversized = directory.resolve("oversized.png");
        Files.write(oversized, new byte[InstanceIconLoader.MAXIMUM_ENCODED_BYTES + 1]);
        Path nonRegular = Files.createDirectory(directory.resolve("directory.png"));

        assertEquals(expectedDefault, InstanceIconLoader.load(GameInstanceIconType.FORGE, corrupt));
        assertEquals(expectedDefault, InstanceIconLoader.load(GameInstanceIconType.FORGE, oversized));
        assertEquals(expectedDefault, InstanceIconLoader.load(GameInstanceIconType.FORGE, nonRegular));
    }

    /// No-follow attributes reject links and invalid file kinds while accepting the exact encoded byte ceiling.
    @Test
    public void validatesSymbolicLinkAndEncodedFileBoundaries() {
        assertThrows(
                IOException.class,
                () -> InstanceIconLoader.validateCustomFileAttributes(true, true, 1L));
        assertThrows(
                IOException.class,
                () -> InstanceIconLoader.validateCustomFileAttributes(false, false, 1L));
        assertThrows(
                IOException.class,
                () -> InstanceIconLoader.validateCustomFileAttributes(false, true, 0L));
        assertThrows(
                IOException.class,
                () -> InstanceIconLoader.validateCustomFileAttributes(
                        false,
                        true,
                        InstanceIconLoader.MAXIMUM_ENCODED_BYTES + 1L));
        assertDoesNotThrow(() -> InstanceIconLoader.validateCustomFileAttributes(
                false,
                true,
                InstanceIconLoader.MAXIMUM_ENCODED_BYTES));
    }

    /// Oversized edge and pixel counts are rejected from BMP headers before absent pixel bodies are decoded.
    @Test
    public void rejectsDimensionAndPixelLimitsBeforeDecode() throws IOException {
        Path directory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
        InstanceIconData expectedDefault = InstanceIconLoader.loadBuiltIn(GameInstanceIconType.DEFAULT);
        Path excessiveEdge = directory.resolve("edge.bmp");
        writeBmpHeader(excessiveEdge, InstanceIconLoader.MAXIMUM_SOURCE_EDGE + 1, 1);
        Path excessivePixels = directory.resolve("pixels.bmp");
        writeBmpHeader(excessivePixels, 4_097, 4_097);

        assertEquals(expectedDefault, InstanceIconLoader.load(GameInstanceIconType.FORGE, excessiveEdge));
        assertEquals(expectedDefault, InstanceIconLoader.load(GameInstanceIconType.FORGE, excessivePixels));
    }

    /// Blocking icon I/O and decoding fail immediately when accidentally invoked on the EDT.
    @Test
    public void rejectsLoadingOnEventDispatchThread() throws Exception {
        InstanceIconLoader.loadBuiltIn(GameInstanceIconType.DEFAULT);
        EventQueue.invokeAndWait(() -> {
            assertTrue(EventQueue.isDispatchThread());
            assertThrows(
                    IllegalStateException.class,
                    () -> InstanceIconLoader.loadBuiltIn(GameInstanceIconType.DEFAULT));
            assertThrows(
                    IllegalStateException.class,
                    () -> InstanceIconLoader.load(GameInstanceIconType.DEFAULT, null));
        });
    }

    /// Returns whether one normalized icon contains any non-transparent pixel.
    ///
    /// @param data normalized icon data
    /// @return whether at least one alpha channel is nonzero
    private static boolean hasVisiblePixel(InstanceIconData data) {
        return Arrays.stream(data.copyArgbPixels())
                .anyMatch(pixel -> (pixel >>> 24) != 0);
    }

    /// Writes one deterministic solid PNG fixture.
    ///
    /// @param path destination file
    /// @param width source width
    /// @param height source height
    /// @param argb packed fill pixel
    /// @throws IOException when the PNG cannot be written
    private static void writeSolidPng(Path path, int width, int height, int argb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setRGB(x, y, argb);
                }
            }
            assertTrue(ImageIO.write(image, "PNG", path.toFile()));
        } finally {
            image.flush();
        }
    }

    /// Writes a minimal little-endian BMP header whose dimensions can be probed without a pixel body.
    ///
    /// @param path destination file
    /// @param width declared width
    /// @param height declared height
    /// @throws IOException when the header cannot be written
    private static void writeBmpHeader(Path path, int width, int height) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'B');
        header.put((byte) 'M');
        header.putInt(54);
        header.putInt(0);
        header.putInt(54);
        header.putInt(40);
        header.putInt(width);
        header.putInt(height);
        header.putShort((short) 1);
        header.putShort((short) 24);
        header.putInt(0);
        header.putInt(0);
        header.putInt(2_835);
        header.putInt(2_835);
        header.putInt(0);
        header.putInt(0);
        assertFalse(header.hasRemaining());
        Files.write(path, header.array());
    }
}
