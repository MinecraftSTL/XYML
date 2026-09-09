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
package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Immutable launch context and log-line snapshot consumed by the limited analyzer.
///
/// @param gameVersion detected Minecraft version, or null when unavailable
/// @param mainClass resolved launch main class, or null when unavailable
/// @param exitType classified process exit
/// @param operatingSystem operating system used for the launch
/// @param systemCodePage Windows ANSI code page, or a negative value when unavailable
/// @param gameDirectory resolved game directory, or null when unavailable
/// @param javaRuntime immutable selected-runtime context and optional repair boundary
/// @param maxMemoryMiB configured maximum heap in MiB, or null when automatic or unknown
/// @param logLines immutable console or persisted-log lines in source order
/// @param missingDependencySearch optional application boundary for searching named missing mods
@NotNullByDefault
public record LogAnalyzable(
        @Nullable String gameVersion,
        @Nullable String mainClass,
        ProcessListener.ExitType exitType,
        OperatingSystem operatingSystem,
        int systemCodePage,
        @Nullable Path gameDirectory,
        JavaRuntimeContext javaRuntime,
        @Nullable Integer maxMemoryMiB,
        @Unmodifiable List<String> logLines,
        @Nullable MissingDependencySearch missingDependencySearch) {
    /// Validates scalar context and defensively copies the log snapshot.
    public LogAnalyzable {
        Objects.requireNonNull(exitType, "exitType");
        Objects.requireNonNull(operatingSystem, "operatingSystem");
        Objects.requireNonNull(javaRuntime, "javaRuntime");
        validatePositive("maxMemoryMiB", maxMemoryMiB);
        logLines = List.copyOf(Objects.requireNonNull(logLines, "logLines"));
    }

    /// Creates analysis input from an immutable runtime context without an application search boundary.
    ///
    /// This overload preserves the pre-search-boundary constructor contract for Core and MCP callers.
    ///
    /// @param gameVersion detected Minecraft version, or null when unavailable
    /// @param mainClass resolved launch main class, or null when unavailable
    /// @param exitType classified process exit
    /// @param operatingSystem operating system used for the launch
    /// @param systemCodePage Windows ANSI code page, or a negative value when unavailable
    /// @param gameDirectory resolved game directory, or null when unavailable
    /// @param javaRuntime immutable selected-runtime context and optional repair boundary
    /// @param maxMemoryMiB configured maximum heap in MiB, or null when automatic or unknown
    /// @param logLines immutable console or persisted-log lines in source order
    public LogAnalyzable(
            @Nullable String gameVersion,
            @Nullable String mainClass,
            ProcessListener.ExitType exitType,
            OperatingSystem operatingSystem,
            int systemCodePage,
            @Nullable Path gameDirectory,
            JavaRuntimeContext javaRuntime,
            @Nullable Integer maxMemoryMiB,
            List<String> logLines) {
        this(
                gameVersion,
                mainClass,
                exitType,
                operatingSystem,
                systemCodePage,
                gameDirectory,
                javaRuntime,
                maxMemoryMiB,
                logLines,
                null);
    }

    /// Creates analysis input without an application-level Java repair boundary.
    ///
    /// @param gameVersion detected Minecraft version, or null when unavailable
    /// @param mainClass resolved launch main class, or null when unavailable
    /// @param exitType classified process exit
    /// @param operatingSystem operating system used for the launch
    /// @param systemCodePage Windows ANSI code page, or a negative value when unavailable
    /// @param gameDirectory resolved game directory, or null when unavailable
    /// @param javaPath selected Java executable, or null when unavailable
    /// @param requiredJavaVersion exact recommended Java major version, or null when unknown
    /// @param currentJavaVersion selected Java major version, or null when unknown
    /// @param javaBits selected Java runtime bitness
    /// @param maxMemoryMiB configured maximum heap in MiB, or null when automatic or unknown
    /// @param logLines immutable console or persisted-log lines in source order
    public LogAnalyzable(
            @Nullable String gameVersion,
            @Nullable String mainClass,
            ProcessListener.ExitType exitType,
            OperatingSystem operatingSystem,
            int systemCodePage,
            @Nullable Path gameDirectory,
            @Nullable Path javaPath,
            @Nullable Integer requiredJavaVersion,
            @Nullable Integer currentJavaVersion,
            Bits javaBits,
            @Nullable Integer maxMemoryMiB,
            List<String> logLines) {
        this(
                gameVersion,
                mainClass,
                exitType,
                operatingSystem,
                systemCodePage,
                gameDirectory,
                new JavaRuntimeContext(
                        javaPath,
                        requiredJavaVersion,
                        currentJavaVersion,
                        javaBits,
                        null),
                maxMemoryMiB,
                logLines,
                null);
    }

    /// Returns the selected Java executable when launch context is available.
    ///
    /// @return selected Java executable, or null when unavailable
    public @Nullable Path javaPath() {
        return javaRuntime.javaPath();
    }

    /// Returns the exact recommended Java major version.
    ///
    /// @return recommended Java major version, or null when unknown
    public @Nullable Integer requiredJavaVersion() {
        return javaRuntime.requiredJavaVersion();
    }

    /// Returns the selected Java major version.
    ///
    /// @return selected Java major version, or null when unknown
    public @Nullable Integer currentJavaVersion() {
        return javaRuntime.currentJavaVersion();
    }

    /// Returns the selected Java runtime bitness.
    ///
    /// @return selected Java runtime bitness
    public Bits javaBits() {
        return javaRuntime.javaBits();
    }

    /// Returns the optional application-level Java repair boundary.
    ///
    /// @return Java repair task factory, or null when this input cannot perform repairs
    public @Nullable JavaRuntimeRepair javaRuntimeRepair() {
        return javaRuntime.repair();
    }

    /// Returns the complete source joined with line feeds for regular-expression analysis.
    ///
    /// @return immutable combined log text
    public String logText() {
        return String.join("\n", logLines);
    }

    /// Returns a context copy containing a different immutable log source.
    ///
    /// @param replacementLogLines replacement lines in source order
    /// @return context copy with the replacement log snapshot
    public LogAnalyzable withLogLines(List<String> replacementLogLines) {
        return new LogAnalyzable(
                gameVersion,
                mainClass,
                exitType,
                operatingSystem,
                systemCodePage,
                gameDirectory,
                javaRuntime,
                maxMemoryMiB,
                replacementLogLines,
                missingDependencySearch);
    }

    /// Returns a context copy that can create an application-level Java selection task.
    ///
    /// @param repair Java selection task factory
    /// @return context copy retaining all launch metadata and logs
    public LogAnalyzable withJavaRuntimeRepair(JavaRuntimeRepair repair) {
        return new LogAnalyzable(
                gameVersion,
                mainClass,
                exitType,
                operatingSystem,
                systemCodePage,
                gameDirectory,
                javaRuntime.withRepair(Objects.requireNonNull(repair, "repair")),
                maxMemoryMiB,
                logLines,
                missingDependencySearch);
    }

    /// Returns a context copy that can create a search task for named missing dependencies.
    ///
    /// @param search missing-dependency search task factory
    /// @return context copy retaining all launch metadata and logs
    public LogAnalyzable withMissingDependencySearch(MissingDependencySearch search) {
        return new LogAnalyzable(
                gameVersion,
                mainClass,
                exitType,
                operatingSystem,
                systemCodePage,
                gameDirectory,
                javaRuntime,
                maxMemoryMiB,
                logLines,
                Objects.requireNonNull(search, "search"));
    }

    /// Reports whether either launch path contains a character outside ASCII.
    ///
    /// @return true when the game or Java path contains a non-ASCII character
    public boolean hasNonAsciiPath() {
        return containsNonAscii(gameDirectory) || containsNonAscii(javaRuntime.javaPath());
    }

    /// Validates an optional positive integer component.
    ///
    /// @param name component name used in failures
    /// @param value optional component value
    private static void validatePositive(String name, @Nullable Integer value) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /// Reports whether an optional path contains any non-ASCII code point.
    ///
    /// @param path path to inspect, or null when unavailable
    /// @return true when the path contains a non-ASCII code point
    private static boolean containsNonAscii(@Nullable Path path) {
        return path != null && path.toString().codePoints().anyMatch(codePoint -> codePoint > 0x7F);
    }

    /// Creates the stopped task that selects a compatible Java runtime without removing registered runtimes.
    @FunctionalInterface
    @NotNullByDefault
    public interface JavaRuntimeRepair {
        /// Creates a fresh stopped Java selection task.
        ///
        /// @return task that selects a compatible Java runtime, downloading one only when necessary
        Task<?> createTask();

        /// Returns the immutable runtime choices currently known to the application.
        ///
        /// Implementations should perform discovery as a read-only operation. An empty list means that the
        /// application will use its normal automatic acquisition path when [#createTask()] is requested.
        ///
        /// @return immutable candidate snapshot in stable display order
        default @Unmodifiable List<JavaRuntimeCandidate> candidates() {
            return List.of();
        }

        /// Creates a fresh task for one explicitly selected runtime candidate.
        ///
        /// The default implementation retains compatibility with older boundaries that do not expose candidates;
        /// passing null selects the boundary's ordinary automatic path, while a non-null identifier is rejected.
        ///
        /// @param candidateId stable candidate identifier, or null for the ordinary automatic path
        /// @return stopped repair task
        default Task<?> createTask(@Nullable String candidateId) {
            if (candidateId != null) {
                throw new IllegalArgumentException("Java runtime candidate selection is unavailable");
            }
            return createTask();
        }
    }

    /// Creates a stopped task that searches for one or more validated missing mod identifiers.
    @FunctionalInterface
    @NotNullByDefault
    public interface MissingDependencySearch {
        /// Creates a fresh stopped search task.
        ///
        /// The supplied identifiers are immutable, ordered by their appearance in the diagnosis, and contain only
        /// validated mod IDs. The application layer decides how the search is presented.
        ///
        /// @param dependencyIds validated missing mod identifiers
        /// @return task that opens or prepares the corresponding search
        Task<?> createTask(@Unmodifiable List<String> dependencyIds);

        /// Starts an optional read-only lookup for the supplied dependency identifiers.
        ///
        /// Implementations may schedule provider discovery and cache immutable candidates before the user invokes
        /// [#createTask(List)]. This hook must not install, replace, or otherwise mutate launcher or game files. The
        /// default is deliberately empty so existing command-line and test boundaries remain source-compatible.
        ///
        /// @param dependencyIds validated missing mod identifiers in diagnosis order
        /// @param gameVersion detected Minecraft version, or null when unavailable
        default void prefetch(
                @Unmodifiable List<String> dependencyIds,
                @Nullable String gameVersion) {
            Objects.requireNonNull(dependencyIds, "dependencyIds");
        }
    }

    /// Immutable display and selection metadata for one Java runtime repair choice.
    ///
    /// The identifier is intentionally opaque to Core callers. Application layers may use it to bind a choice to a
    /// runtime discovered during a read-only snapshot, but must revalidate that binding before persistent mutation.
    ///
    /// @param id stable opaque identifier used when creating the selected task
    /// @param displayName concise human-readable runtime description
    /// @param recommended whether this is the best candidate for the current launch
    @NotNullByDefault
    public record JavaRuntimeCandidate(String id, String displayName, boolean recommended) {
        /// Validates immutable candidate metadata.
        public JavaRuntimeCandidate {
            id = requireNonBlank(id, "id");
            displayName = requireNonBlank(displayName, "displayName");
        }

        /// Uses the display name in standard Swing and command-line choice controls.
        @Override
        public String toString() {
            return displayName;
        }

        /// Rejects an empty or whitespace-only candidate field.
        ///
        /// @param value candidate field
        /// @param name field name used in the exception
        /// @return unchanged validated value
        private static String requireNonBlank(String value, String name) {
            String checked = Objects.requireNonNull(value, name);
            if (checked.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return checked;
        }
    }

    /// Immutable selected-runtime metadata and optional application repair boundary.
    ///
    /// @param javaPath selected Java executable, or null when unavailable
    /// @param requiredJavaVersion exact recommended Java major version, or null when unknown
    /// @param currentJavaVersion selected Java major version, or null when unknown
    /// @param javaBits selected Java runtime bitness
    /// @param repair application-level Java repair boundary, or null when unavailable
    @NotNullByDefault
    public record JavaRuntimeContext(
            @Nullable Path javaPath,
            @Nullable Integer requiredJavaVersion,
            @Nullable Integer currentJavaVersion,
            Bits javaBits,
            @Nullable JavaRuntimeRepair repair) {
        /// Validates version numbers and runtime bitness.
        public JavaRuntimeContext {
            validatePositive("requiredJavaVersion", requiredJavaVersion);
            validatePositive("currentJavaVersion", currentJavaVersion);
            Objects.requireNonNull(javaBits, "javaBits");
        }

        /// Returns a context copy with the supplied Java repair boundary.
        ///
        /// @param selectionRepair Java selection task factory
        /// @return runtime context retaining all selected-runtime metadata
        public JavaRuntimeContext withRepair(JavaRuntimeRepair selectionRepair) {
            return new JavaRuntimeContext(
                    javaPath,
                    requiredJavaVersion,
                    currentJavaVersion,
                    javaBits,
                    Objects.requireNonNull(selectionRepair, "selectionRepair"));
        }
    }
}
