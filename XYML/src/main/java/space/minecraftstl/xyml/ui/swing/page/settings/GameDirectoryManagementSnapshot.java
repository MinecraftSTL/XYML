// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Immutable effective game-directory list rendered by [GameDirectoryManagementPanel].
///
/// @param revision monotonic service revision used to publish a selection change even when entry values otherwise match
/// @param entries effective local-first game-directory entries
@NotNullByDefault
public record GameDirectoryManagementSnapshot(
        long revision,
        @Unmodifiable List<GameDirectoryManagementEntry> entries) {
    /// Validates the revision and defensively copies entries.
    public GameDirectoryManagementSnapshot {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}
