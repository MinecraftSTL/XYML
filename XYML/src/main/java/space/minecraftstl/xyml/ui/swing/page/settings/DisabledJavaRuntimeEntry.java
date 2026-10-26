// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
