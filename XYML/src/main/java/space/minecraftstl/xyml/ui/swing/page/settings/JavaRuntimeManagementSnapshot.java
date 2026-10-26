// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.java.JavaRuntime;

import java.util.List;
import java.util.Objects;

/// Immutable local Java-runtime state rendered by [JavaManagementPanel].
///
/// @param initialized whether the first local discovery has completed
/// @param revision monotonically increasing runtime-discovery revision
/// @param writable whether user Java settings may currently be changed
/// @param runtimes sorted discovered local Java runtimes
/// @param disabledRuntimes disabled configured paths and their explicit inspection states
@NotNullByDefault
public record JavaRuntimeManagementSnapshot(
        boolean initialized,
        long revision,
        boolean writable,
        @Unmodifiable List<JavaRuntime> runtimes,
        @Unmodifiable List<DisabledJavaRuntimeEntry> disabledRuntimes) {
    /// Validates the revision and defensively copies both runtime lists.
    public JavaRuntimeManagementSnapshot {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        runtimes = List.copyOf(Objects.requireNonNull(runtimes, "runtimes"));
        disabledRuntimes = List.copyOf(Objects.requireNonNull(disabledRuntimes, "disabledRuntimes"));
    }

    /// Creates a writable snapshot without disabled entries for existing local-runtime consumers.
    ///
    /// @param initialized whether the first local discovery has completed
    /// @param revision monotonically increasing runtime-discovery revision
    /// @param runtimes sorted discovered local Java runtimes
    public JavaRuntimeManagementSnapshot(
            boolean initialized,
            long revision,
            @Unmodifiable List<JavaRuntime> runtimes) {
        this(initialized, revision, true, runtimes, List.of());
    }
}
