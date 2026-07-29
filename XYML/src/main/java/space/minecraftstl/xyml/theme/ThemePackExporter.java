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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.gson.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// Bounded background exporter for zip-compatible theme-pack files.
///
/// Export never overwrites an existing target. It validates all assets, referenced image pixels, duplicate entry
/// names, and aggregate sizes before creating a same-directory temporary archive and atomically publishing it.
@NotNullByDefault
public final class ThemePackExporter {
    /// Recommended theme-pack extension.
    public static final String FILE_EXTENSION = ".xyml-theme";

    /// Root manifest entry name.
    public static final String MANIFEST_ENTRY = LocalThemePackRepository.MANIFEST_ENTRY;

    /// Prefix for unpublished export files.
    private static final String TEMPORARY_PREFIX = ".xyml-theme-export-";

    /// Prevents utility-class construction.
    private ThemePackExporter() {
    }

    /// Exports using launcher-default resource ceilings on a caller-owned non-EDT executor.
    ///
    /// @param manifest manifest to encode
    /// @param assets reopenable asset sources
    /// @param outputFile absent target file
    /// @param executor caller-owned non-EDT worker executor
    /// @return completion stage containing the published file
    public static CompletionStage<Path> export(
            ThemePackManifest manifest,
            @Unmodifiable List<ThemePackAsset> assets,
            Path outputFile,
            Executor executor) {
        return export(manifest, assets, outputFile, ThemePackArchiveLimits.launcherDefaults(), executor);
    }

    /// Exports using explicit resource ceilings on a caller-owned non-EDT executor.
    ///
    /// @param manifest manifest to encode
    /// @param assets reopenable asset sources
    /// @param outputFile absent target file
    /// @param limits resource ceilings
    /// @param executor caller-owned non-EDT worker executor
    /// @return completion stage containing the published file
    public static CompletionStage<Path> export(
            ThemePackManifest manifest,
            @Unmodifiable List<ThemePackAsset> assets,
            Path outputFile,
            ThemePackArchiveLimits limits,
            Executor executor) {
        ThemePackManifest checkedManifest = Objects.requireNonNull(manifest, "manifest");
        @Unmodifiable List<ThemePackAsset> copiedAssets = List.copyOf(assets);
        Path target = Objects.requireNonNull(outputFile, "outputFile").toAbsolutePath().normalize();
        ThemePackArchiveLimits checkedLimits = Objects.requireNonNull(limits, "limits");
        Executor checkedExecutor = Objects.requireNonNull(executor, "executor");
        CompletableFuture<Path> future = new CompletableFuture<>();
        try {
            checkedExecutor.execute(() -> {
                try {
                    ThemePackIoSupport.requireBackgroundThread();
                    future.complete(exportBlocking(
                            checkedManifest,
                            copiedAssets,
                            target,
                            checkedLimits));
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    /// Performs one complete validated export on the scheduled worker.
    private static Path exportBlocking(
            ThemePackManifest manifest,
            @Unmodifiable List<ThemePackAsset> assets,
            Path outputFile,
            ThemePackArchiveLimits limits) throws IOException {
        if (assets.size() + 1 > limits.maximumEntryCount()) {
            throw new IOException("Theme-pack export exceeds the entry-count limit");
        }
        if (Files.exists(outputFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(outputFile.toString());
        }
        @Nullable Path parent = outputFile.getParent();
        if (parent == null) {
            throw new IOException("Theme-pack export target has no parent directory");
        }
        ThemePackIoSupport.createAbsoluteDirectoriesWithoutLinks(parent);
        requireDirectDirectory(parent);

        byte[] manifestBytes = JsonUtils.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8);
        if (manifestBytes.length <= 0 || manifestBytes.length > limits.maximumManifestBytes()) {
            throw new IOException("Theme-pack manifest exceeds its byte limit");
        }
        @Unmodifiable List<MeasuredAsset> measuredAssets = validateAssets(manifest, assets, limits);

        Path temporary = Files.createTempFile(parent, TEMPORARY_PREFIX, ".tmp").toAbsolutePath().normalize();
        boolean published = false;
        @Nullable Throwable failure = null;
        try {
            writeArchive(temporary, manifestBytes, measuredAssets, limits);
            publishAtomically(temporary, outputFile);
            published = true;
            return outputFile;
        } catch (IOException | RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            if (!published) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    /// Validates names, readability, exact sizes, total size, references, and referenced image pixels.
    private static @Unmodifiable List<MeasuredAsset> validateAssets(
            ThemePackManifest manifest,
            @Unmodifiable List<ThemePackAsset> assets,
            ThemePackArchiveLimits limits) throws IOException {
        Set<String> entries = new HashSet<>();
        Set<String> portableEntries = new HashSet<>();
        entries.add(MANIFEST_ENTRY);
        portableEntries.add(MANIFEST_ENTRY.toLowerCase(Locale.ROOT));
        List<MeasuredAsset> measured = new ArrayList<>(assets.size());
        long total = 0L;
        for (ThemePackAsset asset : assets) {
            Objects.requireNonNull(asset, "asset");
            if (!entries.add(asset.entryName())
                    || !portableEntries.add(asset.entryName().toLowerCase(Locale.ROOT))) {
                throw new IOException("Duplicate or case-colliding theme-pack export entry: "
                        + asset.entryName());
            }
            byte[] data;
            try (InputStream input = asset.source().openStream()) {
                data = ThemePackIoSupport.readBounded(input, limits.maximumSingleAssetBytes());
            }
            total = ThemePackIoSupport.checkedTotal(total, data.length, limits.maximumExpandedBytes());
            measured.add(new MeasuredAsset(asset.entryName(), data));
        }
        for (String reference : manifest.referencedAssets()) {
            if (!entries.contains(reference)) {
                throw new IOException("Theme-pack manifest references an absent export asset: " + reference);
            }
            MeasuredAsset asset = measured.stream()
                    .filter(candidate -> reference.equals(candidate.entryName()))
                    .findFirst()
                    .orElseThrow();
            ThemePackIoSupport.validateImage(
                    new ThemePackResource.Bytes(asset.data, reference),
                    limits);
        }
        measured.sort(Comparator.comparing(MeasuredAsset::entryName));
        return List.copyOf(measured);
    }

    /// Writes one deterministic archive and repeats actual byte ceilings while copying sources.
    private static void writeArchive(
            Path temporary,
            byte @Unmodifiable [] manifestBytes,
            @Unmodifiable List<MeasuredAsset> assets,
            ThemePackArchiveLimits limits) throws IOException {
        try (OutputStream fileOutput = Files.newOutputStream(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             ZipOutputStream zip = new ZipOutputStream(fileOutput, StandardCharsets.UTF_8)) {
            zip.putNextEntry(newArchiveEntry(MANIFEST_ENTRY));
            zip.write(manifestBytes);
            zip.closeEntry();
            long total = manifestBytes.length;
            for (MeasuredAsset measured : assets) {
                zip.putNextEntry(newArchiveEntry(measured.entryName()));
                zip.write(measured.data);
                zip.closeEntry();
                total = ThemePackIoSupport.checkedTotal(
                        total,
                        measured.data.length,
                        limits.maximumExpandedBytes());
            }
            zip.finish();
        }
    }

    /// Requires an existing direct parent directory that is not a link.
    private static void requireDirectDirectory(Path directory) throws IOException {
        ThemePackIoSupport.requireNoSymbolicPath(directory, true, "theme-pack export parent");
    }

    /// Creates one reproducible archive entry with a stable epoch timestamp.
    private static ZipEntry newArchiveEntry(String entryName) {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(0L);
        return entry;
    }

    /// Publishes an export atomically without overwriting a race-created target.
    private static void publishAtomically(Path temporary, Path outputFile) throws IOException {
        try {
            Files.move(temporary, outputFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Theme-pack export filesystem does not support atomic publication", exception);
        }
    }

    /// One validated immutable asset snapshot.
    ///
    /// @param entryName normalized destination entry
    /// @param data immutable bounded source bytes
    @NotNullByDefault
    private record MeasuredAsset(String entryName, byte @Unmodifiable [] data) {
        /// Validates and defensively copies the snapshot.
        private MeasuredAsset {
            entryName = ThemePackAsset.normalizeEntryName(entryName);
            data = Objects.requireNonNull(data, "data").clone();
        }

        /// Returns a defensive copy of the snapshot.
        @Override
        public byte @Unmodifiable [] data() {
            return data.clone();
        }
    }
}
