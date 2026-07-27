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
package space.minecraftstl.xyml.theme;

import kala.compress.archivers.zip.UnixStat;
import kala.compress.archivers.zip.ZipArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveOutputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.util.gson.JsonUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests asynchronous publication, bounded archive handling, and no-follow local resources.
@NotNullByDefault
public final class LocalThemePackRepositoryTest {
    /// Temporary test filesystem root.
    @TempDir
    private Path temporaryDirectory;

    /// Import schedules all I/O, validates an image, atomically publishes, and reopens a contained asset.
    @Test
    public void importsOnlyAfterExecutorRunsAndListsValidatedPack() throws Exception {
        byte[] image = png(8, 6);
        Path archive = temporaryDirectory.resolve("valid.hmcl-theme");
        writeZip(archive, Map.of(
                "manifest.json", manifest("example.valid", "assets/background.png"),
                "assets/background.png", image));
        Path repositoryDirectory = temporaryDirectory.resolve("repository");
        LocalThemePackRepository repository = new LocalThemePackRepository(repositoryDirectory);
        QueuedExecutor executor = new QueuedExecutor();

        var importStage = repository.importArchive(archive, executor);
        assertFalse(Files.exists(repositoryDirectory));
        executor.runNext();
        InstalledThemePack installed = importStage.toCompletableFuture().join();

        assertEquals("example.valid", installed.manifest().id());
        assertEquals(repositoryDirectory.resolve("example.valid").toAbsolutePath(), installed.directory());
        try (InputStream input = installed.asset("assets/background.png").openStream()) {
            assertArrayEquals(image, input.readAllBytes());
        }

        var listStage = repository.listInstalled(executor);
        executor.runNext();
        assertEquals(List.of(installed), listStage.toCompletableFuture().join());
    }

    /// Export validates and schedules all blocking work, writes a deterministic archive, and never overwrites.
    @Test
    public void exportsThenImportsWithoutImplicitOverwrite() throws Exception {
        byte[] image = png(4, 4);
        ThemePackManifest manifest = parseManifest(manifest("example.export", "assets/wallpaper.png"));
        ThemePackAsset asset = new ThemePackAsset(
                new ThemePackResource.Bytes(image, "wallpaper.png"),
                "assets/wallpaper.png");
        Path output = temporaryDirectory.resolve("export.hmcl-theme");
        QueuedExecutor executor = new QueuedExecutor();

        var exportStage = ThemePackExporter.export(manifest, List.of(asset), output, executor);
        assertFalse(Files.exists(output));
        executor.runNext();
        assertEquals(output.toAbsolutePath(), exportStage.toCompletableFuture().join());
        assertTrue(Files.isRegularFile(output));

        var overwriteStage = ThemePackExporter.export(manifest, List.of(asset), output, executor);
        executor.runNext();
        CompletionException overwrite = assertThrows(
                CompletionException.class,
                () -> overwriteStage.toCompletableFuture().join());
        assertInstanceOf(java.nio.file.FileAlreadyExistsException.class, overwrite.getCause());

        LocalThemePackRepository repository = new LocalThemePackRepository(temporaryDirectory.resolve("installed"));
        var importStage = repository.importArchive(output, executor);
        executor.runNext();
        assertEquals("example.export", importStage.toCompletableFuture().join().manifest().id());
    }

    /// Traversal paths and archives exceeding a configured entry count fail before any installation is published.
    @Test
    public void rejectsTraversalAndEntryCountBeforePublication() throws Exception {
        Path traversal = temporaryDirectory.resolve("traversal.hmcl-theme");
        LinkedHashMap<String, byte[]> traversalEntries = new LinkedHashMap<>();
        traversalEntries.put("manifest.json", manifest("example.traversal", "assets/pixel.png"));
        traversalEntries.put("../escape", new byte[]{1});
        traversalEntries.put("assets/pixel.png", png(1, 1));
        writeZip(traversal, traversalEntries);

        Path repositoryDirectory = temporaryDirectory.resolve("repository");
        QueuedExecutor executor = new QueuedExecutor();
        LocalThemePackRepository repository = new LocalThemePackRepository(repositoryDirectory);
        var traversalStage = repository.importArchive(traversal, executor);
        executor.runNext();
        assertThrows(CompletionException.class, () -> traversalStage.toCompletableFuture().join());
        assertFalse(Files.exists(repositoryDirectory.resolve("example.traversal")));

        Path tooMany = temporaryDirectory.resolve("too-many.hmcl-theme");
        writeZip(tooMany, Map.of(
                "manifest.json", manifest("example.entries", "assets/pixel.png"),
                "assets/pixel.png", png(1, 1),
                "assets/extra.bin", new byte[]{1}));
        ThemePackArchiveLimits limits = new ThemePackArchiveLimits(2, 1_024 * 1_024, 2_000_000, 65_536, 64, 4_096);
        LocalThemePackRepository bounded = new LocalThemePackRepository(repositoryDirectory, limits);
        var countStage = bounded.importArchive(tooMany, executor);
        executor.runNext();
        assertThrows(CompletionException.class, () -> countStage.toCompletableFuture().join());
        assertFalse(Files.exists(repositoryDirectory.resolve("example.entries")));
    }

    /// Unix symbolic-link entries are rejected from central-directory metadata without relying on host link support.
    @Test
    public void rejectsArchiveSymbolicLinkEntry() throws Exception {
        Path archive = temporaryDirectory.resolve("symlink.hmcl-theme");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(
                Files.newOutputStream(archive),
                StandardCharsets.UTF_8)) {
            writeKalaEntry(zip, new ZipArchiveEntry("manifest.json"),
                    manifest("example.symlink", "assets/pixel.png"));
            writeKalaEntry(zip, new ZipArchiveEntry("assets/pixel.png"), png(1, 1));
            ZipArchiveEntry link = new ZipArchiveEntry("assets/link.png");
            link.setUnixMode(UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM);
            writeKalaEntry(zip, link, "pixel.png".getBytes(StandardCharsets.UTF_8));
        }
        Path repositoryDirectory = temporaryDirectory.resolve("repository");
        QueuedExecutor executor = new QueuedExecutor();
        var stage = new LocalThemePackRepository(repositoryDirectory).importArchive(archive, executor);

        executor.runNext();

        assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        assertFalse(Files.exists(repositoryDirectory.resolve("example.symlink")));
    }

    /// Manifest, single-entry, and aggregate expanded-byte ceilings each reject before publication.
    @Test
    public void rejectsManifestSingleEntryAndAggregateSizeLimits() throws Exception {
        byte[] pixel = png(1, 1);
        byte[] manifest = manifest("example.limits", "assets/pixel.png");
        Path repositoryDirectory = temporaryDirectory.resolve("repository");
        QueuedExecutor executor = new QueuedExecutor();

        Path manifestArchive = temporaryDirectory.resolve("manifest-limit.hmcl-theme");
        writeZip(manifestArchive, Map.of("manifest.json", manifest, "assets/pixel.png", pixel));
        ThemePackArchiveLimits manifestLimits = new ThemePackArchiveLimits(
                8, 1_024, 4_096, 64, 64, 4_096);
        assertImportFails(manifestArchive, repositoryDirectory, manifestLimits, executor);

        Path singleArchive = temporaryDirectory.resolve("single-limit.hmcl-theme");
        writeZip(singleArchive, Map.of(
                "manifest.json", manifest,
                "assets/pixel.png", pixel,
                "assets/oversized.bin", new byte[700]));
        ThemePackArchiveLimits singleLimits = new ThemePackArchiveLimits(
                8, 512, 4_096, 512, 64, 4_096);
        assertImportFails(singleArchive, repositoryDirectory, singleLimits, executor);

        Path totalArchive = temporaryDirectory.resolve("total-limit.hmcl-theme");
        writeZip(totalArchive, Map.of(
                "manifest.json", manifest,
                "assets/pixel.png", pixel,
                "assets/one.bin", new byte[450],
                "assets/two.bin", new byte[450]));
        ThemePackArchiveLimits totalLimits = new ThemePackArchiveLimits(
                8, 1_024, 1_024, 512, 64, 4_096);
        assertImportFails(totalArchive, repositoryDirectory, totalLimits, executor);

        assertFalse(Files.exists(repositoryDirectory.resolve("example.limits")));
    }

    /// A compact encoded image whose declared dimensions exceed the pixel policy is rejected before publication.
    @Test
    public void rejectsOversizedReferencedImageDimensions() throws Exception {
        byte[] oversizedHeader = withPngDimensions(png(1, 1), 5_000, 4_000);
        Path archive = temporaryDirectory.resolve("wide.hmcl-theme");
        writeZip(archive, Map.of(
                "manifest.json", manifest("example.wide", "assets/wide.png"),
                "assets/wide.png", oversizedHeader));
        Path repositoryDirectory = temporaryDirectory.resolve("repository");
        LocalThemePackRepository repository = new LocalThemePackRepository(repositoryDirectory);
        QueuedExecutor executor = new QueuedExecutor();

        var stage = repository.importArchive(archive, executor);
        executor.runNext();

        CompletionException failure = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        assertInstanceOf(IOException.class, failure.getCause());
        assertFalse(Files.exists(repositoryDirectory.resolve("example.wide")));
    }

    /// Missing referenced assets are rejected by export before a target file is created.
    @Test
    public void exportRejectsMissingReferencedAsset() {
        ThemePackManifest manifest = parseManifest(manifest("example.missing", "assets/missing.png"));
        Path output = temporaryDirectory.resolve("missing.hmcl-theme");
        QueuedExecutor executor = new QueuedExecutor();

        var stage = ThemePackExporter.export(manifest, List.of(), output, executor);
        executor.runNext();

        assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        assertFalse(Files.exists(output));
    }

    /// Even a misconfigured inline executor cannot make repository I/O run on the event-dispatch thread.
    @Test
    public void rejectsDirectExecutorOnEventDispatchThread() throws Exception {
        Path repositoryDirectory = temporaryDirectory.resolve("edt-repository");
        LocalThemePackRepository repository = new LocalThemePackRepository(repositoryDirectory);
        AtomicReference<@Nullable Throwable> captured = new AtomicReference<>();

        EventQueue.invokeAndWait(() -> {
            try {
                repository.listInstalled(Runnable::run).toCompletableFuture().join();
            } catch (CompletionException failure) {
                captured.set(failure.getCause());
            }
        });

        assertInstanceOf(IllegalStateException.class, Objects.requireNonNull(captured.get()));
        assertFalse(Files.exists(repositoryDirectory));
    }

    /// Export rejects names that collide on case-insensitive target filesystems.
    @Test
    public void exportRejectsCaseCollidingAssetNames() throws Exception {
        byte[] image = png(1, 1);
        ThemePackManifest manifest = parseManifest(manifest("example.case", "assets/Tile.png"));
        Path output = temporaryDirectory.resolve("case-collision.hmcl-theme");
        QueuedExecutor executor = new QueuedExecutor();

        var stage = ThemePackExporter.export(
                manifest,
                List.of(
                        new ThemePackAsset(new ThemePackResource.Bytes(image, "upper"), "assets/Tile.png"),
                        new ThemePackAsset(new ThemePackResource.Bytes(image, "lower"), "assets/tile.png")),
                output,
                executor);
        executor.runNext();

        assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        assertFalse(Files.exists(output));
    }

    /// Exact deletion rejects a stale expected path and then removes only the validated installation.
    @Test
    public void deleteRequiresExactRevalidatedInstallation() throws Exception {
        Path archive = temporaryDirectory.resolve("delete.hmcl-theme");
        writeZip(archive, Map.of(
                "manifest.json", manifest("example.delete", "assets/pixel.png"),
                "assets/pixel.png", png(1, 1)));
        Path repositoryDirectory = temporaryDirectory.resolve("delete-repository");
        LocalThemePackRepository repository = new LocalThemePackRepository(repositoryDirectory);
        QueuedExecutor executor = new QueuedExecutor();
        var importStage = repository.importArchive(archive, executor);
        executor.runNext();
        InstalledThemePack installed = importStage.toCompletableFuture().join();

        var staleStage = repository.deleteInstalled(
                installed.manifest().id(),
                temporaryDirectory.resolve("stale-location"),
                executor);
        executor.runNext();
        assertThrows(CompletionException.class, () -> staleStage.toCompletableFuture().join());
        assertTrue(Files.isDirectory(installed.directory()));

        var deleteStage = repository.deleteInstalled(
                installed.manifest().id(),
                installed.directory(),
                executor);
        executor.runNext();
        deleteStage.toCompletableFuture().join();
        assertFalse(Files.exists(installed.directory()));
    }

    /// Local archives cannot reuse an embedded package ID whose persisted reference resolves to trusted content.
    @Test
    public void importRejectsReservedBuiltinPackageId() throws Exception {
        Path archive = temporaryDirectory.resolve("reserved.hmcl-theme");
        writeZip(archive, Map.of(
                "manifest.json", manifest("hmcl.default", "assets/pixel.png"),
                "assets/pixel.png", png(1, 1)));
        Path repositoryDirectory = temporaryDirectory.resolve("reserved-repository");
        LocalThemePackRepository repository = new LocalThemePackRepository(repositoryDirectory);
        QueuedExecutor executor = new QueuedExecutor();

        var importStage = repository.importArchive(archive, executor);
        executor.runNext();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> importStage.toCompletableFuture().join());
        assertInstanceOf(java.nio.file.FileAlreadyExistsException.class, failure.getCause());
        assertFalse(Files.exists(repositoryDirectory.resolve("hmcl.default")));
    }

    /// A direct symbolic-link resource is refused whenever the host permits creation of the test link.
    @Test
    public void localResourceRejectsSymbolicLink() throws Exception {
        Path target = temporaryDirectory.resolve("target.png");
        Files.write(target, png(1, 1));
        Path link = temporaryDirectory.resolve("link.png");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }

        ThemePackResource.File resource = new ThemePackResource.File(link);
        assertThrows(IOException.class, resource::openStream);
    }

    /// Builds one canonical simple manifest as UTF-8 bytes.
    private static byte @Unmodifiable [] manifest(String id, String imagePath) {
        return ("""
                {
                  "$schema": "https://schemas.glavo.site/hmcl/theme-pack/1.0.0",
                  "id": "%s",
                  "version": "1.0.0",
                  "name": "Test",
                  "theme": {
                    "background": { "type": "image", "path": "%s" }
                  }
                }
                """.formatted(id, imagePath)).getBytes(StandardCharsets.UTF_8);
    }

    /// Parses manifest bytes through the production adapter.
    private static ThemePackManifest parseManifest(byte @Unmodifiable [] bytes) {
        return Objects.requireNonNull(JsonUtils.fromJson(
                new String(bytes, StandardCharsets.UTF_8),
                ThemePackManifest.class));
    }

    /// Encodes one opaque PNG fixture.
    private static byte @Unmodifiable [] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        try {
            image.setRGB(0, 0, Color.MAGENTA.getRGB());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("PNG writer is unavailable");
            }
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }

    /// Rewrites a PNG IHDR width and height plus its CRC without expanding pixel data.
    private static byte @Unmodifiable [] withPngDimensions(
            byte @Unmodifiable [] source,
            int width,
            int height) {
        byte[] result = source.clone();
        ByteBuffer buffer = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(16, width);
        buffer.putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(result, 12, 17);
        buffer.putInt(29, (int) crc.getValue());
        return result;
    }

    /// Writes a zip fixture preserving map insertion order where supplied.
    private static void writeZip(Path output, Map<String, byte @Unmodifiable []> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    /// Writes one entry through Kala so Unix mode metadata can be represented in a fixture.
    private static void writeKalaEntry(
            ZipArchiveOutputStream output,
            ZipArchiveEntry entry,
            byte @Unmodifiable [] bytes) throws IOException {
        output.putArchiveEntry(entry);
        output.write(bytes);
        output.closeArchiveEntry();
    }

    /// Imports with one explicit limit policy and asserts exceptional completion after queued execution.
    private static void assertImportFails(
            Path archive,
            Path repositoryDirectory,
            ThemePackArchiveLimits limits,
            QueuedExecutor executor) {
        var stage = new LocalThemePackRepository(repositoryDirectory, limits).importArchive(archive, executor);
        executor.runNext();
        assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    }

    /// Deterministic executor proving repository and exporter methods do not perform I/O before scheduling.
    @NotNullByDefault
    private static final class QueuedExecutor implements Executor {
        /// Scheduled operations in FIFO order.
        private final ArrayDeque<Runnable> operations = new ArrayDeque<>();

        /// Enqueues one operation without running it inline.
        @Override
        public void execute(Runnable command) {
            operations.add(Objects.requireNonNull(command, "command"));
        }

        /// Runs the next scheduled operation.
        private void runNext() {
            @Nullable Runnable operation = operations.poll();
            if (operation == null) {
                throw new IllegalStateException("No queued operation");
            }
            operation.run();
        }
    }
}
