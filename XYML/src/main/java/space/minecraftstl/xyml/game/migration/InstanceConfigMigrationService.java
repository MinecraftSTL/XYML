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
package space.minecraftstl.xyml.game.migration;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationContent;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/// Copies the fixed Minecraft configuration surface between captured running directories.
///
/// The service never follows symbolic links and never deletes destination-only content. Replacement is staged in the
/// destination directory before an atomic move is attempted, preventing a failed copy from truncating an existing file.
@NotNullByDefault
public final class InstanceConfigMigrationService {
    /// Utility class; no instances.
    private InstanceConfigMigrationService() {
    }

    /// Executes one migration synchronously on the calling worker thread.
    ///
    /// @param request immutable captured migration request
    /// @return immutable per-content result
    /// @throws IOException when a selected path is unsafe or cannot be copied
    public static InstanceConfigMigrationResult migrate(InstanceConfigMigrationRequest request) throws IOException {
        InstanceConfigMigrationRequest checkedRequest = Objects.requireNonNull(request, "request");
        Path sourceRoot = checkedRequest.sourceDirectory();
        Path targetRoot = checkedRequest.targetDirectory();
        validateRoot(sourceRoot, false);
        validateRoot(targetRoot, true);

        Map<InstanceConfigMigrationContent, InstanceConfigMigrationResult.Counts> counts =
                new EnumMap<>(InstanceConfigMigrationContent.class);
        for (InstanceConfigMigrationContent content : checkedRequest.contents()) {
            checkInterrupted();
            MutableCounts current = new MutableCounts();
            Path source = resolveFixed(sourceRoot, content.relativePath());
            Path target = resolveFixed(targetRoot, content.relativePath());
            if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) {
                counts.put(content, current.freeze());
                continue;
            }
            BasicFileAttributes sourceAttributes = attributes(source);
            if (content.directory()) {
                if (!sourceAttributes.isDirectory()) {
                    throw new IOException("Migration source is not a directory: " + source);
                }
                copyDirectory(sourceRoot, targetRoot, source, checkedRequest.replaceExisting(), current);
            } else {
                if (!sourceAttributes.isRegularFile()) {
                    throw new IOException("Migration source is not a regular file: " + source);
                }
                copyFile(targetRoot, source, target, checkedRequest.replaceExisting(), current);
            }
            counts.put(content, current.freeze());
        }
        return new InstanceConfigMigrationResult(counts);
    }

    /// Copies one selected directory without following symbolic links.
    private static void copyDirectory(
            Path sourceRoot,
            Path targetRoot,
            Path source,
            boolean replaceExisting,
            MutableCounts counts) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            /// Creates and validates each corresponding destination directory.
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                checkInterrupted();
                if (attributes.isSymbolicLink()) {
                    throw new IOException("Symbolic links are not supported in migration sources: " + directory);
                }
                Path relative = sourceRoot.relativize(directory);
                ensureDirectory(targetRoot, targetRoot.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            /// Copies one regular source file.
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                checkInterrupted();
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    throw new IOException("Only regular files are supported in migration sources: " + file);
                }
                Path relative = sourceRoot.relativize(file);
                copyFile(targetRoot, file, targetRoot.resolve(relative), replaceExisting, counts);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /// Copies one regular file through a destination-local temporary file.
    private static void copyFile(
            Path targetRoot,
            Path source,
            Path target,
            boolean replaceExisting,
            MutableCounts counts) throws IOException {
        Path parent = Objects.requireNonNull(target.getParent(), "target parent");
        ensureDirectory(targetRoot, parent);
        boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (targetExists) {
            BasicFileAttributes targetAttributes = attributes(target);
            if (!targetAttributes.isRegularFile() || targetAttributes.isSymbolicLink()) {
                throw new IOException("Migration target is not a regular file: " + target);
            }
            if (!replaceExisting) {
                counts.skipped++;
                return;
            }
        }

        Path temporary = Files.createTempFile(parent, ".xyml-migration-", ".tmp");
        boolean committed = false;
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            checkInterrupted();
            try {
                if (replaceExisting) {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException exception) {
                if (replaceExisting) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, target);
                }
            } catch (FileAlreadyExistsException conflict) {
                if (replaceExisting) {
                    throw conflict;
                }
                counts.skipped++;
                return;
            }
            committed = true;
            if (targetExists) {
                counts.replaced++;
            } else {
                counts.copied++;
            }
        } finally {
            if (!committed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /// Creates a destination directory while rejecting symbolic-link and non-directory collisions.
    private static void ensureDirectory(Path targetRoot, Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(targetRoot)) {
            throw new IOException("Migration target escapes its running directory: " + directory);
        }
        Path relative = targetRoot.relativize(normalized);
        Path current = targetRoot;
        if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(current);
        }
        validateDirectory(current);
        for (Path name : relative) {
            current = current.resolve(name);
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
            } else {
                validateDirectory(current);
            }
        }
    }

    /// Validates an existing or creatable running-directory root.
    private static void validateRoot(Path root, boolean create) throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) {
                throw new IOException("Migration source directory does not exist: " + root);
            }
            Files.createDirectories(root);
        }
        validateDirectory(root);
    }

    /// Rejects a symbolic link or non-directory path.
    private static void validateDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = attributes(directory);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Migration path is not a real directory: " + directory);
        }
    }

    /// Resolves one fixed relative path and verifies containment.
    private static Path resolveFixed(Path root, String relativePath) throws IOException {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Migration path escapes its running directory: " + relativePath);
        }
        return resolved;
    }

    /// Reads attributes without following symbolic links.
    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    /// Preserves interruption and stops before the next filesystem mutation.
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Instance configuration migration was interrupted");
        }
    }

    /// Mutable worker-local counters frozen into the public result.
    @NotNullByDefault
    private static final class MutableCounts {
        /// Newly created files.
        private int copied;

        /// Replaced existing files.
        private int replaced;

        /// Existing files retained without replacement.
        private int skipped;

        /// Freezes the current counters.
        private InstanceConfigMigrationResult.Counts freeze() {
            return new InstanceConfigMigrationResult.Counts(copied, replaced, skipped);
        }
    }
}
