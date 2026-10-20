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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Reads the small, launcher-owned text resources exposed by the MCP adapter.
///
/// This class is deliberately stateless. Callers remain responsible for resolving an instance and for holding the
/// task resources that protect the selected path while a read is in progress.
@NotNullByDefault
final class XYMLMcpResourceReader {
    /// Prevents construction of this stateless helper.
    private XYMLMcpResourceReader() {
    }

    /// Creates a JSON-safe text-resource response with stable field order.
    ///
    /// @param uri requested resource URI
    /// @param text resource body
    /// @return immutable resource response
    static @Unmodifiable Map<String, String> textResource(String uri, String text) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("uri", Objects.requireNonNull(uri, "uri"));
        result.put("mime_type", "text/plain");
        result.put("text", Objects.requireNonNull(text, "text"));
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Lists direct regular files in one captured crash-report directory.
    ///
    /// Symbolic links and entries whose real parent is not the captured directory are omitted. A missing directory is
    /// represented by an empty response because an instance may not have produced a crash report yet.
    ///
    /// @param root captured crash-report directory
    /// @return sorted file names joined by line breaks
    /// @throws IOException when the directory cannot be resolved or listed
    static String listCrashReports(Path root) throws IOException {
        Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        Path realRoot = normalizedRoot.toRealPath();
        try (Stream<Path> paths = Files.list(normalizedRoot)) {
            return paths.filter(path -> isOwnedRegularFile(path, realRoot))
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.joining("\n"));
        }
    }

    /// Decodes and validates one URI path segment.
    ///
    /// @param raw encoded path segment
    /// @return decoded segment that cannot traverse a directory
    /// @throws IllegalArgumentException when the segment is malformed or unsafe
    static String decodePathSegment(String raw) {
        final String decoded;
        try {
            decoded = URLDecoder.decode(
                    Objects.requireNonNull(raw, "raw").replace("+", "%2B"),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid XYML resource URI", exception);
        }
        if (decoded.isBlank() || decoded.contains("/") || decoded.contains("\\")
                || ".".equals(decoded) || "..".equals(decoded)) {
            throw new IllegalArgumentException("Invalid XYML resource path segment");
        }
        return decoded;
    }

    /// Decodes an instance identifier embedded in an MCP resource URI.
    ///
    /// @param raw encoded instance identifier
    /// @return validated instance identifier
    static GameInstanceID instanceIdFromUri(String raw) {
        return new GameInstanceID(decodePathSegment(raw));
    }

    /// Reads one crash report after proving it remains inside the captured directory.
    ///
    /// @param root captured crash-report directory
    /// @param rawPath report file name or relative path supplied by the URI
    /// @return report text
    /// @throws IOException when the path is unsafe or cannot be read
    static String readCrashReport(Path root, String rawPath) throws IOException {
        return XYMLMcpCrashReportResolver.readReport(root, rawPath);
    }

    /// Reads the latest log from one captured run directory.
    ///
    /// Both the conventional `logs/latest.log` location and the legacy run-root location are accepted. Missing or
    /// malformed locations return an empty body so resource discovery remains read-only and fail-closed.
    ///
    /// @param runDirectory captured effective run directory
    /// @return latest-log text, or an empty string when absent or unsafe
    /// @throws IOException when an existing selected file cannot be read
    static String readLog(Path runDirectory) throws IOException {
        Path normalizedRunDirectory = Objects.requireNonNull(runDirectory, "runDirectory")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(normalizedRunDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        Path realRoot = normalizedRunDirectory.toRealPath();
        Path logsDirectory = normalizedRunDirectory.resolve("logs");
        if (Files.exists(logsDirectory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(logsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        Path latest = Files.isDirectory(logsDirectory, LinkOption.NOFOLLOW_LINKS)
                ? logsDirectory.resolve("latest.log")
                : normalizedRunDirectory.resolve("latest.log");
        return readIfPresent(latest, realRoot);
    }

    /// Returns whether a direct child is a non-symbolic-link regular file owned by the captured directory.
    ///
    /// @param path candidate directory entry
    /// @param realRoot real captured directory
    /// @return true when the entry can be safely returned
    private static boolean isOwnedRegularFile(Path path, Path realRoot) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path realPath = path.toRealPath();
            return realRoot.equals(realPath.getParent());
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }

    /// Reads a regular file only when its resolved path remains below the captured root.
    ///
    /// @param path candidate file
    /// @param realRoot captured run-directory root
    /// @return file contents, or an empty string when absent or unsafe
    /// @throws IOException when an existing safe file cannot be read
    private static String readIfPresent(Path path, Path realRoot) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        Path realPath = path.toRealPath();
        if (!realPath.startsWith(realRoot) || !Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        return Files.readString(realPath, StandardCharsets.UTF_8);
    }
}
