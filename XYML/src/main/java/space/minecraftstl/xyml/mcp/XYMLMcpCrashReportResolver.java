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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Resolves explicit, referenced, or embedded crash reports within one instance-owned directory.
@NotNullByDefault
final class XYMLMcpCrashReportResolver {
    /// Source identifier used when no crash report was resolved.
    static final String SOURCE_NONE = "none";

    /// Source identifier used for an explicit `crash_report_path` argument.
    static final String SOURCE_EXPLICIT = "explicit";

    /// Source identifier used for a report referenced by a launcher-owned log.
    static final String SOURCE_REFERENCED = "referenced";

    /// Source identifier used for a report embedded in launcher output.
    static final String SOURCE_EMBEDDED = "embedded";

    /// Marker that precedes filesystem paths in launcher crash output.
    private static final String REFERENCED_REPORT_MARKER =
            "#@!@# Game crashed! Crash report saved to: #@!@# ";

    /// Prevents instantiation of this stateless resolver.
    private XYMLMcpCrashReportResolver() {
    }

    /// Resolves one crash report without allowing any filesystem read outside the instance report root.
    ///
    /// @param crashReportsRoot instance `crash-reports` directory
    /// @param logText raw game or launcher output
    /// @param explicitPath optional direct report file name or absolute owned path
    /// @param followReferencedPaths whether launcher-owned log references may be followed
    /// @return immutable report resolution and non-fatal warnings
    /// @throws IOException if an explicit report cannot be validated or read
    static Resolution resolve(
            Path crashReportsRoot,
            String logText,
            @Nullable String explicitPath,
            boolean followReferencedPaths) throws IOException {
        Path root = Objects.requireNonNull(crashReportsRoot, "crashReportsRoot");
        String checkedLog = Objects.requireNonNull(logText, "logText");
        if (explicitPath != null && !explicitPath.isBlank()) {
            return new Resolution(readReport(root, explicitPath), SOURCE_EXPLICIT, List.of());
        }

        List<String> warnings = new ArrayList<>();
        if (followReferencedPaths) {
            try {
                @Nullable String referenced = CrashReportAnalyzer.findCrashReport(
                        checkedLog,
                        path -> readReport(root, path.toString()));
                if (referenced != null) {
                    return new Resolution(referenced, SOURCE_REFERENCED, warnings);
                }
            } catch (IOException | InvalidPathException exception) {
                warnings.add("Referenced crash report was not read from this instance: "
                        + Objects.requireNonNullElse(exception.getMessage(), exception.getClass().getSimpleName()));
            }
        } else if (checkedLog.contains(REFERENCED_REPORT_MARKER)) {
            warnings.add("Referenced crash report paths in log_text are not followed; "
                    + "pass a direct instance report file name as crash_report_path.");
        }

        @Nullable String embedded = CrashReportAnalyzer.extractCrashReport(checkedLog);
        return embedded == null
                ? new Resolution(null, SOURCE_NONE, warnings)
                : new Resolution(embedded, SOURCE_EMBEDDED, warnings);
    }

    /// Reads one direct regular file after proving its lexical and real parent is the instance report root.
    ///
    /// @param crashReportsRoot instance `crash-reports` directory
    /// @param rawPath direct file name or absolute owned path
    /// @return UTF-8 report text
    /// @throws IOException if the path is absent, nested, outside the root, or unreadable
    static String readReport(Path crashReportsRoot, String rawPath) throws IOException {
        Path root = Objects.requireNonNull(crashReportsRoot, "crashReportsRoot").toAbsolutePath().normalize();
        Path supplied = Path.of(Objects.requireNonNull(rawPath, "rawPath"));
        Path candidate = supplied.isAbsolute() ? supplied.normalize() : root.resolve(supplied).normalize();
        if (!root.equals(candidate.getParent()) || !Files.isRegularFile(candidate)) {
            throw new IOException("Crash report is not a direct file in the instance crash-reports directory");
        }
        Path realRoot = root.toRealPath();
        Path realReport = candidate.toRealPath();
        if (!realRoot.equals(realReport.getParent())) {
            throw new IOException("Crash report resolves outside the instance crash-reports directory");
        }
        return Files.readString(realReport, StandardCharsets.UTF_8);
    }

    /// Captures resolved report text, its stable source identifier, and non-fatal discovery warnings.
    ///
    /// @param report resolved report text, or `null`
    /// @param source stable source identifier
    /// @param warnings immutable non-fatal warnings
    @NotNullByDefault
    record Resolution(
            @Nullable String report,
            String source,
            @Unmodifiable List<String> warnings) {
        /// Validates the source identifier and snapshots warnings.
        Resolution {
            Objects.requireNonNull(source, "source");
            warnings = List.copyOf(warnings);
        }
    }
}
