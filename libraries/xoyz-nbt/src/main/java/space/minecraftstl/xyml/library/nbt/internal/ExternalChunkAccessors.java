/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.internal;

import space.minecraftstl.xyml.library.nbt.io.ExternalChunkAccessor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/// Internal implementations and factories for Java Anvil external chunk companions.
@NotNullByDefault
public final class ExternalChunkAccessors {
    /// Case-insensitive Java Anvil region filename pattern eligible for `.mcc` companions.
    public static final Pattern FILE_NAME_PATTERN = Pattern.compile(
            "r\\.(?<regionX>-?\\d+)\\.(?<regionZ>-?\\d+)\\.mca",
            Pattern.CASE_INSENSITIVE);

    public static final Function<Path, ExternalChunkAccessor> DEFAULT_FACTORY = new Function<>() {
        @Override
        public ExternalChunkAccessor apply(Path path) {
            return ExternalChunkAccessor.of(path);
        }

        @Override
        public String toString() {
            return "ExternalChunkAccessor.defaultFactory()";
        }
    };

    public static final Function<Path, ExternalChunkAccessor> EMPTY_FACTORY = new Function<>() {
        @Override
        public ExternalChunkAccessor apply(Path path) {
            return ExternalChunkAccessor.emptyAccessor();
        }

        @Override
        public String toString() {
            return "ExternalChunkAccessor.emptyFactory()";
        }
    };

    public static final ExternalChunkAccessor EMPTY = new ExternalChunkAccessor() {
        @Override
        public String toString() {
            return "ExternalChunkAccessor.emptyAccessor()";
        }
    };

    /// Filesystem-backed companion accessor for one parsed Anvil region coordinate.
    ///
    /// @param path source region path
    /// @param regionX signed region X coordinate
    /// @param regionZ signed region Z coordinate
    @NotNullByDefault
    public record FileExternalChunkAccessor(Path path, int regionX, int regionZ) implements ExternalChunkAccessor {
        /// Resolves the expected companion, accepting an existing case-insensitive `.mcc` name.
        ///
        /// @param source source region path
        /// @param chunkLocalX local chunk X coordinate from 0 through 31
        /// @param chunkLocalZ local chunk Z coordinate from 0 through 31
        /// @return resolved companion path
        public Path locate(Path source, int chunkLocalX, int chunkLocalZ) {
            Objects.checkIndex(chunkLocalX, ChunkUtils.CHUNKS_PER_REGION_SIDE);
            Objects.checkIndex(chunkLocalZ, ChunkUtils.CHUNKS_PER_REGION_SIDE);

            long chunkX = (long) regionX * ChunkUtils.CHUNKS_PER_REGION_SIDE + chunkLocalX;
            long chunkZ = (long) regionZ * ChunkUtils.CHUNKS_PER_REGION_SIDE + chunkLocalZ;
            Path expected = source.resolveSibling("c.%d.%d.mcc".formatted(
                    chunkX,
                    chunkZ
            ));
            if (Files.exists(expected, LinkOption.NOFOLLOW_LINKS)) {
                return expected;
            }
            @Nullable Path parent = expected.getParent();
            @Nullable Path fileName = expected.getFileName();
            if (parent == null || fileName == null) {
                return expected;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
                for (Path entry : entries) {
                    @Nullable Path entryName = entry.getFileName();
                    if (entryName != null && entryName.toString().equalsIgnoreCase(fileName.toString())
                            && isRegularNonSymbolic(entry)) {
                        return entry;
                    }
                }
            } catch (IOException ignored) {
                // The normal path is retained; the subsequent open/write reports the I/O failure.
            }
            return expected;
        }

        /// Opens an existing regular companion without following symbolic links.
        ///
        /// @param chunkLocalX local chunk X coordinate from 0 through 31
        /// @param chunkLocalZ local chunk Z coordinate from 0 through 31
        /// @return companion input stream
        /// @throws IOException if the companion is missing, unsafe, or cannot be opened
        @Override
        public InputStream openInputStream(int chunkLocalX, int chunkLocalZ) throws IOException {
            Path chunkFile = locate(path, chunkLocalX, chunkLocalZ);
            requireSafeParent(chunkFile);
            requireRegularNonSymbolic(chunkFile);
            return Files.newInputStream(chunkFile, LinkOption.NOFOLLOW_LINKS);
        }

        /// Opens a companion for replacement after validating its parent path.
        ///
        /// @param chunkLocalX local chunk X coordinate from 0 through 31
        /// @param chunkLocalZ local chunk Z coordinate from 0 through 31
        /// @return companion output stream, or `null` when writing is unsupported
        /// @throws IOException if the companion path is unsafe or cannot be opened
        @Override
        public @Nullable OutputStream openOutputStream(int chunkLocalX, int chunkLocalZ) throws IOException {
            Path chunkFile = locate(path, chunkLocalX, chunkLocalZ);
            Path absolute = chunkFile.toAbsolutePath();
            @Nullable Path parent = absolute.getParent();
            if (parent == null) {
                throw new IOException("External chunk companion has no parent directory: " + chunkFile);
            }
            createDirectoriesNoFollow(parent);
            requireSafeParent(chunkFile);
            if (Files.exists(chunkFile, LinkOption.NOFOLLOW_LINKS)) {
                requireRegularNonSymbolic(chunkFile);
            }
            return Files.newOutputStream(chunkFile, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
    }

    /// Prevents utility-class construction.
    private ExternalChunkAccessors() {
    }

    /// Returns whether a companion candidate is a regular non-symbolic file.
    ///
    /// @param path companion candidate
    /// @return whether the candidate is an ordinary non-symbolic file
    private static boolean isRegularNonSymbolic(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink();
        } catch (IOException ignored) {
            return false;
        }
    }

    /// Requires one companion to be an ordinary non-symbolic file below a safe parent chain.
    ///
    /// @param path companion path
    /// @throws IOException if the path or a parent component is unsafe
    private static void requireRegularNonSymbolic(Path path) throws IOException {
        requireSafeParent(path);
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("External chunk companion is not a regular non-symbolic file: " + path);
        }
    }

    /// Requires every existing parent component to be a real directory.
    ///
    /// @param file file whose parent chain is being checked
    /// @throws IOException if an existing component is symbolic, special, or inaccessible
    private static void requireSafeParent(Path file) throws IOException {
        Path absolute = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        @Nullable Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("External chunk path has no parent directory: " + file);
        }
        validateDirectoryChain(parent);
    }

    /// Creates a directory chain while rejecting symbolic-link components.
    ///
    /// @param directory directory to create
    /// @throws IOException if a component is not a real directory or creation fails
    private static void createDirectoriesNoFollow(Path directory) throws IOException {
        Path absolute = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        validateDirectoryChain(absolute);
        Files.createDirectories(absolute);
        validateDirectoryChain(absolute);
    }

    /// Checks existing components from a path toward the filesystem root.
    ///
    /// @param leaf deepest component to inspect
    /// @throws IOException if an existing component is not a non-symbolic directory
    private static void validateDirectoryChain(Path leaf) throws IOException {
        @Nullable Path current = leaf;
        while (current != null) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || attributes.isSymbolicLink() || Files.isSymbolicLink(current)) {
                    throw new IOException("External chunk path component is not a regular non-symbolic directory: "
                            + current);
                }
                rejectAliasedDirectoryEntry(current);
            } catch (NoSuchFileException missing) {
                // Missing descendants are safe to create; ancestors are still checked.
            }
            current = current.getParent();
        }
    }

    /// Rejects a directory entry which resolves outside its lexical parent.
    ///
    /// This also catches Windows junctions, which some NIO providers expose as ordinary directories
    /// rather than symbolic links.
    ///
    /// @param directory existing directory component
    /// @throws IOException if the component redirects traversal or cannot be resolved
    private static void rejectAliasedDirectoryEntry(Path directory) throws IOException {
        @Nullable Path parent = directory.getParent();
        @Nullable Path name = directory.getFileName();
        if (parent == null || name == null) {
            return;
        }
        Path lexicalEntry = parent.toRealPath().resolve(name).toAbsolutePath().normalize();
        Path realEntry = directory.toRealPath().toAbsolutePath().normalize();
        if (!lexicalEntry.equals(realEntry)) {
            throw new IOException("External chunk path component redirects through a symbolic link or junction: "
                    + directory);
        }
    }
}
