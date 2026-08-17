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
package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.LocalAddonManager;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/// Plans and applies local Mod copies without exposing mutable manager state to Swing.
@NotNullByDefault
final class ModImportFileOperations {
    /// Prevents utility-class construction.
    private ModImportFileOperations() {
    }

    /// Normalizes and defensively captures import sources in caller order.
    ///
    /// @param sources candidate import sources
    /// @return immutable normalized sources
    static @Unmodifiable List<Path> normalizeSources(@Unmodifiable List<Path> sources) {
        @Unmodifiable List<Path> normalized = Objects.requireNonNull(sources, "sources").stream()
                .map(source -> Objects.requireNonNull(source, "source").toAbsolutePath().normalize())
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one Mod source is required");
        }
        return normalized;
    }

    /// Derives the same rename-stable, case-normalized key used by local Mod entries.
    ///
    /// @param source Mod source or target path
    /// @return normalized local key
    static String normalizedLocalKey(Path source) {
        String addOnName = LocalAddonManager.getLocalAddonName(
                Objects.requireNonNull(source, "source"));
        return FileUtils.getNameWithoutExtension(Path.of(addOnName)).toLowerCase(Locale.ROOT);
    }

    /// Preflights every source and applies the complete ordered import plan.
    ///
    /// An absent conflict action means an ordinary copy that is valid only while the target local
    /// key remains free. All source validation and destination selection finish before the first
    /// copy starts.
    ///
    /// @param directory managed Mod directory
    /// @param indexedPaths current manager-indexed Mod paths, including supported subdirectories
    /// @param sources immutable source paths
    /// @param conflictActions explicit decisions keyed by source path
    /// @param cancellation cooperative cancellation checked before every irreversible operation
    /// @throws IOException when inspection, copying, replacement, or cleanup fails
    static void importMods(
            Path directory,
            @Unmodifiable List<Path> indexedPaths,
            @Unmodifiable List<Path> sources,
            @Unmodifiable Map<Path, ModImportConflictAction> conflictActions,
            LoadCancellation cancellation) throws IOException {
        Path normalizedDirectory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
        @Unmodifiable List<Path> normalizedSources = normalizeSources(sources);
        @Unmodifiable Map<Path, ModImportConflictAction> normalizedActions = normalizeActions(
                conflictActions, normalizedSources);
        Objects.requireNonNull(cancellation, "cancellation");

        Map<String, List<Path>> occupiedPaths = new HashMap<>();
        Set<String> occupiedNames = new HashSet<>();
        captureIndexedPaths(indexedPaths, occupiedPaths);
        captureDirectEntries(normalizedDirectory, occupiedPaths, occupiedNames);

        List<PlannedImport> plan = new ArrayList<>(normalizedSources.size());
        for (Path source : normalizedSources) {
            requireNotCancelled(cancellation);
            @Nullable ModImportConflictAction action = normalizedActions.get(source);
            String sourceName = fileName(source);
            String sourceKey = normalizedLocalKey(source);
            boolean conflict = occupiedPaths.containsKey(sourceKey)
                    || occupiedNames.contains(sourceName.toLowerCase(Locale.ROOT));
            if (conflict && action == null) {
                throw new ModImportConflictException(source);
            }
            if (action == ModImportConflictAction.SKIP) {
                continue;
            }
            validateSource(source);

            if (action == ModImportConflictAction.KEEP) {
                Path target = uniqueKeptTarget(
                        normalizedDirectory, sourceName, occupiedPaths.keySet(), occupiedNames);
                plan.add(new PlannedImport(source, target, false, List.of()));
                reserveTarget(target, occupiedPaths, occupiedNames, false);
                continue;
            }

            Path target = normalizedDirectory.resolve(sourceName).normalize();
            boolean replace = action == ModImportConflictAction.REPLACE;
            @Unmodifiable List<Path> replacedPaths = replace
                    ? List.copyOf(occupiedPaths.getOrDefault(sourceKey, List.of()))
                    : List.of();
            plan.add(new PlannedImport(source, target, replace, replacedPaths));
            reserveTarget(target, occupiedPaths, occupiedNames, replace);
        }

        Files.createDirectories(normalizedDirectory);
        for (PlannedImport plannedImport : plan) {
            requireNotCancelled(cancellation);
            apply(plannedImport);
        }
    }

    /// Normalizes decisions and rejects entries unrelated to the source batch.
    ///
    /// @param conflictActions candidate source decisions
    /// @param sources normalized source batch
    /// @return immutable normalized decisions
    private static @Unmodifiable Map<Path, ModImportConflictAction> normalizeActions(
            @Unmodifiable Map<Path, ModImportConflictAction> conflictActions,
            @Unmodifiable List<Path> sources) {
        Map<Path, ModImportConflictAction> normalized = new LinkedHashMap<>();
        Objects.requireNonNull(conflictActions, "conflictActions").forEach((source, action) -> {
            Path normalizedSource = Objects.requireNonNull(source, "conflict source")
                    .toAbsolutePath().normalize();
            ModImportConflictAction previous = normalized.put(
                    normalizedSource,
                    Objects.requireNonNull(action, "conflict action"));
            if (previous != null && previous != action) {
                throw new IllegalArgumentException("Conflicting decisions for Mod source " + source);
            }
        });
        if (!sources.containsAll(normalized.keySet())) {
            throw new IllegalArgumentException("Conflict decisions must belong to the import sources");
        }
        return Map.copyOf(normalized);
    }

    /// Validates one source without mutating the target directory.
    ///
    /// @param source normalized source path
    /// @throws IOException when the source is not a regular file
    private static void validateSource(Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Mod source is not a regular file: " + source);
        }
        if (!ModManager.isFileNameMod(source)) {
            throw new IllegalArgumentException("Unsupported Mod file: " + source);
        }
    }

    /// Captures manager-indexed paths, including Mods held in supported subdirectories.
    ///
    /// @param indexedPaths current indexed paths
    /// @param occupiedPaths mutable local-key index
    private static void captureIndexedPaths(
            @Unmodifiable List<Path> indexedPaths,
            Map<String, List<Path>> occupiedPaths) {
        for (Path path : Objects.requireNonNull(indexedPaths, "indexedPaths")) {
            addOccupiedPath(occupiedPaths, path);
        }
    }

    /// Captures every direct name plus Mod files omitted from the active manager index, such as archives.
    ///
    /// @param directory managed directory
    /// @param occupiedPaths mutable local-key index
    /// @param occupiedNames mutable direct-name index
    /// @throws IOException when directory enumeration fails
    private static void captureDirectEntries(
            Path directory,
            Map<String, List<Path>> occupiedPaths,
            Set<String> occupiedNames) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                occupiedNames.add(fileName(entry).toLowerCase(Locale.ROOT));
                if (Files.isRegularFile(entry) && ModManager.isFileNameMod(entry)) {
                    addOccupiedPath(occupiedPaths, entry);
                }
            }
        }
    }

    /// Adds one normalized path to the mutable local-key index without duplicating it.
    ///
    /// @param occupiedPaths mutable local-key index
    /// @param path occupied Mod path
    private static void addOccupiedPath(Map<String, List<Path>> occupiedPaths, Path path) {
        Path normalized = Objects.requireNonNull(path, "occupied path").toAbsolutePath().normalize();
        List<Path> paths = occupiedPaths.computeIfAbsent(
                normalizedLocalKey(normalized), ignored -> new ArrayList<>());
        if (!paths.contains(normalized)) {
            paths.add(normalized);
        }
    }

    /// Finds a free kept-copy target by inserting successively more hyphens before the Mod extension.
    ///
    /// @param directory managed directory
    /// @param sourceName original file name
    /// @param occupiedKeys current local keys
    /// @param occupiedNames current direct names
    /// @return normalized unique target path
    private static Path uniqueKeptTarget(
            Path directory,
            String sourceName,
            Set<String> occupiedKeys,
            Set<String> occupiedNames) {
        String addOnName = LocalAddonManager.getLocalAddonName(Path.of(sourceName));
        int extensionIndex = addOnName.lastIndexOf('.');
        if (extensionIndex <= 0) {
            throw new IllegalArgumentException("Mod source must have a base name and extension: " + sourceName);
        }
        String baseName = addOnName.substring(0, extensionIndex);
        String extensionAndSuffix = sourceName.substring(extensionIndex);
        String hyphens = "-";
        while (true) {
            String candidateName = baseName + hyphens + extensionAndSuffix;
            Path candidate = directory.resolve(candidateName).normalize();
            if (!occupiedKeys.contains(normalizedLocalKey(candidate))
                    && !occupiedNames.contains(candidateName.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
            hyphens += "-";
        }
    }

    /// Reserves one planned output for later sources in the same batch.
    ///
    /// @param target planned target
    /// @param occupiedPaths mutable local-key index
    /// @param occupiedNames mutable direct-name index
    /// @param replace whether earlier same-key paths will be removed
    private static void reserveTarget(
            Path target,
            Map<String, List<Path>> occupiedPaths,
            Set<String> occupiedNames,
            boolean replace) {
        occupiedNames.add(fileName(target).toLowerCase(Locale.ROOT));
        if (replace) {
            occupiedPaths.put(
                    normalizedLocalKey(target),
                    new ArrayList<>(List.of(target.toAbsolutePath().normalize())));
        } else {
            addOccupiedPath(occupiedPaths, target);
        }
    }

    /// Copies one planned source and removes superseded same-key paths only after the new file exists.
    ///
    /// @param plannedImport immutable planned import
    /// @throws IOException when copying or cleanup fails
    private static void apply(PlannedImport plannedImport) throws IOException {
        if (plannedImport.replace()) {
            if (!sameFile(plannedImport.source(), plannedImport.target())) {
                FileUtils.copyFile(plannedImport.source(), plannedImport.target());
            }
            for (Path replacedPath : plannedImport.replacedPaths()) {
                if (!sameFile(replacedPath, plannedImport.target())) {
                    Files.deleteIfExists(replacedPath);
                }
            }
            return;
        }
        try {
            Files.copy(
                    plannedImport.source(),
                    plannedImport.target(),
                    StandardCopyOption.COPY_ATTRIBUTES);
        } catch (FileAlreadyExistsException conflict) {
            throw new ModImportConflictException(plannedImport.source());
        }
    }

    /// Compares normalized paths and existing file identities without requiring both paths to exist.
    ///
    /// @param first first path
    /// @param second second path
    /// @return whether both paths identify the same file
    /// @throws IOException when existing file identity inspection fails
    private static boolean sameFile(Path first, Path second) throws IOException {
        Path normalizedFirst = first.toAbsolutePath().normalize();
        Path normalizedSecond = second.toAbsolutePath().normalize();
        return normalizedFirst.equals(normalizedSecond)
                || Files.exists(normalizedFirst)
                && Files.exists(normalizedSecond)
                && Files.isSameFile(normalizedFirst, normalizedSecond);
    }

    /// Returns one required final path component.
    ///
    /// @param path source or target path
    /// @return file name text
    private static String fileName(Path path) {
        return Objects.requireNonNull(
                Objects.requireNonNull(path, "path").getFileName(),
                "Mod path must have a file name").toString();
    }

    /// Throws when cooperative cancellation was requested before irreversible work.
    ///
    /// @param cancellation cancellation signal
    private static void requireNotCancelled(LoadCancellation cancellation) {
        if (cancellation.isCancelled()) {
            throw new CancellationException("Mod import was cancelled");
        }
    }

    /// Immutable preflighted copy and optional replacement cleanup.
    ///
    /// @param source normalized source
    /// @param target normalized target
    /// @param replace whether the target may be replaced
    /// @param replacedPaths immutable same-key paths removed after copying
    @NotNullByDefault
    private record PlannedImport(
            Path source,
            Path target,
            boolean replace,
            @Unmodifiable List<Path> replacedPaths) {
        /// Defensively captures normalized paths.
        private PlannedImport {
            source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
            target = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
            replacedPaths = Objects.requireNonNull(replacedPaths, "replacedPaths").stream()
                    .map(path -> Objects.requireNonNull(path, "replaced path")
                            .toAbsolutePath().normalize())
                    .toList();
        }
    }
}
