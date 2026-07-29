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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/// Describes one Java executable path retained in the disabled-runtime settings set.
///
/// @param configuredPath original path text preserved exactly as stored in user settings
/// @param status explicit inspection state for the configured path
/// @param resolvedBinary canonical executable path only when status is [Status#AVAILABLE], otherwise `null`
@NotNullByDefault
public record DisabledJavaRuntimeEntry(
        String configuredPath,
        Status status,
        @Nullable Path resolvedBinary) {
    /// Classifies whether a disabled path has been inspected and can currently restore a Java runtime.
    @NotNullByDefault
    public enum Status {
        /// The path has not been touched by filesystem or Java probing.
        UNCHECKED,

        /// The path resolved to an executable that produced valid Java runtime metadata.
        AVAILABLE,

        /// The path was missing, inaccessible, malformed, or did not produce valid Java runtime metadata.
        INVALID
    }

    /// Validates the explicit inspection state while retaining the configured path's original spelling.
    public DisabledJavaRuntimeEntry {
        configuredPath = Objects.requireNonNull(configuredPath, "configuredPath");
        status = Objects.requireNonNull(status, "status");
        if ((status == Status.AVAILABLE) != (resolvedBinary != null)) {
            throw new IllegalArgumentException("resolvedBinary must be present only for an available entry");
        }
    }

    /// Creates a disabled entry without touching its configured path.
    ///
    /// @param configuredPath original path text
    /// @return unchecked disabled entry
    public static DisabledJavaRuntimeEntry unchecked(String configuredPath) {
        return new DisabledJavaRuntimeEntry(configuredPath, Status.UNCHECKED, null);
    }

    /// Creates an inspected invalid disabled entry.
    ///
    /// @param configuredPath original path text
    /// @return invalid disabled entry
    public static DisabledJavaRuntimeEntry invalid(String configuredPath) {
        return new DisabledJavaRuntimeEntry(configuredPath, Status.INVALID, null);
    }

    /// Creates an inspected available disabled entry.
    ///
    /// @param configuredPath original path text
    /// @param resolvedBinary canonical Java executable path
    /// @return available disabled entry
    public static DisabledJavaRuntimeEntry available(String configuredPath, Path resolvedBinary) {
        return new DisabledJavaRuntimeEntry(
                configuredPath,
                Status.AVAILABLE,
                Objects.requireNonNull(resolvedBinary, "resolvedBinary"));
    }
}
