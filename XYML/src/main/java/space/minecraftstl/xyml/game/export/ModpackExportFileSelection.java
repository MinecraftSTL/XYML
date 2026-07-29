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
package space.minecraftstl.xyml.game.export;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/// Immutable non-empty selection of files or directory roots relative to an instance run directory.
///
/// A selected file implicitly selects every ancestor directory. A selected directory expands to every
/// descendant directory and file. Symbolic links are excluded so a local archive never follows an
/// instance entry outside its run directory. This exact expansion is required because the core
/// exporters match whitelist entries literally and otherwise prune a selected file when its parent is absent.
@NotNullByDefault
public final class ModpackExportFileSelection {
    /// Normalized portable relative roots in stable user-selected order.
    private final @Unmodifiable List<String> selectedPaths;

    /// Creates a validated immutable file selection.
    ///
    /// @param selectedPaths non-empty relative file or directory paths
    private ModpackExportFileSelection(Collection<String> selectedPaths) {
        Objects.requireNonNull(selectedPaths, "selectedPaths");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String selectedPath : selectedPaths) {
            normalized.add(normalizeRelativePath(selectedPath));
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one export file must be selected");
        }
        this.selectedPaths = List.copyOf(normalized);
    }

    /// Creates an immutable non-empty selection from relative paths.
    ///
    /// @param selectedPaths selected relative files or directories
    /// @return validated immutable selection
    public static ModpackExportFileSelection of(Collection<String> selectedPaths) {
        return new ModpackExportFileSelection(selectedPaths);
    }

    /// Returns the normalized user-selected roots in stable order.
    public @Unmodifiable List<String> selectedPaths() {
        return selectedPaths;
    }

    /// Expands selected roots into the literal whitelist expected by the existing export tasks.
    ///
    /// @param runDirectory effective instance run directory
    /// @return immutable portable relative paths including required ancestors and descendants
    /// @throws IOException when a selected path disappeared, is a symbolic link, or cannot be enumerated
    public @Unmodifiable List<String> expand(Path runDirectory) throws IOException {
        Path normalizedRunDirectory = Objects.requireNonNull(runDirectory, "runDirectory")
                .toAbsolutePath()
                .normalize();
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        for (String selectedPath : selectedPaths) {
            Path selected = normalizedRunDirectory.resolve(toPlatformPath(selectedPath)).normalize();
            if (!selected.startsWith(normalizedRunDirectory)) {
                throw new IllegalArgumentException("Selected export path escapes the run directory: " + selectedPath);
            }
            if (Files.notExists(selected)) {
                throw new NoSuchFileException(selected.toString());
            }
            if (Files.isSymbolicLink(selected)) {
                throw new FileSystemException(
                        selected.toString(),
                        null,
                        "Symbolic links cannot be included in modpack exports");
            }

            addAncestors(selectedPath, expanded);
            if (Files.isDirectory(selected)) {
                try (var stream = Files.walk(selected)) {
                    stream
                            .filter(path -> !path.equals(selected))
                            .filter(path -> !Files.isSymbolicLink(path))
                            .sorted(Comparator.comparing(
                                    path -> toPortablePath(normalizedRunDirectory.relativize(path))))
                            .map(normalizedRunDirectory::relativize)
                            .map(ModpackExportFileSelection::toPortablePath)
                            .forEach(path -> {
                                addAncestors(path, expanded);
                                expanded.add(path);
                            });
                }
            }
            expanded.add(selectedPath);
        }
        return List.copyOf(expanded);
    }

    /// Adds every non-root ancestor of one normalized portable path.
    ///
    /// @param path normalized portable relative path
    /// @param destination stable expanded destination
    private static void addAncestors(String path, LinkedHashSet<String> destination) {
        int separator = path.indexOf('/');
        while (separator >= 0) {
            destination.add(path.substring(0, separator));
            separator = path.indexOf('/', separator + 1);
        }
    }

    /// Normalizes one user path and rejects root, absolute, or traversal selections.
    ///
    /// @param candidate user-supplied relative path
    /// @return portable normalized relative path
    private static String normalizeRelativePath(String candidate) {
        Objects.requireNonNull(candidate, "selected path");
        String portable = candidate.replace('\\', '/');
        if (portable.isBlank() || portable.startsWith("/") || portable.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Export selection must be a non-root relative path: " + candidate);
        }

        Path normalized = toPlatformPath(portable).normalize();
        String normalizedPortable = toPortablePath(normalized);
        if (normalized.isAbsolute()
                || normalizedPortable.isEmpty()
                || normalizedPortable.equals("..")
                || normalizedPortable.startsWith("../")) {
            throw new IllegalArgumentException("Export selection escapes the run directory: " + candidate);
        }
        return normalizedPortable;
    }

    /// Converts a portable slash-separated path to the current platform path representation.
    ///
    /// @param portable portable relative path
    /// @return current-platform relative path
    private static Path toPlatformPath(String portable) {
        return Path.of(portable.replace('/', File.separatorChar));
    }

    /// Converts one current-platform path to a portable slash-separated path.
    ///
    /// @param path current-platform relative path
    /// @return portable relative path
    private static String toPortablePath(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }
}
