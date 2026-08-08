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
/// @param javaPath selected Java executable, or null when unavailable
/// @param requiredJavaVersion exact recommended Java major version, or null when unknown
/// @param currentJavaVersion selected Java major version, or null when unknown
/// @param javaBits selected Java runtime bitness
/// @param maxMemoryMiB configured maximum heap in MiB, or null when automatic or unknown
/// @param logLines immutable console or persisted-log lines in source order
@NotNullByDefault
public record LogAnalyzable(
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
        @Unmodifiable List<String> logLines) {
    /// Validates scalar context and defensively copies the log snapshot.
    public LogAnalyzable {
        Objects.requireNonNull(exitType, "exitType");
        Objects.requireNonNull(operatingSystem, "operatingSystem");
        Objects.requireNonNull(javaBits, "javaBits");
        validatePositive("requiredJavaVersion", requiredJavaVersion);
        validatePositive("currentJavaVersion", currentJavaVersion);
        validatePositive("maxMemoryMiB", maxMemoryMiB);
        logLines = List.copyOf(Objects.requireNonNull(logLines, "logLines"));
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
                javaPath,
                requiredJavaVersion,
                currentJavaVersion,
                javaBits,
                maxMemoryMiB,
                replacementLogLines);
    }

    /// Reports whether either launch path contains a character outside ASCII.
    ///
    /// @return true when the game or Java path contains a non-ASCII character
    public boolean hasNonAsciiPath() {
        return containsNonAscii(gameDirectory) || containsNonAscii(javaPath);
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
}
