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

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

/// Reopenable theme-pack asset source consumed only by background import or export work.
@NotNullByDefault
public sealed interface ThemePackResource
        permits ThemePackResource.File, ThemePackResource.ContainedFile,
        ThemePackResource.Builtin, ThemePackResource.Bytes {
    /// Returns the stable resource name used for diagnostics and format detection.
    ///
    /// @return resource name
    String name();

    /// Opens a fresh resource stream.
    ///
    /// @return fresh input
    /// @throws IOException when the source is unavailable or unsafe
    InputStream openStream() throws IOException;

    /// Returns the direct backing file when present.
    ///
    /// @return local file or `null`
    default @Nullable Path file() {
        return null;
    }

    /// Direct local regular-file resource that refuses symbolic links.
    ///
    /// @param path source path
    /// @param name stable resource name
    @NotNullByDefault
    record File(Path path, String name) implements ThemePackResource {
        /// Creates a source named after its file.
        ///
        /// @param path source path
        public File(Path path) {
            this(path, Objects.requireNonNull(path, "path").getFileName().toString());
        }

        /// Normalizes the source path and validates the display name.
        public File {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            name = Objects.requireNonNull(name, "name").trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Theme-pack resource name is blank");
            }
        }

        /// Opens a no-follow regular file after repeating metadata checks.
        @Override
        public InputStream openStream() throws IOException {
            ThemePackIoSupport.requireBackgroundThread();
            ThemePackIoSupport.requireNoSymbolicPath(path, false, "theme-pack resource");
            return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        }

        /// Returns the backing file.
        @Override
        public Path file() {
            return path;
        }
    }

    /// Regular file constrained below an installed theme-pack root.
    ///
    /// Every path segment is checked without following links on every open so later filesystem mutation cannot turn
    /// a previously validated installed pack into an escape path.
    ///
    /// @param root installed theme-pack root
    /// @param entryName normalized asset entry name
    @NotNullByDefault
    record ContainedFile(Path root, String entryName) implements ThemePackResource {
        /// Normalizes the root and entry name.
        public ContainedFile {
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            entryName = ThemePackAsset.normalizeEntryName(entryName);
        }

        /// Returns the normalized asset entry name.
        @Override
        public String name() {
            return entryName;
        }

        /// Opens the contained regular file after proving every existing segment is not linked.
        @Override
        public InputStream openStream() throws IOException {
            ThemePackIoSupport.requireBackgroundThread();
            ThemePackIoSupport.requireNoSymbolicPath(root, true, "installed theme-pack root");
            Path file = root.resolve(entryName).toAbsolutePath().normalize();
            if (!file.startsWith(root)) {
                throw new IOException("Installed theme-pack asset escapes its root");
            }
            ThemePackIoSupport.requireNoSymbolicPath(file, false, "installed theme-pack asset");
            return Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        }

        /// Returns the normalized contained file path without asserting its current existence.
        @Override
        public Path file() {
            return root.resolve(entryName).toAbsolutePath().normalize();
        }

    }

    /// Bundled classpath resource.
    ///
    /// @param resourcePath absolute classpath path
    /// @param name stable resource name
    @NotNullByDefault
    record Builtin(String resourcePath, String name) implements ThemePackResource {
        /// Validates classpath and display names.
        public Builtin {
            resourcePath = Objects.requireNonNull(resourcePath, "resourcePath");
            name = Objects.requireNonNull(name, "name").trim();
            if (!resourcePath.startsWith("/") || name.isEmpty()) {
                throw new IllegalArgumentException("Invalid bundled theme-pack resource");
            }
        }

        /// Opens the bundled source.
        @Override
        public InputStream openStream() throws IOException {
            ThemePackIoSupport.requireBackgroundThread();
            @Nullable InputStream input = ThemePackResource.class.getResourceAsStream(resourcePath);
            if (input == null) {
                throw new FileNotFoundException("Bundled theme-pack resource is missing: " + resourcePath);
            }
            return input;
        }
    }

    /// Immutable in-memory resource useful for generated exports and deterministic tests.
    ///
    /// @param data immutable bytes
    /// @param name stable resource name
    @NotNullByDefault
    record Bytes(byte @Unmodifiable [] data, String name) implements ThemePackResource {
        /// Defensively copies resource bytes.
        public Bytes {
            data = Arrays.copyOf(Objects.requireNonNull(data, "data"), data.length);
            name = Objects.requireNonNull(name, "name").trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Theme-pack resource name is blank");
            }
        }

        /// Returns a defensive copy of the immutable bytes.
        @Override
        public byte @Unmodifiable [] data() {
            return Arrays.copyOf(data, data.length);
        }

        /// Opens a stream over a private snapshot.
        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(data);
        }
    }
}
