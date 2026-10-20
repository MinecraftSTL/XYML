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
package space.minecraftstl.xyml.gradle.cache;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/// Manages the immutable library snapshot reused by the root `run` workflow.
///
/// Only a successful root `build` promotes a snapshot. Consumers validate the complete manifest and every artifact
/// digest before using it, so partial or externally modified snapshots fail closed.
@NotNullByDefault
public final class RunLibraryCache {
    /// Snapshot manifest file name.
    private static final String MANIFEST_NAME = "cache.properties";

    /// Current snapshot manifest format.
    private static final String FORMAT_VERSION = "1";

    /// Utility class; no instances are needed.
    private RunLibraryCache() {
    }

    /// Resolves a complete, valid snapshot.
    ///
    /// @param cacheDirectory snapshot directory
    /// @param expectedLibraries exact library names required by the caller
    /// @return immutable library-to-artifact map, or an empty map when any validation fails
    public static @Unmodifiable Map<String, Path> resolve(
            Path cacheDirectory,
            @Unmodifiable List<String> expectedLibraries) {
        Path normalizedCache = cacheDirectory.toAbsolutePath().normalize();
        Path manifest = normalizedCache.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }

        try {
            Properties properties = loadProperties(manifest);
            if (!FORMAT_VERSION.equals(properties.getProperty("format"))) {
                return Map.of();
            }
            List<String> libraries = parseLibraries(properties.getProperty("libraries"));
            if (!new LinkedHashSet<>(libraries).equals(new LinkedHashSet<>(expectedLibraries))
                    || libraries.size() != expectedLibraries.size()) {
                return Map.of();
            }

            Map<String, Path> result = new LinkedHashMap<>();
            for (String library : expectedLibraries) {
                @Nullable String relativeArtifact = properties.getProperty(library + ".artifact");
                @Nullable String expectedDigest = properties.getProperty(library + ".sha256");
                if (relativeArtifact == null || relativeArtifact.isBlank()
                        || expectedDigest == null || expectedDigest.isBlank()) {
                    return Map.of();
                }
                Path artifact = normalizedCache.resolve(relativeArtifact).normalize();
                if (!artifact.startsWith(normalizedCache)
                        || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                        || !expectedDigest.equals(sha256(artifact))) {
                    return Map.of();
                }
                result.put(library, artifact);
            }
            return Map.copyOf(result);
        } catch (IOException | RuntimeException exception) {
            return Map.of();
        }
    }

    /// Promotes a complete set of artifacts as the newest successful build snapshot.
    ///
    /// @param cacheDirectory destination snapshot directory
    /// @param buildVersion version recorded for diagnostics
    /// @param artifacts library names and built JAR files
    /// @throws IOException when an artifact cannot be copied or the snapshot cannot be installed
    public static void promote(
            Path cacheDirectory,
            String buildVersion,
            @Unmodifiable Map<String, Path> artifacts) throws IOException {
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("At least one library artifact is required");
        }
        Path normalizedCache = cacheDirectory.toAbsolutePath().normalize();
        Path staging = sibling(normalizedCache, ".staging");
        deleteTree(staging);
        Files.createDirectories(staging);

        Properties properties = new Properties();
        properties.setProperty("format", FORMAT_VERSION);
        properties.setProperty("version", buildVersion);
        List<String> libraries = artifacts.keySet().stream().sorted().toList();
        properties.setProperty("libraries", String.join(",", libraries));
        for (String library : libraries) {
            validateLibraryName(library);
            Path source = artifacts.get(library).toAbsolutePath().normalize();
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Library build did not produce a regular JAR: " + source);
            }
            Path destination = staging.resolve(library).resolve(source.getFileName().toString());
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            String relativePath = staging.relativize(destination).toString().replace('\\', '/');
            properties.setProperty(library + ".artifact", relativePath);
            properties.setProperty(library + ".sha256", sha256(destination));
        }
        storeProperties(staging.resolve(MANIFEST_NAME), properties);
        replaceDirectory(staging, normalizedCache);
    }

    /// Installs a validated snapshot copied from an isolated branch build.
    ///
    /// @param sourceDirectory source snapshot directory
    /// @param cacheDirectory destination snapshot directory
    /// @param expectedLibraries exact library names required in the source snapshot
    /// @throws IOException when the source is invalid or cannot be installed
    public static void install(
            Path sourceDirectory,
            Path cacheDirectory,
            @Unmodifiable List<String> expectedLibraries) throws IOException {
        if (resolve(sourceDirectory, expectedLibraries).isEmpty()) {
            throw new IOException("Branch build did not produce a valid run-library snapshot: " + sourceDirectory);
        }
        Path normalizedCache = cacheDirectory.toAbsolutePath().normalize();
        Path staging = sibling(normalizedCache, ".staging");
        deleteTree(staging);
        copyTree(sourceDirectory.toAbsolutePath().normalize(), staging);
        replaceDirectory(staging, normalizedCache);
    }

    /// Loads one UTF-8 properties file.
    ///
    /// @param file properties file
    /// @return parsed properties
    /// @throws IOException when the file cannot be read
    private static Properties loadProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    /// Writes one UTF-8 properties file.
    ///
    /// @param file destination file
    /// @param properties properties to write
    /// @throws IOException when the file cannot be written
    private static void storeProperties(Path file, Properties properties) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, "XYML run library cache");
        }
    }

    /// Parses the ordered library list from a manifest value.
    ///
    /// @param value manifest value, or null
    /// @return immutable library list
    private static @Unmodifiable List<String> parseLibraries(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String library = part.trim();
            if (library.isEmpty() || result.contains(library)) {
                return List.of();
            }
            result.add(library);
        }
        return List.copyOf(result);
    }

    /// Ensures one name cannot escape its snapshot subdirectory.
    ///
    /// @param library library name
    private static void validateLibraryName(String library) {
        if (library.isBlank() || library.contains("/") || library.contains("\\")
                || ".".equals(library) || "..".equals(library)) {
            throw new IllegalArgumentException("Invalid library name: " + library);
        }
    }

    /// Computes the lowercase SHA-256 digest for a file.
    ///
    /// @param file file to hash
    /// @return lowercase hexadecimal digest
    /// @throws IOException when the file cannot be read
    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Returns a sibling path with a suffix appended to the file name.
    ///
    /// @param path base path
    /// @param suffix suffix to append
    /// @return sibling path
    private static Path sibling(Path path, String suffix) {
        @Nullable Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Cache path must have a parent: " + path);
        }
        return parent.resolve(path.getFileName().toString() + suffix);
    }

    /// Replaces a directory while retaining the old directory until the new tree is ready.
    ///
    /// @param staging complete staged directory
    /// @param destination destination directory
    /// @throws IOException when replacement or recovery fails
    private static void replaceDirectory(Path staging, Path destination) throws IOException {
        Path backup = sibling(destination, ".previous");
        deleteTree(backup);
        boolean hadDestination = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
        if (hadDestination) {
            move(destination, backup);
        }
        try {
            move(staging, destination);
        } catch (IOException exception) {
            if (hadDestination && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                move(backup, destination);
            }
            throw exception;
        }
        deleteTree(backup);
    }

    /// Moves one directory, using an atomic filesystem operation where supported.
    ///
    /// @param source source path
    /// @param destination destination path
    /// @throws IOException when the move fails
    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    /// Copies a complete directory tree without following symbolic links.
    ///
    /// @param source source directory
    /// @param destination destination directory
    /// @throws IOException when the tree cannot be copied
    private static void copyTree(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Snapshot source is not a directory: " + source);
        }
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    throw new IOException("Snapshot contains an unsupported entry: " + path);
                }
            }
        }
    }

    /// Deletes one owned directory tree without following symbolic links.
    ///
    /// @param root owned directory
    /// @throws IOException when an entry cannot be deleted
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
