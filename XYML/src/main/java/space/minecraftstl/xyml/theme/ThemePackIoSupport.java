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

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/// Shared bounded I/O and validation primitives for local theme-pack operations.
@NotNullByDefault
final class ThemePackIoSupport {
    /// Buffer size used for bounded streaming copies.
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    /// Prevents utility-class construction.
    private ThemePackIoSupport() {
    }

    /// Rejects resource and filesystem work on the AWT event-dispatch thread.
    ///
    /// @throws IllegalStateException when the current worker is the EDT
    static void requireBackgroundThread() {
        if (EventQueue.isDispatchThread()) {
            throw new IllegalStateException("Theme-pack I/O must run outside the event-dispatch thread");
        }
    }

    /// Reads a stream into memory under an inclusive byte ceiling.
    ///
    /// @param input source stream
    /// @param maximumBytes maximum accepted bytes
    /// @return immutable byte snapshot
    /// @throws IOException when the stream is empty or exceeds the limit
    static byte @Unmodifiable [] readBounded(InputStream input, long maximumBytes) throws IOException {
        if (maximumBytes <= 0L || maximumBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid in-memory byte limit: " + maximumBytes);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maximumBytes, COPY_BUFFER_SIZE));
        copyBounded(input, output, maximumBytes);
        byte[] bytes = output.toByteArray();
        if (bytes.length == 0) {
            throw new IOException("Theme-pack resource is empty");
        }
        return bytes;
    }

    /// Copies a stream while enforcing an inclusive per-stream byte ceiling.
    ///
    /// @param input source stream
    /// @param output destination stream
    /// @param maximumBytes maximum bytes
    /// @return copied bytes
    /// @throws IOException when the stream exceeds the limit or I/O fails
    static long copyBounded(InputStream input, OutputStream output, long maximumBytes) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0L;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return total;
            }
            total = checkedTotal(total, read, maximumBytes);
            output.write(buffer, 0, read);
        }
    }

    /// Adds non-negative byte counts without overflow under one inclusive ceiling.
    ///
    /// @param current current bytes
    /// @param additional bytes to add
    /// @param maximum inclusive limit
    /// @return checked total
    /// @throws IOException when the count overflows or exceeds the ceiling
    static long checkedTotal(long current, long additional, long maximum) throws IOException {
        if (current < 0L || additional < 0L || current > maximum - additional) {
            throw new IOException("Theme pack exceeds its expanded-size limit");
        }
        return current + additional;
    }

    /// Parses a complete bounded UTF-8 manifest snapshot.
    ///
    /// @param bytes manifest bytes
    /// @return parsed manifest
    /// @throws IOException when JSON is malformed or incomplete
    static ThemePackManifest parseManifest(byte @Unmodifiable [] bytes) throws IOException {
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            return JsonUtils.fromNonNullJsonFully(input, ThemePackManifest.class);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid theme-pack manifest", exception);
        }
    }

    /// Fully validates one referenced image under encoded and decoded-dimension ceilings.
    ///
    /// @param resource referenced image source
    /// @param limits resource limits
    /// @throws IOException when the source is missing, malformed, oversized, or unsupported
    static void validateImage(ThemePackResource resource, ThemePackArchiveLimits limits) throws IOException {
        byte[] encoded;
        try (InputStream input = resource.openStream()) {
            encoded = readBounded(input, limits.maximumSingleAssetBytes());
        }
        try (InputStream input = new ByteArrayInputStream(encoded);
             ImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported theme-pack image: " + resource.name());
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateImageDimensions(width, height, limits, resource.name());
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new IOException("Theme-pack image has no decodable frame: " + resource.name());
                }
                try {
                    validateImageDimensions(decoded.getWidth(), decoded.getHeight(), limits, resource.name());
                } finally {
                    decoded.flush();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    /// Validates decoded image dimensions with overflow-safe arithmetic.
    private static void validateImageDimensions(
            int width,
            int height,
            ThemePackArchiveLimits limits,
            String name) throws IOException {
        if (width <= 0
                || height <= 0
                || width > limits.maximumImageEdge()
                || height > limits.maximumImageEdge()
                || (long) width * height > limits.maximumImagePixels()) {
            throw new IOException("Theme-pack image exceeds its pixel limit: " + name);
        }
    }

    /// Normalizes a portable archive path and rejects absolute or traversal segments.
    ///
    /// @param entryName decoded archive entry name
    /// @return immutable path segments
    /// @throws IOException when the path is unsafe
    static @Unmodifiable List<String> normalizeArchiveEntry(String entryName) throws IOException {
        String checked = Objects.requireNonNull(entryName, "entryName");
        if (checked.isBlank() || checked.indexOf('\0') >= 0 || checked.length() > 1_024) {
            throw new IOException("Theme-pack archive entry name is empty or too long");
        }
        String portable = checked.replace('\\', '/');
        if (portable.startsWith("/")
                || portable.startsWith("//")
                || (portable.length() >= 3
                && Character.isLetter(portable.charAt(0))
                && portable.charAt(1) == ':'
                && portable.charAt(2) == '/')) {
            throw new IOException("Theme-pack archive contains an absolute path: " + checked);
        }
        String[] raw = portable.split("/", -1);
        List<String> segments = new ArrayList<>(raw.length);
        for (int index = 0; index < raw.length; index++) {
            String segment = raw[index];
            if (segment.isEmpty() && index == raw.length - 1) {
                continue;
            }
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || segment.indexOf(':') >= 0) {
                throw new IOException("Theme-pack archive contains a dangerous path: " + checked);
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            throw new IOException("Theme-pack archive contains an empty path");
        }
        return List.copyOf(segments);
    }

    /// Resolves validated path segments below one normalized root.
    ///
    /// @param root containment root
    /// @param segments validated relative segments
    /// @return contained normalized path
    /// @throws IOException when resolution escapes unexpectedly
    static Path resolveContained(Path root, @Unmodifiable List<String> segments) throws IOException {
        Path resolved = root;
        for (String segment : segments) {
            resolved = resolved.resolve(segment);
        }
        Path normalized = resolved.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("Theme-pack path escapes containment root");
        }
        return normalized;
    }

    /// Requires every existing segment of one absolute path to be direct rather than symbolic.
    ///
    /// @param path existing path to validate
    /// @param directory whether the final path must be a directory instead of a regular file
    /// @param label diagnostic label
    /// @throws IOException when any segment is linked, an intermediate segment is not a directory, or the final
    ///                     filesystem type differs from the requested type
    static void requireNoSymbolicPath(Path path, boolean directory, String label) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        @Nullable Path current = absolute.getRoot();
        if (current == null) {
            throw new IOException(label + " has no filesystem root: " + path);
        }
        BasicFileAttributes rootAttributes = Files.readAttributes(
                current,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (rootAttributes.isSymbolicLink() || !rootAttributes.isDirectory()) {
            throw new IOException(label + " filesystem root is unsafe: " + current);
        }
        for (Path segment : absolute) {
            current = current.resolve(segment);
            BasicFileAttributes attributes = Files.readAttributes(
                    current,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw new IOException(label + " traverses a symbolic link: " + current);
            }
            boolean finalSegment = current.equals(absolute);
            if (!finalSegment && !attributes.isDirectory()) {
                throw new IOException(label + " traverses a non-directory: " + current);
            }
            if (finalSegment && (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
                throw new IOException(label + " has an unexpected filesystem type: " + current);
            }
        }
    }

    /// Creates descendant directories one segment at a time without following links.
    ///
    /// @param root extraction root
    /// @param directory descendant directory
    /// @throws IOException when a segment is linked or conflicts with a file
    static void createDirectoriesWithoutLinks(Path root, Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("Theme-pack directory escapes containment root");
        }
        Path current = root;
        for (Path segment : root.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unsafe theme-pack directory segment: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    /// Creates an absolute directory path from its filesystem root without following any existing link.
    ///
    /// @param directory absolute or relative directory to create
    /// @return normalized absolute directory
    /// @throws IOException when any existing segment is linked or is not a directory
    static Path createAbsoluteDirectoriesWithoutLinks(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        @Nullable Path current = absolute.getRoot();
        if (current == null) {
            throw new IOException("Theme-pack directory has no filesystem root: " + directory);
        }
        requireNoSymbolicPath(current, true, "theme-pack filesystem root");
        for (Path segment : absolute) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(
                        current,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw new IOException("Theme-pack directory path is unsafe: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
        return absolute;
    }

    /// Deletes one failed staging tree without following symbolic links.
    ///
    /// @param root staging root
    /// @throws IOException when cleanup fails
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            /// Deletes each visited regular file or link.
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            /// Deletes each directory after its children.
            @Override
            public FileVisitResult postVisitDirectory(
                    Path directory,
                    @Nullable IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
